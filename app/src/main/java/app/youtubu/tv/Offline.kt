package app.youtubu.tv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException
import java.io.InterruptedIOException

private const val MB = 1024L * 1024
/** room left in the cache for normal (online) playback */
private val ONLINE_RESERVE = if (BuildConfig.PAD) 1024 * MB else 400 * MB
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

    // next to the video cache (memory card or inside), so the list always matches what is stored
    private fun dir(context: Context) = File(Media.root(context), "offline").apply { mkdirs() }

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
                File(dir(context), "$id.vtt").delete()
                File(dir(context), "$id.nosub").delete()
            }
            save(context)
        }
        return entries.values.map { it.video }.reversed()
    }

    /** The song's stored subtitles, if it has any. */
    fun subtitleFile(context: Context, videoId: String): File? =
        File(dir(context), "$videoId.vtt").takeIf { it.exists() }

    /** Whether subtitles were already looked for (found or not), so each song is asked about once. */
    fun subtitleTried(context: Context, videoId: String): Boolean =
        File(dir(context), "$videoId.vtt").exists() || File(dir(context), "$videoId.nosub").exists()

    /** Forgets every "no subtitles" mark, so those songs are looked up again (after the rules changed). */
    fun retrySubtitles(context: Context) {
        dir(context).listFiles { f -> f.name.endsWith(".nosub") }?.forEach { it.delete() }
        // empty subtitle files (header only, no lines) are removed too, so those songs are asked about again
        dir(context).listFiles { f -> f.name.endsWith(".vtt") }?.forEach { f ->
            if (!f.readText().contains("-->")) f.delete()
        }
    }

    fun saveSubtitle(context: Context, videoId: String, vtt: String?) {
        if (vtt != null) File(dir(context), "$videoId.vtt").writeText(vtt)
        else File(dir(context), "$videoId.nosub").writeText("")
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

    /** download speed of the last seconds, bytes per second */
    var speed by mutableLongStateOf(0L)
        private set

    /** projector: one song at a time (it pauses while a video plays); tablet: several songs at once */
    // (32 songs = up to 64 connections, picture and sound each; measured: more connections = faster)
    private val workers = if (BuildConfig.PAD) 32 else 1

    private class Paused : IOException("暂停")

    @Volatile
    private var pausing = false
    private val writers = Collections.synchronizedSet(mutableSetOf<CacheWriter>())
    private var job: Job? = null
    private val lock = Any()
    private val failed = mutableSetOf<String>()
    private val failures = mutableMapOf<String, Int>()
    /** songs being downloaded right now (so two workers never take the same one) */
    private val busy = mutableSetOf<String>()
    /** what each worker is doing, for the status line */
    private val progress = ConcurrentHashMap<Int, String>()
    private val newBytes = AtomicLong()

    // the Invidious server blocks an IP that asks for too many manifests at once, so manifests are fetched
    // one at a time with a gap (the video data itself comes from its media servers, in parallel)
    private val manifestGate = Semaphore(1)
    private val localGate = Semaphore(8)
    @Volatile
    private var lastManifest = 0L
    /** all workers wait until then when the server says "too many requests" or cannot be reached */
    @Volatile
    private var holdUntil = 0L
    private var holdStep = 60_000L
    private var lastRefresh = System.currentTimeMillis()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            delay(if (BuildConfig.PAD) 3_000 else 15_000) // let the home screen load first
            // download speed, averaged over a few seconds
            launch {
                while (isActive) {
                    delay(2_000)
                    val bytes = newBytes.getAndSet(0) / 2
                    speed = (speed + bytes) / 2
                    updateStatus()
                }
            }
            // tablet: keeps running for days, so the song lists are refreshed now and then (new songs get stored too)
            if (BuildConfig.PAD) launch {
                while (isActive) {
                    if (System.currentTimeMillis() - lastRefresh > 6 * 60 * 60_000L && Net.unmetered(context)) {
                        lastRefresh = System.currentTimeMillis()
                        withContext(Dispatchers.Main) { state.loadAll() }
                    }
                    delay(60_000)
                }
            }
            // tablet: subtitles for songs stored before (or whose subtitles could not be fetched then)
            if (BuildConfig.PAD) launch {
                // v2.2: subtitles also in Chinese / English when the channel's language has none
                // v2.4: also drop empty subtitle files and ask again
                if (state.store.subtitleRules < 3) {
                    Offline.retrySubtitles(context)
                    state.store.subtitleRules = 3
                }
                delay(20_000)
                while (isActive) {
                    val todo = if (holdUntil > System.currentTimeMillis() || !Net.unmetered(context) || !state.store.autoPrecache) null
                    else Offline.videos(context).firstOrNull { !Offline.subtitleTried(context, it.id) }
                    if (todo == null) {
                        delay(60_000)
                        continue
                    }
                    try {
                        fetchSubtitle(todo)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (serverBusy(e)) holdFor(e)
                        else Offline.saveSubtitle(context, todo.id, null)
                        delay(10_000)
                    }
                }
            }
            repeat(workers) { w ->
                launch {
                    delay(w * if (BuildConfig.PAD) 300L else 1_500L)
                    while (isActive) {
                        val wait = step(w)
                        if (wait > 0) delay(wait)
                    }
                }
            }
        }
    }

    /** Stops the current downloads at once (a song started playing); they continue later where they stopped. */
    fun pause() {
        pausing = true
        synchronized(writers) { writers.toList() }.forEach { it.cancel() }
    }

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

    private fun updateStatus() {
        val items = progress.values.toList()
        if (items.isEmpty()) return
        val sp = if (speed > 0) " · ${formatSpeed(speed)}" else ""
        val shown = items.take(3).joinToString("；") + if (items.size > 3) " 等" else ""
        status = "正在存 ${items.size} 首$sp：$shown"
    }

    /** One step of a worker's loop; returns how long to wait before the next one. */
    private suspend fun step(w: Int): Long {
        if (!state.store.autoPrecache) {
            status = "已关闭"
            return 30_000
        }
        if (playing()) {
            status = "正在放视频，暂停下载"
            return 3_000
        }
        pausing = false
        // tablet: while a song plays, only 4 downloads go on, so the memory card is not too busy to read the video
        if (BuildConfig.PAD && state.screen is Screen.Player && w >= PLAY_WORKERS) return 3_000
        if (!Net.online(context)) {
            status = "没有网络，等网络恢复后继续"
            return 30_000
        }
        if (BuildConfig.PAD && !Net.unmetered(context)) {
            status = "只用 WiFi 下载：现在连的是手机热点或流量，不下载"
            return 60_000
        }
        val hold = holdUntil - System.currentTimeMillis()
        if (hold > 0) {
            status = "服务器让我们慢一点，${hold / 60_000 + 1} 分钟后继续"
            return hold
        }
        if (Media.cacheUsed() > Media.cacheLimit - ONLINE_RESERVE) {
            status = "已存满（${formatSize(Media.cacheUsed())}）"
            return 20 * 60_000
        }
        val target = synchronized(lock) {
            candidates().firstOrNull { it.id !in busy && !Offline.isComplete(context, it.id) }?.also { busy += it.id }
        }
        if (target == null) {
            // nothing free right now, but other songs are still downloading or song lists still loading:
            // look again soon (sleeping long here left only a few workers busy)
            val stillGoing = synchronized(lock) { busy.isNotEmpty() } || state.rows.values.any { it.loading }
            if (stillGoing) return 30_000
            status = "推荐的歌都已存好"
            return if (BuildConfig.PAD) 10 * 60_000L else 20 * 60_000L
        }
        return try {
            download(target, w)
            0
        } catch (e: Paused) {
            status = "正在放视频，暂停下载"
            3_000
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // the server is overloaded or blocks us for a while: everybody waits (1 min, then longer), the song is fine
            if (serverBusy(e)) {
                holdFor(e)
                return 5_000
            }
            // a song is only given up after 3 failures (a short network hiccup must not rule it out)
            val count = synchronized(lock) {
                val c = (failures[target.id] ?: 0) + 1
                failures[target.id] = c
                if (c >= 3) failed += target.id
                c
            }
            Media.log("离线缓存「${target.title.take(24)}」失败（第 $count 次）：${e.message}")
            10_000
        } finally {
            synchronized(lock) { busy -= target.id }
            progress.remove(w)
            updateStatus()
        }
    }

    private suspend fun download(video: Video, w: Int) {
        val name = video.title.take(16)
        progress[w] = "$name 0%"
        updateStatus()

        // the plain kind of manifest (whole stream files) is needed; Invidious sometimes sends the segmented kind
        var text = ""
        for (attempt in 1..5) {
            text = try {
                manifestText(video.id)
            } catch (e: IOException) {
                if (attempt == 5 || serverBusy(e)) throw e
                delay(2_000)
                continue
            }
            if (!text.contains("sabr-segment")) break
            delay(2_000)
        }
        holdStep = 60_000L
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
        // 720p H.264 (hardware decoding on every device) and AAC audio
        val videoRep = reps.filter { it.mime == "video/mp4" && it.codecs.startsWith("avc1") && it.height <= 720 }.maxByOrNull { it.height }
            ?: throw IOException("没有 H.264 画面")
        val audioRep = reps.filter { it.mime == "audio/mp4" && it.codecs.startsWith("mp4a") }
            .maxByOrNull { if (it.id == "140") Int.MAX_VALUE else it.bandwidth }
            ?: throw IOException("没有 AAC 声音")

        val streams = listOf(videoRep, audioRep).map { rep ->
            val url = Regex("<BaseURL>(.*?)</BaseURL>").find(rep.xml)?.groupValues?.get(1)?.replace("&amp;", "&")
                ?: throw IOException("清单里没有地址")
            Uri.parse(url)
        }
        val cache = Media.cache(context)
        val sizes = streams.map { it.getQueryParameter("clen")?.toLongOrNull() ?: 0L }
        val total = sizes.sum()
        val done = AtomicLong(streams.indices.sumOf { i ->
            val key = Media.cacheKeys.buildCacheKey(DataSpec(streams[i]))
            if (sizes[i] > 0) cache.getCachedBytes(key, 0, sizes[i]) else 0L
        })
        var lastPct = -1
        fun onBytes(n: Long) {
            if (total <= 0) return
            val pct = (done.addAndGet(n) * 100 / total).toInt().coerceIn(0, 100)
            if (pct != lastPct) {
                lastPct = pct
                progress[w] = "$name $pct%"
            }
        }

        // picture and sound at the same time
        val keys = coroutineScope {
            streams.indices.map { i -> async(Dispatchers.IO) { cacheStream(streams[i], sizes[i], ::onBytes) } }.awaitAll()
        }

        val manifest = text.substringBefore("<AdaptationSet") +
            videoRep.setTag + "\n" + videoRep.xml + "\n</AdaptationSet>\n" +
            audioRep.setTag + "\n" + audioRep.xml + "\n</AdaptationSet>\n</Period>\n</MPD>\n"
        Offline.add(context, video, manifest, keys)
        if (BuildConfig.PAD) {
            try {
                fetchSubtitle(video)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (serverBusy(e)) holdFor(e) // the song is stored; its subtitles come later
            }
        }
        // the cover picture too, so the offline page looks right without internet
        SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(video.thumbnail).build())
        Media.log("离线存好「${video.title.take(24)}」${videoRep.height}p，${formatSize(keys.sumOf { it.second })}")
        val list = Offline.videos(context)
        withContext(Dispatchers.Main) { state.offlineVideos = list }
    }

    /**
     * Every request to the Invidious server goes through here. A public server: one at a time, with a gap
     * (it blocks clients that ask too fast). Our own server on the home laptop: up to 8 at once.
     */
    private suspend fun <T> gated(block: suspend () -> T): T {
        if (state.store.instance == LOCAL_INSTANCE) return localGate.withPermit { block() }
        return manifestGate.withPermit {
            val wait = lastManifest + MANIFEST_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                block()
            } finally {
                lastManifest = System.currentTimeMillis()
            }
        }
    }

    private suspend fun manifestText(videoId: String): String = gated { Media.fetchManifestText(state.api, videoId) }

    /** Fetches and stores the song's subtitles (in its channel's language), once per song. */
    private suspend fun fetchSubtitle(video: Video) {
        if (Offline.subtitleTried(context, video.id)) return
        val lang = state.channels.firstOrNull { it.id == video.authorId }?.lang ?: guessLang(video.author)
        val vtt = gated { state.api.subtitle(video.id, lang) }
        Offline.saveSubtitle(context, video.id, vtt)
    }

    /** Server overloaded or blocking us: every worker waits (1 minute, then longer each time). */
    private fun holdFor(e: Exception) {
        // another server first; waiting only when every server has problems
        if (state.failover(e.message)) return
        synchronized(lock) {
            if (System.currentTimeMillis() >= holdUntil) {
                holdUntil = System.currentTimeMillis() + holdStep
                Media.log("服务器忙（${e.message?.take(60)}），全部暂停 ${holdStep / 60_000} 分钟")
                holdStep = minOf(holdStep * 2, 30 * 60_000L)
            }
        }
    }

    private fun serverBusy(e: Exception): Boolean {
        val m = e.message ?: return false
        return m.contains("429") || m.contains("HTTP 5") || m.contains("ailed to connect") || m.contains("Unable to resolve")
    }

    /**
     * Stores one stream file in 4 MB pieces (YouTube slows down one long connection; short ranges go at full speed).
     * A failed or stuck piece is tried again on its own; pieces already stored are skipped.
     */
    private suspend fun cacheStream(uri: Uri, size: Long, onBytes: (Long) -> Unit): Pair<String, Long> {
        val key = Media.cacheKeys.buildCacheKey(DataSpec(uri))
        val cache = Media.cache(context)
        if (size <= 0) {
            runWriter(DataSpec.Builder().setUri(uri).build(), onBytes)
        } else {
            var pos = 0L
            while (pos < size) {
                val len = minOf(CHUNK, size - pos)
                if (cache.getCachedBytes(key, pos, len) < len) {
                    val spec = DataSpec.Builder().setUri(uri).setPosition(pos).setLength(len).build()
                    var attempt = 0
                    while (true) {
                        try {
                            runWriter(spec, onBytes)
                            break
                        } catch (e: Paused) {
                            throw e
                        } catch (e: IOException) {
                            attempt++
                            if (attempt >= 4) throw IOException("第 ${pos / CHUNK + 1} 段下载失败：${e.message}")
                            delay(1_000L * attempt)
                        }
                    }
                }
                pos += len
            }
        }
        val length = cache.getCachedBytes(key, 0, Long.MAX_VALUE)
        if (size > 0 && length < size) throw IOException("没有存完整")
        return key to length
    }

    private fun runWriter(spec: DataSpec, onBytes: (Long) -> Unit) {
        val writer = CacheWriter(Media.downloadSource(context).createDataSource(), spec, null) { _, _, new ->
            newBytes.addAndGet(new)
            onBytes(new)
        }
        writers += writer
        try {
            if (pausing) throw Paused()
            writer.cache()
        } catch (e: InterruptedIOException) {
            // cancelled because a video started, or the connection got stuck (read timeout)
            if (pausing) throw Paused()
            throw IOException("连接卡住，重新连接")
        } finally {
            writers -= writer
        }
    }
}

private const val CHUNK = 4L * 1024 * 1024
private const val MANIFEST_GAP_MS = 700L
private const val PLAY_WORKERS = 4

fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024))
    else -> "%d KB/s".format(bytesPerSecond / 1024)
}
