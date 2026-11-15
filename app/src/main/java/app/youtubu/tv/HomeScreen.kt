package app.youtubu.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CARD_W = 264.dp
private val CARD_H = 148.dp

/** The picker: one big row of song cards per channel, favourites first. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(state: AppState) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(rowKey: String, index: Int) = requesters.getOrPut("$rowKey#$index") { FocusRequester() }
    val scope = rememberCoroutineScope()
    var homeHasFocus by remember { mutableStateOf(false) }

    // rows that have something to show (a failed channel without cache is left out)
    val channelRows = state.channels.filter { ch ->
        val row = state.rows[ch.id]
        row == null || row.loading || row.videos.isNotEmpty()
    }
    val rowKeys = listOf(FAVORITES_KEY) + channelRows.map { it.id }

    fun play(rowKey: String, videos: List<Video>, index: Int) {
        state.lastFocus = rowKey to index
        state.screen = Screen.Player(rowKey, videos, index)
    }

    fun onCardFocused(rowKey: String, index: Int, video: Video?) {
        state.currentVideo = video
        state.lastFocus = rowKey to index
        // TV style: the focused row slides up under the header, its title stays visible
        // the favourites row scrolls to the very top so the header with the hints stays visible
        val position = if (rowKey == FAVORITES_KEY) 0 else rowKeys.indexOf(rowKey) + 1
        if (position >= 0 && state.homeList.firstVisibleItemIndex != position) {
            scope.launch { state.homeList.animateScrollToItem(position) }
        }
    }

    // focus the card the child came back from, or the first card once something is loaded
    val firstLoaded = state.favorites.isNotEmpty() || channelRows.any { state.rows[it.id]?.videos?.isNotEmpty() == true }
    LaunchedEffect(firstLoaded) {
        if (homeHasFocus) return@LaunchedEffect
        val target = state.lastFocus
        repeat(20) {
            val ok = try {
                if (target != null && target.first in rowKeys) {
                    state.homeList.scrollToItem(rowKeys.indexOf(target.first) + 1)
                    delay(50)
                    requester(target.first, target.second).requestFocus()
                } else {
                    requester(FAVORITES_KEY, 0).requestFocus()
                }
                true
            } catch (e: IllegalStateException) {
                false
            }
            if (ok) return@LaunchedEffect
            delay(100)
        }
    }

    // Back on the picker does nothing: a toddler pressing it should not leave the app
    BackHandler {
        scope.launch { state.homeList.animateScrollToItem(0) }
        try {
            requester(FAVORITES_KEY, 0).requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.bgLight, C.bg)))
            .onFocusChanged { homeHasFocus = it.hasFocus },
    ) {
        LazyColumn(
            state = state.homeList,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 300.dp),
        ) {
            item(key = "header") { Header(state) }
            item(key = FAVORITES_KEY) {
                FavoritesRow(state, ::requester, ::play, ::onCardFocused)
            }
            items(channelRows, key = { it.id }) { channel ->
                val row = state.rows[channel.id] ?: RowState(loading = true)
                VideoRow(
                    rowKey = channel.id,
                    title = channel.name,
                    header = { Avatar(channel.avatar) },
                    row = row,
                    requester = ::requester,
                    onPlay = ::play,
                    onFocused = ::onCardFocused,
                )
            }
        }
        Toast(state.toast, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun Header(state: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(C.yellow),
            contentAlignment = Alignment.Center,
        ) { T("▶", size = 16.sp, color = C.bg) }
        Spacer(Modifier.width(12.dp))
        T("油图布儿歌", size = 22.sp, weight = FontWeight.Bold)
        Spacer(Modifier.width(32.dp))
        OfflineButton(state.offlineVideos.size) { state.screen = Screen.Offline }
        Spacer(Modifier.weight(1f))
        T("按 ≡ 加入最喜欢 · 长按 ≡ 家长设置", size = 13.sp, color = C.faint)
    }
}

/** Big button for the offline page: songs stored on the projector, for when the internet is slow. */
@Composable
private fun OfflineButton(count: Int, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "scale")
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) C.yellow else C.card)
            .border(3.dp, if (focused) C.yellow else C.faint.copy(alpha = 0.3f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("⬇", size = 18.sp, color = if (focused) C.bg else C.yellow, weight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        T("离线模式", size = 18.sp, color = if (focused) C.bg else C.text, weight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        T("$count 首", size = 15.sp, color = if (focused) C.bg else C.dim)
    }
}

@Composable
private fun FavoritesRow(
    state: AppState,
    requester: (String, Int) -> FocusRequester,
    onPlay: (String, List<Video>, Int) -> Unit,
    onFocused: (String, Int, Video?) -> Unit,
) {
    if (state.favorites.isEmpty()) {
        Column(Modifier.padding(top = 14.dp)) {
            RowTitle("最喜欢") { Heart() }
            Box(Modifier.padding(horizontal = 48.dp, vertical = 14.dp)) {
                HintCard(
                    "还没有最喜欢的歌\n看到喜欢的，按遥控器上的 ≡ 键",
                    Modifier.focusRequester(requester(FAVORITES_KEY, 0)),
                    onFocused = { onFocused(FAVORITES_KEY, 0, null) },
                )
            }
        }
    } else {
        VideoRow(
            rowKey = FAVORITES_KEY,
            title = "最喜欢",
            header = { Heart() },
            row = RowState(state.favorites),
            requester = requester,
            onPlay = onPlay,
            onFocused = onFocused,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VideoRow(
    rowKey: String,
    title: String,
    header: @Composable () -> Unit,
    row: RowState,
    requester: (String, Int) -> FocusRequester,
    onPlay: (String, List<Video>, Int) -> Unit,
    onFocused: (String, Int, Video?) -> Unit,
) {
    Column(Modifier.padding(top = 14.dp)) {
        RowTitle(title, header)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (row.videos.isEmpty()) {
                items(5) { SkeletonCard() }
            } else {
                itemsIndexed(row.videos, key = { _, v -> v.id }) { index, video ->
                    VideoCard(
                        video,
                        Modifier.focusRequester(requester(rowKey, index)),
                        onFocused = { onFocused(rowKey, index, video) },
                        onClick = { onPlay(rowKey, row.videos, index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowTitle(title: String, icon: @Composable () -> Unit) {
    Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        T(title, size = 21.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Avatar(url: String) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(C.card),
    ) {
        if (url.isNotEmpty()) {
            AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun Heart() {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(C.pink),
        contentAlignment = Alignment.Center,
    ) { T("♥", size = 22.sp, color = Color.White) }
}

@Composable
internal fun VideoCard(video: Video, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "scale")
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(CARD_W)) {
        Box(
            modifier
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .size(CARD_W, CARD_H)
                .shadow(if (focused) 24.dp else 0.dp, shape, ambientColor = C.yellow, spotColor = C.yellow)
                .clip(shape)
                .background(C.card)
                .border(if (focused) 5.dp else 0.dp, if (focused) C.yellow else Color.Transparent, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        ) {
            AsyncImage(video.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (video.seconds > 0) {
                T(
                    formatTime(video.seconds.toLong()),
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    size = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(if (focused) 16.dp else 8.dp))
        T(
            video.title,
            Modifier.padding(horizontal = 4.dp),
            size = 14.sp,
            color = if (focused) C.text else C.faint,
            weight = if (focused) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
        )
    }
}

@Composable
private fun HintCard(text: String, modifier: Modifier, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .size(CARD_W * 1.6f, CARD_H)
            .clip(shape)
            .background(C.card.copy(alpha = 0.6f))
            .border(if (focused) 4.dp else 2.dp, if (focused) C.yellow else C.faint, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        T(text, size = 17.sp, color = C.dim, align = TextAlign.Center)
    }
}

@Composable
private fun SkeletonCard() {
    Box(
        Modifier
            .size(CARD_W, CARD_H)
            .clip(RoundedCornerShape(18.dp))
            .background(C.card.copy(alpha = 0.5f)),
    )
}

@Composable
fun Toast(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    T(
        text,
        modifier
            .padding(top = 28.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xEE000000))
            .padding(horizontal = 28.dp, vertical = 14.dp),
        size = 22.sp,
        weight = FontWeight.Bold,
    )
}
