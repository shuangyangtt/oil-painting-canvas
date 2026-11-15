package app.youtubu.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Channel(val id: String, val name: String, val avatar: String = "")

data class Video(
    val id: String,
    val title: String,
    val author: String,
    val authorId: String,
    val seconds: Int,
) {
    // YouTube's image servers are not IP blocked and much faster than going through Invidious
    // (the videos themselves always go through Invidious). hqdefault is 4:3, the UI crops it to 16:9.
    val thumbnail get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

/** Verified English and German nursery song channels (checked through Invidious on 2026-11-15). */
val DEFAULT_CHANNELS = listOf(
    Channel("UCLsooMJoIpl_7ux2jvdPB-Q", "Super Simple Songs"),
    Channel("UCooPit08nD5lkSjgc5Gjxqw", "Sing mit mir – Kinderlieder"),
    Channel("UCcdwLMPsaU2ezNSJU1nFoBQ", "Pinkfong Baby Shark"),
    Channel("UCctbi1Jw2jiVhj2ogdwiFdA", "Kinderlieder zum Mitsingen und Bewegen"),
    Channel("UCKAqou7V9FAWXpZd9xtOg3Q", "Little Baby Bum"),
    Channel("UC-OEheLPb_kpsbEhfvUgYkg", "Hurra Kinderlieder"),
    Channel("UCbCmjCuTUZos6Inko4u57UQ", "CoComelon"),
    Channel("UCD4S7zLdV660OAIB-CEJvhQ", "CoComelon Deutsch"),
    Channel("UCJkWoS4RsldA1coEIot5yDA", "Mother Goose Club"),
    Channel("UCGh8WvsmJTI9m8eh6g2bwvQ", "Pinkfong, Baby Hai! (Deutsch)"),
    Channel("UC56cowXhoqRWHeqfSJkIQaA", "Bounce Patrol"),
    Channel("UCHAQPpmpJqY50V046kQ63Mw", "Little Baby Bum Deutsch"),
    Channel("UC6zhI71atP7YLoZyIyCIGNw", "Dave and Ava"),
    Channel("UC91MMRlxNMvkxhU4JDJdcuQ", "Lichterkinder"),
    Channel("UCtf9cFBJkHVAf2qMqF01xYg", "Hey Bear Sensory"),
    Channel("UC7HM-Pm3mLzZBvhJKFnL6oA", "Volker Rosin"),
    Channel("UCafNlR92d6W0e9ow_QHRx1w", "Rolf Zuckowski"),
    Channel("UCv4Pvhg1LY8U9E-XF8hn6WA", "KiKAninchen"),
)

const val DEFAULT_INSTANCE = "https://yt.chocolatemoo53.com"

object Net {
    /** Whether the projector has a network connection at all. */
    fun online(context: android.content.Context): Boolean = try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetwork != null
    } catch (_: Exception) {
        true
    }

    // the Invidious instance rejects requests without a browser User-Agent (403)
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()
}

class Invidious(private val base: () -> String) {

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base().trimEnd('/') + path).build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }

    /** The channel's most popular videos (the classics a toddler wants), without Shorts and live streams. */
    suspend fun popularVideos(channelId: String): List<Video> {
        val json = JSONObject(get("/api/v1/channels/$channelId/videos?sort_by=popular"))
        val list = json.optJSONArray("videos") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter {
                !it.optBoolean("liveNow") && !it.optBoolean("isUpcoming") &&
                    !it.optBoolean("premium") && it.optInt("lengthSeconds") >= 45
            }
            .map { it.toVideo() }
    }

    suspend fun channelInfo(channelId: String): Channel {
        val json = JSONObject(get("/api/v1/channels/$channelId"))
        return Channel(json.getString("authorId"), json.getString("author"), avatarFrom(json))
    }

    /** Turns a channel link, @handle or channel id into a channel id, or null. */
    suspend fun resolveChannel(input: String): String? {
        val text = input.trim()
        Regex("UC[\\w-]{22}").find(text)?.let { return it.value }
        val url = when {
            text.startsWith("@") -> "https://www.youtube.com/$text"
            text.startsWith("http") -> text
            text.contains("youtube.com") -> "https://$text"
            else -> return null
        }
        return try {
            val json = JSONObject(get("/api/v1/resolveurl?url=" + URLEncoder.encode(url, "UTF-8")))
            json.optString("ucid").ifEmpty { null }
        } catch (e: IOException) {
            null
        }
    }

    suspend fun searchChannels(query: String): List<Channel> {
        val arr = JSONArray(get("/api/v1/search?type=channel&q=" + URLEncoder.encode(query, "UTF-8")))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "channel" }
            .take(8)
            .map { Channel(it.getString("authorId"), it.getString("author"), avatarFrom(it)) }
    }

    fun dashUrl(videoId: String) = base().trimEnd('/') + "/api/manifest/dash/id/$videoId?local=true"

    /** 360p single file (video + audio), works on every device; used when DASH fails. */
    fun mp4Url(videoId: String) = base().trimEnd('/') + "/latest_version?id=$videoId&itag=18&local=true"

    private fun avatarFrom(json: JSONObject): String {
        val thumbs = json.optJSONArray("authorThumbnails") ?: return ""
        var best = ""
        var bestDiff = Int.MAX_VALUE
        for (i in 0 until thumbs.length()) {
            val t = thumbs.getJSONObject(i)
            val diff = kotlin.math.abs(t.optInt("width") - 176)
            if (diff < bestDiff) {
                bestDiff = diff
                best = t.optString("url")
            }
        }
        if (best.startsWith("//")) best = "https:$best"
        // channel avatars straight from YouTube's image server, like the video thumbnails
        val ggpht = best.indexOf("/ggpht/")
        if (ggpht >= 0) best = "https://yt3.ggpht.com/" + best.substring(ggpht + "/ggpht/".length)
        return best
    }
}

fun JSONObject.toVideo() = Video(
    id = getString("videoId"),
    title = optString("title"),
    author = optString("author"),
    authorId = optString("authorId"),
    seconds = optInt("lengthSeconds"),
)

fun Video.toJson(): JSONObject = JSONObject()
    .put("videoId", id).put("title", title).put("author", author)
    .put("authorId", authorId).put("lengthSeconds", seconds)

fun Channel.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("avatar", avatar)

/** Everything is kept on the projector itself: channels, favourites and a cache of each row. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("youtubu", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "rows").apply { mkdirs() }

    var instance: String
        get() = prefs.getString("instance", null) ?: DEFAULT_INSTANCE
        set(value) = prefs.edit().putString("instance", value.trim().trimEnd('/')).apply()

    var autoPrecache: Boolean
        get() = prefs.getBoolean("autoPrecache", true)
        set(value) = prefs.edit().putBoolean("autoPrecache", value).apply()

    var maxHeight: Int
        get() = prefs.getInt("maxHeight", 1080)
        set(value) = prefs.edit().putInt("maxHeight", value).apply()

    fun channels(): List<Channel> {
        val raw = prefs.getString("channels", null) ?: return DEFAULT_CHANNELS
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Channel(it.getString("id"), it.optString("name"), it.optString("avatar")) }
    }

    fun saveChannels(list: List<Channel>) {
        prefs.edit().putString("channels", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun favorites(): List<Video> {
        val arr = JSONArray(prefs.getString("favorites", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).toVideo() }
    }

    fun saveFavorites(list: List<Video>) {
        prefs.edit().putString("favorites", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    /** Cached row and the time it was fetched, so the app starts instantly and works when Invidious is slow. */
    fun cachedRow(channelId: String): Pair<Long, List<Video>>? {
        val file = File(cacheDir, "$channelId.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("videos")
            json.getLong("time") to (0 until arr.length()).map { arr.getJSONObject(it).toVideo() }
        } catch (e: Exception) {
            null
        }
    }

    fun cacheRow(channelId: String, videos: List<Video>) {
        val json = JSONObject().put("time", System.currentTimeMillis())
            .put("videos", JSONArray(videos.map { it.toJson() }))
        File(cacheDir, "$channelId.json").writeText(json.toString())
    }

    fun clearRowCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
