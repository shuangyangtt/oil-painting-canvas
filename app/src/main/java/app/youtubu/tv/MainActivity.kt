package app.youtubu.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    private val handler = Handler(Looper.getMainLooper())
    private var menuDown = false
    private var menuLongFired = false
    private val menuLongPress = Runnable { fireMenuLong() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { Net.http })) }
                .crossfade(true)
                .build()
        }
        state = AppState(Store(applicationContext), lifecycleScope)
        state.loadAll()
        setContent { App(state) }
    }

    /**
     * The ≡ key: short press = add/remove the song from the favourites,
     * long press (about 1 second) = hidden parent settings.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MENU) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    menuDown = true
                    menuLongFired = false
                    handler.postDelayed(menuLongPress, 900)
                } else if (event.isLongPress || event.eventTime - event.downTime >= 900) {
                    fireMenuLong()
                }
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(menuLongPress)
                if (menuDown && !menuLongFired) state.onMenuShort()
                menuDown = false
            }
        }
        return true
    }

    private fun fireMenuLong() {
        if (!menuDown || menuLongFired) return
        menuLongFired = true
        handler.removeCallbacks(menuLongPress)
        state.onMenuLong()
    }
}
