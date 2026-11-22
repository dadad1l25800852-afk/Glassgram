package dev.youfree.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class Video(
    val id: String, val title: String, val channel: String, val channelId: String,
    val thumbnail: String, val description: String, val publishedAt: String,
    val duration: String, val viewCount: String, val kind: String = "video",
    val embeddable: Boolean = true
)
data class Comment(val author: String, val text: String, val avatar: String)

data class Feed(val videos: List<Video>, val nextPageToken: String = "")

class ApiClient {
    var servers: List<String> = emptyList()
    var token: String = ""
    @Volatile var lastSuccessfulServer: String = ""
        private set

    suspend fun ping(address: String): Long? = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        runCatching {
            val conn = URL(address.trimEnd('/') + "/health").openConnection() as HttpsURLConnection
            conn.connectTimeout = 2500; conn.readTimeout = 2500
            conn.requestMethod = "GET"; conn.useCaches = false
            try { if (conn.responseCode == 200) (System.nanoTime() - start) / 1_000_000 else null }
            finally { conn.disconnect() }
        }.getOrNull()
    }

    suspend fun sortedHealthyServers(): List<String> = withContext(Dispatchers.IO) {
        servers.mapNotNull { address -> ping(address)?.let { address to it } }
            .sortedBy { it.second }.map { it.first }
    }

    /** Metadata uses independent HTTPS requests; a failed relay is retried on the next server. */
    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (server in servers) {
            try {
                val conn = URL(server.trimEnd('/') + path).openConnection() as HttpsURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 9000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                conn.instanceFollowRedirects = false
                try {
                    val status = conn.responseCode
                    if (status != 200) {
                        val message = conn.errorStream?.bufferedReader()?.use { it.readText().take(300) } ?: "HTTP $status"
                        throw IllegalStateException("HTTP $status: $message")
                    }
                    val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    lastSuccessfulServer = server
                    return@withContext data
                } finally { conn.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Добавь адрес HTTPS-сервера в настройках")
    }

    fun thumbnailURL(imageURL: String): String? {
        val server = lastSuccessfulServer.ifBlank { servers.firstOrNull().orEmpty() }
        return if (server.isBlank()) null else server.trimEnd('/') + "/api/v1/thumbnail?url=" + URLEncoder.encode(imageURL, "UTF-8")
    }

    suspend fun status(): Boolean = get("/api/v1/status").optBoolean("youtubeApiEnabled")
    suspend fun home(): Feed = feed(get("/api/v1/home"))
    suspend fun search(query: String, channels: Boolean = false): Feed = feed(get(
        "/api/v1/search?q=" + URLEncoder.encode(query, "UTF-8") + "&type=" + (if (channels) "channel" else "video")
    ))
    suspend fun videoInfo(id: String): Video? = feed(get("/api/v1/video/${safeId(id)}")).videos.firstOrNull()
    suspend fun channelVideos(id: String): Feed = feed(get("/api/v1/channel/${safeId(id)}/videos"))
    suspend fun comments(id: String): List<Comment> {
        val result = get("/api/v1/comments/${safeId(id)}").getJSONArray("items")
        return (0 until result.length()).map { result.getJSONObject(it) }.map {
            Comment(it.optString("author"), it.optString("text"), it.optString("avatar"))
        }
    }
    private fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{2,80}")))
        return id
    }
    private fun feed(json: JSONObject): Feed {
        val items = json.getJSONArray("items")
        return Feed((0 until items.length()).map { items.getJSONObject(it) }.map {
            Video(it.optString("id"), it.optString("title"), it.optString("channelTitle"),
                it.optString("channelId"), it.optString("thumbnail"), it.optString("description"),
                it.optString("publishedAt"), it.optString("duration"), it.optString("viewCount"),
                it.optString("kind", "video"), it.optBoolean("embeddable",true))
        }, json.optString("nextPageToken"))
    }
}
