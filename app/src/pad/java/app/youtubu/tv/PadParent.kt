package app.youtubu.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PadParent(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { state.screen = Screen.Home }

    var used by remember { mutableLongStateOf(Media.cacheUsed()) }
    var auto by remember { mutableStateOf(state.store.autoPrecache) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            used = Media.cacheUsed()
            delay(2_000)
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(
            Modifier.fillMaxWidth().background(C.bgLight).padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("家长设置", size = 26.sp, weight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Btn("完成", C.yellow, C.bg) { state.screen = Screen.Home }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Card {
                    T("下载", size = 20.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val limit = Media.cacheLimit.coerceAtLeast(1)
                    T(
                        "已存 ${state.offlineVideos.size} 首 · ${formatSize(used)} / ${formatSize(Media.cacheLimit)}",
                        size = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x33FFFFFF))) {
                        Box(
                            Modifier.fillMaxWidth((used.toFloat() / limit).coerceIn(0f, 1f)).height(14.dp)
                                .clip(RoundedCornerShape(7.dp)).background(C.yellow),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    T("现在：" + state.precacher.status, size = 16.sp, color = C.dim)
                    Spacer(Modifier.height(6.dp))
                    T(
                        "顺序：先「最喜欢」，再每个频道轮流（最受欢迎的先存）。每首 720p。只用家里的 WiFi 下载（手机热点不下载，不会用流量）。存满 16 GB 就停止下载，带出去只放存好的歌，不需要网络。" +
                            "华为平板关屏几分钟后会冻结应用、停止下载，所以：插着电、开着油图布时屏幕会保持亮着，1 分钟没人碰就变成黑色的「夜间下载」画面。睡前插上电、打开油图布放着就行。",
                        size = 14.sp, color = C.faint,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("自动下载", Modifier.weight(1f), size = 18.sp)
                        Switch(auto) {
                            auto = it
                            state.store.autoPrecache = it
                            if (it) DownloadService.start(context)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Btn("刷新歌单", C.card, C.text) {
                            state.refreshAll()
                            state.showToast("正在刷新各频道的歌单")
                        }
                        Btn(if (confirmClear) "再点一次：全部删掉" else "清空所有下载", if (confirmClear) C.pink else C.card, C.text) {
                            if (!confirmClear) {
                                confirmClear = true
                                scope.launch { delay(4_000); confirmClear = false }
                            } else {
                                confirmClear = false
                                scope.launch(Dispatchers.IO) {
                                    Media.clearCache(context)
                                    withContext(Dispatchers.Main) {
                                        state.offlineVideos = emptyList()
                                        state.showToast("已清空，会重新开始下载")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card {
                    T("频道（${state.channels.size} 个）", size = 20.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    T("只下载这些频道的歌。删掉的频道，已经存好的歌会放在「更多儿歌」里。", size = 14.sp, color = C.faint)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = TextStyle(color = C.text, fontSize = 18.sp),
                            cursorBrush = SolidColor(C.yellow),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(C.card)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            decorationBox = { inner ->
                                if (input.isEmpty()) T("粘贴频道链接、@名字，或频道名", size = 18.sp, color = C.faint)
                                inner()
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Btn(if (adding) "查找中…" else "添加", C.yellow, C.bg) {
                            val text = input.trim()
                            if (text.isEmpty() || adding) return@Btn
                            adding = true
                            scope.launch {
                                try {
                                    val id = state.api.resolveChannel(text)
                                    if (id == null) state.showToast("没找到这个频道")
                                    else if (state.hasChannel(id)) state.showToast("这个频道已经有了")
                                    else {
                                        val ch = state.api.channelInfo(id)
                                        state.addChannel(ch)
                                        input = ""
                                        state.showToast("已添加：${ch.name}")
                                    }
                                } catch (e: Exception) {
                                    state.showToast("查找失败：${e.message}")
                                }
                                adding = false
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val counts = state.offlineVideos.groupingBy { it.authorId }.eachCount()
                    for (ch in state.channels) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(ch.avatar, null, Modifier.size(42.dp).clip(CircleShape).background(C.card), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                T(ch.name, size = 18.sp, maxLines = 1)
                                T("已存 ${counts[ch.id] ?: 0} 首", size = 14.sp, color = C.faint)
                            }
                            val sure = confirmRemove == ch.id
                            Btn(if (sure) "确定删除" else "删除", if (sure) C.pink else C.card, C.text) {
                                if (!sure) {
                                    confirmRemove = ch.id
                                    scope.launch { delay(4_000); if (confirmRemove == ch.id) confirmRemove = null }
                                } else {
                                    confirmRemove = null
                                    state.removeChannel(ch.id)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card {
                    T("关于", size = 20.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    T("油图布儿歌 平板版 ${BuildConfig.VERSION_NAME} · 视频来源 ${state.store.instance}", size = 14.sp, color = C.faint)
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 900.dp).clip(RoundedCornerShape(20.dp)).background(C.bgLight).padding(22.dp),
        content = content,
    )
}

@Composable
private fun Btn(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
    ) { T(text, size = 17.sp, color = fg, weight = FontWeight.Bold) }
}

@Composable
private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) Color(0xFF34C759) else Color(0x44FFFFFF))
            .clickable { onChange(!on) }
            .padding(4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White)) }
}
