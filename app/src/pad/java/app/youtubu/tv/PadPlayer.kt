package app.youtubu.tv

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C as MediaC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private const val OVERLAY_MS = 4_000L

/** Full screen player for stored songs. Tap = show the big buttons; the next song follows by itself. */
@OptIn(UnstableApi::class)
@Composable
fun PadPlayer(state: AppState, screen: Screen.Player) {
    val context = LocalContext.current
    val videos = screen.videos
    var index by remember { mutableIntStateOf(screen.index) }
    var playCount by remember { mutableIntStateOf(0) }
    val video = videos[index]
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var overlayUntil by remember { mutableLongStateOf(System.currentTimeMillis() + OVERLAY_MS) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var skips by remember { mutableIntStateOf(0) }
    var subtitles by remember { mutableStateOf(state.store.subtitles) }
    var hasSubtitle by remember { mutableStateOf(false) }

    val player = remember {
        // everything is on the tablet: a bigger buffer (20–60 s) smooths over a busy memory card
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_000, 2_000).build()
        ExoPlayer.Builder(context).setLoadControl(load).build().apply { playWhenReady = true }
    }

    fun showOverlay() {
        overlayUntil = System.currentTimeMillis() + OVERLAY_MS
    }

    fun go(step: Int) {
        index = (index + step).mod(videos.size)
        playCount++
    }

    fun back() {
        state.screen = Screen.Home
    }

    LaunchedEffect(index, playCount) {
        state.currentVideo = video
        position = 0
        duration = 0
        val manifest = Offline.manifestIfComplete(context, video.id)
        if (manifest == null) {
            // removed from storage in the meantime: go on with the next one
            skips++
            if (skips >= videos.size) {
                state.showToast("这些歌已经被删掉了")
                back()
            } else go(1)
            return@LaunchedEffect
        }
        skips = 0
        // stored subtitles play along (shown or hidden with the 字 button); the media source factory parses
        // the WebVTT file while loading (the old SingleSampleMediaSource way fails on Media3 1.11:
        // "Legacy decoding is disabled, can't handle text/vtt samples")
        val sub = Offline.subtitleFile(context, video.id)
        hasSubtitle = sub != null
        val item = MediaItem.Builder().setUri(manifest).setMimeType(MimeTypes.APPLICATION_MPD).apply {
            if (sub != null) setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setSelectionFlags(MediaC.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }.build()
        // local files (manifest, subtitles) directly, video data only from the stored cache
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, Media.offlineSource(context)))
            .createMediaSource(item)
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) go(1)
                // a pause in the middle of a song: logged, so stutters can be traced
                if (playbackState == Player.STATE_BUFFERING && player.currentPosition > 1_000) {
                    Media.log("平板播放缓冲：「${videos[index].title.take(20)}」${formatTime(player.currentPosition / 1000)}")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = player.playWhenReady
            }

            override fun onPlayerError(error: PlaybackException) {
                Media.log("平板播放出错（${error.errorCodeName}）：${error.cause?.message ?: error.message}")
                state.showToast("这首放不了，换下一首")
                go(1)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            state.currentVideo = null
        }
    }

    LaunchedEffect(subtitles) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(MediaC.TRACK_TYPE_TEXT, !subtitles)
            .build()
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            position = player.currentPosition
            duration = player.duration.takeIf { it != MediaC.TIME_UNSET } ?: 0
            delay(250)
        }
    }

    BackHandler { back() }

    val overlay = now < overlayUntil || !playing
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) {
                if (overlay) overlayUntil = 0 else showOverlay()
            },
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    // big, easy to read subtitles on a dark band
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setApplyEmbeddedFontSizes(false)
                        setFractionalTextSize(0.065f)
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE, 0xAA000000.toInt(), android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.WHITE, null,
                            ),
                        )
                    }
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color.Transparent, Color(0xAA000000))),
                ),
            ) {
                // top: back, title, favourite
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(80.dp, C.yellow, { back() }) { BackIcon(Modifier.size(36.dp)) }
                    Spacer(Modifier.width(20.dp))
                    T(video.title, Modifier.weight(1f), size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.width(20.dp))
                    // subtitles on / off (remembered)
                    RoundButton(80.dp, if (subtitles) C.yellow else Color(0x55FFFFFF), {
                        subtitles = !subtitles
                        state.store.subtitles = subtitles
                        state.showToast(
                            if (!subtitles) "字幕已关闭" else if (hasSubtitle) "字幕已打开" else "字幕已打开（这首歌没有字幕）",
                        )
                        showOverlay()
                    }) { T("字", size = 34.sp, color = if (subtitles) C.bg else Color.White, weight = FontWeight.Black) }
                    Spacer(Modifier.width(16.dp))
                    val fav = state.isFavorite(video)
                    RoundButton(80.dp, if (fav) C.pink else Color(0x55FFFFFF), {
                        state.toggleFavorite(video)
                        showOverlay()
                    }) { HeartIcon(Modifier.size(40.dp), Color.White) }
                }

                // middle: previous, play / pause, next
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(96.dp, Color(0x55FFFFFF), { go(-1); showOverlay() }) { SkipIcon(Modifier.size(40.dp), forward = false) }
                    RoundButton(128.dp, C.yellow, {
                        if (player.playWhenReady) player.pause() else player.play()
                        playing = player.playWhenReady
                        showOverlay()
                    }) {
                        if (playing) PauseIcon(Modifier.size(52.dp)) else PlayIcon(Modifier.size(56.dp))
                    }
                    RoundButton(96.dp, Color(0x55FFFFFF), { go(1); showOverlay() }) { SkipIcon(Modifier.size(40.dp), forward = true) }
                }

                // bottom: progress (look only, so a toddler cannot jump around by accident)
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 26.dp)) {
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x44FFFFFF))) {
                        val f = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.yellow))
                    }
                    Spacer(Modifier.height(8.dp))
                    T("${formatTime(position / 1000)} / ${formatTime(duration / 1000)}", size = 16.sp, color = C.dim)
                }
            }
        }
    }
}

@Composable
private fun RoundButton(size: Dp, color: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BackIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.62f, w * 0.15f); lineTo(w * 0.28f, w * 0.5f); lineTo(w * 0.62f, w * 0.85f)
        }
        drawPath(p, C.bg, style = Stroke(w * 0.16f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun PlayIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val p = Path().apply { moveTo(w * 0.25f, w * 0.12f); lineTo(w * 0.88f, w * 0.5f); lineTo(w * 0.25f, w * 0.88f); close() }
        drawPath(p, C.bg)
    }
}

@Composable
private fun PauseIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        drawRoundRect(C.bg, Offset(w * 0.18f, w * 0.12f), androidx.compose.ui.geometry.Size(w * 0.22f, w * 0.76f), r)
        drawRoundRect(C.bg, Offset(w * 0.60f, w * 0.12f), androidx.compose.ui.geometry.Size(w * 0.22f, w * 0.76f), r)
    }
}

@Composable
private fun SkipIcon(modifier: Modifier, forward: Boolean) {
    Canvas(modifier) {
        val w = size.width
        fun x(v: Float) = if (forward) w * v else w * (1 - v)
        val p = Path().apply { moveTo(x(0.12f), w * 0.15f); lineTo(x(0.72f), w * 0.5f); lineTo(x(0.12f), w * 0.85f); close() }
        drawPath(p, Color.White)
        drawRect(Color.White, Offset(minOf(x(0.74f), x(0.88f)), w * 0.15f), androidx.compose.ui.geometry.Size(w * 0.14f, w * 0.7f))
    }
}
