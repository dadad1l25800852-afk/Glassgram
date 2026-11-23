package dev.youfree.app.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * HTTP CONNECT -> local SOCKS5 relay for ByeDPI.
 * The official WebView ProxyController supports HTTP proxies, while ByeDPI
 * listens on SOCKS5. No TLS termination, key logging, or content alteration.
 */
class SocksHttpBridge(private val socksPort: Int) : AutoCloseable {
    private val workers = Executors.newFixedThreadPool(24)
    private val sessions = Semaphore(8)
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var closed = false

    init {
        Thread({
            while (!closed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                if (!sessions.tryAcquire()) {
                    client.close()
                    continue
                }
                workers.execute {
                    try { handle(client) }
                    catch (_: Exception) { runCatching { client.close() } }
                    finally { sessions.release() }
                }
            }
        }, "YouFree-HTTP-to-ByeDPI").apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket) {
        client.use { browser ->
            browser.soTimeout = 10000
            val input = browser.getInputStream()
            val output = browser.getOutputStream()
            val header = readHeaders(input)
            val first = header.lineSequence().firstOrNull().orEmpty()
            val parts = first.trim().split(" ")
            if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                output.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val authority = parts[1]
            val host = if (authority.startsWith("[")) authority.substringAfter("[").substringBefore("]")
                else authority.substringBeforeLast(":")
            val port = authority.substringAfterLast(":").toIntOrNull() ?: return
            require(host.isNotEmpty() && host.length <= 253 && port in 1..65535)
            Socket().use { upstream ->
                upstream.connect(InetSocketAddress("127.0.0.1", socksPort), 3000)
                upstream.soTimeout = 12000
                val socksIn = upstream.getInputStream()
                val socksOut = upstream.getOutputStream()
                socksOut.write(byteArrayOf(5, 1, 0))
                require(socksIn.read() == 5 && socksIn.read() == 0) { "SOCKS auth" }
                val addr = host.toByteArray(StandardCharsets.UTF_8)
                require(addr.size in 1..255)
                socksOut.write(byteArrayOf(5, 1, 0, 3, addr.size.toByte()))
                socksOut.write(addr)
                socksOut.write(byteArrayOf((port shr 8).toByte(), (port and 0xff).toByte()))
                socksOut.flush()
                val prefix = ByteArray(4)
                readFully(socksIn, prefix)
                require(prefix[0].toInt() == 5 && prefix[1].toInt() == 0) { "SOCKS connect rejected" }
                val addressBytes = when (prefix[3].toInt() and 0xff) {
                    1 -> 4
                    4 -> 16
                    3 -> socksIn.read().also { require(it in 0..255) }
                    else -> error("SOCKS address format")
                }
                val addressAndPort = ByteArray(addressBytes + 2)
                readFully(socksIn, addressAndPort)
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
                browser.soTimeout = 0
                upstream.soTimeout = 0

                val upload = Thread({
                    try { input.copyTo(socksOut, bufferSize = 32 * 1024) }
                    catch (_: Exception) { }
                    finally { runCatching { upstream.shutdownOutput() } }
                }, "YouFree-upload")
                upload.isDaemon = true
                upload.start()
                try { socksIn.copyTo(output, bufferSize = 32 * 1024) }
                catch (_: Exception) { }
                finally {
                    runCatching { browser.shutdownOutput() }
                    runCatching { upstream.close() }
                    upload.join(250)
                }
            }
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray) {
        var pos = 0
        while (pos < buf.size) {
            val n = stream.read(buf, pos, buf.size - pos)
            if (n < 0) error("SOCKS unexpectedly closed")
            pos += n
        }
    }

    private fun readHeaders(stream: InputStream): String {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < 16_384) {
            val ch = stream.read()
            if (ch < 0) break
            buffer.write(ch)
            val bytes = buffer.toByteArray()
            val n = bytes.size
            if (n >= 4 && bytes[n - 4] == 13.toByte() && bytes[n - 3] == 10.toByte() &&
                bytes[n - 2] == 13.toByte() && bytes[n - 1] == 10.toByte()) {
                return buffer.toString("ISO-8859-1")
            }
        }
        error("Incomplete or oversized proxy request")
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        workers.shutdownNow()
    }
}
