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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.drivelift.app.ui.LocalReducedMotion
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
 * One drag detector that decides its axis once (horizontal when `abs(dragX) > abs(dragY)`), so a
 * horizontal swap between activities can never fire during the vertical swipe-up that collapses
 * the island, and the other way round. A vertical axis is only claimed when [enableVertical].
 */
@Composable
internal fun Modifier.cockpitAxisLockedSwipe(
    key: Any?,
    enableVertical: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
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
    return this.pointerInput(key, enableVertical) {
        var total = Offset.Zero
        var verticalTotal = 0f
        var axis = AXIS_NONE
        detectDragGestures(
            onDragStart = {
                total = Offset.Zero
                verticalTotal = 0f
                axis = AXIS_NONE
            },
            onDragEnd = {
                when (axis) {
                    AXIS_HORIZONTAL -> {
                        horizontalEnd()
                        if (total.x <= -Tokens.Cockpit.CYCLE_SWIPE_THRESHOLD_PX) {
                            next()
                        } else if (total.x >= Tokens.Cockpit.CYCLE_SWIPE_THRESHOLD_PX) {
                            previous()
                        }
                    }
                    AXIS_VERTICAL -> verticalEnd(verticalTotal <= Tokens.Cockpit.EXPAND_SWIPE_COLLAPSE_PX)
                    else -> Unit
                }
            },
            onDragCancel = {
                when (axis) {
                    AXIS_HORIZONTAL -> horizontalEnd()
                    AXIS_VERTICAL -> verticalEnd(false)
                    else -> Unit
                }
            },
            onDrag = { change, amount ->
                total += amount
                if (axis == AXIS_NONE &&
                    (abs(total.x) >= AXIS_LOCK_PX || abs(total.y) >= AXIS_LOCK_PX)
                ) {
                    axis = if (abs(total.x) > abs(total.y)) {
                        AXIS_HORIZONTAL
                    } else if (enableVertical) {
                        verticalStart()
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
                        verticalTotal = (verticalTotal + amount.y).coerceAtMost(0f)
                        verticalDrag(verticalTotal)
                    }
                    else -> Unit
                }
            },
        )
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = navManeuverIcon(nav.turnDirection),
            contentDescription = "Next maneuver",
            tint = accent,
            modifier = Modifier.size(Tokens.Icon),
        )
    }
    Spacer(Modifier.width(Tokens.Small))
    // The next-turn distance when there is one, else the time and distance left ("38 min · 20 km"),
    // so a notification with no turn distance still shows a live figure and never drops the distance.
    Text(
        text = nav.headlineText,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = CockpitLivePalette.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .weight(1f, fill = false)
            .testTag("cockpit-nav-distance"),
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
    modifier: Modifier = Modifier,
) {
    val primary = slotState.primary
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
            is CockpitLiveActivity.MapsNavActivity -> NavigationCard(primary, actions)
            else -> Unit
        }
        if (slotState.visibleQueue.size > 1) {
            ActivityPagerDots(count = slotState.visibleQueue.size, activeIndex = slotState.activeIndex)
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
) {
    val maneuverImage = remember(nav.maneuverIcon) {
        nav.maneuverIcon?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
    // Google Maps sends a dark arrow on transparent, which vanishes on the black tile.
    val maneuverStyle = remember(nav.maneuverIcon) {
        nav.maneuverIcon?.takeIf { !it.isRecycled }?.let { ManeuverIconContrast.classify(it) }
            ?: ManeuverIconStyle.AS_IS
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Medium))
            .clickable(role = Role.Button, onClickLabel = "Open navigation") { actions.onOpenNavigation(nav) }
            .testTag("cockpit-nav-card"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        val straightActive = nav.turnDirection == MapsTurnDirection.STRAIGHT ||
            nav.turnDirection == MapsTurnDirection.UTURN
        // Four lane slots share the whole row, so the arrows sit evenly instead of bunching left.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cockpit-nav-lanes"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LaneArrow(Icons.Outlined.TurnLeft, nav.turnDirection == MapsTurnDirection.LEFT, "cockpit-nav-lane-0")
            LaneArrow(Icons.Outlined.ArrowUpward, straightActive, "cockpit-nav-lane-1")
            LaneArrow(Icons.Outlined.ArrowUpward, straightActive, "cockpit-nav-lane-2")
            LaneArrow(Icons.Outlined.TurnRight, nav.turnDirection == MapsTurnDirection.RIGHT, "cockpit-nav-lane-3")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nav.headlineText.ifEmpty { "Navigating" },
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = CockpitLivePalette.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("cockpit-nav-card-distance"),
                )
                Text(
                    text = nav.roadText.ifEmpty { nav.maneuverText },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
            Box(
                modifier = Modifier
                    .size(Tokens.Cockpit.NavManeuverBox)
                    .clip(RoundedCornerShape(Tokens.RowLoose))
                    .background(
                        if (maneuverStyle == ManeuverIconStyle.LIGHT_BADGE) {
                            CockpitLivePalette.White
                        } else {
                            CockpitLivePalette.Charcoal
                        },
                    )
                    .testTag("cockpit-nav-maneuver-tile"),
                contentAlignment = Alignment.Center,
            ) {
                if (maneuverImage != null) {
                    Image(
                        bitmap = maneuverImage,
                        contentDescription = "Next maneuver",
                        contentScale = ContentScale.Fit,
                        colorFilter = if (maneuverStyle == ManeuverIconStyle.WHITE_TINT) {
                            ColorFilter.tint(CockpitLivePalette.White)
                        } else {
                            null
                        },
                        modifier = Modifier.size(Tokens.Cockpit.NavManeuverBox - Tokens.Medium),
                    )
                } else {
                    Icon(
                        imageVector = navManeuverIcon(nav.turnDirection),
                        contentDescription = "Next maneuver",
                        tint = CockpitLivePalette.White,
                        modifier = Modifier.size(Tokens.Header),
                    )
                }
            }
        }
        NavigationTripFooter(nav)
    }
}

/**
 * Muted trip line under the turn header: kilometres driven and left, the arrival estimate when the
 * notification has one, and a 2 dp progress hairline at the very bottom of the card.
 */
@Composable
private fun NavigationTripFooter(nav: CockpitLiveActivity.MapsNavActivity) {
    val remaining = nav.remainingTripKm
    val drove = nav.droveTripKm
    val trip = if (remaining != null && drove != null) {
        TripProgress(startKm = drove + remaining, remainingKm = remaining, droveKm = drove)
    } else {
        null
    }
    if (trip == null && nav.etaText.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            if (trip != null) {
                Text(
                    text = TripDistanceFormat.footer(trip.droveKm, trip.remainingKm, nav.tripUsesMiles),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = Tokens.Cockpit.NavTripTextSize,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cockpit-nav-trip-text"),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (nav.etaText.isNotBlank()) {
                Text(
                    text = nav.etaText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = Tokens.Cockpit.NavTripTextSize,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
private fun LaneArrow(icon: ImageVector, active: Boolean, tag: String) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (active) CockpitLivePalette.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(Tokens.Cockpit.NavLaneArrow)
            .testTag(tag),
    )
}
