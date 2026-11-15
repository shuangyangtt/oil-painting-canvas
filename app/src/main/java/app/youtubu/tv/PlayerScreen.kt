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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.common.Format
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /** 0 = DASH with the cached manifest, 1 = DASH with a fresh manifest, 2 = 360p single file (most robust) */
    var level by remember { mutableIntStateOf(0) }
    // lowered for the rest of this session when the projector cannot keep up (frozen picture)
    var heightCap by remember { mutableIntStateOf(state.store.maxHeight) }
    val scope = rememberCoroutineScope()
    val loadJob = remember { arrayOfNulls<Job>(1) }

    val selector = remember { DefaultTrackSelector(context) }
    fun applyTrackLimits() {
        selector.parameters = selector.buildUponParameters()
            // H.264 has a hardware decoder on every projector; AV1 and VP9 are often decoded in software,
            // which cannot keep up: the picture freezes while the music goes on
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
            .setMaxVideoSize(Int.MAX_VALUE, heightCap)
            // switching resolution must not re-create the decoder mid-song (another cause of frozen pictures)
            .setAllowVideoNonSeamlessAdaptiveness(false)
            .build()
    }
    val player = remember {
        applyTrackLimits()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).setTrackSelector(selector).build().apply { playWhenReady = true }
    }

    fun showOverlay() {
        overlayUntil = System.currentTimeMillis() + OVERLAY_MS
    }

    fun next(step: Int = 1) {
        index = (index + step).mod(videos.size)
        playCount++
        showOverlay()
    }

    fun short(title: String) = if (title.length > 28) title.take(28) + "…" else title

    // also called from the player listener, so it reads the current index instead of a captured video
    fun load(newLevel: Int, startMs: Long) {
        level = newLevel
        val video = videos[index]
        loadJob[0]?.cancel()
        loadJob[0] = scope.launch {
            val dataSource = Media.dataSource(context)
            // a song stored for offline use plays from the projector, without asking the internet at all
            val offline = if (newLevel == 0) Offline.manifestIfComplete(context, video.id) else null
            if (offline != null) Media.log("从投影仪上的离线缓存播放")
            val source = if (offline != null) {
                DashMediaSource.Factory(DefaultDashChunkSource.Factory(dataSource), DefaultDataSource.Factory(context))
                    .createMediaSource(MediaItem.Builder().setUri(offline).setMimeType(MimeTypes.APPLICATION_MPD).build())
            } else if (newLevel < 2) {
                val manifest = try {
                    Media.manifest(context, state.api, video.id, fresh = newLevel == 1)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Media.log("「${short(video.title)}」取清单失败：${e.message}，改用 360p")
                    null
                }
                if (manifest == null) {
                    level = 2
                    ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(state.api.mp4Url(video.id)))
                } else {
                    DashMediaSource.Factory(DefaultDashChunkSource.Factory(dataSource), DefaultDataSource.Factory(context))
                        .createMediaSource(MediaItem.Builder().setUri(manifest).setMimeType(MimeTypes.APPLICATION_MPD).build())
                }
            } else {
                ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(state.api.mp4Url(video.id)))
            }
            player.setMediaSource(source, startMs)
            player.prepare()
            player.play()
        }
    }

    LaunchedEffect(index, playCount) {
        state.currentVideo = video
        if (screen.rowKey == OFFLINE_KEY) state.lastOfflineIndex = index else state.lastFocus = screen.rowKey to index
        position = 0
        duration = 0
        Media.log("播放「${short(video.title)}」")
        state.precacher.pause()
        load(0, 0)
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
                // carry on where it stopped instead of starting the song again
                val at = player.currentPosition
                Media.log("出错（${error.errorCodeName}）：${error.cause?.message ?: error.message}")
                if (!Net.online(context)) {
                    // no internet: go on with the next song stored on the projector, if there is one
                    val stored = (1..videos.size).map { (index + it).mod(videos.size) }
                        .firstOrNull { Offline.isComplete(context, videos[it].id) }
                    if (stored != null && stored != index) {
                        state.showToast("没有网络，放一首存好的歌")
                        index = stored
                        playCount++
                    } else {
                        state.showToast("没有网络。去「离线模式」看存好的歌吧")
                        state.screen = Screen.Home
                    }
                } else if (level < 2) {
                    load(level + 1, at)
                } else {
                    state.showToast("这首歌现在放不了，换下一首")
                    next()
                }
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Media.log("解码器：$decoderName")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                Media.log("画面：${format.sampleMimeType} ${format.width}×${format.height}")
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (droppedFrames >= 20) Media.log("掉帧 $droppedFrames 帧")
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            loadJob[0]?.cancel()
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
            player.release()
            state.currentVideo = null
        }
    }

    // progress bar, overlay timer and the frozen picture watchdog
    LaunchedEffect(Unit) {
        var lastRendered = -1
        var lastChange = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            position = player.currentPosition
            duration = player.duration.takeIf { it != MediaC.TIME_UNSET } ?: 0

            val counters = player.videoDecoderCounters
            if (counters != null && player.isPlaying && player.playbackState == Player.STATE_READY) {
                counters.ensureUpdated()
                val rendered = counters.renderedOutputBufferCount
                if (rendered != lastRendered) {
                    lastRendered = rendered
                    lastChange = now
                } else if (now - lastChange > 3000) {
                    // the music plays on but no new picture for 3 seconds: the decoder is stuck
                    val at = player.currentPosition
                    lastChange = now + 5000
                    if (heightCap > 720) {
                        heightCap = 720
                        Media.log("画面卡住，降到 720p 从 ${formatTime(at / 1000)} 接着放")
                        applyTrackLimits()
                        load(level.coerceAtMost(1), at)
                    } else if (level < 2) {
                        Media.log("画面卡住，改用 360p 从 ${formatTime(at / 1000)} 接着放")
                        load(2, at)
                    } else {
                        Media.log("画面卡住，换下一首")
                        next()
                    }
                }
            } else {
                lastChange = now
            }
            delay(250)
        }
    }

    BackHandler { state.screen = if (screen.rowKey == OFFLINE_KEY) Screen.Offline else Screen.Home }

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
