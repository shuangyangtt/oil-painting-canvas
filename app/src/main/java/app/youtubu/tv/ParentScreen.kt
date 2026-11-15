package app.youtubu.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

/** Hidden parent settings (long press ≡): phone page address + simple channel list. */
@Composable
fun ParentScreen(state: AppState) {
    val context = LocalContext.current
    val server = remember { ParentServer(context.applicationContext, state) }
    DisposableEffect(Unit) {
        server.start()
        onDispose { server.stop() }
    }
    var address by remember { mutableStateOf(localAddress(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            address = localAddress(context)
        }
    }
    val url = address?.let { "http://$it:$PARENT_PORT" }

    BackHandler { state.screen = Screen.Home }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        try {
            firstFocus.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Row(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.width(300.dp)) {
            T("家长设置", size = 30.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (url != null) {
                Box(
                    Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(12.dp),
                ) { QrCode(url) }
                Spacer(Modifier.height(14.dp))
                T("用手机扫码，或在电脑浏览器打开：", size = 15.sp, color = C.dim)
                T(url, size = 22.sp, color = C.yellow, weight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                T("可以粘贴频道链接、搜索频道、删除和排序。手机要和投影仪连同一个 WiFi，这个页面开着的时候才能用。", size = 14.sp, color = C.faint)
            } else {
                T("投影仪还没有连上网络", size = 18.sp, color = C.pink)
            }
            Spacer(Modifier.weight(1f))
            var cacheUsed by remember { mutableStateOf(Media.cacheUsed()) }
            LaunchedEffect(Unit) {
                while (true) {
                    cacheUsed = Media.cacheUsed()
                    delay(3000)
                }
            }
            T("视频缓存：已用 ${formatSize(cacheUsed)} / ${formatSize(Media.cacheLimit)} · 离线 ${state.offlineVideos.size} 首", size = 15.sp, color = C.dim)
            T("自动缓存：${state.precacher.status}", size = 13.sp, color = C.faint, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("返回", Modifier.focusRequester(firstFocus)) { state.screen = Screen.Home }
                TvButton("重新加载频道") {
                    state.refreshAll()
                    state.showToast("正在重新加载所有频道")
                }
                var auto by remember { mutableStateOf(state.store.autoPrecache) }
                TvButton(if (auto) "自动缓存：开" else "自动缓存：关") {
                    auto = !auto
                    state.store.autoPrecache = auto
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            T("电视上的频道（${state.channels.size} 个）", size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                itemsIndexed(state.channels, key = { _, c -> c.id }) { index, channel ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(C.card.copy(alpha = 0.6f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(C.card),
                        ) {
                            if (channel.avatar.isNotEmpty()) {
                                AsyncImage(channel.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        val count = state.rows[channel.id]?.videos?.size ?: 0
                        Column(Modifier.weight(1f)) {
                            T(channel.name, size = 16.sp, maxLines = 1)
                            T(if (count > 0) "$count 首" else "加载中…", size = 12.sp, color = C.faint)
                        }
                        TvButton("↑", enabled = index > 0) { state.moveChannel(channel.id, -1) }
                        TvButton("↓", enabled = index < state.channels.size - 1) { state.moveChannel(channel.id, 1) }
                        val confirming = confirmDelete == channel.id
                        TvButton(if (confirming) "再按一次删除" else "删除", danger = true) {
                            if (confirming) {
                                state.removeChannel(channel.id)
                                confirmDelete = null
                            } else {
                                confirmDelete = channel.id
                            }
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Toast(state.toast) }
}

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val color = if (danger) C.pink else C.yellow
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) color else C.card)
            .border(2.dp, if (focused) color else Color.Transparent, shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(
            text,
            size = 15.sp,
            weight = FontWeight.Bold,
            color = when {
                !enabled -> C.faint.copy(alpha = 0.25f)
                focused -> C.bg
                else -> C.text
            },
        )
    }
}

@Composable
private fun QrCode(text: String) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(Modifier.fillMaxSize()) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
