package com.drivelift.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale

/** Playback speeds offered by the speed button, in order. */
internal val VIDEO_SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)

internal const val SKIP_MS = 10_000L

internal fun nextVideoSpeed(current: Float): Float {
    val i = VIDEO_SPEEDS.indexOfFirst { it == current }
    return VIDEO_SPEEDS[(if (i < 0) 0 else i + 1) % VIDEO_SPEEDS.size]
}

internal fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else String.format(Locale.US, "%sx", speed.toString().trimEnd('0'))

/** 0:07, 4:05, 1:02:09. */
internal fun formatPlaybackTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** The file always plays as the untouched original; this says what that is. */
internal fun qualityLabel(width: Int, height: Int, frameRate: Float): String {
    if (width <= 0 || height <= 0) return "Original quality"
    val fps = if (frameRate > 0f) "  ·  ${Math.round(frameRate)} fps" else ""
    return "Original  ·  ${width}x$height$fps"
}

/** Frames per second assumed when the video does not say. */
internal const val DEFAULT_FRAME_RATE = 30f

/**
 * Where one frame forward or back lands, clamped to the video. The frame on screen is worked out
 * from the position (see [frameIndexAt]), the neighbouring frame number is taken, and the seek
 * goes to that frame's timestamp rounded down. Media3 skips frames earlier than the seek
 * position, so seeking at or just before the timestamp shows exactly that frame; working from
 * frame numbers means repeated steps never drift.
 *
 * [afterSeek] says whether the position is a seek target the player just went to (scrub, skip or
 * an earlier step) rather than where ordinary playback was paused.
 */
internal fun frameStepTarget(
    position: Long,
    duration: Long,
    frameRate: Float,
    forward: Boolean,
    afterSeek: Boolean = true,
): Long {
    val fps = (if (frameRate > 0f) frameRate else DEFAULT_FRAME_RATE).toDouble()
    val frame = frameIndexAt(position, fps, afterSeek)
    val next = (if (forward) frame + 1 else frame - 1).coerceAtLeast(0)
    val target = Math.floor(next * 1000.0 / fps).toLong()
    return if (duration > 0) target.coerceAtMost(duration) else target
}

/**
 * The frame on screen at [position] ms.
 * - After a seek, Media3 shows the first frame whose timestamp is at or after the seek position,
 *   so this rounds up: 1001 ms at 60 fps shows frame 61, and an earlier step target (a frame
 *   timestamp rounded down by under 1 ms) maps back to its own frame at any rate below 1000 fps.
 * - After ordinary playback is paused, Media3 keeps showing the last frame it released, the one
 *   at or before the position, so this rounds down: 1250 ms at 2 fps shows the 1000 ms frame.
 */
internal fun frameIndexAt(position: Long, fps: Double, afterSeek: Boolean = true): Long {
    val exact = position.coerceAtLeast(0) * fps / 1000.0
    return if (afterSeek) Math.ceil(exact - FRAME_EPSILON).toLong() else Math.floor(exact + FRAME_EPSILON).toLong()
}

private const val FRAME_EPSILON = 1e-9

internal fun skipTarget(position: Long, duration: Long, deltaMs: Long): Long {
    val target = (position + deltaMs).coerceAtLeast(0)
    return if (duration > 0) target.coerceAtMost(duration) else target
}

/** What the controls show. Positions are in milliseconds. */
internal data class VideoUiState(
    val playing: Boolean,
    val position: Long,
    val duration: Long,
    val speed: Float,
    val quality: String,
    val capturing: Boolean = false,
    /** The picture is zoomed in; a Fit button brings it back to the whole frame. */
    val zoomed: Boolean = false,
)

/**
 * DriveLift's own player controls on a glass bar: a scrubber that shows where a drag will land,
 * 10-second skips, play/pause, speed and frame capture, with the original quality named above.
 */
@Composable
internal fun VideoControls(
    state: VideoUiState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
    onFit: () -> Unit = {},
    /** True while a finger is dragging the scrubber, false when it lifts. */
    onScrubbingChange: (Boolean) -> Unit = {},
    /** One frame back (false) or forward (true); offered while the video is paused. */
    onStepFrame: (Boolean) -> Unit = {},
) {
    // While dragging, the bar and the time show the target, and the seek happens on release.
    var dragTarget by remember { mutableStateOf<Long?>(null) }
    val shown = dragTarget ?: state.position
    val duration = state.duration.coerceAtLeast(0)

    GlassPanel(modifier.fillMaxWidth().testTag("video-controls")) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)) {
            // Speed and Fit sit beside the quality label, or on their own row on a narrow phone or
            // with large text, so the transport buttons below always keep 48 dp each.
            val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
                LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
            val options: @Composable () -> Unit = {
                if (state.zoomed) {
                    TextButton(
                        onClick = onFit,
                        modifier = Modifier.semantics { contentDescription = "Fit the whole picture" },
                    ) { Text("Fit") }
                }
                TextButton(
                    onClick = onSpeed,
                    modifier = Modifier.semantics { contentDescription = "Playback speed ${speedLabel(state.speed)}" },
                ) { Text(speedLabel(state.speed)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.quality,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (stacked) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!stacked) options()
            }
            if (stacked) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { options() }
            }
            Slider(
                value = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = {
                    if (dragTarget == null) onScrubbingChange(true)
                    dragTarget = (it * duration).toLong()
                },
                onValueChangeFinished = {
                    dragTarget?.let(onSeek)
                    dragTarget = null
                    onScrubbingChange(false)
                },
                enabled = duration > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video-scrubber")
                    .semantics {
                        contentDescription = "Seek"
                        stateDescription = "${formatPlaybackTime(shown)} of ${formatPlaybackTime(duration)}"
                    },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatPlaybackTime(shown),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("video-elapsed"),
                )
                Text(
                    "-" + formatPlaybackTime(duration - shown),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = Tokens.Small),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            // Frame by frame, on its own row so the transport buttons keep 48 dp on any phone.
            if (!state.playing) {
                Row(
                    Modifier.fillMaxWidth().testTag("frame-step"),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onStepFrame(false) },
                        modifier = Modifier.semantics { contentDescription = "Previous frame" },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
                        Text("Frame")
                    }
                    TextButton(
                        onClick = { onStepFrame(true) },
                        modifier = Modifier.semantics { contentDescription = "Next frame" },
                    ) {
                        Text("Frame")
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onSeek(skipTarget(state.position, duration, -SKIP_MS)) },
                    modifier = Modifier.size(Tokens.TouchTarget),
                ) { Icon(Icons.Outlined.Replay10, contentDescription = "Back 10 seconds") }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(Tokens.TouchTarget)) {
                    Icon(
                        if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Tokens.EmptyState),
                    )
                }
                IconButton(
                    onClick = { onSeek(skipTarget(state.position, duration, SKIP_MS)) },
                    modifier = Modifier.size(Tokens.TouchTarget),
                ) { Icon(Icons.Outlined.Forward10, contentDescription = "Forward 10 seconds") }
                IconButton(
                    onClick = onCapture,
                    enabled = !state.capturing,
                    modifier = Modifier.size(Tokens.TouchTarget),
                ) { Icon(Icons.Outlined.PhotoCamera, contentDescription = "Save this frame as a photo") }
            }
        }
    }
}
