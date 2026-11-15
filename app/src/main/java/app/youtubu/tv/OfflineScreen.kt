package app.youtubu.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Offline mode: every song stored on the projector, playable without internet. */
@Composable
fun OfflineScreen(state: AppState) {
    val context = LocalContext.current
    var bytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { Offline.videos(context) }
        state.offlineVideos = list
        bytes = withContext(Dispatchers.IO) { Offline.totalBytes(context) }
    }
    val videos = state.offlineVideos
    val gridState = rememberLazyGridState()
    val requesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun requester(i: Int) = requesters.getOrPut(i) { FocusRequester() }

    LaunchedEffect(videos.isNotEmpty()) {
        if (videos.isEmpty()) return@LaunchedEffect
        val target = state.lastOfflineIndex.coerceIn(0, videos.size - 1)
        repeat(20) {
            try {
                gridState.scrollToItem(target)
                delay(50)
                requester(target).requestFocus()
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                delay(100)
            }
        }
    }

    BackHandler { state.screen = Screen.Home }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.bgLight, C.bg))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(C.yellow),
                    contentAlignment = Alignment.Center,
                ) { T("⬇", size = 22.sp, color = C.bg, weight = FontWeight.Bold) }
                Spacer(Modifier.width(14.dp))
                Column {
                    T("离线模式", size = 26.sp, weight = FontWeight.Bold)
                    T(
                        "这些歌已经存在投影仪上，没有网络也能看 · ${videos.size} 首 · ${formatSize(bytes)}",
                        size = 14.sp,
                        color = C.dim,
                    )
                }
            }
            if (videos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    T(
                        "还没有存好的歌\n投影仪开着、不放视频的时候，会自动把推荐的歌存下来\n（按返回键回去选歌）",
                        size = 20.sp,
                        color = C.dim,
                        align = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    itemsIndexed(videos, key = { _, v -> v.id }) { index, video ->
                        Box(Modifier.padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                            VideoCard(
                                video,
                                Modifier.focusRequester(requester(index)),
                                onFocused = {
                                    state.currentVideo = video
                                    state.lastOfflineIndex = index
                                },
                                onClick = {
                                    state.lastOfflineIndex = index
                                    state.screen = Screen.Player(OFFLINE_KEY, videos, index)
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(0.dp))
        Toast(state.toast, Modifier.align(Alignment.TopCenter))
    }
}
