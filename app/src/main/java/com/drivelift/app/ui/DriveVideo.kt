package com.drivelift.app.ui

import androidx.annotation.OptIn
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DriveVideoStream
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException

internal sealed interface VideoProblem {
    data object AuthExpired : VideoProblem
    data class Failed(val message: String) : VideoProblem
}

/**
 * What the shared player plays: a Drive stream (with the account's authorized connection) or a
 * video on this phone (read through its content URI, no account or network involved).
 */
internal class VideoSource(
    /** Stable identity for saved position, speed and zoom. */
    val key: String,
    /** File name, used for saved frames. */
    val name: String,
    val uri: String,
    val dataSourceFactory: androidx.media3.datasource.DataSource.Factory,
    val local: Boolean,
)

/** Turns a player error into what the viewer shows. */
@OptIn(UnstableApi::class)
internal fun videoProblemFor(error: PlaybackException, local: Boolean = false): VideoProblem {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return when (cause.responseCode) {
                401 -> VideoProblem.AuthExpired
                403, 404, 410 -> VideoProblem.Failed("Google Drive did not allow this video to stream. It may have been moved or deleted.")
                else -> VideoProblem.Failed("Google Drive returned an error (HTTP ${cause.responseCode}) while streaming this video.")
            }
        }
        cause = cause.cause
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            VideoProblem.Failed(
                if (local) "This phone can't play this video's format. You can open it in another app instead."
                else "This phone can't play this video's format. You can open it in Google Drive instead.",
            )
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            VideoProblem.Failed("Connection problem while streaming. Check the connection and try again.")
        else -> VideoProblem.Failed(
            "The video stopped (" + error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ') + ").",
        )
    }
}

/**
 * Streams a Drive video inside DriveLift with the connected account. Nothing is downloaded in
 * full first, playback pauses when the app leaves the screen, and the player is released when
 * the viewer closes.
 */
@Composable
internal fun VideoPreviewContent(
    vm: AppViewModel,
    file: DriveFile,
    conn: DriveConn,
    cachedThumb: ImageBitmap?,
    onOpenExternally: () -> Unit,
    onChromeVisibleChange: (Boolean) -> Unit = {},
) {
    var reload by remember(file.id) { mutableIntStateOf(0) }
    var stream by remember(file.id) { mutableStateOf<DriveVideoStream?>(null) }
    var problem by remember(file.id) { mutableStateOf<VideoProblem?>(null) }

    LaunchedEffect(file.id, reload, conn) {
        if (problem == VideoProblem.AuthExpired && conn != DriveConn.CONNECTED) return@LaunchedEffect
        stream = null
        problem = null
        try {
            stream = vm.openVideoStream(file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: AuthRequiredException) {
            problem = VideoProblem.AuthExpired
        } catch (e: Exception) {
            problem = VideoProblem.Failed(describeError(e))
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val current = stream
        if (current != null && problem == null) {
            val source = remember(current) {
                @OptIn(UnstableApi::class)
                VideoSource(file.id, file.name, current.url, OkHttpDataSource.Factory(current.callFactory), local = false)
            }
            VideoPlayer(vm, source, onProblem = { problem = it }, onChromeVisibleChange = onChromeVisibleChange)
        } else {
            if (cachedThumb != null) {
                Image(
                    bitmap = cachedThumb,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Same scrim as the image viewer, so notices stay readable over bright frames.
            Box(Modifier.fillMaxSize().testTag("video-placeholder-scrim").background(GlassPalette.ThumbScrim))
            when (val p = problem) {
                null -> GlassPanel(Modifier.padding(Tokens.Edge)) {
                    Column(Modifier.padding(Tokens.Edge), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(Tokens.Medium))
                        Text("Preparing video", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                VideoProblem.AuthExpired -> ViewerNoticeCard(
                    title = "Google Drive authorization expired",
                    body = "Sign in to your connected Google Drive account again to play this video.",
                    primaryLabel = "Reconnect",
                    onPrimary = { vm.connectDrive(interactive = true) },
                    secondaryLabel = "Try again",
                    onSecondary = { reload++ },
                )
                is VideoProblem.Failed -> ViewerNoticeCard(
                    title = "Could not play video",
                    body = p.message,
                    primaryLabel = "Try again",
                    onPrimary = { reload++ },
                    secondaryLabel = "Open in Google Drive",
                    onSecondary = onOpenExternally,
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayer(
    vm: AppViewModel,
    source: VideoSource,
    onProblem: (VideoProblem) -> Unit,
    onChromeVisibleChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Survive rotation and process recreation: where the video was, its speed and whether it played.
    var savedPosition by rememberSaveable(source.key) { mutableLongStateOf(0L) }
    var savedSpeed by rememberSaveable(source.key) { mutableFloatStateOf(1f) }
    var savedPlay by rememberSaveable(source.key) { mutableStateOf(true) }
    val currentOnChrome by rememberUpdatedState(onChromeVisibleChange)
    val scope = rememberCoroutineScope()
    val currentOnProblem by rememberUpdatedState(onProblem)
    val player = remember(source) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source.dataSourceFactory))
            // Takes audio focus like any media app: other audio pauses or ducks, and a call or
            // another player pauses this one. Unplugging headphones pauses instead of blaring.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps recently played media, so a step or skip back reuses it instead of fetching
            // it from Drive again. Bounded by the player's usual buffer size limit.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBackBuffer(Tokens.Motion.VIDEO_BACK_BUFFER, /* retainBackBufferFromKeyframe = */ true)
                    .build(),
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(source.uri), savedPosition)
                setPlaybackSpeed(savedSpeed)
                prepare()
                playWhenReady = savedPlay
            }
    }
    var playing by remember(player) { mutableStateOf(false) }
    // What the user asked for, which stays true while a playing stream buffers.
    var wantPlay by remember(player) { mutableStateOf(savedPlay) }
    var ended by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var quality by remember(player) { mutableStateOf(qualityLabel(0, 0, -1f)) }
    var capturing by remember(player) { mutableStateOf(false) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var interaction by remember(player) { mutableIntStateOf(0) }
    // While a finger is on the scrubber the controls never hide, so the seek is not lost.
    var scrubbing by remember(player) { mutableStateOf(false) }
    // The last position this player was told to seek to. While the video is still there, the
    // frame on screen is the one at or after it; after playback it is the one at or before.
    // Restoring after rotation or recreation is itself a seek to the saved position.
    var lastSeekTarget by remember(player) { mutableStateOf<Long?>(savedPosition) }
    // Step seeks run one at a time; see StepSeekQueue. [stepIssued] restarts the give-up timer.
    val steps = remember(player) { StepSeekQueue() }
    var stepIssued by remember(player) { mutableIntStateOf(0) }
    var stepTappedAt by remember(player) { mutableLongStateOf(0L) }
    fun issueStep(target: Long) {
        player.seekTo(target)
        lastSeekTarget = target
        stepIssued++
    }
    fun stepFinished() {
        steps.completed()?.let(::issueStep)
    }
    fun seekTo(target: Long) {
        steps.clear()
        player.seekTo(target)
        lastSeekTarget = target
    }
    var textureView by remember(player) { mutableStateOf<TextureView?>(null) }
    var videoAspect by remember(player) { mutableFloatStateOf(DEFAULT_ASPECT) }
    // False until the player reports the real picture size; until then the default shape is only
    // a placeholder and must not be used to limit a restored zoom position.
    var aspectKnown by remember(player) { mutableStateOf(false) }
    var viewport by remember(player) { mutableStateOf(IntSize.Zero) }
    // Zoom stays exactly where the user leaves it (through hiding controls, seeking, pausing and
    // rotation) until they pinch again or tap Fit.
    var savedZoom by rememberSaveable(source.key) { mutableFloatStateOf(1f) }
    var savedPanX by rememberSaveable(source.key) { mutableFloatStateOf(0f) }
    var savedPanY by rememberSaveable(source.key) { mutableFloatStateOf(0f) }
    val zoom = remember(source.key) {
        ImageTransformState().apply {
            scale = savedZoom
            offset = Offset(savedPanX, savedPanY)
        }
    }
    fun rememberZoom() {
        savedZoom = zoom.scale
        savedPanX = zoom.offset.x
        savedPanY = zoom.offset.y
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = currentOnProblem(videoProblemFor(error, source.local))

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                savedPlay = playWhenReady
                wantPlay = playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
            }

            // After a seek the player draws the frame at the target; that is when a step is done.
            override fun onRenderedFirstFrame() {
                if (steps.inFlight != null) {
                    // Local log only, to measure step latency on a phone (adb logcat -s DriveLiftStep).
                    android.util.Log.d(
                        "DriveLiftStep",
                        "frame shown ${android.os.SystemClock.elapsedRealtime() - stepTappedAt} ms after the step tap",
                    )
                    stepFinished()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                // The screen stays on only while the video is actually playing.
                textureView?.keepScreenOn = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                quality = qualityLabel(videoSize.width, videoSize.height, player.videoFormat?.frameRate ?: -1f)
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    aspectKnown = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Position for the scrubber: state feedback only, refreshed while the viewer is open.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            savedPosition = position
            if (player.duration > 0) duration = player.duration
            delay(Tokens.Motion.VIDEO_POSITION_REFRESH.toLong())
        }
    }
    // A step seek that never draws a frame must not block the next step.
    LaunchedEffect(stepIssued) {
        if (steps.inFlight == null) return@LaunchedEffect
        delay(Tokens.Motion.VIDEO_STEP_TIMEOUT.toLong())
        stepFinished()
    }
    // Controls step aside after a few seconds of playback without a touch.
    LaunchedEffect(controlsVisible, playing, interaction, scrubbing) {
        if (controlsVisible && playing && !scrubbing) {
            delay(Tokens.Motion.VIDEO_CONTROLS_HIDE.toLong())
            controlsVisible = false
        }
    }
    // A rotation or resize changes the fitted picture and the screen, so a restored pan offset is
    // limited again at once; the zoom level itself is kept.
    LaunchedEffect(viewport, videoAspect, aspectKnown) {
        if (aspectKnown && viewport.width > 0 && viewport.height > 0) {
            zoom.applyVideoGesture(Offset.Zero, 1f, fittedVideoSize(viewport, videoAspect), viewport)
            rememberZoom()
        }
    }
    // No background playback: leaving the screen pauses, and the user resumes with play. A
    // rotation also stops the activity briefly; that keeps playing where it was.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) player.pause()
    }
    // Hidden controls give a clear, full-screen picture: the viewer's top bar and Android's
    // status and navigation bars step aside too. A swipe from the edge shows the bars briefly.
    DisposableEffect(controlsVisible) {
        currentOnChrome(controlsVisible)
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible) insets?.show(WindowInsetsCompat.Type.systemBars())
        else insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            currentOnChrome(true)
        }
    }

    fun touched() {
        controlsVisible = true
        interaction++
    }

    fun captureFrame() {
        val view = textureView
        val size = player.videoSize
        if (view == null || size.width <= 0 || size.height <= 0) {
            scope.launch { vm.messages.emit("The video is not showing a picture yet. Try again in a moment.") }
            return
        }
        capturing = true
        val at = player.currentPosition
        // The decoded frame at the video's own resolution; zoom and screen size do not affect it.
        val bitmap = view.getBitmap(size.width, size.height)
        scope.launch {
            if (bitmap != null) {
                vm.saveVideoFrame(bitmap, source.name, at)
            } else {
                vm.messages.emit("Couldn't capture this frame.")
            }
            capturing = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().onSizeChanged { viewport = it },
            contentAlignment = Alignment.Center,
        ) {
            // Full screen, fitted to the video's shape; the top bar overlays it while the
            // controls are shown. A TextureView, unlike a SurfaceView, can be scaled for zoom.
            AndroidView(
                factory = { viewContext ->
                    TextureView(viewContext).also { view ->
                        player.setVideoTextureView(view)
                        view.keepScreenOn = player.isPlaying
                        textureView = view
                    }
                },
                modifier = Modifier
                    .aspectRatio(videoAspect)
                    .testTag("viewer-video")
                    .graphicsLayer(
                        scaleX = zoom.scale,
                        scaleY = zoom.scale,
                        translationX = zoom.offset.x,
                        translationY = zoom.offset.y,
                    ),
            )
        }
        // Tap shows or hides the controls; double tap on the left or right half skips 10 s; pinch
        // zooms smoothly up to 4x and a drag moves around the zoomed picture.
        Box(
            Modifier
                .fillMaxSize()
                .testTag("video-touch")
                .pointerInput(player) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom.applyVideoGesture(pan, gestureZoom, fittedVideoSize(viewport, videoAspect), viewport)
                        rememberZoom()
                    }
                }
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) controlsVisible = false else touched()
                        },
                        onDoubleTap = { offset ->
                            val delta = if (offset.x < size.width / 2f) -SKIP_MS else SKIP_MS
                            seekTo(skipTarget(player.currentPosition, duration, delta))
                            touched()
                        },
                    )
                },
        )
        if (controlsVisible) {
            VideoControls(
                state = VideoUiState(
                    playing = wantPlay && !ended,
                    position = position,
                    duration = duration,
                    speed = savedSpeed,
                    quality = quality,
                    capturing = capturing,
                    zoomed = zoom.scale > ZOOMED_THRESHOLD,
                ),
                onPlayPause = {
                    // Decided by intent, not by isPlaying: a buffering stream can still be paused.
                    if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                        player.pause()
                    } else {
                        // After the end, Play starts the video again from the beginning. A step
                        // still waiting is applied first, so playback starts where the user stepped.
                        val waiting = steps.pending
                        if (player.playbackState == Player.STATE_ENDED) seekTo(0)
                        else if (waiting != null) seekTo(waiting)
                        else steps.clear()
                        player.play()
                    }
                    touched()
                },
                onSeek = {
                    seekTo(it)
                    position = it
                    touched()
                },
                onSpeed = {
                    savedSpeed = nextVideoSpeed(savedSpeed)
                    player.setPlaybackSpeed(savedSpeed)
                    touched()
                },
                onCapture = {
                    captureFrame()
                    touched()
                },
                onStepFrame = { forward ->
                    // Stepping implies a still picture: a tap while playing pauses first, then
                    // moves about one frame. Rapid taps count from the newest step target.
                    player.pause()
                    val queued = steps.latest
                    val now = player.currentPosition
                    val target = frameStepTarget(
                        queued ?: now, duration, player.videoFormat?.frameRate ?: -1f, forward,
                        afterSeek = queued != null || lastSeekTarget == now,
                    )
                    stepTappedAt = android.os.SystemClock.elapsedRealtime()
                    steps.request(target)?.let(::issueStep)
                    position = target
                    touched()
                },
                onScrubbingChange = { active ->
                    scrubbing = active
                    touched()
                },
                onFit = {
                    zoom.reset()
                    rememberZoom()
                    touched()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(Tokens.Edge),
            )
        }
    }
}


/** The size the video occupies when fitted inside [viewport] with its own shape. */
internal fun fittedVideoSize(viewport: IntSize, aspect: Float): androidx.compose.ui.geometry.Size {
    if (viewport.width <= 0 || viewport.height <= 0 || aspect <= 0f) return androidx.compose.ui.geometry.Size.Zero
    val viewportAspect = viewport.width.toFloat() / viewport.height
    return if (viewportAspect > aspect) {
        androidx.compose.ui.geometry.Size(viewport.height * aspect, viewport.height.toFloat())
    } else {
        androidx.compose.ui.geometry.Size(viewport.width.toFloat(), viewport.width / aspect)
    }
}

/**
 * Pinch and drag for the video picture. Panning is limited by the zoomed picture itself, so its
 * edges stop at the screen edges and it can never be dragged out of view; along an axis where the
 * zoomed picture is still smaller than the screen it stays centred.
 */
internal fun ImageTransformState.applyVideoGesture(
    pan: Offset,
    zoom: Float,
    picture: androidx.compose.ui.geometry.Size,
    viewport: IntSize,
) {
    val nextScale = (scale * zoom).coerceIn(1f, MAX_VIDEO_ZOOM)
    val maxX = ((picture.width * nextScale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((picture.height * nextScale - viewport.height) / 2f).coerceAtLeast(0f)
    scale = nextScale
    offset = if (nextScale <= 1.01f) {
        Offset.Zero
    } else {
        Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }
}

private const val MAX_VIDEO_ZOOM = 4f
private const val DEFAULT_ASPECT = 16f / 9f
private const val ZOOMED_THRESHOLD = 1.05f

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
