package app.youtubu.tv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

private const val MB = 1024L * 1024
/** room left in the cache for normal (online) playback */
private const val ONLINE_RESERVE = 400 * MB
private const val MAX_SECONDS = 15 * 60

/**
 * Songs stored completely on the projector (720p H.264 + audio), playable without any network.
 * Each one has a small manifest with only the stored formats, so the player cannot pick anything else.
 */
@OptIn(UnstableApi::class)
object Offline {
    private class Entry(val video: Video, val keys: List<Pair<String, Long>>, val bytes: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "offline").apply { mkdirs() }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(dir(context), "index.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val keys = o.getJSONArray("keys")
                val video = o.getJSONObject("video").toVideo()
                entries[video.id] = Entry(
                    video,
                    (0 until keys.length()).map { keys.getJSONObject(it).let { k -> k.getString("key") to k.getLong("length") } },
                    o.getLong("bytes"),
                )
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun save(context: Context) {
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(
                JSONObject().put("video", e.video.toJson()).put("bytes", e.bytes)
                    .put("keys", JSONArray(e.keys.map { JSONObject().put("key", it.first).put("length", it.second) })),
            )
        }
        File(dir(context), "index.json").writeText(arr.toString())
    }

    @Synchronized
    fun isComplete(context: Context, videoId: String): Boolean {
        ensureLoaded(context)
        val entry = entries[videoId] ?: return false
        val cache = Media.cache(context)
        return entry.keys.all { (key, length) -> cache.isCached(key, 0, length) }
    }

    /** The offline manifest, if every byte of the song is still in the cache. */
    fun manifestIfComplete(context: Context, videoId: String): Uri? =
        if (isComplete(context, videoId)) Uri.fromFile(File(dir(context), "$videoId.mpd")) else null

    /** All complete songs, newest first. Songs whose data was pushed out of the cache are forgotten. */
    @Synchronized
    fun videos(context: Context): List<Video> {
        ensureLoaded(context)
        val gone = entries.keys.filter { !isComplete(context, it) }
        if (gone.isNotEmpty()) {
            gone.forEach { id ->
                entries.remove(id)
                File(dir(context), "$id.mpd").delete()
            }
            save(context)
        }
        return entries.values.map { it.video }.reversed()
    }

    @Synchronized
    fun totalBytes(context: Context): Long {
        ensureLoaded(context)
        return entries.values.sumOf { it.bytes }
    }

    @Synchronized
    fun add(context: Context, video: Video, manifest: String, keys: List<Pair<String, Long>>) {
        ensureLoaded(context)
        File(dir(context), "${video.id}.mpd").writeText(manifest)
        entries.remove(video.id)
        entries[video.id] = Entry(video, keys, keys.sumOf { it.second })
        save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        entries.clear()
        loaded = true
        dir(context).listFiles()?.forEach { it.delete() }
    }
}

/**
 * Fills the cache with recommended songs (favourites first, then every channel's most popular songs in turn)
 * whenever the projector is on and no video is playing, so there is always something to watch
 * when the internet is slow.
 */
@OptIn(UnstableApi::class)
class Precacher(private val context: Context, private val state: AppState, private val scope: CoroutineScope) {
    var status by mutableStateOf("等待开始")
        private set

    @Volatile
    private var writer: CacheWriter? = null
    private var job: Job? = null
    private val failed = mutableSetOf<String>()
    private val failures = mutableMapOf<String, Int>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            delay(if (BuildConfig.PAD) 3_000 else 15_000) // let the home screen load first
            while (isActive) {
                // tablet: keeps running for days, so the song lists are refreshed now and then (new songs get stored too)
                if (BuildConfig.PAD && System.currentTimeMillis() - lastRefresh > 6 * 60 * 60_000L) {
                    lastRefresh = System.currentTimeMillis()
                    withContext(Dispatchers.Main) { state.loadAll() }
                    delay(20_000)
                }
                val wait = step()
                if (wait > 0) delay(wait)
            }
        }
    }

    /** Stops the current download at once (a song started playing); it continues later where it stopped. */
    fun pause() {
        writer?.cancel()
    }

    private var lastRefresh = System.currentTimeMillis()

    // the tablet only plays stored songs (no internet needed), so downloading goes on while a song plays
    private fun playing() = !BuildConfig.PAD && state.screen is Screen.Player

    private fun candidates(): List<Video> {
        val rows = state.channels.mapNotNull { state.rows[it.id]?.videos }
        val list = mutableListOf<Video>()
        list += state.favorites
        val longest = rows.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (row in rows) row.getOrNull(i)?.let { list += it }
        return list.distinctBy { it.id }.filter { it.seconds in 45..MAX_SECONDS && it.id !in failed }
    }

    /** One step of the loop; returns how long to wait before the next one. */
    private suspend fun step(): Long {
        if (!state.store.autoPrecache) {
            status = "已关闭"
            return 30_000
        }
        if (playing()) {
            status = "正在放视频，暂停下载"
            return 3_000
        }
        if (!Net.online(context)) {
            status = "没有网络，等网络恢复后继续"
            return 30_000
        }
        if (Media.cacheUsed() > Media.cacheLimit - ONLINE_RESERVE) {
            status = "已存满（${formatSize(Media.cacheUsed())}）"
            return 20 * 60_000
        }
        val target = candidates().firstOrNull { !Offline.isComplete(context, it.id) }
        if (target == null) {
            status = "推荐的歌都已存好"
            return if (BuildConfig.PAD) 60 * 60_000L else 20 * 60_000L
        }
        return try {
            download(target)
            0
        } catch (e: InterruptedIOException) {
            status = "正在放视频，暂停下载"
            3_000
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // a song is only given up after 3 failures (a short network hiccup must not rule it out)
            val count = (failures[target.id] ?: 0) + 1
            failures[target.id] = count
            if (count >= 3) failed += target.id
            Media.log("离线缓存「${target.title.take(24)}」失败（第 $count 次）：${e.message}")
            30_000
        }
    }

    private suspend fun download(video: Video) {
        val number = Offline.videos(context).size + 1
        status = "正在存第 $number 首：${video.title.take(30)}"

        // the plain kind of manifest (whole stream files) is needed; Invidious sometimes sends the segmented kind
        var text = ""
        for (attempt in 1..5) {
            text = Media.fetchManifestText(state.api, video.id)
            if (!text.contains("sabr-segment")) break
            delay(2_000)
        }
        if (text.contains("sabr-segment")) throw IOException("只拿到分段格式的清单")

        val sets = Regex("<AdaptationSet\\b[^>]*>.*?</AdaptationSet>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.value }.toList()
        fun attr(tag: String, name: String) = Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
        data class Rep(val setTag: String, val xml: String, val mime: String, val codecs: String, val height: Int, val bandwidth: Int, val id: String)
        val reps = sets.flatMap { set ->
            val setTag = Regex("<AdaptationSet\\b[^>]*>").find(set)!!.value
            Regex("<Representation\\b[^>]*>.*?</Representation>", RegexOption.DOT_MATCHES_ALL).findAll(set).map { m ->
                val open = Regex("<Representation\\b[^>]*>").find(m.value)!!.value
                Rep(
                    setTag, m.value,
                    attr(setTag, "mimeType") ?: attr(open, "mimeType") ?: "",
                    attr(open, "codecs") ?: "",
                    attr(open, "height")?.toIntOrNull() ?: 0,
                    attr(open, "bandwidth")?.toIntOrNull() ?: 0,
                    attr(open, "id") ?: "",
                )
            }.toList()
        }
        // 720p H.264 (hardware decoding on every projector) and AAC audio
        val videoRep = reps.filter { it.mime == "video/mp4" && it.codecs.startsWith("avc1") && it.height <= 720 }.maxByOrNull { it.height }
            ?: throw IOException("没有 H.264 画面")
        val audioRep = reps.filter { it.mime == "audio/mp4" && it.codecs.startsWith("mp4a") }
            .maxByOrNull { if (it.id == "140") Int.MAX_VALUE else it.bandwidth }
            ?: throw IOException("没有 AAC 声音")

        val keys = mutableListOf<Pair<String, Long>>()
        for (rep in listOf(videoRep, audioRep)) {
            val url = Regex("<BaseURL>(.*?)</BaseURL>").find(rep.xml)?.groupValues?.get(1)?.replace("&amp;", "&")
                ?: throw IOException("清单里没有地址")
            val spec = DataSpec.Builder().setUri(Uri.parse(url)).build()
            val key = Media.cacheKeys.buildCacheKey(spec)
            val dataSource = Media.dataSource(context).createDataSource()
            val cacheWriter = CacheWriter(dataSource, spec, null) { length, cached, _ ->
                if (length > 0 && rep === videoRep) {
                    status = "正在存第 $number 首（${cached * 100 / length}%）：${video.title.take(30)}"
                }
            }
            writer = cacheWriter
            try {
                if (playing()) throw InterruptedIOException()
                cacheWriter.cache()
            } finally {
                writer = null
            }
            val length = Media.cache(context).getCachedBytes(key, 0, Long.MAX_VALUE)
            keys += key to length
        }

        val manifest = text.substringBefore("<AdaptationSet") +
            videoRep.setTag + "\n" + videoRep.xml + "\n</AdaptationSet>\n" +
            audioRep.setTag + "\n" + audioRep.xml + "\n</AdaptationSet>\n</Period>\n</MPD>\n"
        Offline.add(context, video, manifest, keys)
        // the cover picture too, so the offline page looks right without internet
        SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(video.thumbnail).build())
        Media.log("离线存好「${video.title.take(24)}」${videoRep.height}p，${formatSize(keys.sumOf { it.second })}")
        val list = Offline.videos(context)
        withContext(Dispatchers.Main) { state.offlineVideos = list }
    }
}
