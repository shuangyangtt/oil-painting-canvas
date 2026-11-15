package app.youtubu.tv

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C as MediaC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private const val SEEK_MS = 10_000L
private const val OVERLAY_MS = 4_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(state: AppState, screen: Screen.Player) {
    val context = LocalContext.current
    val videos = screen.videos
    var index by remember { mutableIntStateOf(screen.index) }
    // bumped for every song change, so a one-song list (e.g. one favourite) starts again from the beginning
    var playCount by remember { mutableIntStateOf(0) }
    val video = videos[index]

    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var overlayUntil by remember { mutableLongStateOf(System.currentTimeMillis() + OVERLAY_MS) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var usingFallback by remember { mutableStateOf(false) }

    val dataSource = remember { OkHttpDataSource.Factory(Net.http) }
    val player = remember {
        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, state.store.maxHeight)
                .build()
        }
        ExoPlayer.Builder(context).setTrackSelector(selector).build().apply { playWhenReady = true }
    }

    fun showOverlay() {
        overlayUntil = System.currentTimeMillis() + OVERLAY_MS
    }

    fun next(step: Int = 1) {
        index = (index + step).mod(videos.size)
        playCount++
        showOverlay()
    }

    // also called from the player listener, so it reads the current index instead of a captured video
    fun load(fallback: Boolean) {
        usingFallback = fallback
        val video = videos[index]
        val source = if (fallback) {
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(state.api.mp4Url(video.id)))
        } else {
            DashMediaSource.Factory(dataSource).createMediaSource(
                MediaItem.Builder().setUri(state.api.dashUrl(video.id)).setMimeType(MimeTypes.APPLICATION_MPD).build(),
            )
        }
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    LaunchedEffect(index, playCount) {
        state.currentVideo = video
        state.lastFocus = screen.rowKey to index
        position = 0
        duration = 0
        load(fallback = false)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_ENDED) next()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = player.playWhenReady
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!usingFallback) {
                    load(fallback = true)
                } else {
                    state.showToast("这首歌现在放不了，换下一首")
                    next()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            state.currentVideo = null
        }
    }

    // progress bar and overlay timer
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            position = player.currentPosition
            duration = player.duration.takeIf { it != MediaC.TIME_UNSET } ?: 0
            delay(250)
        }
    }

    BackHandler { state.screen = Screen.Home }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            if (player.playWhenReady) player.pause() else player.play()
                            playing = player.playWhenReady
                            showOverlay()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); playing = true; showOverlay(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); playing = false; showOverlay(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        player.seekTo((player.currentPosition - SEEK_MS).coerceAtLeast(0))
                        showOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val target = player.currentPosition + SEEK_MS
                        if (duration in 1..target) next() else player.seekTo(target)
                        showOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> { next(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> { next(-1); true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showOverlay(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering) Spinner(Modifier.align(Alignment.Center))

        val overlay = !playing || now < overlayUntil
        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!playing && !buffering) PauseBadge(Modifier.align(Alignment.Center))
                if (state.isFavorite(video)) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(32.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(C.pink),
                        contentAlignment = Alignment.Center,
                    ) { T("♥", size = 30.sp) }
                }
                BottomBar(video, position, duration, Modifier.align(Alignment.BottomCenter))
            }
        }
        Toast(state.toast, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun BottomBar(video: Video, position: Long, duration: Long, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 32.dp),
    ) {
        T(video.title, size = 24.sp, weight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        T(video.author, size = 16.sp, color = C.dim, maxLines = 1)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            T(formatTime(position / 1000), size = 15.sp, color = C.dim)
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x55FFFFFF)),
            ) {
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(C.yellow),
                )
            }
            T(formatTime(duration / 1000), size = 15.sp, color = C.dim)
        }
        Spacer(Modifier.height(10.dp))
        T("◀ ▶ 后退/快进   OK 暂停   ≡ 最喜欢   ↩ 选别的歌", size = 13.sp, color = C.faint)
    }
}

@Composable
private fun PauseBadge(modifier: Modifier) {
    Box(
        modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(Color(0xAA000000)),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(2) {
                Box(
                    Modifier
                        .width(16.dp)
                        .height(50.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White),
                )
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(modifier.size(72.dp)) {
        drawArc(
            color = C.yellow,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()),
            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
