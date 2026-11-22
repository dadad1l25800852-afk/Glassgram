package dev.youfree.app.network

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.youfree.app.data.ApiClient
import dev.youfree.app.data.AppConfig
import dev.youfree.app.happ.HappSubscription
import dev.youfree.app.happ.XrayEmbedded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume

enum class RouteMode { DIRECT, RELAY, HAPP, UNAVAILABLE }

class RouteEngine(private val context: Context, private val api: ApiClient) : AutoCloseable {
    private val guard = Mutex()
    private val xray = XrayEmbedded()
    private val importer = HappSubscription()
    private var loadedLink = ""
    private var nodes: List<XrayEmbedded.Node> = emptyList()
    private var currentNode = 0
    private var xrayStarted = false
    private var localProxy: LocalConnectProxy? = null
    private var relayOffset = 0
    var mode: RouteMode = RouteMode.UNAVAILABLE
        private set
    var chosenNodeName: String = ""
        private set

    /** Import an open Happ subscription and check real YouTube connectivity via each node. */
    suspend fun importHapp(link: String): List<Pair<String, Long?>> = guard.withLock {
        withContext(Dispatchers.IO) {
            stopXray()
            val clear = importer.import(link)
            val parsed = xray.decodeOpenSubscription(clear)
            require(parsed.isNotEmpty()) { "В подписке нет поддерживаемых серверов" }
            val ranked = xray.benchmark(parsed)
            require(ranked.any { it.delayMs != null }) {
                "Серверы импортированы, но ни один не смог открыть YouTube через Xray"
            }
            nodes = ranked
            loadedLink = link
            currentNode = 0
            chosenNodeName = nodes.first().name
            nodes.map { it.name to it.delayMs }
        }
    }

    suspend fun prepare(config: AppConfig, forceRelay: Boolean = false): RouteMode = guard.withLock {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            mode = if (directAvailable()) RouteMode.DIRECT else RouteMode.UNAVAILABLE
            return@withLock mode
        }
        if (config.happLink.isNotBlank()) {
            try {
                if (loadedLink != config.happLink || nodes.isEmpty()) {
                    val text = importer.import(config.happLink)
                    val parsed = withContext(Dispatchers.IO) { xray.decodeOpenSubscription(text) }
                    nodes = withContext(Dispatchers.IO) { xray.benchmark(parsed) }
                    loadedLink = config.happLink
                    currentNode = 0
                }
                if (nodes.isNotEmpty()) {
                    stopXray()
                    val available = nodes.filter { it.delayMs != null }
                    if (available.isNotEmpty()) {
                        val chosen = available[currentNode % available.size]
                        val port = withContext(Dispatchers.IO) { xray.start(chosen) }
                        xrayStarted = true
                        chosenNodeName = chosen.name
                        changeProxy("http://127.0.0.1:$port")
                        localProxy?.close(); localProxy = null
                        mode = RouteMode.HAPP
                        return@withLock mode
                    }
                }
            } catch (_: Exception) { stopXray() }
            // If the subscription is broken, still allow unproxied playback when possible.
        }
        if (!forceRelay && directAvailable()) {
            changeProxy(null)
            localProxy?.close(); localProxy = null
            stopXray()
            mode = RouteMode.DIRECT
            return@withLock mode
        }
        val healthy = api.sortedHealthyServers()
        if (healthy.isEmpty()) {
            changeProxy(null)
            mode = RouteMode.UNAVAILABLE
            return@withLock mode
        }
        val shift = relayOffset % healthy.size
        val ordered = healthy.drop(shift) + healthy.take(shift)
        val p = LocalConnectProxy(config.token, ordered)
        p.start()
        try {
            changeProxy("http://127.0.0.1:${p.port}")
            localProxy?.close(); localProxy = p
            mode = RouteMode.RELAY
        } catch (_: Exception) { p.close(); mode = RouteMode.UNAVAILABLE }
        mode
    }

    fun rotateRelay() {
        if (mode == RouteMode.HAPP) currentNode++
        else { relayOffset++; localProxy?.nextServer() }
    }

    private fun stopXray() {
        if (xrayStarted) {
            runCatching { xray.stop() }
            xrayStarted = false
        }
    }

    private suspend fun directAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://www.youtube.com/generate_204").openConnection() as HttpsURLConnection
            c.connectTimeout = 2300; c.readTimeout = 2300; c.useCaches = false
            c.instanceFollowRedirects = false
            try { c.responseCode == 204 || c.responseCode == 200 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    private suspend fun changeProxy(address: String?) = suspendCancellableCoroutine<Unit> { continuation ->
        val controller = ProxyController.getInstance()
        val done = Runnable { if (continuation.isActive) continuation.resume(Unit) }
        if (address == null) controller.clearProxyOverride(context.mainExecutor, done)
        else controller.setProxyOverride(ProxyConfig.Builder().addProxyRule(address).build(), context.mainExecutor, done)
    }

    override fun close() {
        localProxy?.close()
        stopXray()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(context.mainExecutor) { }
        }
    }
}
