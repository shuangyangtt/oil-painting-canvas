package app.youtubu.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val IDLE_MS = 60_000L

fun isCharging(context: Context): Boolean {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
}

/** Still songs to fetch (not finished, not full, not switched off). */
fun downloading(state: AppState): Boolean {
    val s = state.precacher.status
    return state.store.autoPrecache && !s.startsWith("推荐的歌都已存好") && !s.startsWith("已存满") && s != "已关闭"
}

/**
 * Huawei freezes apps a few minutes after the screen goes off, which stops the downloads.
 * So while the tablet is charging and there is still something to download, the app keeps the screen on;
 * after a minute without touches it shows a black "night download" screen at the lowest brightness.
 */
@Composable
fun NightDownload(state: AppState, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val window = (context as Activity).window
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var charging by remember { mutableStateOf(isCharging(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            charging = isCharging(context)
            delay(2_000)
        }
    }

    val keepOn = charging && downloading(state)
    val night = keepOn && state.screen == Screen.Home && now - lastTouch > IDLE_MS

    DisposableEffect(keepOn, night) {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = if (night) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        onDispose { }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch = System.currentTimeMillis()
                    now = lastTouch
                }
            }
        },
    ) {
        content()
        if (night) {
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .clickable(remember { MutableInteractionSource() }, null) { lastTouch = System.currentTimeMillis(); now = lastTouch },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
                    T("正在下载儿歌 · 已存 ${state.offlineVideos.size} 首 · ${formatSize(Media.cacheUsed())} / ${formatSize(Media.cacheLimit)}",
                        size = 18.sp, color = Color(0x88FFFFFF), align = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    T(state.precacher.status, size = 14.sp, color = Color(0x55FFFFFF), align = TextAlign.Center, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    T("点一下屏幕回到儿歌", size = 14.sp, color = Color(0x55FFFFFF))
                }
            }
        }
    }
}
