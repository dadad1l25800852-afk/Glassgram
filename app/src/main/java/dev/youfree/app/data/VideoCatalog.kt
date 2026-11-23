package dev.youfree.app.data

import android.content.Context
import android.text.Html
import android.util.Xml
import dev.youfree.app.network.RouteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val published: String = ""
) {
    val thumbnail: String
        get() = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
}

/** Native YouTube Data API v3 catalog; no HTML scraping or unofficial streams. */
class VideoCatalog(private val routes: RouteManager) {
    private fun client(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(7, TimeUnit.SECONDS)
        routes.proxy()?.let { b.proxy(it) }
        return b.build()
    }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun parse(items: JSONArray, search: Boolean): List<Video> {
        val videos = mutableListOf<Video>()
        for (index in 0 until items.length()) {
            val obj = items.optJSONObject(index) ?: continue
            val videoId = if (search) obj.optJSONObject("id")?.optString("videoId").orEmpty()
                else obj.optString("id")
            if (!videoId.matches(Regex("^[A-Za-z0-9_-]{11}$"))) continue
            val snippet = obj.optJSONObject("snippet") ?: continue
            videos += Video(videoId,
                clean(snippet.optString("title", "Видео")),
                clean(snippet.optString("channelTitle", "YouTube")),
                snippet.optString("publishedAt", ""))
        }
        return videos
    }

    private suspend fun dataApi(path: String, params: Map<String, String>, key: String,
                                isSearch: Boolean): List<Video> = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Для поиска нужен ключ YouTube Data API" }
        val uri = HttpUrl.Builder().scheme("https").host("www.googleapis.com")
            .addPathSegments("youtube/v3/" + path)
        (params + mapOf("key" to key, "part" to "snippet", "maxResults" to "25"))
            .forEach { (k, v) -> uri.addQueryParameter(k, v) }
        val req = Request.Builder().url(uri.build()).get().build()
        client().newCall(req).execute().use { response ->
            val body = response.body?.string() ?: error("Нет ответа YouTube API")
            val json = JSONObject(body)
            if (!response.isSuccessful) {
                val reason = json.optJSONObject("error")?.optString("message") ?: "HTTP " + response.code
                error(reason)
            }
            parse(json.optJSONArray("items") ?: JSONArray(), isSearch)
        }
    }

    suspend fun home(key: String): List<Video> =
        if (key.isBlank()) rssHome()
        else dataApi("videos", mapOf("chart" to "mostPopular", "regionCode" to "RU"),
                     key, false)

    suspend fun search(key: String, query: String): List<Video> =
        dataApi("search", mapOf("q" to query, "type" to "video"), key, true)

    suspend fun shorts(key: String): List<Video> =
        dataApi("search", mapOf("q" to "#shorts", "type" to "video",
            "videoDuration" to "short"), key, true)

    private suspend fun rssHome(): List<Video> = withContext(Dispatchers.IO) {
        // Public YouTube RSS is only a small channel feed, not YouTube recommendations.
        // No API key is needed, and no mock videos are ever returned.
        val channels = listOf("UCBR8-60-B28hp2BmDPdntcQ", "UC_x5XG1OV2P6uZZ5FSM9Ttw")
        val list = mutableListOf<Video>()
        for (channelId in channels) {
            try {
                val url = HttpUrl.Builder().scheme("https").host("www.youtube.com")
                    .addPathSegments("feeds/videos.xml")
                    .addQueryParameter("channel_id", channelId).build()
                client().newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val parser = Xml.newPullParser()
                    response.body?.byteStream()?.use { input ->
                        parser.setInput(input, "UTF-8")
                        var inside = false
                        var id = ""
                        var title = ""
                        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                            val name = parser.name.orEmpty()
                            if (parser.eventType == XmlPullParser.START_TAG) {
                                if (name == "entry") { inside = true; id = ""; title = "" }
                                else if (inside && name.endsWith("videoId")) {
                                    id = parser.nextText().trim()
                                } else if (inside && name == "title") {
                                    title = parser.nextText().trim()
                                }
                            } else if (parser.eventType == XmlPullParser.END_TAG && name == "entry") {
                                if (id.matches(Regex("^[A-Za-z0-9_-]{11}$"))) {
                                    list += Video(id, clean(title), "YouTube")
                                }
                                inside = false
                            }
                            parser.next()
                        }
                    }
                }
            } catch (_: Exception) { /* next public feed */ }
        }
        list.distinctBy { it.id }.take(30)
    }
}

/** Local watch history only; no impersonation of Google OAuth. */
class LocalVideoStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("youfree_history", Context.MODE_PRIVATE)
    fun history(): List<Video> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val x = array.optJSONObject(i) ?: return@mapNotNull null
            Video(x.getString("id"), x.optString("title"), x.optString("channel"))
        }
    }.getOrDefault(emptyList())

    fun savePlayed(video: Video) {
        val videos = (listOf(video) + history().filter { it.id != video.id }).take(60)
        val arr = JSONArray()
        videos.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun saved(): List<Video> = runCatching {
        val arr = JSONArray(prefs.getString("saved", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            Video(v.getString("id"), v.optString("title"), v.optString("channel"))
        }
    }.getOrDefault(emptyList())

    fun toggleSaved(v: Video) {
        val list = saved()
        val next = if (list.any { it.id == v.id }) list.filter { it.id != v.id }
                   else listOf(v) + list
        val arr = JSONArray()
        next.take(100).forEach { arr.put(JSONObject().put("id", it.id)
            .put("title", it.title).put("channel", it.channel)) }
        prefs.edit().putString("saved", arr.toString()).apply()
    }
}
