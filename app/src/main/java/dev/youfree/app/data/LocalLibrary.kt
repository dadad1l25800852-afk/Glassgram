package dev.youfree.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device-local lists; they do not sync with a Google account. */
class LocalLibrary(context: Context) {
    private val preferences = context.getSharedPreferences("youfree.library", Context.MODE_PRIVATE)

    fun getVideos(key: String): List<Video> = runCatching {
        val raw = JSONArray(preferences.getString(key, "[]"))
        (0 until raw.length()).map { raw.getJSONObject(it) }.map { item ->
            Video(item.optString("id"), item.optString("title"), item.optString("channel"),
                item.optString("channelId"), item.optString("thumbnail"), item.optString("description"),
                item.optString("publishedAt"), item.optString("duration"), item.optString("viewCount"))
        }
    }.getOrDefault(emptyList())

    fun putVideos(key: String, videos: List<Video>) {
        val array = JSONArray()
        videos.take(100).forEach { video ->
            array.put(JSONObject().put("id", video.id).put("title", video.title)
                .put("channel", video.channel).put("channelId", video.channelId)
                .put("thumbnail", video.thumbnail).put("description", video.description)
                .put("publishedAt", video.publishedAt).put("duration", video.duration)
                .put("viewCount", video.viewCount))
        }
        preferences.edit().putString(key, array.toString()).apply()
    }
    fun getSubscriptions(): Set<String> = preferences.getStringSet("subscriptions", emptySet())?.toSet() ?: emptySet()
    fun putSubscriptions(ids: Set<String>) { preferences.edit().putStringSet("subscriptions", ids).apply() }
}
