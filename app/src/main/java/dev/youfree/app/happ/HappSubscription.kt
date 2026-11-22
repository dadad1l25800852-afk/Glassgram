package dev.youfree.app.happ

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Fetch a subscription without downgrading HTTPS or forwarding credentials elsewhere. */
class HappSubscription {
    suspend fun import(link: String): String = withContext(Dispatchers.IO) {
        when (val item = HappLinkParser.classify(link)) {
            is HappLinkParser.Input.Nodes -> item.text
            is HappLinkParser.Input.Subscription -> {
                val conn = URL(item.url).openConnection() as HttpsURLConnection
                conn.connectTimeout = 6500
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("Accept", "text/plain, application/octet-stream;q=0.8")
                try {
                    check(conn.responseCode == 200) { "Сервер подписки вернул HTTP ${conn.responseCode}" }
                    check(conn.contentLengthLong <= 750_000L) { "Подписка слишком большая" }
                    val bytes = conn.inputStream.use { stream ->
                        val buffer = ByteArray(750_001)
                        var count = 0
                        while (count < buffer.size) {
                            val n = stream.read(buffer, count, buffer.size - count)
                            if (n < 0) break
                            count += n
                        }
                        require(count <= 750_000) { "Подписка слишком большая" }
                        buffer.copyOf(count)
                    }
                    HappLinkParser.cleanNodes(String(bytes, Charsets.UTF_8))
                } finally { conn.disconnect() }
            }
        }
    }
}
