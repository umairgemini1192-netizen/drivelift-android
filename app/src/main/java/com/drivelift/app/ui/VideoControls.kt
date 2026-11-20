package com.drivelift.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
    /** The picture is zoomed in; a Fit button brings it back to the whole frame. */
    val zoomed: Boolean = false,
)

/**
 * DriveLift's own player controls on a glass panel (owner decision #32), in labelled groups so
 * each control is easy to find (issue #14): the original quality on its own line; Speed, Step and
 * Picture groups that wrap onto more lines on a narrow phone or with large text; the scrubber; and
 * the playback row with Play/Pause as the one lead element. Every control keeps a 48 dp touch
 * target.
 */
// The custom scrubber thumb and track (SliderDefaults.Track with sliderState) are marked
// experimental in Material 3 1.3; the API used is pinned by the Compose BOM in app/build.gradle.kts.
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
internal fun VideoControls(
    state: VideoUiState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    /** The speed the user picked from the Speed group. */
    onSpeed: (Float) -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
    onFit: () -> Unit = {},
    /** Turns the screen between portrait and landscape. */
    onRotate: () -> Unit = {},
    /** The video picture's quarter turns clockwise so far (0 to 3), for the Turn control. */
    turns: Int = 0,
    /** Turns the video picture 90 degrees clockwise. */
    onTurn: () -> Unit = {},
    /** True while a finger is dragging the scrubber, false when it lifts. */
    onScrubbingChange: (Boolean) -> Unit = {},
    /** About one frame back (false) or forward (true). The player pauses first if it is playing. */
    onStepFrame: (Boolean) -> Unit = {},
) {
    // While dragging, the bar and the time show the target, and the seek happens on release.
    var dragTarget by remember { mutableStateOf<Long?>(null) }
    val shown = dragTarget ?: state.position
    val duration = state.duration.coerceAtLeast(0)
    val scrubberInteraction = remember { MutableInteractionSource() }

    GlassPanel(modifier.fillMaxWidth().testTag("video-controls")) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)) {
            // Never cut short: it wraps instead (owner decision #32).
            Text(
                state.quality,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = Tokens.Small).testTag("video-quality"),
            )
            // Tool groups, each with its name beside it: Speed (0.5x, and 1x that steps up to 1.5x
            // and 2x), then Step and Picture, which share a line where they fit. Kept compact so
            // the panel does not cover more of the picture than it must.
            ControlGroup("Speed", "video-speed", Modifier.fillMaxWidth().padding(top = Tokens.Small)) {
                SpeedChoice(
                    label = speedLabel(SLOW_SPEED),
                    description = "Speed ${speedLabel(SLOW_SPEED)}",
                    selected = state.speed == SLOW_SPEED,
                    tag = "video-speed-slow",
                    onClick = { onSpeed(SLOW_SPEED) },
                )
                SpeedChoice(
                    label = speedLabel(steppedSpeedShown(state.speed)),
                    description = steppedSpeedDescription(state.speed),
                    selected = state.speed in STEPPED_SPEEDS,
                    tag = "video-speed-step",
                    onClick = { onSpeed(nextSteppedSpeed(state.speed)) },
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = Tokens.Small),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Section),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                // Small steps of about one frame. Shown while playing too: a step pauses the video
                // first (issue #29). Exact for constant frame rate; variable-rate or unknown-rate
                // files can occasionally repeat or skip a frame, so the group says "step", not
                // "frame" (docs/HOW_IT_WORKS.md).
                ControlGroup("Step", "frame-step") {
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
                }
                ControlGroup("Picture", "video-picture") {
                    PlayerWell(onClick = onRotate, icon = Icons.Outlined.ScreenRotation, description = "Rotate the screen")
                    PlayerWell(
                        onClick = onTurn,
                        icon = Icons.Outlined.RotateRight,
                        description = turnDescription(turns),
                        // The arrow turns with the picture, so the well shows the turn it is at.
                        iconRotation = quarterTurnDegrees(turns).toFloat(),
                    )
                    PlayerWell(
                        onClick = onCapture,
                        icon = Icons.Outlined.PhotoCamera,
                        description = "Save this frame as a photo",
                        enabled = !state.capturing,
                    )
                    if (state.zoomed) {
                        PlayerWell(onClick = onFit, icon = Icons.Outlined.FitScreen, description = "Fit the whole picture")
                    }
                }
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
                    "-" + formatPlaybackTime(duration - shown),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = Tokens.Small),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            // Playback: skip back, Play/Pause, skip forward.
            Row(
                Modifier.fillMaxWidth().padding(vertical = Tokens.Small).testTag("video-playback"),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Section, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerWell(
                    onClick = { onSeek(skipTarget(state.position, duration, -SKIP_MS)) },
                    icon = Icons.Outlined.Replay10,
                    description = "Back 10 seconds",
                )
                // The one lead element on the player (rule 6): a solid coral disc.
                IconButton(onClick = onPlayPause, modifier = Modifier.size(Tokens.PlayerLead)) {
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
                    onClick = { onSeek(skipTarget(state.position, duration, SKIP_MS)) },
                    icon = Icons.Outlined.Forward10,
                    description = "Forward 10 seconds",
                )
            }
        }
    }
}

/** A named group of controls: its name, then its buttons on the same line. */
@Composable
private fun ControlGroup(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/**
 * One Speed button. The one playing now has a coral edge and coral text; the other is plain, so
 * the current speed is visible at a glance without competing with Play/Pause.
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

/** A secondary transport action: a 44 dp flat well inside a 48 dp touch target (rule 7). */
@Composable
private fun PlayerWell(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    /** Degrees the icon is turned clockwise, for a well that shows a state (the Turn control). */
    iconRotation: Float = 0f,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(Tokens.TouchTarget)) {
        Box(
            Modifier
                .size(Tokens.PlayerWell)
                .background(GlassPalette.Well, CircleShape)
                .border(Tokens.Hairline, GlassPalette.WellEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.graphicsLayer(rotationZ = iconRotation))
        }
    }
}
