package app.youtubu.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PadApp(state: AppState) {
    NightDownload(state) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            when (val screen = state.screen) {
                is Screen.Player -> PadPlayer(state, screen)
                Screen.Parent -> PadParent(state)
                else -> PadHome(state)
            }
            Toast(state.toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        }
    }
}

private class PadRow(val key: String, val title: String, val avatar: String?, val videos: List<Video>)

/** Only the songs stored on the tablet, so everything plays at once and without internet. */
private fun padRows(state: AppState): List<PadRow> {
    val stored = state.offlineVideos
    val byId = stored.associateBy { it.id }
    val rows = mutableListOf<PadRow>()
    val favs = state.favorites.mapNotNull { byId[it.id] }
    if (favs.isNotEmpty()) rows += PadRow(FAVORITES_KEY, "最喜欢", null, favs)
    val used = mutableSetOf<String>()
    for (ch in state.channels) {
        val mine = stored.filter { it.authorId == ch.id }
        if (mine.isEmpty()) continue
        // same order as the channel's list (most popular first)
        val order = state.rows[ch.id]?.videos?.withIndex()?.associate { it.value.id to it.index } ?: emptyMap()
        rows += PadRow(ch.id, ch.name, ch.avatar, mine.sortedBy { order[it.id] ?: Int.MAX_VALUE })
        used += mine.map { it.id }
    }
    val rest = stored.filter { it.id !in used }
    if (rest.isNotEmpty()) rows += PadRow(OFFLINE_KEY, "更多儿歌", null, rest)
    return rows
}

@Composable
fun PadHome(state: AppState) {
    // the home screen never leaves the app with the back gesture (a toddler swipes a lot)
    BackHandler { }
    val rows = padRows(state)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.bgLight, C.bg))),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("油图布儿歌", size = 30.sp, color = C.yellow, weight = FontWeight.Black)
            Spacer(Modifier.width(18.dp))
            T("${state.offlineVideos.size} 首", size = 18.sp, color = C.faint)
            Spacer(Modifier.weight(1f))
            ParentLock { state.screen = Screen.Parent }
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T("🎵", size = 80.sp)
                    Spacer(Modifier.height(12.dp))
                    T("正在下载儿歌，马上就好", size = 28.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    T(state.precacher.status, size = 18.sp, color = C.dim, align = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                items(rows, key = { it.key }) { row ->
                    Column(Modifier.padding(top = 14.dp)) {
                        Row(Modifier.padding(start = 28.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (row.key == FAVORITES_KEY) {
                                HeartIcon(Modifier.size(34.dp), C.pink)
                            } else if (!row.avatar.isNullOrEmpty()) {
                                AsyncImage(
                                    row.avatar, null,
                                    Modifier.size(38.dp).clip(CircleShape).background(C.card),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            T(row.title, size = 22.sp, weight = FontWeight.Bold, maxLines = 1)
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            itemsIndexed(row.videos, key = { _, v -> row.key + v.id }) { i, v ->
                                PadCard(v) { state.screen = Screen.Player(row.key, row.videos, i) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadCard(video: Video, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")
    Column(
        Modifier
            .width(300.dp)
            .scale(scale)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        AsyncImage(
            video.thumbnail, null,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)).background(C.card),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(8.dp))
        T(video.title, Modifier.padding(horizontal = 6.dp), size = 16.sp, color = C.dim, maxLines = 2)
    }
}

/** Parents hold the little lock for 2 seconds; a child tapping it does nothing. */
@Composable
fun ParentLock(onOpen: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0x22FFFFFF))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    var job: Job? = null
                    job = scope.launch {
                        val t0 = System.currentTimeMillis()
                        while (true) {
                            progress = ((System.currentTimeMillis() - t0) / 2000f).coerceAtMost(1f)
                            if (progress >= 1f) {
                                progress = 0f
                                onOpen()
                                break
                            }
                            delay(16)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width
            val c = Color(0x99FFFFFF)
            drawArc(c, 180f, 180f, false, Offset(w * 0.22f, w * 0.05f), androidx.compose.ui.geometry.Size(w * 0.56f, w * 0.6f), style = Stroke(w * 0.12f))
            drawRoundRect(c, Offset(w * 0.1f, w * 0.4f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.58f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
        }
        if (progress > 0f) {
            Canvas(Modifier.size(52.dp)) {
                drawArc(C.yellow, -90f, 360f * progress, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
fun HeartIcon(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, h * 0.88f)
            cubicTo(w * 0.05f, h * 0.55f, w * -0.02f, h * 0.18f, w * 0.27f, h * 0.12f)
            cubicTo(w * 0.40f, h * 0.10f, w * 0.48f, h * 0.2f, w / 2, h * 0.28f)
            cubicTo(w * 0.52f, h * 0.2f, w * 0.60f, h * 0.10f, w * 0.73f, h * 0.12f)
            cubicTo(w * 1.02f, h * 0.18f, w * 0.95f, h * 0.55f, w / 2, h * 0.88f)
            close()
        }
        drawPath(p, color)
    }
}
