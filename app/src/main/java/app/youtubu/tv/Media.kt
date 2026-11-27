package app.youtubu.tv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GB = 1024L * 1024 * 1024
private const val MANIFEST_MAX_AGE_MS = 5 * 60 * 60 * 1000L

/** Video cache (up to 4 GB), cached DASH manifests and a small playback log for the parent page. */
@OptIn(UnstableApi::class)
object Media {
    private var cache: SimpleCache? = null
    var cacheLimit = 0L
        private set

    private var root: File? = null

    /** Whether the songs are kept on the tablet's memory card. */
    var onSdCard = false
        private set

    /**
     * Where the video cache and the offline song list live (always together, so one never outlives the other).
     * Tablet: on the memory card when one is inserted, else inside the tablet; projector: inside.
     */
    @Synchronized
    fun root(context: Context): File {
        root?.let { return it }
        val app = context.applicationContext
        var chosen = app.filesDir
        if (BuildConfig.PAD) {
            val card = try {
                app.getExternalFilesDirs(null).drop(1).firstOrNull { d ->
                    d != null && android.os.Environment.isExternalStorageRemovable(d) &&
                        android.os.Environment.getExternalStorageState(d) == android.os.Environment.MEDIA_MOUNTED
                }
            } catch (_: Exception) {
                null
            }
            if (card != null) {
                chosen = card
                onSdCard = true
            }
        }
        root = chosen
        return chosen
    }

    @Synchronized
    fun cache(context: Context): SimpleCache {
        cache?.let { return it }
        val app = context.applicationContext
        val base = root(app)
        if (onSdCard) {
            // songs stored inside the tablet by an older version: removed, the memory card takes over
            val old = File(app.filesDir, "video-cache")
            if (old.exists()) {
                try {
                    SimpleCache.delete(old, StandaloneDatabaseProvider(app))
                } catch (_: Exception) {
                    old.deleteRecursively()
                }
                File(app.filesDir, "offline").deleteRecursively()
                Media.log("已删除平板内部的旧缓存，改存到内存卡")
            }
        }
        val dir = File(base, "video-cache").apply { mkdirs() }
        val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        // projector: 4 GB, leaving at least 1 GB free; tablet: 128 GB of songs (memory card) for offline use, leaving 2 GB free
        val max = if (BuildConfig.PAD) 128 * GB else 4 * GB
        val keepFree = if (BuildConfig.PAD) 2 * GB else GB
        cacheLimit = minOf(max, dir.usableSpace + used - keepFree).coerceAtLeast(256L * 1024 * 1024)
        return SimpleCache(dir, LeastRecentlyUsedCacheEvictor(cacheLimit), StandaloneDatabaseProvider(context.applicationContext))
            .also { cache = it }
    }

    fun cacheUsed(): Long = cache?.cacheSpace ?: 0

    fun clearCache(context: Context) {
        val c = cache(context)
        for (key in c.keys.toList()) c.removeResource(key)
        File(context.filesDir, "manifests").listFiles()?.forEach { it.delete() }
        Offline.clear(context)
    }

    /**
     * Invidious stream URLs change on every request (expire, signature, ...), so the cache key is built
     * from what identifies the file itself: format (itag), size (clen) and upload version (lmt).
     */
    val cacheKeys = CacheKeyFactory { spec ->
        val uri = spec.uri
        val path = uri.path.orEmpty()
        when {
            path.endsWith("/videoplayback") && uri.getQueryParameter("itag") != null ->
                "v:" + uri.getQueryParameter("itag") + ":" + uri.getQueryParameter("clen") + ":" + uri.getQueryParameter("lmt")
            path.endsWith("/sabr-segment") ->
                "s:" + uri.getQueryParameter("video_id") + ":" + uri.getQueryParameter("itag") + ":" +
                    uri.getQueryParameter("is_audio") + ":" + uri.getQueryParameter("seq") + ":" + uri.getQueryParameter("init")
            path.endsWith("/latest_version") ->
                "mp4:" + uri.getQueryParameter("id") + ":" + uri.getQueryParameter("itag")
            else -> spec.key ?: uri.toString()
        }
    }

    fun dataSource(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Net.http))
        .setCacheKeyFactory(cacheKeys)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // downloads give up on a stuck connection after 15 seconds and try that piece again
    // own connection per request (HTTP/1.1): with HTTP/2 all parallel downloads would share one TCP connection
    private val downloadHttp by lazy {
        Net.http.newBuilder()
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool(64, 2, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }

    /** For background downloads (offline songs): same cache, quicker to notice a stuck connection. */
    fun downloadSource(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(downloadHttp))
        .setCacheKeyFactory(cacheKeys)

    class Speed(val pingMs: Long, val bytesPerSecond: Long, val problem: String?)

    /**
     * Speed test of one server: how fast it answers (a channel's info) and how fast a song downloads from it
     * (manifest of [videoId], then the first 4 MB of its picture). Three requests, so it is gentle.
     */
    suspend fun speedTest(url: String, videoId: String): Speed = withContext(Dispatchers.IO) {
        val api = Invidious { url }
        val t0 = System.currentTimeMillis()
        try {
            api.channelInfo("UCLsooMJoIpl_7ux2jvdPB-Q")
        } catch (e: Exception) {
            return@withContext Speed(0, 0, "连不上（${e.message?.take(40)}）")
        }
        val ping = System.currentTimeMillis() - t0
        val text = try {
            fetchManifestText(api, videoId)
        } catch (e: Exception) {
            return@withContext Speed(ping, 0, "取不到播放清单（${e.message?.take(40)}）")
        }
        val rep = Regex("<Representation\\b[^>]*>.*?</Representation>", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .map { it.value }.firstOrNull { it.contains("avc1") && it.contains("<BaseURL>") }
            ?: return@withContext Speed(ping, 0, "播放清单是空的")
        val stream = Regex("<BaseURL>(.*?)</BaseURL>").find(rep)!!.groupValues[1].replace("&amp;", "&")
        val t1 = System.currentTimeMillis()
        val bytes = try {
            downloadHttp.newCall(Request.Builder().url(stream).header("Range", "bytes=0-4194303").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body.source().let { src ->
                    var n = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        val read = src.read(buf, 65536)
                        if (read < 0) break
                        n += read
                        buf.clear()
                    }
                    n
                }
            }
        } catch (e: Exception) {
            return@withContext Speed(ping, 0, "下载失败（${e.message?.take(40)}）")
        }
        val ms = (System.currentTimeMillis() - t1).coerceAtLeast(1)
        Speed(ping, bytes * 1000 / ms, null)
    }

    /**
     * The DASH manifest of a video as a local file. It is kept for 5 hours (the stream URLs in it stay
     * valid that long), so a song played again starts without asking Invidious, straight from the cache.
     */
    suspend fun manifest(context: Context, api: Invidious, videoId: String, fresh: Boolean): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "manifests").apply { mkdirs() }
            val file = File(dir, "$videoId.mpd")
            val age = System.currentTimeMillis() - file.lastModified()
            if (!fresh && file.exists() && age < MANIFEST_MAX_AGE_MS) return@withContext Uri.fromFile(file)
            val text = fetchManifestText(api, videoId)
            file.writeText(text)
            // old manifests are useless after a few hours
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MANIFEST_MAX_AGE_MS }?.forEach { it.delete() }
            Uri.fromFile(file)
        }

    /** Downloads a DASH manifest from Invidious, with all stream URLs made absolute. */
    suspend fun fetchManifestText(api: Invidious, videoId: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(api.dashUrl(videoId)).build()
            val text = Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("清单 HTTP ${response.code}")
                val body = response.body.string()
                // Invidious sometimes writes relative stream URLs; the manifest is stored as a local file,
                // so they are made absolute against the address the manifest really came from (after redirects)
                // (both kinds of manifest: plain stream files in <BaseURL>, or 5 second "sabr-segment" pieces
                // in SegmentTemplate initialization/media attributes, which use $Number$ placeholders)
                val origin = response.request.url
                val base = origin.scheme + "://" + origin.host + (if (origin.port != 443 && origin.port != 80) ":" + origin.port else "")
                body
                    .replace(Regex("<BaseURL>(/[^<]*)</BaseURL>")) { "<BaseURL>" + base + it.groupValues[1] + "</BaseURL>" }
                    .replace(Regex("""(initialization|media)="(/[^"]*)"""")) { it.groupValues[1] + "=\"" + base + it.groupValues[2] + "\"" }
            }
            if (!text.contains("<MPD")) throw IOException("清单内容不对")
            text
        }

    // ---- playback log, shown on the parent page so problems on the projector can be diagnosed ----

    private val log = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun log(message: String) {
        log.addLast(timeFormat.format(Date()) + "  " + message)
        while (log.size > 150) log.removeFirst()
        android.util.Log.i("Youtubu", message)
    }

    @Synchronized
    fun logLines(): List<String> = log.toList().asReversed()
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "%d MB".format(bytes / (1024 * 1024))
}
