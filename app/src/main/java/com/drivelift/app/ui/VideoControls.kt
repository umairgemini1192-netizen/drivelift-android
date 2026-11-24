package com.drivelift.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.zIndex
import java.util.Locale

/**
 * The Speed group shows two buttons, 0.5x and 1x (owner, issue #14, PR #62: "0.5x ==> 1x, two
 * options only" and "when I tap 1x it should increase to 1.5x eventually 2x"). 0.5x is its own
 * button. The second button steps through [STEPPED_SPEEDS], one step per tap.
 */
internal const val SLOW_SPEED = 0.5f

/** The speeds the second Speed button steps through, in order. */
internal val STEPPED_SPEEDS = listOf(1f, 1.5f, 2f)

/** What the second Speed button shows: the current speed when it is one of its steps, else 1x. */
internal fun steppedSpeedShown(current: Float): Float = if (current in STEPPED_SPEEDS) current else STEPPED_SPEEDS.first()

/**
 * Where a tap on the second Speed button goes: 1x to 1.5x to 2x. The owner did not say what comes
 * after 2x, so it goes back to 1x (flagged in PR #62). From 0.5x the first tap goes to 1x.
 */
internal fun nextSteppedSpeed(current: Float): Float {
    val i = STEPPED_SPEEDS.indexOf(current)
    return if (i < 0) STEPPED_SPEEDS.first() else STEPPED_SPEEDS[(i + 1) % STEPPED_SPEEDS.size]
}

/** Spoken description of the second Speed button: its speed and where the next tap goes. */
internal fun steppedSpeedDescription(current: Float): String =
    "Speed ${speedLabel(steppedSpeedShown(current))}, tap for ${speedLabel(nextSteppedSpeed(current))}"

internal const val SKIP_MS = 10_000L

/**
 * The orientation Rotate asks for: landscape from portrait, portrait from landscape. The sensor
 * variants let the phone still flip within that orientation (either landscape side).
 */
internal fun rotatedOrientation(landscapeNow: Boolean): Int =
    if (landscapeNow) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

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
    val fps = if (frameRate > 0f) "  ·  ${Math.round(frameRate)}\u00A0fps" else ""
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
    // Frames exist only before the end: a 2000 ms clip at 2 fps has frames 0 to 3 (0 to 1500 ms).
    val lastFrame = if (duration > 0) (Math.ceil(duration * fps / 1000.0 - FRAME_EPSILON).toLong() - 1).coerceAtLeast(0) else Long.MAX_VALUE
    val frame = frameIndexAt(position, fps, afterSeek).coerceAtMost(lastFrame)
    val next = (if (forward) frame + 1 else frame - 1).coerceIn(0, lastFrame)
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

/**
 * Keeps frame steps bounded, in order and honestly measured.
 *
 * - At most one step seek runs at a time. Taps made while it runs only move the pending target,
 *   counted from the newest target, so rapid taps add up to the right frame and no tap starts its
 *   own network or decode job.
 * - A step is complete only when a frame whose own presentation time belongs to that step's
 *   target is rendered ([rendered] with the frame's timestamp, see [matches]). A late frame from
 *   an older seek carries the older timestamp, does not match and is ignored, so it can never
 *   release a newer request.
 * - When that frame also satisfies the waiting request (taps that came back to the same frame),
 *   the waiting request is complete too and no redundant seek is made: a seek to the position
 *   already shown would draw no new frame and only wait for the timeout.
 * - [timedOut] is separate recovery for a seek that never shows a frame. It acts only for the
 *   generation it was started for; any later request, [clear] or completion makes it a no-op.
 * - Each request keeps the time of its first and last tap, so the log reports how long that
 *   request's own taps waited for its frame, and says when a request was superseded or timed out.
 */
internal class StepController(
    private val now: () -> Long,
    /** Starts the seek for [target]; [generation] identifies the request for its timeout. */
    private val issue: (target: Long, generation: Int) -> Unit,
    /** The nominal duration of one frame, in milliseconds. */
    private val frameDurationMs: () -> Double,
    private val log: (String) -> Unit = {},
) {
    private class Request(val target: Long, val firstTapAt: Long, val lastTapAt: Long, val taps: Int) {
        var generation = 0
        var issuedAt = 0L
    }

    private var generation = 0
    private var inFlight: Request? = null
    private var pending: Request? = null

    /** The newest step target asked for, or null when no step is running or waiting. */
    val latest: Long? get() = (pending ?: inFlight)?.target

    /** The target still waiting behind the running seek, if any. */
    val pendingTarget: Long? get() = pending?.target

    /** The generation of the running seek, or null. */
    val inFlightGeneration: Int? get() = inFlight?.generation

    /** A tap asked for [target]. Seeks now if nothing runs, otherwise replaces the pending target. */
    fun tap(target: Long) {
        val t = now()
        val waiting = pending
        if (inFlight == null) {
            start(Request(target, t, t, 1))
        } else {
            // Coalesced: the request keeps the time of its first tap, since that tap waits longest.
            pending = Request(target, waiting?.firstTapAt ?: t, t, (waiting?.taps ?: 0) + 1)
        }
    }

    /**
     * Whether a frame presented at [frameMs] is the frame a seek to [target] shows. The player
     * shows the first frame at or after the target; a variable-rate file can place it a little
     * late, so up to three quarters of a nominal frame is accepted. The neighbouring frame is a
     * whole frame away and never matches, which is what keeps a stale frame from passing.
     */
    fun matches(frameMs: Long, target: Long): Boolean =
        frameMs >= target - 1 && frameMs <= target + (frameDurationMs() * 0.75).toLong()

    /** The player rendered a frame whose presentation time is [frameMs]. */
    fun rendered(frameMs: Long) {
        val running = inFlight ?: return
        if (!matches(frameMs, running.target)) return
        val t = now()
        logShown(running, t)
        inFlight = null
        val waiting = pending
        if (waiting != null && matches(frameMs, waiting.target)) {
            // The taps came back to the frame on screen: done, without seeking to it again.
            pending = null
            waiting.generation = ++generation
            waiting.issuedAt = t
            logShown(waiting, t)
            return
        }
        startPending()
    }

    private fun logShown(request: Request, t: Long) = log(
        "step to ${request.target} ms (gen ${request.generation}, ${request.taps} taps): frame shown " +
            "${t - request.firstTapAt} ms after its first tap, ${t - request.lastTapAt} ms after its last, " +
            "${t - request.issuedAt} ms after the seek started",
    )

    /** The seek for [forGeneration] has shown no frame in time; stop it holding back the next step. */
    fun timedOut(forGeneration: Int) {
        val running = inFlight ?: return
        if (running.generation != forGeneration) return
        log("step to ${running.target} ms (gen ${running.generation}) timed out after ${now() - running.issuedAt} ms without a frame")
        inFlight = null
        startPending()
    }

    /** Another seek (scrub, skip, play) replaces any step still running or waiting. */
    fun clear() {
        inFlight?.let { log("step to ${it.target} ms (gen ${it.generation}) superseded before its frame was shown") }
        pending?.let { log("waiting step to ${it.target} ms superseded before it started") }
        inFlight = null
        pending = null
        generation++
    }

    private fun startPending() {
        val next = pending ?: return
        pending = null
        start(next)
    }

    private fun start(request: Request) {
        request.generation = ++generation
        request.issuedAt = now()
        inFlight = request
        issue(request.target, request.generation)
    }
}

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
    /** The picture is zoomed in; tapping Fit brings it back to the whole frame. */
    val zoomed: Boolean = false,
    /** True when the video fills the viewport edge-to-edge (center crop), false when fitted. */
    val fillMode: Boolean = false,
)

/**
 * DriveLift's custom player controls on a unified glass panel: original quality metadata, a clean
 * icon-only utility transport bar (no 'Speed', 'Step', or 'Picture' text labels) including a
 * Fit/Fill aspect-ratio toggle, an [isSeeking]-locked scrubber that never fights playback ticks,
 * and centered -10s / Play-Pause / +10s playback controls. Every button enforces a 48 dp minimum
 * touch target with ripple and haptic feedback, isolated above the tap-to-hide overlay.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
internal fun VideoControls(
    state: VideoUiState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    /** The speed the user picked from the speed toggle pills. */
    onSpeed: (Float) -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
    /** Direct relative skip (e.g. -10s / +10s); falls back to [onSeek] when null. */
    onSkip: ((Long) -> Unit)? = null,
    onFit: () -> Unit = {},
    /** Toggles between Fit (contain) and Fill (center-crop edge-to-edge) aspect ratio. */
    onToggleFillMode: () -> Unit = {},
    /** Turns the screen between portrait and landscape. */
    onRotate: () -> Unit = {},
    /** The video picture's quarter turns clockwise so far (0 to 3), for the Turn control. */
    turns: Int = 0,
    /** Turns the video picture 90 degrees clockwise. */
    onTurn: () -> Unit = {},
    /** True while a finger is dragging/tapping the scrubber, false when it lifts. */
    onScrubbingChange: (Boolean) -> Unit = {},
    /** About one frame back (false) or forward (true). The player pauses first if it is playing. */
    onStepFrame: (Boolean) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    // Explicit isSeeking lock prevents incoming position ticks from fighting finger drag/tap.
    var isSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableLongStateOf(0L) }
    val shown = if (isSeeking) seekPositionMs else state.position
    val duration = state.duration.coerceAtLeast(0)
    val scrubberInteraction = remember { MutableInteractionSource() }

    GlassPanel(
        modifier
            .fillMaxWidth()
            .zIndex(2f)
            .pointerInput(Unit) {
                detectTapGestures { /* Isolate control bar surface from tap-to-hide overlay. */ }
            }
            .testTag("video-controls"),
    ) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)) {
            // Never cut short: it wraps instead (owner decision #32).
            Text(
                state.quality,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = Tokens.Tiny).testTag("video-quality"),
            )
            // Consolidated icon-only utility transport bar (zero 'Speed', 'Step', or 'Picture'
            // text labels) with Fit/Fill aspect-ratio toggle and full 48 dp touch targets.
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Tokens.Small)
                    .testTag("video-toolbar"),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                SpeedChoice(
                    label = speedLabel(SLOW_SPEED),
                    description = "Speed ${speedLabel(SLOW_SPEED)}",
                    selected = state.speed == SLOW_SPEED,
                    tag = "video-speed-slow",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSpeed(SLOW_SPEED)
                    },
                )
                SpeedChoice(
                    label = speedLabel(steppedSpeedShown(state.speed)),
                    description = steppedSpeedDescription(state.speed),
                    selected = state.speed in STEPPED_SPEEDS,
                    tag = "video-speed-step",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSpeed(nextSteppedSpeed(state.speed))
                    },
                )
                PlayerWell(
                    onClick = { onStepFrame(false) },
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    description = "Step back about one frame",
                )
                PlayerWell(
                    onClick = { onStepFrame(true) },
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    description = "Step forward about one frame",
                )
                val showFit = state.zoomed || state.fillMode
                PlayerWell(
                    onClick = {
                        if (state.zoomed) onFit() else onToggleFillMode()
                    },
                    icon = if (showFit) Icons.Outlined.FitScreen else Icons.Outlined.AspectRatio,
                    description = if (showFit) "Fit the whole picture" else "Fill screen",
                    active = showFit,
                    tag = "video-aspect-toggle",
                )
                PlayerWell(
                    onClick = onRotate,
                    icon = Icons.Outlined.ScreenRotation,
                    description = "Rotate the screen",
                )
                PlayerWell(
                    onClick = onTurn,
                    icon = Icons.Outlined.RotateRight,
                    description = turnDescription(turns),
                    iconRotation = quarterTurnDegrees(turns).toFloat(),
                )
                PlayerWell(
                    onClick = onCapture,
                    icon = Icons.Outlined.PhotoCamera,
                    description = "Save this frame as a photo",
                    enabled = !state.capturing,
                )
            }
            Slider(
                value = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    val target = (fraction.coerceIn(0f, 1f) * duration).toLong()
                    if (!isSeeking) {
                        isSeeking = true
                        onScrubbingChange(true)
                    }
                    seekPositionMs = target
                },
                onValueChangeFinished = {
                    val target = seekPositionMs
                    isSeeking = false
                    onScrubbingChange(false)
                    onSeek(target)
                },
                enabled = duration > 0,
                interactionSource = scrubberInteraction,
                // One continuous track with a round thumb on it: no gap either side of the thumb.
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = scrubberInteraction,
                        enabled = duration > 0,
                        thumbSize = DpSize(Tokens.ScrubberThumb, Tokens.ScrubberThumb),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        enabled = duration > 0,
                        thumbTrackGapSize = Tokens.NoInset,
                        modifier = Modifier.height(Tokens.ProgressHeight),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Tokens.Small)
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
                    "-" + formatPlaybackTime((duration - shown).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = Tokens.Small),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            // Playback: skip back 10s, Play/Pause, skip forward 10s.
            Row(
                Modifier.fillMaxWidth().padding(vertical = Tokens.Small).testTag("video-playback"),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Section, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerWell(
                    onClick = {
                        if (onSkip != null) onSkip(-SKIP_MS)
                        else onSeek(skipTarget(shown, duration, -SKIP_MS))
                    },
                    icon = Icons.Outlined.Replay10,
                    description = "Back 10 seconds",
                )
                // The one lead element on the player (rule 6): solid electric cyan disc.
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPlayPause()
                    },
                    modifier = Modifier.sizeIn(minWidth = Tokens.PlayerLead, minHeight = Tokens.PlayerLead),
                ) {
                    Box(
                        Modifier
                            .size(Tokens.PlayerLead)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (state.playing) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(Tokens.Header),
                        )
                    }
                }
                PlayerWell(
                    onClick = {
                        if (onSkip != null) onSkip(SKIP_MS)
                        else onSeek(skipTarget(shown, duration, SKIP_MS))
                    },
                    icon = Icons.Outlined.Forward10,
                    description = "Forward 10 seconds",
                )
            }
        }
    }
}

/**
 * One compact Speed toggle pill with full 48 dp touch target. Active speed uses primary cyan
 * outline and text so current speed is legible at a glance without competing with Play/Pause.
 */
@Composable
private fun SpeedChoice(label: String, description: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = Tokens.PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = GlassPalette.Well,
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(Tokens.Hairline, if (selected) MaterialTheme.colorScheme.primary else GlassPalette.WellEdge),
        contentPadding = PaddingValues(horizontal = Tokens.Small),
        modifier = Modifier
            .heightIn(min = Tokens.TouchTarget)
            .widthIn(min = Tokens.TouchTarget)
            .testTag(tag)
            .semantics {
                contentDescription = description
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            },
    ) {
        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * A secondary icon-only transport action: a 44 dp flat well centered inside a 48 dp touch target
 * with Material ripple and immediate haptic feedback (rule 7).
 */
@Composable
private fun PlayerWell(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    active: Boolean = false,
    tag: String? = null,
    /** Degrees the icon is turned clockwise, for a well that shows a state (the Turn control). */
    iconRotation: Float = 0f,
) {
    val haptics = LocalHapticFeedback.current
    val edgeColor = if (active) MaterialTheme.colorScheme.primary else GlassPalette.WellEdge
    val iconTint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Box(
            Modifier
                .size(Tokens.PlayerWell)
                .background(GlassPalette.Well, CircleShape)
                .border(Tokens.Hairline, edgeColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = iconTint,
                modifier = Modifier.graphicsLayer(rotationZ = iconRotation),
            )
        }
    }
}
