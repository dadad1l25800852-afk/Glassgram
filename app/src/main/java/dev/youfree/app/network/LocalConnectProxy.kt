package dev.youfree.app.network

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local HTTP CONNECT proxy, bound ONLY to 127.0.0.1. WebView speaks to this proxy;
 * this process opens certificate-verified TLS connections to the self-hosted relay.
 * The proxied YouTube TLS session remains end-to-end encrypted.
 *
 * NOT a VpnService, not a TLS interception implementation, and not a system proxy.
 */
class LocalConnectProxy(private val token: String, private val candidates: List<String>) : AutoCloseable {
    private val workers = Executors.newCachedThreadPool()
    private val capacity = Semaphore(64)
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    @Volatile private var running = true
    @Volatile private var offset = 0

    fun start() {
        workers.execute {
            while (running) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!capacity.tryAcquire()) { socket.close(); continue }
                workers.execute {
                    try { handle(socket) } catch (_: Exception) { socket.close() }
                    finally { capacity.release() }
                }
            }
        }
    }

    fun nextServer() { if (candidates.isNotEmpty()) offset = (offset + 1) % candidates.size }

    private fun handle(client: Socket) {
        client.use { local ->
            local.soTimeout = 8000
            val first = readHeaders(local.getInputStream())
            val request = first.substringBefore("\r\n")
            val match = Regex("^CONNECT ([^ ]+):443 HTTP/1\\.[01]$").matchEntire(request)
            if (match == null) {
                local.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val hostname = match.groupValues[1]
            if (!allowed(hostname)) {
                local.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            var upstream: SSLSocket? = null
            for (i in candidates.indices) {
                val address = candidates[(offset + i) % candidates.size]
                try {
                    val uri = URI(address)
                    val port = if (uri.port == -1) 443 else uri.port
                    val s = SSLSocketFactory.getDefault().createSocket(uri.host, port) as SSLSocket
                    s.soTimeout = 8000
                    val parameters: SSLParameters = s.sslParameters
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    s.sslParameters = parameters
                    s.startHandshake()
                    val command = "CONNECT $hostname:443 HTTP/1.1\r\nHost: $hostname:443\r\n" +
                        "Proxy-Authorization: Bearer $token\r\nConnection: keep-alive\r\n\r\n"
                    s.outputStream.write(command.toByteArray(StandardCharsets.US_ASCII))
                    val reply = readHeaders(s.inputStream)
                    if (!reply.startsWith("HTTP/1.1 200 ")) { s.close(); continue }
                    upstream = s
                    offset = (offset + i) % candidates.size
                    break
                } catch (_: Exception) { /* try the next independently owned server */ }
            }
            if (upstream == null) {
                local.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            upstream.use { remote ->
                local.soTimeout = 0; remote.soTimeout = 0
                local.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                val outbound = workers.submit {
                    runCatching { local.getInputStream().copyTo(remote.outputStream) }
                    runCatching { remote.shutdownOutput() }
                }
                runCatching { remote.inputStream.copyTo(local.getOutputStream()) }
                runCatching { local.shutdownOutput() }
                // Closing the remote wakes the other copy coroutine if the peer disconnected.
                runCatching { outbound.get() }
            }
        }
    }

    private fun readHeaders(input: InputStream): String {
        val result = java.io.ByteArrayOutputStream()
        var lastFour = 0
        while (result.size() < 16384) {
            val b = input.read()
            if (b == -1) error("connection closed")
            result.write(b)
            lastFour = (lastFour shl 8) or (b and 255)
            if (lastFour == 0x0D0A0D0A) return result.toString("ISO-8859-1")
        }
        error("oversized header")
    }

    private fun allowed(host: String): Boolean {
        if (host.length > 253 || !host.matches(Regex("[a-zA-Z0-9.-]+"))) return false
        return listOf("youtube.com", "youtube-nocookie.com", "googlevideo.com", "ytimg.com", "ggpht.com", "google.com", "googleapis.com", "gstatic.com",
                "doubleclick.net", "googleadservices.com", "googlesyndication.com", "googleusercontent.com", "google-analytics.com")
            .any { host.equals(it, true) || host.endsWith(".$it", true) }
    }

    override fun close() {
        running = false
        server.close()
        workers.shutdownNow()
    }
}
