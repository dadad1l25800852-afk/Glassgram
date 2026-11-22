package dev.youfree.app.happ

import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket

/**
 * Uses XTLS/libXray's public Invoke API v3. Not the proprietary Happ binary.
 * The native AAR is obtained from the official libXray release by the CI build.
 * Reflection deliberately isolates the app from JNI binding class-case changes;
 * a missing or incompatible library raises an explicit error, never fake success.
 */
class XrayEmbedded {
    private val entrypoint: java.lang.reflect.Method by lazy {
        val cls = Class.forName("libXray.LibXray")
        cls.getMethod("invoke", String::class.java)
    }

    private fun invoke(method: String, payload: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject()
            .put("apiVersion", 3).put("method", method).put("payload", payload)
        val output = try {
            entrypoint.invoke(null, request.toString()) as? String
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(e.targetException.message ?: "Ошибка нативного Xray", e)
        }
        val result = JSONObject(output ?: error("Нет ответа от Xray"))
        if (!result.optBoolean("success", false)) {
            throw IllegalStateException(result.optString("error", "Ошибка Xray"))
        }
        return result.optJSONObject("data") ?: JSONObject()
    }

    data class Node(val name: String, val outbound: JSONObject, val delayMs: Long? = null) {
        // JSONObject isn't immutable; only this file touches raw outbounds.
        fun withDelay(delay: Long?) = copy(delayMs = delay)
    }

    fun decodeOpenSubscription(links: String): List<Node> {
        val data = invoke("convertShareLinksToXrayJson", JSONObject().put("text", links))
        val arr = data.getJSONArray("outbounds")
        return (0 until arr.length()).map { index ->
            val node = arr.getJSONObject(index)
            val tag = node.optString("tag").takeIf { it.isNotBlank() } ?: "Сервер ${index + 1}"
            Node(tag, node)
        }
    }

    fun benchmark(nodes: List<Node>): List<Node> {
        require(nodes.size in 1..80)
        val measured = mutableListOf<Node>()
        for (batch in nodes.chunked(5)) {
            val configs = JSONArray()
            batch.forEach { node ->
                configs.put(JSONObject().put("xrayJson", JSONObject()
                    .put("outbounds", JSONArray().put(node.outbound)).toString())
                    .put("outboundTag", node.outbound.optString("tag", "proxy")))
            }
            val data = invoke("pingBatch", JSONObject().put("configs", configs)
                .put("timeout", 5).put("url", "https://www.youtube.com/generate_204"))
            // libXray version 3 responses use data.results; errors remain unranked.
            val results = data.optJSONArray("results")
                ?: throw IllegalStateException("Неизвестный формат ответа pingBatch")
            batch.forEachIndexed { idx, node ->
                val item = results.getJSONObject(idx)
                val latency = if (item.optBoolean("success", false)) item.optLong("delay", -1) else -1L
                measured += node.withDelay(latency.takeIf { it in 0..9999 })
            }
        }
        return measured.sortedWith(compareBy<Node> { it.delayMs ?: Long.MAX_VALUE }.thenBy { it.name })
    }

    /** Xray listens as an HTTP CONNECT proxy, but never captures TLS to Google. */
    fun start(node: Node): Int {
        val port = ServerSocket(0).use { it.localPort }
        val json = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("listen", "127.0.0.1").put("port", port)
                .put("protocol", "http").put("tag", "youfree-in")
                .put("settings", JSONObject())))
            .put("outbounds", JSONArray().put(node.outbound))
            .put("routing", JSONObject().put("domainStrategy", "AsIs")
                .put("rules", JSONArray().put(JSONObject()
                    .put("type", "field").put("inboundTag", JSONArray().put("youfree-in"))
                    .put("outboundTag", node.outbound.optString("tag", "proxy")))))
        invoke("runXray", JSONObject().put("xrayJson", json.toString()))
        // A successful Invoke response means Xray accepted the configuration; this
        // makes a separate loopback TCP check so the UI never reports an absent proxy.
        val listening = (1..8).any {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                }
                true
            } catch (_: java.io.IOException) {
                Thread.sleep(75)
                false
            }
        }
        if (!listening) {
            runCatching { stop() }
            error("Xray принял конфигурацию, но локальный прокси не запустился")
        }
        return port
    }


    /**
     * Android VpnService owns the TUN FD; Xray's native TUN inbound receives it
     * through XRAY_TUN_FD, set by YouFreeVpnService before this call.
     *
     * The Android VPN builder, not Xray, installs OS routes. Outbound sockets
     * are bound to the pre-VPN physical interface to avoid recursive tunneling.
     */
    fun startTun(node: Node, physicalInterface: String) {
        require(physicalInterface.matches(Regex("[A-Za-z0-9_.-]{2,32}"))) {
            "Неизвестный сетевой интерфейс"
        }
        val outbound = JSONObject(node.outbound.toString()).put("tag", "selected")
        val inbound = JSONObject().put("tag", "youfree-tun").put("protocol", "tun")
            .put("settings", JSONObject().put("mtu", 1500)
                .put("autoOutboundsInterface", physicalInterface))
        val config = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
        invoke("runXray", JSONObject().put("xrayJson", config.toString()))
    }

    fun stop() { invoke("stopXray") }
}
