package dev.youfree.app.network

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class ConnectionMode { DIRECT, VPN, BYEDPI }

data class ConnectionStatus(
    val mode: ConnectionMode = ConnectionMode.DIRECT,
    val text: String = "Прямое соединение",
    val busy: Boolean = false,
    val proxyPort: Int? = null,
    val revision: Int = 0,
    val videoTestPassed: Boolean? = null
)

/**
 * Application-only routing. Does not intercept any other installed app.
 * Xray: WebView -> Xray loopback HTTP inbound -> encrypted outbound.
 * ByeDPI: WebView -> HTTP CONNECT -> local SOCKS5 -> native ByeDPI.
 */
class RouteManager(private val context: Context) {
    val status = MutableStateFlow(ConnectionStatus())
    private val xray = XrayEmbedded()
    private var bridge: SocksHttpBridge? = null
    private var xrayStarted = false
    private var dpiStarted = false
    private var generation = 0

    fun proxy(): Proxy? = status.value.proxyPort?.let {
        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it))
    }

    private suspend fun applyWebViewProxy(port: Int?) = withContext(Dispatchers.Main) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            error("Системный WebView не поддерживает прокси")
        }
        val controller = ProxyController.getInstance()
        val executor = ContextCompat.getMainExecutor(context)
        if (port == null) {
            controller.clearProxyOverride(executor, Runnable {})
        } else {
            controller.setProxyOverride(
                ProxyConfig.Builder().addProxyRule("http://127.0.0.1:" + port).build(),
                executor, Runnable {})
        }
        delay(150)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun shutdown() {
        runCatching { bridge?.close() }
        bridge = null
        if (xrayStarted) {
            runCatching { withContext(Dispatchers.IO) { xray.stop() } }
            xrayStarted = false
        }
        if (dpiStarted) {
            context.startService(Intent(context, DpiService::class.java)
                .setAction(DpiService.STOP))
            dpiStarted = false
            delay(250)
        }
        runCatching { applyWebViewProxy(null) }
    }

    suspend fun direct() {
        status.value = status.value.copy(busy = true, text = "Отключаю маршрут…")
        shutdown()
        generation++
        status.value = ConnectionStatus(revision = generation)
    }

    suspend fun vpn(link: String) {
        status.value = status.value.copy(busy = true, text = "Проверяю VPN-серверы…")
        try {
            shutdown()
            val best = withContext(Dispatchers.IO) {
                val source = HappSubscription().import(link)
                val nodes = xray.decodeOpenSubscription(source)
                require(nodes.isNotEmpty()) { "В подписке нет серверов" }
                xray.benchmark(nodes).firstOrNull { it.delayMs != null }
                    ?: error("Ни один сервер не ответил через YouTube")
            }
            val port = withContext(Dispatchers.IO) { xray.start(best) }
            xrayStarted = true
            applyWebViewProxy(port)
            generation++
            status.value = ConnectionStatus(
                mode = ConnectionMode.VPN,
                text = "Xray · " + best.name + " · " + best.delayMs + " мс",
                proxyPort = port, revision = generation
            )
        } catch (e: Exception) {
            shutdown()
            generation++
            status.value = ConnectionStatus(text = "Ошибка VPN: " + e.message,
                revision = generation)
        }
    }

    suspend fun byedpi(strategy: String) {
        status.value = status.value.copy(busy = true, text = "Запускаю встроенный ByeDPI…")
        try {
            require(strategy.length < 2048 && !strategy.contains('\n')) {
                "Некорректная стратегия ByeDPI"
            }
            shutdown()
            val nativePort = freePort()
            val intent = Intent(context, DpiService::class.java)
                .setAction(DpiService.START)
                .putExtra(DpiService.PORT, nativePort)
                .putExtra(DpiService.STRATEGY, strategy)
            ContextCompat.startForegroundService(context, intent)
            dpiStarted = true
            val alive = withContext(Dispatchers.IO) {
                (1..60).any {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", nativePort), 150)
                            socket.soTimeout = 150
                            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                            socket.getInputStream().read() == 5 &&
                                socket.getInputStream().read() == 0
                        }
                    } catch (_: Exception) {
                        Thread.sleep(75)
                        false
                    }
                }
            }
            require(alive) { "ByeDPI не запустился. Проверь параметры" }
            val adapter = SocksHttpBridge(nativePort)
            bridge = adapter
            applyWebViewProxy(adapter.port)
            generation++
            status.value = ConnectionStatus(
                mode = ConnectionMode.BYEDPI,
                text = "ByeDPI · локальный движок активен",
                proxyPort = adapter.port,
                revision = generation
            )
            testYouTube()
        } catch (e: Exception) {
            shutdown()
            generation++
            status.value = ConnectionStatus(text = "Ошибка ByeDPI: " + e.message,
                revision = generation)
        }
    }

    suspend fun testYouTube() {
        val current = status.value
        val p = proxy() ?: return
        val result = withContext(Dispatchers.IO) {
            runCatching {
                OkHttpClient.Builder().proxy(p)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .build()
                    .newCall(Request.Builder()
                        .url("https://www.youtube.com/generate_204").build())
                    .execute().use { it.code == 204 || it.isSuccessful }
            }.getOrDefault(false)
        }
        status.value = current.copy(
            videoTestPassed = result,
            text = current.text + if (result) " · YouTube отвечает"
                   else " · YouTube не ответил на проверку"
        )
    }

    fun close() {
        bridge?.close()
        if (dpiStarted) context.startService(
            Intent(context, DpiService::class.java).setAction(DpiService.STOP))
        if (xrayStarted) runCatching { xray.stop() }
    }
}
