package com.drivelift.app.ui

import androidx.annotation.OptIn
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DriveVideoStream
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException

internal sealed interface VideoProblem {
    data object AuthExpired : VideoProblem
    data class Failed(val message: String) : VideoProblem
}

/** Turns a player error into what the viewer shows. */
@OptIn(UnstableApi::class)
internal fun videoProblemFor(error: PlaybackException): VideoProblem {
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
            VideoProblem.Failed("This phone can't play this video's format. You can open it in Google Drive instead.")
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
            VideoPlayer(current, onProblem = { problem = it })
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
private fun VideoPlayer(stream: DriveVideoStream, onProblem: (VideoProblem) -> Unit) {
    val context = LocalContext.current
    val currentOnProblem by rememberUpdatedState(onProblem)
    val player = remember(stream) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(stream.callFactory)))
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
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(stream.url))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = currentOnProblem(videoProblemFor(error))
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // No background playback: leaving the screen pauses, and the user resumes with play.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                // The screen stays on only while the video is actually playing; paused, ended or
                // failed playback lets the phone time out as usual.
                keepScreenOn = player.isPlaying
                val view = this
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        view.keepScreenOn = isPlaying
                    }
                })
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(top = Tokens.ViewerTopClearance)
            .testTag("viewer-video"),
    )
}
