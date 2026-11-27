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

    @Synchronized
    fun cache(context: Context): SimpleCache {
        cache?.let { return it }
        val dir = File(context.applicationContext.filesDir, "video-cache").apply { mkdirs() }
        val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        // projector: 4 GB, leaving at least 1 GB free; tablet: 16 GB of songs for offline use, leaving 2 GB free
        val max = if (BuildConfig.PAD) 16 * GB else 4 * GB
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
    private val downloadHttp by lazy { Net.http.newBuilder().readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** For background downloads (offline songs): same cache, quicker to notice a stuck connection. */
    fun downloadSource(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(downloadHttp))
        .setCacheKeyFactory(cacheKeys)

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
