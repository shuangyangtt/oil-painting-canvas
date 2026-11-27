package app.youtubu.tv

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tablet: the app state and the downloader live as long as the process (not the screen),
 * so the download service keeps going when the app is closed or the screen is off.
 */
object Core {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var state: AppState? = null

    @Synchronized
    fun get(context: Context): AppState {
        state?.let { return it }
        val app = context.applicationContext
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { Net.http })) }
                .crossfade(true)
                .build()
        }
        Thread { Media.cache(app) }.start()
        val s = AppState(Store(app), scope)
        state = s
        s.loadAll()
        s.precacher = Precacher(app, s, scope)
        s.precacher.start()
        refreshOffline(app)
        return s
    }

    fun refreshOffline(context: Context) {
        val s = state ?: return
        scope.launch(Dispatchers.IO) {
            val list = Offline.videos(context.applicationContext)
            withContext(Dispatchers.Main) { s.offlineVideos = list }
        }
    }
}
