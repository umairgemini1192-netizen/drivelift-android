package com.drivelift.app.feature.cockpit.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.UTurnLeft
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitDurationFormat
import com.drivelift.app.feature.cockpit.activities.TripDistanceFormat
import com.drivelift.app.feature.cockpit.activities.TripProgress
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.hardware.CockpitHaptics
import com.drivelift.app.feature.cockpit.notifications.CallAudioOutput
import com.drivelift.app.feature.cockpit.notifications.CallAudioUiState
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.TABULAR_DIGITS
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Wall-clock source for the live call and recording clocks. Tests provide a fixed time. */
val LocalCockpitNowMs = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

/** What the live activity cards can fire. Every default does nothing, so previews stay inert. */
data class CockpitActivityActions(
    val onAnswerCall: (CockpitLiveActivity.CallActivity) -> Unit = {},
    val onDeclineCall: (CockpitLiveActivity.CallActivity) -> Unit = {},
    val onHangUpCall: (CockpitLiveActivity.CallActivity) -> Unit = {},
    val onToggleMute: (CockpitLiveActivity.CallActivity) -> Unit = {},
    val onCycleAudioOutput: (CockpitLiveActivity.CallActivity) -> Unit = {},
    val onOpenCall: (CockpitLiveActivity.CallActivity) -> Unit = {},
    val onStopRecording: (CockpitLiveActivity.ScreenRecordingActivity) -> Unit = {},
    val onOpenNavigation: (CockpitLiveActivity.MapsNavActivity) -> Unit = {},
    val onRefreshCallAudio: (CockpitLiveActivity.CallActivity) -> Unit = {},
)

private val WaveformPhases = floatArrayOf(0f, 0.15f, 0.3f, 0.45f)
private const val WAVEFORM_STATIC_PHASE = 0.1f
private const val WAVEFORM_MIN_FRACTION = 0.34f
private const val WAVEFORM_HARMONIC_WEIGHT = 0.25f
private const val WAVEFORM_HARMONIC_OFFSET = 0.3f
private const val PULSE_HALO_SCALE = 0.25f
private const val PULSE_HALO_ALPHA = 0.45f
private const val SWAP_MIN_ALPHA = 0.2f
private const val AXIS_LOCK_PX = 12f
private const val DOT_MIN_ALPHA = 0.35f

/** Dedicated OLED cards: a call, a screen recording and turn-by-turn navigation each own the whole island. */
internal fun usesDedicatedCard(activity: CockpitLiveActivity): Boolean = when (activity) {
    is CockpitLiveActivity.CallActivity,
    is CockpitLiveActivity.ScreenRecordingActivity,
    is CockpitLiveActivity.MapsNavActivity -> true
    is CockpitLiveActivity.TransferActivity,
    is CockpitLiveActivity.TimerRecordingActivity,
    is CockpitLiveActivity.MediaActivity,
    is CockpitLiveActivity.WhatsAppAlertActivity,
    is CockpitLiveActivity.IdleQuickDockActivity -> false
}

/** One or two capital letters for the gradient monogram avatar. */
internal fun callerInitials(name: String): String {
    val letters = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        .take(2)
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    return if (letters.isEmpty()) "?" else letters.joinToString("").uppercase()
}

internal fun navManeuverIcon(direction: MapsTurnDirection): ImageVector = when (direction) {
    MapsTurnDirection.LEFT -> Icons.Outlined.TurnLeft
    MapsTurnDirection.RIGHT -> Icons.Outlined.TurnRight
    MapsTurnDirection.UTURN -> Icons.Outlined.UTurnLeft
    MapsTurnDirection.ARRIVE -> Icons.Outlined.Flag
    MapsTurnDirection.STRAIGHT -> Icons.Outlined.ArrowUpward
}

/** Wall-clock time that advances once a second, from [LocalCockpitNowMs]. */
@Composable
internal fun rememberTickingNowMs(): State<Long> {
    val provider = LocalCockpitNowMs.current
    return produceState(initialValue = provider(), provider) {
        while (true) {
            delay(Tokens.Cockpit.TICK_INTERVAL_MS)
            value = provider()
        }
    }
}

/**
 * Directional slide plus fade for a swap between activities. Runs only when [key] changes after the
 * first composition, slides in from the side the finger moved toward, and snaps when the phone has
 * animations turned off. It never changes layout, so the island does not jump while it plays.
 */
@Composable
internal fun SwapTransition(
    key: Any,
    direction: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val slidePx = with(LocalDensity.current) { Tokens.Section.toPx() }
    val seenContent = remember { booleanArrayOf(false) }
    val swapDirection = remember(key) { direction }
    val progress = remember(key) {
        Animatable(if (reducedMotion || !seenContent[0]) 1f else 0f).also { seenContent[0] = true }
    }
    LaunchedEffect(progress) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(Tokens.Cockpit.SWAP_SLIDE_MS, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            val fraction = progress.value.coerceIn(0f, 1f)
            alpha = SWAP_MIN_ALPHA + (1f - SWAP_MIN_ALPHA) * fraction
            translationX = (1f - fraction) * swapDirection * slidePx
        },
    ) {
        content()
    }
}

/**
 * One axis-locked drag detector running on [PointerEventPass.Initial] so horizontal swap drags
 * intercept cleanly BEFORE child `clickable` / `combinedClickable` controls can consume them,
 * while normal taps and long-presses below touch slop pass straight through untouched.
 *
 * The axis locks once (`abs(dragX) > abs(dragY)` for horizontal, upward drag for vertical when
 * [enableVertical] is true), so a horizontal swap can never fire during a vertical swipe-up and
 * vice versa.
 */
@Composable
internal fun Modifier.cockpitAxisLockedSwipe(
    key: Any?,
    enableVertical: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    enableHorizontal: Boolean = true,
    verticalThresholdPx: Float = Tokens.Cockpit.EXPAND_SWIPE_COLLAPSE_PX,
    onHorizontalDrag: (totalPx: Float) -> Unit = {},
    onHorizontalEnd: () -> Unit = {},
    onVerticalStart: () -> Unit = {},
    onVerticalDrag: (totalPx: Float) -> Unit = {},
    onVerticalEnd: (shouldCollapse: Boolean) -> Unit = {},
): Modifier {
    val next by rememberUpdatedState(onSwipeNext)
    val previous by rememberUpdatedState(onSwipePrevious)
    val horizontalDrag by rememberUpdatedState(onHorizontalDrag)
    val horizontalEnd by rememberUpdatedState(onHorizontalEnd)
    val verticalStart by rememberUpdatedState(onVerticalStart)
    val verticalDrag by rememberUpdatedState(onVerticalDrag)
    val verticalEnd by rememberUpdatedState(onVerticalEnd)
    return this.pointerInput(key, enableHorizontal, enableVertical, verticalThresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var total = Offset.Zero
            var verticalTotal = 0f
            var axis = AXIS_NONE
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    when (axis) {
                        AXIS_HORIZONTAL -> horizontalEnd()
                        AXIS_VERTICAL -> verticalEnd(false)
                        else -> Unit
                    }
                    break
                }
                if (!change.pressed) {
                    when (axis) {
                        AXIS_HORIZONTAL -> {
                            change.consume()
                            horizontalEnd()
                            if (total.x <= -Tokens.Cockpit.CYCLE_SWIPE_THRESHOLD_PX) {
                                next()
                            } else if (total.x >= Tokens.Cockpit.CYCLE_SWIPE_THRESHOLD_PX) {
                                previous()
                            }
                        }
                        AXIS_VERTICAL -> {
                            change.consume()
                            verticalEnd(verticalTotal <= verticalThresholdPx)
                        }
                        else -> Unit
                    }
                    break
                }
                val delta = change.position - change.previousPosition
                total += delta
                var justLockedVertical = false
                if (axis == AXIS_NONE &&
                    (abs(total.x) >= AXIS_LOCK_PX || abs(total.y) >= AXIS_LOCK_PX)
                ) {
                    axis = if (abs(total.x) > abs(total.y)) {
                        if (enableHorizontal) AXIS_HORIZONTAL else AXIS_IGNORED
                    } else if (enableVertical && total.y < 0f) {
                        verticalStart()
                        verticalTotal = total.y.coerceAtMost(0f)
                        justLockedVertical = true
                        AXIS_VERTICAL
                    } else {
                        AXIS_IGNORED
                    }
                }
                when (axis) {
                    AXIS_HORIZONTAL -> {
                        change.consume()
                        horizontalDrag(total.x)
                    }
                    AXIS_VERTICAL -> {
                        change.consume()
                        if (!justLockedVertical) {
                            verticalTotal = (verticalTotal + delta.y).coerceAtMost(0f)
                        }
                        verticalDrag(verticalTotal)
                    }
                    else -> Unit
                }
            }
        }
    }
}

private const val AXIS_NONE = 0
private const val AXIS_HORIZONTAL = 1
private const val AXIS_VERTICAL = 2
private const val AXIS_IGNORED = 3

/** Caller picture, or a gradient monogram when the notification has none. */
@Composable
internal fun CallAvatar(
    avatar: Bitmap?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val image = remember(avatar) { avatar?.takeIf { !it.isRecycled }?.asImageBitmap() }
    val scheme = MaterialTheme.colorScheme
    val brush = remember(name, scheme) {
        val pairs = listOf(
            scheme.primary to scheme.tertiary,
            scheme.secondary to scheme.primary,
            scheme.tertiary to scheme.secondary,
        )
        val pair = pairs[(name.hashCode() and Int.MAX_VALUE) % pairs.size]
        Brush.linearGradient(listOf(pair.first, pair.second))
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = callerInitials(name),
                    style = MaterialTheme.typography.titleMedium,
                    color = CockpitLivePalette.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Four emerald and cyan bars with smoothly interpolated, vertically centred heights. */
@Composable
internal fun VoiceWaveform(modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "cockpit_waveform")
    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Tokens.Cockpit.WAVEFORM_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveform_phase",
    )
    val phase = if (reducedMotion) WAVEFORM_STATIC_PHASE else animatedPhase
    val barCount = WaveformPhases.size
    Canvas(
        modifier = modifier
            .size(
                width = Tokens.Cockpit.WaveformBarWidth * barCount + Tokens.Cockpit.WaveformGap * (barCount - 1),
                height = Tokens.Cockpit.WaveformHeight,
            )
            .semantics { contentDescription = "Call audio is active" },
    ) {
        val barWidth = Tokens.Cockpit.WaveformBarWidth.toPx()
        val gap = Tokens.Cockpit.WaveformGap.toPx()
        val minHeight = size.height * WAVEFORM_MIN_FRACTION
        for (index in 0 until barCount) {
            val turn = 2f * PI.toFloat()
            val base = sin(turn * (phase + WaveformPhases[index]))
            val harmonic = sin(turn * (2f * (phase + WaveformPhases[index]) + WAVEFORM_HARMONIC_OFFSET))
            val wave = (0.5f + 0.5f * ((1f - WAVEFORM_HARMONIC_WEIGHT) * base + WAVEFORM_HARMONIC_WEIGHT * harmonic))
                .coerceIn(0f, 1f)
            val barHeight = minHeight + (size.height - minHeight) * wave
            drawRoundRect(
                color = if (index % 2 == 0) CockpitLivePalette.Emerald else CockpitLivePalette.Cyan,
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
internal fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "cockpit_recording_dot")
    val pulseAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = DOT_MIN_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Tokens.Cockpit.RECORDING_PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_alpha",
    )
    Box(
        modifier = modifier
            .size(Tokens.Cockpit.RecordingDot)
            .graphicsLayer { alpha = if (reducedMotion) 1f else pulseAlpha }
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------------------------
// Collapsed pill bodies. Each emits two clusters into a Row that spaces them apart.
// ---------------------------------------------------------------------------------------------

@Composable
internal fun RowScope.CollapsedCallContent(call: CockpitLiveActivity.CallActivity) {
    val ringing = call.state == CallState.INCOMING
    Row(
        modifier = Modifier.weight(1f, fill = false),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        Icon(
            imageVector = Icons.Outlined.Call,
            contentDescription = null,
            tint = if (ringing) CockpitLivePalette.Answer else CockpitLivePalette.Emerald,
            modifier = Modifier.size(Tokens.IconSmall),
        )
        if (ringing) {
            Text(
                text = call.callerName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            val now by rememberTickingNowMs()
            Text(
                text = CockpitDurationFormat.clock(now - call.startedAtMs),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.testTag("cockpit-call-timer"),
            )
        }
    }
    Spacer(Modifier.width(Tokens.Small))
    if (ringing) {
        Text(
            text = "Incoming",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = CockpitLivePalette.Answer,
            maxLines = 1,
        )
    } else {
        VoiceWaveform(modifier = Modifier.testTag("cockpit-call-waveform"))
    }
}

@Composable
internal fun RowScope.CollapsedRecordingContent(recording: CockpitLiveActivity.ScreenRecordingActivity) {
    val now by rememberTickingNowMs()
    Row(
        modifier = Modifier.weight(1f, fill = false),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        PulsingDot(color = CockpitLivePalette.Recording)
        Text(
            text = CockpitDurationFormat.clock(now - recording.startedAtMs),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = CockpitLivePalette.Recording,
            maxLines = 1,
            modifier = Modifier.testTag("cockpit-recording-timer"),
        )
    }
    Spacer(Modifier.width(Tokens.Small))
    Box(
        modifier = Modifier
            .size(Tokens.Cockpit.RecordingStopBox)
            .clip(RoundedCornerShape(Tokens.Tiny))
            .background(CockpitLivePalette.Recording)
            .semantics { contentDescription = "Screen recording in progress" },
    )
}

@Composable
internal fun RowScope.CollapsedNavigationContent(nav: CockpitLiveActivity.MapsNavActivity, accent: Color) {
    Row(
        modifier = Modifier.weight(1f, fill = false),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = navManeuverIcon(nav.turnDirection),
            contentDescription = "Next maneuver",
            tint = accent,
            modifier = Modifier.size(Tokens.Icon),
        )
    }
    Spacer(Modifier.width(Tokens.Small))
    Text(
        text = nav.distanceText.ifEmpty { nav.etaText },
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = CockpitLivePalette.White,
        maxLines = 1,
        modifier = Modifier.testTag("cockpit-nav-distance"),
    )
}

// ---------------------------------------------------------------------------------------------
// Expanded dedicated cards.
// ---------------------------------------------------------------------------------------------

/**
 * Body of the expanded island for a call, a screen recording or navigation. The caller supplies the
 * surface (pure OLED black with the specular rim), padding and the swipe gestures.
 */
@Composable
internal fun DedicatedActivityCard(
    slotState: CockpitSlotState,
    callAudio: CallAudioUiState,
    actions: CockpitActivityActions,
    onCycleNext: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val primary = slotState.primary
    val canSwap = slotState.visibleQueue.size > 1
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        when (primary) {
            is CockpitLiveActivity.CallActivity -> {
                if (primary.state == CallState.INCOMING) {
                    IncomingCallBanner(primary, actions)
                } else {
                    OngoingCallCard(primary, callAudio, actions)
                }
            }
            is CockpitLiveActivity.ScreenRecordingActivity -> RecordingBanner(primary, actions)
            is CockpitLiveActivity.MapsNavActivity -> NavigationCard(
                nav = primary,
                actions = actions,
                canSwap = canSwap,
                onCycleNext = onCycleNext,
            )
            else -> Unit
        }
        if (canSwap) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ActivityPagerDots(count = slotState.visibleQueue.size, activeIndex = slotState.activeIndex)
                }
                if (primary !is CockpitLiveActivity.MapsNavActivity) {
                    CallCircleButton(
                        size = Tokens.Cockpit.HeaderActionWell,
                        container = CockpitLivePalette.Charcoal,
                        content = CockpitLivePalette.Cyan,
                        icon = Icons.Outlined.SwapHoriz,
                        description = "Swap activity",
                        tag = "cockpit-cycle-next",
                        onClick = onCycleNext,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityPagerDots(count: Int, activeIndex: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Activity ${activeIndex + 1} of $count. Swipe sideways to switch." }
            .testTag("cockpit-pager-dots"),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until count) {
            Box(
                modifier = Modifier
                    .size(Tokens.Cockpit.PagerDot)
                    .clip(CircleShape)
                    .background(
                        if (index == activeIndex) CockpitLivePalette.White else CockpitLivePalette.Charcoal,
                    ),
            )
        }
    }
}

@Composable
private fun CallCircleButton(
    size: Dp,
    container: Color,
    content: Color,
    icon: ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticContext = LocalContext.current
    val touchSize = size.coerceAtLeast(Tokens.TouchTarget)
    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) {
                CockpitHaptics.performClick(hapticContext)
                onClick()
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = content,
                modifier = Modifier.size(Tokens.Icon),
            )
        }
    }
}

@Composable
private fun IncomingCallBanner(
    call: CockpitLiveActivity.CallActivity,
    actions: CockpitActivityActions,
) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "cockpit_answer_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Tokens.Cockpit.ANSWER_PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "answer_pulse",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cockpit-call-incoming"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        CallAvatar(avatar = call.avatar, name = call.callerName, size = Tokens.Cockpit.CallAvatar)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.callerName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = CockpitLivePalette.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("cockpit-call-caller"),
            )
            Text(
                text = call.sourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CallCircleButton(
            size = Tokens.Cockpit.CallActionButton,
            container = CockpitLivePalette.Decline,
            content = CockpitLivePalette.White,
            icon = Icons.Outlined.CallEnd,
            description = "Decline call from ${call.callerName}",
            tag = "cockpit-call-decline",
            onClick = { actions.onDeclineCall(call) },
        )
        Box(contentAlignment = Alignment.Center) {
            if (!reducedMotion) {
                Box(
                    modifier = Modifier
                        .size(Tokens.Cockpit.CallActionButton)
                        .graphicsLayer {
                            val scale = 1f + PULSE_HALO_SCALE * pulse
                            scaleX = scale
                            scaleY = scale
                            alpha = PULSE_HALO_ALPHA * (1f - pulse)
                        }
                        .clip(CircleShape)
                        .background(CockpitLivePalette.Answer),
                )
            }
            CallCircleButton(
                size = Tokens.Cockpit.CallActionButton,
                container = CockpitLivePalette.Answer,
                content = CockpitLivePalette.White,
                icon = Icons.Outlined.Call,
                description = "Answer call from ${call.callerName}",
                tag = "cockpit-call-answer",
                onClick = { actions.onAnswerCall(call) },
            )
        }
    }
}

@Composable
private fun OngoingCallCard(
    call: CockpitLiveActivity.CallActivity,
    audio: CallAudioUiState,
    actions: CockpitActivityActions,
) {
    val now by rememberTickingNowMs()
    val refresh by rememberUpdatedState(actions.onRefreshCallAudio)
    LaunchedEffect(call.id) {
        while (true) {
            refresh(call)
            delay(Tokens.Cockpit.AUDIO_REFRESH_MS)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cockpit-call-ongoing"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Gutter),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            CallAvatar(avatar = call.avatar, name = call.callerName, size = Tokens.Cockpit.CallAvatar)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.callerName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CockpitLivePalette.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("cockpit-call-caller"),
                )
                Text(
                    text = "${call.sourceLabel} · ${CockpitDurationFormat.clock(now - call.startedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("cockpit-call-subtitle"),
                )
                Text(
                    text = "Audio: ${audioOutputLabel(audio.output)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CockpitLivePalette.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("cockpit-call-route"),
                )
            }
            CallCircleButton(
                size = Tokens.Cockpit.CallInfoButton,
                container = CockpitLivePalette.Charcoal,
                content = CockpitLivePalette.White,
                icon = Icons.Outlined.Info,
                description = "Open call details",
                tag = "cockpit-call-info",
                onClick = { actions.onOpenCall(call) },
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val buttonSize = if (maxWidth < Tokens.Cockpit.COMPACT_CALL_WIDTH_DP.dp) {
                Tokens.Cockpit.CallControlCompact
            } else {
                Tokens.Cockpit.CallControlStandard
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallCircleButton(
                    size = buttonSize,
                    container = if (audio.isOutputActive) CockpitLivePalette.White else CockpitLivePalette.Charcoal,
                    content = if (audio.isOutputActive) CockpitLivePalette.OnLight else CockpitLivePalette.White,
                    icon = audioOutputIcon(audio.output),
                    description = "Audio output: ${audioOutputLabel(audio.output)}. Switch audio output",
                    tag = "cockpit-call-audio",
                    onClick = { actions.onCycleAudioOutput(call) },
                )
                CallCircleButton(
                    size = buttonSize,
                    container = if (audio.isMuted) CockpitLivePalette.White else CockpitLivePalette.Charcoal,
                    content = if (audio.isMuted) CockpitLivePalette.OnLight else CockpitLivePalette.White,
                    icon = if (audio.isMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                    description = if (audio.isMuted) "Unmute microphone" else "Mute microphone",
                    tag = "cockpit-call-mute",
                    onClick = { actions.onToggleMute(call) },
                )
                CallCircleButton(
                    size = buttonSize,
                    container = CockpitLivePalette.Charcoal,
                    content = CockpitLivePalette.White,
                    icon = Icons.Outlined.Videocam,
                    description = if (call.isVideo) "Open video call" else "Open call",
                    tag = "cockpit-call-open",
                    onClick = { actions.onOpenCall(call) },
                )
                CallCircleButton(
                    size = buttonSize,
                    container = CockpitLivePalette.Decline,
                    content = CockpitLivePalette.White,
                    icon = Icons.Outlined.CallEnd,
                    description = "Hang up",
                    tag = "cockpit-call-hangup",
                    onClick = { actions.onHangUpCall(call) },
                )
            }
        }
    }
}

private fun audioOutputIcon(output: CallAudioOutput): ImageVector = when (output) {
    CallAudioOutput.BLUETOOTH -> Icons.Outlined.BluetoothAudio
    CallAudioOutput.WIRED -> Icons.Outlined.Headset
    CallAudioOutput.SPEAKER -> Icons.Outlined.VolumeUp
    CallAudioOutput.EARPIECE -> Icons.Outlined.PhoneInTalk
}

private fun audioOutputLabel(output: CallAudioOutput): String = when (output) {
    CallAudioOutput.BLUETOOTH -> "Bluetooth"
    CallAudioOutput.WIRED -> "Headset"
    CallAudioOutput.SPEAKER -> "Speaker"
    CallAudioOutput.EARPIECE -> "Earpiece"
}

@Composable
private fun RecordingBanner(
    recording: CockpitLiveActivity.ScreenRecordingActivity,
    actions: CockpitActivityActions,
) {
    val now by rememberTickingNowMs()
    val hapticContext = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cockpit-recording-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                PulsingDot(color = CockpitLivePalette.Recording)
                Text(
                    text = CockpitDurationFormat.clock(now - recording.startedAtMs),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CockpitLivePalette.Recording,
                    maxLines = 1,
                    modifier = Modifier.testTag("cockpit-recording-clock"),
                )
            }
            Text(
                text = "Screen Recording",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CockpitLivePalette.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(Tokens.TouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Stop screen recording") {
                    CockpitHaptics.performClick(hapticContext)
                    actions.onStopRecording(recording)
                }
                .testTag("cockpit-recording-stop"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(Tokens.Cockpit.CallControlCompact)
                    .clip(CircleShape)
                    .border(Tokens.Cockpit.StopRingWidth, CockpitLivePalette.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(Tokens.Cockpit.RecordingStopBox + Tokens.Tiny)
                        .clip(RoundedCornerShape(Tokens.Tiny))
                        .background(CockpitLivePalette.Recording)
                        .semantics { contentDescription = "Stop screen recording" },
                )
            }
        }
    }
}

@Composable
private fun NavigationCard(
    nav: CockpitLiveActivity.MapsNavActivity,
    actions: CockpitActivityActions,
    canSwap: Boolean = false,
    onCycleNext: () -> Unit = {},
) {
    val maneuverImage = remember(nav.maneuverIcon) {
        nav.maneuverIcon?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
    val stackHud = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP

    val remaining = nav.remainingTripKm
    val drove = nav.droveTripKm
    val trip = if (remaining != null && drove != null) {
        TripProgress(startKm = drove + remaining, remainingKm = remaining, droveKm = drove)
    } else {
        null
    }
    val etaValue = nav.etaText.ifBlank { "--" }
    val leftLabel = "LEFT"
    val leftValue = TripDistanceFormat.hudValue(nav.remainingTripKm, nav.tripUsesMiles)
    val speedValue = TripDistanceFormat.speedValue(nav.speedKmh)
    val drivenLabel = "DRIVEN"
    val drivenValue = TripDistanceFormat.hudValue(nav.droveTripKm, nav.tripUsesMiles)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cockpit-nav-card"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        // Header row: single clean active turn icon + distance/road/destination + compact icon controls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Medium))
                .clickable(role = Role.Button, onClickLabel = "Open navigation") { actions.onOpenNavigation(nav) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Row),
        ) {
            Box(
                modifier = Modifier
                    .size(Tokens.Cockpit.NavTurnBadgeSize)
                    .clip(RoundedCornerShape(Tokens.RowLoose))
                    .background(CockpitLivePalette.Charcoal)
                    .border(
                        width = Tokens.Hairline,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(Tokens.RowLoose),
                    )
                    .testTag("cockpit-nav-turn-icon"),
                contentAlignment = Alignment.Center,
            ) {
                if (maneuverImage != null) {
                    Image(
                        bitmap = maneuverImage,
                        contentDescription = "Next maneuver",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(Tokens.Cockpit.NavTurnBadgeSize - Tokens.Small),
                    )
                } else {
                    Icon(
                        imageVector = navManeuverIcon(nav.turnDirection),
                        contentDescription = "Next maneuver",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(Tokens.Header),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nav.distanceText.ifEmpty { "Navigating" },
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = TABULAR_DIGITS,
                    ),
                    color = CockpitLivePalette.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("cockpit-nav-card-distance"),
                )
                Text(
                    text = nav.roadText.ifEmpty { nav.maneuverText },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CockpitLivePalette.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nav.destinationText.isNotBlank()) {
                    Text(
                        text = nav.destinationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canSwap) {
                CallCircleButton(
                    size = Tokens.Cockpit.HeaderActionWell,
                    container = CockpitLivePalette.Charcoal,
                    content = CockpitLivePalette.Cyan,
                    icon = Icons.Outlined.SwapHoriz,
                    description = "Swap activity",
                    tag = "cockpit-cycle-next",
                    onClick = onCycleNext,
                )
            }
            CallCircleButton(
                size = Tokens.Cockpit.HeaderActionWell,
                container = CockpitLivePalette.Charcoal,
                content = CockpitLivePalette.White,
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                description = "Open Maps",
                tag = "cockpit-expanded-open-app",
                onClick = { actions.onOpenNavigation(nav) },
            )
        }

        // Structured 4-metric Driving Navigation HUD: ETA, KM Left, Live Speed (km/h), KM Driven.
        if (stackHud) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cockpit-nav-hud-row"),
                verticalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
                ) {
                    NavHudMetricCell(
                        label = "ETA",
                        value = etaValue,
                        accent = MaterialTheme.colorScheme.tertiary,
                        tag = "cockpit-nav-hud-eta",
                        modifier = Modifier.weight(1f),
                    )
                    NavHudMetricCell(
                        label = leftLabel,
                        value = leftValue,
                        accent = CockpitLivePalette.White,
                        tag = "cockpit-nav-hud-left",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
                ) {
                    NavHudMetricCell(
                        label = "KM/H",
                        value = speedValue,
                        accent = MaterialTheme.colorScheme.primary,
                        tag = "cockpit-nav-hud-speed",
                        modifier = Modifier.weight(1f),
                    )
                    NavHudMetricCell(
                        label = drivenLabel,
                        value = drivenValue,
                        accent = MaterialTheme.colorScheme.secondary,
                        tag = "cockpit-nav-hud-driven",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cockpit-nav-hud-row"),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                NavHudMetricCell(
                    label = "ETA",
                    value = etaValue,
                    accent = MaterialTheme.colorScheme.tertiary,
                    tag = "cockpit-nav-hud-eta",
                    modifier = Modifier.weight(1f),
                )
                NavHudMetricCell(
                    label = leftLabel,
                    value = leftValue,
                    accent = CockpitLivePalette.White,
                    tag = "cockpit-nav-hud-left",
                    modifier = Modifier.weight(1f),
                )
                NavHudMetricCell(
                    label = "KM/H",
                    value = speedValue,
                    accent = MaterialTheme.colorScheme.primary,
                    tag = "cockpit-nav-hud-speed",
                    modifier = Modifier.weight(1f),
                )
                NavHudMetricCell(
                    label = drivenLabel,
                    value = drivenValue,
                    accent = MaterialTheme.colorScheme.secondary,
                    tag = "cockpit-nav-hud-driven",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Crisp 2 dp trip progress hairline at the bottom of the HUD.
        if (trip != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Cockpit.NavProgressHairline)
                    .clip(CircleShape)
                    .background(CockpitLivePalette.Charcoal)
                    .testTag("cockpit-nav-progress"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(trip.fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

@Composable
private fun NavHudMetricCell(
    label: String,
    value: String,
    accent: Color,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Tokens.Cockpit.NavHudCellRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(GlassPalette.Well)
            .border(Tokens.Hairline, GlassPalette.WellEdge, shape)
            .padding(horizontal = Tokens.Snug, vertical = Tokens.Snug)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" }
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.Stroke),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = TABULAR_DIGITS,
            ),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = Tokens.Cockpit.NavTripTextSize,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
