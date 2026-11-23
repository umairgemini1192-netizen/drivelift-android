package com.drivelift.app.feature.cockpit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.UTurnLeft
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.CockpitHaptics
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsState
import com.drivelift.app.feature.cockpit.hardware.CockpitRingerMode
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.notifications.CallAudioUiState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.cockpitSpecularRim
import com.drivelift.app.feature.cockpit.overlay.toWideGamutP3
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.GlassLevel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.destructiveGlowBorder
import com.drivelift.app.ui.glass
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Computes the maximum width (in dp) for the collapsed Dynamic Island primary pill so it never
 * collides with system status-bar icons (left clock/notifications or right signal/Wi-Fi/battery),
 * even with positive calibration deltas or a simultaneous satellite bubble.
 */
internal fun computeSafePillMaxWidthDp(
    screenWidthDp: Int,
    widthDeltaDp: Int,
    hasSatelliteBubble: Boolean,
): Int {
    val minFloor = (Tokens.Cockpit.PillMinWidth - Tokens.Header).value.toInt()
    val sideMargin = if (screenWidthDp < Tokens.COMPACT_WIDTH_DP) {
        Tokens.Cockpit.StatusBarCompactSideMargin.value.toInt()
    } else {
        Tokens.Cockpit.StatusBarSafeSideMargin.value.toInt()
    }
    val satelliteReserve = if (hasSatelliteBubble) {
        (Tokens.Cockpit.BubbleSize + Tokens.Cockpit.SplitGap).value.toInt()
    } else {
        0
    }
    val maxForScreen = (screenWidthDp - (sideMargin * 2) - satelliteReserve).coerceAtLeast(minFloor)
    val hardCap = (Tokens.Cockpit.PillHardMaxWidth.value.toInt() - satelliteReserve).coerceAtLeast(minFloor)
    val desired = (Tokens.Cockpit.PillMaxWidth.value.toInt() + widthDeltaDp).coerceAtLeast(minFloor)
    return minOf(desired, maxForScreen, hardCap)
}

/**
 * Formats a TransferActivity subtitle without duplicating identical status text (prevents
 * `"Ready (Ready)"`).
 */
internal fun formatTransferActivitySubtitle(subtitle: String, speedText: String): String {
    val cleanSub = subtitle.trim()
    val cleanSpeed = speedText.trim()
    return when {
        cleanSub.isEmpty() -> cleanSpeed
        cleanSpeed.isEmpty() -> cleanSub
        cleanSub.equals(cleanSpeed, ignoreCase = true) -> cleanSub
        else -> "$cleanSub ($cleanSpeed)"
    }
}

/**
 * System-wide HDR+ Tactile Glass Dynamic Island UI:
 * - Collapsed Single Pill or Simultaneous 2-Activity Split Pill (Primary Pill + Detached Bubble)
 * - Single-surface Material 3 Expressive spatial spring morph (`Animatable(0f..1f)`) with
 *   width-locked expanded content and continuous swipe-up collapse interpolation
 * - Expanded 5-Control Tactile Glass Quick Deck (Wi-Fi marquee SSID, Monthly Mobile Data,
 *   Bluetooth device count, 3-state Ringer Mode, icon-only Flash -> Liquid-Glass slider + SOS)
 * - Real cached app icons in the quick dock and live activity actions
 */
@Composable
fun DynamicIslandOverlay(
    slotState: CockpitSlotState,
    telemetry: DriveLiftTelemetrySummary,
    torchState: TorchUiState,
    pinnedApps: List<PinnedAppInfo>,
    widthDeltaDp: Int = 0,
    onTapPrimary: (CockpitLiveActivity) -> Unit,
    onTapSecondary: (CockpitLiveActivity) -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onCycleNext: () -> Unit,
    onCyclePrevious: () -> Unit,
    onSoftDismiss: (String) -> Unit,
    onRestoreDismissed: () -> Unit,
    onStopDesktopDrop: () -> Unit,
    onOpenDriveLiftCockpit: () -> Unit,
    onToggleInlineReply: (Boolean) -> Unit,
    onSendWhatsAppReply: (notificationKey: String, replyText: String) -> Unit,
    onToggleTorch: () -> Unit,
    onTorchBrightnessChange: (Int) -> Unit,
    onToggleTorchSos: () -> Unit,
    onLaunchPinnedApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    quickControls: CockpitQuickControlsState = CockpitQuickControlsState(),
    onCollapseAnimationFinished: () -> Unit = {},
    onTapWifi: () -> Unit = {},
    onLongPressWifi: () -> Unit = {},
    onTapMobileData: () -> Unit = {},
    onLongPressMobileData: () -> Unit = {},
    onTapBluetooth: () -> Unit = {},
    onCycleRingerMode: () -> Unit = {},
    callAudio: CallAudioUiState = CallAudioUiState(),
    activityActions: CockpitActivityActions = CockpitActivityActions(),
) {
    val reducedMotion = LocalReducedMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val morphSpec = remember(reducedMotion) {
        if (reducedMotion) {
            snap<Float>()
        } else {
            spring(
                dampingRatio = Tokens.Cockpit.MORPH_SPRING_DAMPING,
                stiffness = Tokens.Cockpit.MORPH_SPRING_STIFFNESS,
            )
        }
    }

    // Pre-resolve cached app icon ImageBitmaps so expanding the Island never flickers.
    val cachedAppIcons = remember(pinnedApps) {
        pinnedApps.associate { app ->
            app.packageName to app.iconBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
        }
    }

    val morphProgress = remember { Animatable(if (slotState.isExpanded) 1f else 0f) }
    var isDraggingCollapse by remember { mutableStateOf(false) }

    LaunchedEffect(slotState.isExpanded) {
        val target = if (slotState.isExpanded) 1f else 0f
        if (morphProgress.value != target) {
            morphProgress.animateTo(target, animationSpec = morphSpec)
        }
        isDraggingCollapse = false
        if (!slotState.isExpanded) {
            onCollapseAnimationFinished()
        }
    }

    val progress = morphProgress.value.coerceIn(0f, 1f)
    val settledCollapsed = !slotState.isExpanded && progress <= 0.001f && !isDraggingCollapse

    if (settledCollapsed) {
        CollapsedSplitIslandRow(
            slotState = slotState,
            widthDeltaDp = widthDeltaDp,
            onTapPrimary = { onExpand() },
            onTapSecondary = onTapSecondary,
            onLongPressExpand = onExpand,
            onCycleNext = onCycleNext,
            onCyclePrevious = onCyclePrevious,
            onSwipeUpDismiss = onSoftDismiss,
            modifier = modifier,
        )
        return
    }

    val primary = slotState.primary
    val (accent, secondaryAccent) = activityAccentPair(primary)
    val minPillWidth = (Tokens.Cockpit.PillMinWidth + widthDeltaDp.dp)
        .coerceAtLeast(Tokens.Cockpit.PillMinWidth - Tokens.Header)
    val collapsedSurfaceWidth = if (slotState.secondary != null) {
        minPillWidth + Tokens.Cockpit.SplitGap + Tokens.Cockpit.BubbleSize
    } else {
        minPillWidth
    }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val maxAvailableWidth = (screenWidthDp - (Tokens.Cockpit.MorphBackdropHorizontalPad * 2))
        .coerceAtLeast(minPillWidth)
    val expandedCardWidth = minOf(Tokens.Cockpit.ExpandedMaxWidth, maxAvailableWidth)

    var measuredExpandedHeight by remember { mutableStateOf(Tokens.Cockpit.ExpandedDefaultHeight) }
    val currentWidth = lerp(collapsedSurfaceWidth, expandedCardWidth, progress)
    val currentHeight = lerp(Tokens.Cockpit.PillHeight, measuredExpandedHeight, progress)
    val currentCornerRadius = lerp(
        Tokens.Cockpit.MorphCollapsedCornerRadius,
        Tokens.Cockpit.MorphExpandedCornerRadius,
        progress,
    )
    val currentShape = RoundedCornerShape(currentCornerRadius)
    val slideOffsetPx = with(density) { Tokens.Medium.toPx() }
    val collapseDragRangePx = with(density) { (measuredExpandedHeight * 0.65f).toPx().coerceAtLeast(160f) }

    val settledExpanded = slotState.isExpanded && progress >= 0.999f && !isDraggingCollapse
    val settleSizeModifier = if (settledExpanded && !reducedMotion) {
        Modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Tokens.Cockpit.MORPH_SPRING_DAMPING,
                stiffness = Tokens.Cockpit.MORPH_SPRING_STIFFNESS,
            ),
        )
    } else {
        Modifier
    }
    val dedicatedCard = usesDedicatedCard(primary)
    val surfaceSizeModifier = if (settledExpanded) {
        Modifier.width(expandedCardWidth)
    } else {
        Modifier
            .width(currentWidth)
            .height(currentHeight)
    }

    Box(
        modifier = modifier
            .then(surfaceSizeModifier)
            .clip(currentShape)
            .then(
                if (dedicatedCard) {
                    Modifier.background(CockpitLivePalette.Oled)
                } else {
                    Modifier.glass(shape = currentShape, level = GlassLevel.Island)
                },
            )
            .pointerInput(Unit) {
                detectTapGestures { /* Consume non-button taps so they do not dismiss the backdrop scrim. */ }
            }
            .cockpitSpecularRim(
                shape = currentShape,
                primaryAccent = accent,
                secondaryAccent = secondaryAccent,
                intensity = 0.95f,
                animateOnActive = primary !is CockpitLiveActivity.IdleQuickDockActivity,
            )
            .then(settleSizeModifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (progress < 0.4f) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (1f - (progress / 0.4f)).coerceIn(0f, 1f)
                    },
            ) {
                CollapsedPillSurfaceContent(
                    primary = primary,
                    primaryAccent = accent,
                )
            }
        }

        Box(
            modifier = Modifier
                .requiredWidth(expandedCardWidth)
                .onSizeChanged { size ->
                    val measuredDp = with(density) { size.height.toDp() }
                    if (measuredDp > Tokens.Cockpit.PillHeight) {
                        measuredExpandedHeight = measuredDp
                    }
                }
                .graphicsLayer {
                    alpha = ((progress - 0.14f) / 0.86f).coerceIn(0f, 1f)
                    translationY = (1f - progress) * -slideOffsetPx
                },
        ) {
            SwapTransition(
                key = slotState.primary.id,
                direction = slotState.swapDirection,
            ) {
                ExpandedCockpitCardContent(
                    slotState = slotState,
                    telemetry = telemetry,
                    torchState = torchState,
                    quickControls = quickControls,
                    pinnedApps = pinnedApps,
                    cachedAppIcons = cachedAppIcons,
                    callAudio = callAudio,
                    activityActions = activityActions,
                    onCollapse = onCollapse,
                    onCycleNext = onCycleNext,
                    onCyclePrevious = onCyclePrevious,
                    onSoftDismiss = onSoftDismiss,
                    onRestoreDismissed = onRestoreDismissed,
                    onTapActivity = onTapPrimary,
                    onStopDesktopDrop = onStopDesktopDrop,
                    onOpenDriveLiftCockpit = onOpenDriveLiftCockpit,
                    onToggleInlineReply = onToggleInlineReply,
                    onSendWhatsAppReply = onSendWhatsAppReply,
                    onTapWifi = onTapWifi,
                    onLongPressWifi = onLongPressWifi,
                    onTapMobileData = onTapMobileData,
                    onLongPressMobileData = onLongPressMobileData,
                    onTapBluetooth = onTapBluetooth,
                    onCycleRingerMode = onCycleRingerMode,
                    onToggleTorch = onToggleTorch,
                    onTorchBrightnessChange = onTorchBrightnessChange,
                    onToggleTorchSos = onToggleTorchSos,
                    onLaunchPinnedApp = onLaunchPinnedApp,
                    onVerticalDragStart = {
                        isDraggingCollapse = true
                    },
                    onVerticalDragDelta = { totalDragPx ->
                        val draggedProgress = (1f + (totalDragPx / collapseDragRangePx)).coerceIn(0.18f, 1f)
                        scope.launch { morphProgress.snapTo(draggedProgress) }
                    },
                    onVerticalDragFinish = { shouldCollapse ->
                        isDraggingCollapse = false
                        if (shouldCollapse) {
                            onCollapse()
                        } else {
                            scope.launch { morphProgress.animateTo(1f, animationSpec = morphSpec) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CollapsedPillSurfaceContent(
    primary: CockpitLiveActivity,
    primaryAccent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Cockpit.PillHeight)
            .padding(horizontal = Tokens.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CollapsedPillBody(primary = primary, accent = primaryAccent)
    }
}

/**
 * Two clusters inside a pill row: the call, recording and navigation pills have their own layout,
 * every other activity shows its icon and label on the left and one metric on the right.
 */
@Composable
private fun RowScope.CollapsedPillBody(primary: CockpitLiveActivity, accent: Color) {
    when (primary) {
        is CockpitLiveActivity.CallActivity -> CollapsedCallContent(primary)
        is CockpitLiveActivity.ScreenRecordingActivity -> CollapsedRecordingContent(primary)
        is CockpitLiveActivity.MapsNavActivity -> CollapsedNavigationContent(primary, accent)
        is CockpitLiveActivity.IdleQuickDockActivity -> CollapsedIdleNetworkContent()
        else -> {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                Icon(
                    imageVector = activityIcon(primary),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                Text(
                    text = activityCollapsedLabel(primary),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Tokens.Small))
            Text(
                text = activityCollapsedMetric(primary),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollapsedSplitIslandRow(
    slotState: CockpitSlotState,
    widthDeltaDp: Int,
    onTapPrimary: (CockpitLiveActivity) -> Unit,
    onTapSecondary: (CockpitLiveActivity) -> Unit,
    onLongPressExpand: () -> Unit,
    onCycleNext: () -> Unit,
    onCyclePrevious: () -> Unit,
    modifier: Modifier = Modifier,
    onSwipeUpDismiss: (String) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primary = slotState.primary
    val secondary = slotState.secondary
    val (primaryAccent, secondaryAccent) = activityAccentPair(primary)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxPillWidth = computeSafePillMaxWidthDp(
        screenWidthDp = screenWidthDp,
        widthDeltaDp = widthDeltaDp,
        hasSatelliteBubble = secondary != null,
    ).dp
    val minPillWidth = (Tokens.Cockpit.PillMinWidth + widthDeltaDp.dp)
        .coerceIn(Tokens.Cockpit.PillMinWidth - Tokens.Header, maxPillWidth)
    val canSwap = slotState.visibleQueue.size > 1
    val canSwipeUpDismiss = primary is CockpitLiveActivity.WhatsAppAlertActivity
    val dragOffset = remember { Animatable(0f) }

    Row(
        modifier = modifier
            .testTag("cockpit-collapsed-row")
            .cockpitAxisLockedSwipe(
                key = primary.id,
                enableHorizontal = canSwap,
                enableVertical = canSwipeUpDismiss,
                onSwipeNext = {
                    if (canSwap) {
                        CockpitHaptics.performTick(context)
                        onCycleNext()
                    }
                },
                onSwipePrevious = {
                    if (canSwap) {
                        CockpitHaptics.performTick(context)
                        onCyclePrevious()
                    }
                },
                onHorizontalDrag = { total ->
                    if (canSwap) scope.launch { dragOffset.snapTo(total * Tokens.Cockpit.SWAP_DRAG_DAMPING) }
                },
                onHorizontalEnd = { scope.launch { dragOffset.animateTo(0f, spring()) } },
                onVerticalEnd = { shouldDismiss ->
                    if (shouldDismiss && canSwipeUpDismiss) {
                        CockpitHaptics.performTick(context)
                        onSwipeUpDismiss(primary.id)
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Cockpit.SplitGap),
    ) {
        // Primary Pill centered strictly on WindowInsets.displayCutout with zero hardcoded top offset.
        // Outer hit box meets >= 52 dp height below the cutout to clear Pixel status-bar dead zones.
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = Tokens.Cockpit.PillTouchTargetHeight)
                .combinedClickable(
                    onClickLabel = "Expand ${activityTitle(primary)}",
                    onLongClickLabel = "Expand DriveLift Cockpit",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTapPrimary(primary)
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPressExpand()
                    },
                )
                .padding(bottom = Tokens.Small)
                .testTag("cockpit-primary-pill"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(min = minPillWidth, max = maxPillWidth)
                    .height(Tokens.Cockpit.PillHeight)
                    .clip(Tokens.PillShape)
                    .background(CockpitLivePalette.Oled)
                    .cockpitSpecularRim(
                        shape = Tokens.PillShape,
                        primaryAccent = primaryAccent,
                        secondaryAccent = secondaryAccent,
                        intensity = if (primary is CockpitLiveActivity.IdleQuickDockActivity) 0.55f else 1.0f,
                        animateOnActive = primary !is CockpitLiveActivity.IdleQuickDockActivity,
                    )
                    .padding(horizontal = Tokens.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SwapTransition(
                    key = primary.id,
                    direction = slotState.swapDirection,
                    modifier = Modifier.graphicsLayer { translationX = dragOffset.value },
                ) {
                    Row(
                        modifier = Modifier.widthIn(min = minPillWidth - Tokens.Medium * 2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CollapsedPillBody(primary = primary, accent = primaryAccent)
                    }
                }
            }
        }

        // Satellite bubble for simultaneous activities: tap swaps it into the primary slot.
        if (secondary != null) {
            val (bubblePrimary, bubbleSecondary) = activityAccentPair(secondary)
            Box(
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = Tokens.TouchTarget,
                        minHeight = Tokens.Cockpit.PillTouchTargetHeight,
                    )
                    .combinedClickable(
                        onClickLabel = "Switch to ${activityTitle(secondary)}",
                        onLongClickLabel = "Expand DriveLift Cockpit",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTapSecondary(secondary)
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPressExpand()
                        },
                    )
                    .padding(bottom = Tokens.Small)
                    .testTag("cockpit-secondary-bubble"),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .size(Tokens.Cockpit.BubbleSize)
                        .clip(CircleShape)
                        .background(CockpitLivePalette.Oled)
                        .cockpitSpecularRim(
                            shape = CircleShape,
                            primaryAccent = bubblePrimary,
                            secondaryAccent = bubbleSecondary,
                            intensity = 0.95f,
                            animateOnActive = true,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = activityIcon(secondary),
                        contentDescription = activityTitle(secondary),
                        tint = bubblePrimary,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                }
            }
        }
    }
}

/**
 * Standalone Expanded Cockpit Card composable kept for direct Roborazzi snapshot verification and
 * shared internal layout with [DynamicIslandOverlay].
 */
@Composable
fun ExpandedCockpitCard(
    slotState: CockpitSlotState,
    telemetry: DriveLiftTelemetrySummary,
    torchState: TorchUiState,
    pinnedApps: List<PinnedAppInfo>,
    onCollapse: () -> Unit,
    onCycleNext: () -> Unit,
    onCyclePrevious: () -> Unit,
    onSoftDismiss: (String) -> Unit,
    onRestoreDismissed: () -> Unit,
    onTapActivity: (CockpitLiveActivity) -> Unit,
    onStopDesktopDrop: () -> Unit,
    onOpenDriveLiftCockpit: () -> Unit,
    onToggleInlineReply: (Boolean) -> Unit,
    onSendWhatsAppReply: (notificationKey: String, replyText: String) -> Unit,
    onToggleTorch: () -> Unit,
    onTorchBrightnessChange: (Int) -> Unit,
    onToggleTorchSos: () -> Unit,
    onLaunchPinnedApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    quickControls: CockpitQuickControlsState = CockpitQuickControlsState(),
    onTapWifi: () -> Unit = {},
    onLongPressWifi: () -> Unit = {},
    onTapMobileData: () -> Unit = {},
    onLongPressMobileData: () -> Unit = {},
    onTapBluetooth: () -> Unit = {},
    onCycleRingerMode: () -> Unit = {},
    callAudio: CallAudioUiState = CallAudioUiState(),
    activityActions: CockpitActivityActions = CockpitActivityActions(),
) {
    val primary = slotState.primary
    val (accent, secondaryAccent) = activityAccentPair(primary)
    val dedicatedCard = usesDedicatedCard(primary)
    val cachedAppIcons = remember(pinnedApps) {
        pinnedApps.associate { app ->
            app.packageName to app.iconBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .widthIn(max = Tokens.Cockpit.ExpandedMaxWidth)
            .fillMaxWidth()
            .clip(Tokens.Cockpit.ExpandedCardShape)
            .then(
                if (dedicatedCard) {
                    Modifier.background(CockpitLivePalette.Oled)
                } else {
                    Modifier.glass(shape = Tokens.Cockpit.ExpandedCardShape, level = GlassLevel.Island)
                },
            )
            .cockpitSpecularRim(
                shape = Tokens.Cockpit.ExpandedCardShape,
                primaryAccent = accent,
                secondaryAccent = secondaryAccent,
                intensity = 0.95f,
                animateOnActive = primary !is CockpitLiveActivity.IdleQuickDockActivity,
            ),
    ) {
        ExpandedCockpitCardContent(
            slotState = slotState,
            telemetry = telemetry,
            torchState = torchState,
            quickControls = quickControls,
            pinnedApps = pinnedApps,
            cachedAppIcons = cachedAppIcons,
            callAudio = callAudio,
            activityActions = activityActions,
            onCollapse = onCollapse,
            onCycleNext = onCycleNext,
            onCyclePrevious = onCyclePrevious,
            onSoftDismiss = onSoftDismiss,
            onRestoreDismissed = onRestoreDismissed,
            onTapActivity = onTapActivity,
            onStopDesktopDrop = onStopDesktopDrop,
            onOpenDriveLiftCockpit = onOpenDriveLiftCockpit,
            onToggleInlineReply = onToggleInlineReply,
            onSendWhatsAppReply = onSendWhatsAppReply,
            onTapWifi = onTapWifi,
            onLongPressWifi = onLongPressWifi,
            onTapMobileData = onTapMobileData,
            onLongPressMobileData = onLongPressMobileData,
            onTapBluetooth = onTapBluetooth,
            onCycleRingerMode = onCycleRingerMode,
            onToggleTorch = onToggleTorch,
            onTorchBrightnessChange = onTorchBrightnessChange,
            onToggleTorchSos = onToggleTorchSos,
            onLaunchPinnedApp = onLaunchPinnedApp,
            onVerticalDragStart = {},
            onVerticalDragDelta = {},
            onVerticalDragFinish = { shouldCollapse -> if (shouldCollapse) onCollapse() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ExpandedCockpitCardContent(
    slotState: CockpitSlotState,
    telemetry: DriveLiftTelemetrySummary,
    torchState: TorchUiState,
    quickControls: CockpitQuickControlsState,
    pinnedApps: List<PinnedAppInfo>,
    cachedAppIcons: Map<String, ImageBitmap?>,
    callAudio: CallAudioUiState,
    activityActions: CockpitActivityActions,
    onCollapse: () -> Unit,
    onCycleNext: () -> Unit,
    onCyclePrevious: () -> Unit,
    onSoftDismiss: (String) -> Unit,
    onRestoreDismissed: () -> Unit,
    onTapActivity: (CockpitLiveActivity) -> Unit,
    onStopDesktopDrop: () -> Unit,
    onOpenDriveLiftCockpit: () -> Unit,
    onToggleInlineReply: (Boolean) -> Unit,
    onSendWhatsAppReply: (notificationKey: String, replyText: String) -> Unit,
    onTapWifi: () -> Unit,
    onLongPressWifi: () -> Unit,
    onTapMobileData: () -> Unit,
    onLongPressMobileData: () -> Unit,
    onTapBluetooth: () -> Unit,
    onCycleRingerMode: () -> Unit,
    onToggleTorch: () -> Unit,
    onTorchBrightnessChange: (Int) -> Unit,
    onToggleTorchSos: () -> Unit,
    onLaunchPinnedApp: (String) -> Unit,
    onVerticalDragStart: () -> Unit,
    onVerticalDragDelta: (Float) -> Unit,
    onVerticalDragFinish: (Boolean) -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val largeFontStack = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    val compactWidthStack = largeFontStack ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp.dp - Tokens.Section)
        .coerceAtLeast(Tokens.Cockpit.ExpandedDefaultHeight)
    val primary = slotState.primary
    val (accent, secondaryAccent) = activityAccentPair(primary)
    var replyDraft by rememberSaveable(primary.id) { mutableStateOf("") }
    val canSwap = slotState.visibleQueue.size > 1
    val dragOffset = remember { Animatable(0f) }

    // One axis-locked detector on Initial pass: sideways swaps before child clickables, upward collapses
    // (and dismisses transient chat alerts), never both.
    val swipeModifier = Modifier.cockpitAxisLockedSwipe(
        key = primary.id,
        enableHorizontal = canSwap,
        enableVertical = true,
        onSwipeNext = {
            if (canSwap) {
                CockpitHaptics.performTick(context)
                onCycleNext()
            }
        },
        onSwipePrevious = {
            if (canSwap) {
                CockpitHaptics.performTick(context)
                onCyclePrevious()
            }
        },
        onHorizontalDrag = { total ->
            if (canSwap) scope.launch { dragOffset.snapTo(total * Tokens.Cockpit.SWAP_DRAG_DAMPING) }
        },
        onHorizontalEnd = { scope.launch { dragOffset.animateTo(0f, spring()) } },
        onVerticalStart = onVerticalDragStart,
        onVerticalDrag = onVerticalDragDelta,
        onVerticalEnd = { shouldCollapse ->
            if (shouldCollapse) {
                CockpitHaptics.performTick(context)
                if (primary is CockpitLiveActivity.WhatsAppAlertActivity) {
                    onSoftDismiss(primary.id)
                }
            }
            onVerticalDragFinish(shouldCollapse)
        },
    )
    val dragLayerModifier = Modifier.graphicsLayer { translationX = dragOffset.value }

    if (usesDedicatedCard(primary)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .then(swipeModifier)
                .then(dragLayerModifier)
                .padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)
                .testTag("cockpit-expanded-card"),
        ) {
            DedicatedActivityCard(
                slotState = slotState,
                callAudio = callAudio,
                actions = activityActions,
                onCycleNext = onCycleNext,
            )
        }
        return
    }

    // Build compact icon-only controls for the header so zero oversized text pills clutter the card.
    val primaryOpenDescription = when (primary) {
        is CockpitLiveActivity.TransferActivity -> if (primary.isDesktopDrop) "Open Drop" else "Open Transfers"
        is CockpitLiveActivity.WhatsAppAlertActivity -> "Open Chat"
        is CockpitLiveActivity.MapsNavActivity -> "Open Maps"
        is CockpitLiveActivity.IdleQuickDockActivity -> "Open Cockpit Hub"
        else -> "Open App"
    }
    val onPrimaryOpenClick: () -> Unit = {
        if (primary is CockpitLiveActivity.IdleQuickDockActivity) {
            onOpenDriveLiftCockpit()
        } else {
            onTapActivity(primary)
        }
    }
    val hasSecondaryActivityAction = (primary is CockpitLiveActivity.TransferActivity && primary.canStop) ||
        (primary is CockpitLiveActivity.WhatsAppAlertActivity && primary.canInlineReply)
    val hasActiveDismiss = primary !is CockpitLiveActivity.IdleQuickDockActivity
    val hasRestore = slotState.dismissedCount > 0
    val totalHeaderIcons = 2 + // Open + Collapse
        (if (hasSecondaryActivityAction) 1 else 0) +
        (if (canSwap) 1 else 0) +
        (if (hasRestore) 1 else 0) +
        (if (hasActiveDismiss) 1 else 0)
    val splitCompactActionBar = compactWidthStack || totalHeaderIcons > 3

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxCardHeight)
            .then(swipeModifier)
            .verticalScroll(rememberScrollState())
            .then(dragLayerModifier)
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)
            .testTag("cockpit-expanded-card"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        // Header row: active badge, title + subtitle, and sleek compact icon-only controls.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
        ) {
            Box(
                modifier = Modifier
                    .size(Tokens.Cockpit.AppShortcutTile)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f))
                    .border(Tokens.Hairline, accent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = activityIcon(primary),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(Tokens.Icon),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = activityTitle(primary),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = activitySubtitle(primary, telemetry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!splitCompactActionBar) {
                if (hasRestore) {
                    CompactHeaderIconButton(
                        icon = Icons.Outlined.Restore,
                        contentDescription = "Restore hidden (${slotState.dismissedCount})",
                        tint = MaterialTheme.colorScheme.secondary,
                        tag = "cockpit-restore-hidden",
                        onClick = onRestoreDismissed,
                    )
                }
                CompactHeaderIconButton(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = primaryOpenDescription,
                    tint = accent,
                    tag = "cockpit-expanded-open-app",
                    onClick = onPrimaryOpenClick,
                )
                if (canSwap) {
                    CompactHeaderIconButton(
                        icon = Icons.Outlined.SwapHoriz,
                        contentDescription = "Swap activity (${slotState.activeIndex + 1} of ${slotState.visibleQueue.size})",
                        tint = MaterialTheme.colorScheme.primary,
                        tag = "cockpit-cycle-next",
                        onClick = onCycleNext,
                    )
                }
            } else if (!compactWidthStack && canSwap) {
                CompactHeaderIconButton(
                    icon = Icons.Outlined.SwapHoriz,
                    contentDescription = "Swap activity (${slotState.activeIndex + 1} of ${slotState.visibleQueue.size})",
                    tint = MaterialTheme.colorScheme.primary,
                    tag = "cockpit-cycle-next",
                    onClick = onCycleNext,
                )
            }
            if (hasActiveDismiss) {
                CompactHeaderIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Dismiss activity",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    tag = "cockpit-soft-dismiss",
                    onClick = { onSoftDismiss(primary.id) },
                )
            }
            CompactHeaderIconButton(
                icon = Icons.Outlined.KeyboardArrowUp,
                contentDescription = "Collapse Dynamic Island",
                tint = MaterialTheme.colorScheme.onSurface,
                tag = "cockpit-collapse",
                onClick = onCollapse,
            )
        }

        // Sleek compact icon-only action strip (zero oversized text pills) when 4+ actions or compact width.
        if (splitCompactActionBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cockpit-header-actions"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny, Alignment.End),
            ) {
                when (primary) {
                    is CockpitLiveActivity.TimerRecordingActivity ->
                        StatusChip(label = primary.timeText, accent = accent)
                    is CockpitLiveActivity.MediaActivity ->
                        StatusChip(label = primary.artist.ifBlank { "Playing" }, accent = accent)
                    is CockpitLiveActivity.IdleQuickDockActivity ->
                        StatusChip(label = telemetry.statusHeadline, accent = accent)
                    else -> Unit
                }
                Spacer(Modifier.weight(1f))
                if (compactWidthStack && canSwap) {
                    CompactHeaderIconButton(
                        icon = Icons.Outlined.SwapHoriz,
                        contentDescription = "Swap activity (${slotState.activeIndex + 1} of ${slotState.visibleQueue.size})",
                        tint = MaterialTheme.colorScheme.primary,
                        tag = "cockpit-cycle-next",
                        onClick = onCycleNext,
                    )
                }
                if (hasRestore) {
                    CompactHeaderIconButton(
                        icon = Icons.Outlined.Restore,
                        contentDescription = "Restore hidden (${slotState.dismissedCount})",
                        tint = MaterialTheme.colorScheme.secondary,
                        tag = "cockpit-restore-hidden",
                        onClick = onRestoreDismissed,
                    )
                }
                CompactHeaderIconButton(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = primaryOpenDescription,
                    tint = accent,
                    tag = "cockpit-expanded-open-app",
                    onClick = onPrimaryOpenClick,
                )
                if (primary is CockpitLiveActivity.TransferActivity && primary.canStop) {
                    CompactHeaderIconButton(
                        icon = Icons.Outlined.Stop,
                        contentDescription = "Stop Desktop Drop",
                        tint = MaterialTheme.colorScheme.error,
                        destructive = true,
                        tag = "cockpit-stop-drop",
                        onClick = onStopDesktopDrop,
                    )
                }
                if (primary is CockpitLiveActivity.WhatsAppAlertActivity && primary.canInlineReply) {
                    CompactHeaderIconButton(
                        icon = Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = if (slotState.inlineReplyOpen) "Hide Reply" else "Reply",
                        tint = if (slotState.inlineReplyOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        highlighted = slotState.inlineReplyOpen,
                        tag = "cockpit-reply-toggle",
                        onClick = { onToggleInlineReply(!slotState.inlineReplyOpen) },
                    )
                }
            }
        }

        // Active Live Activity minimal body (progress bar or inline reply field only).
        when (primary) {
            is CockpitLiveActivity.TransferActivity -> {
                if (primary.progressFraction != null) {
                    LinearProgressIndicator(
                        progress = { primary.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Tokens.ProgressHeight)
                            .clip(Tokens.PillShape),
                        color = accent,
                        trackColor = GlassPalette.Dim,
                    )
                }
            }

            is CockpitLiveActivity.WhatsAppAlertActivity -> {
                AnimatedVisibility(visible = slotState.inlineReplyOpen && primary.canInlineReply) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Tokens.Tiny),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        OutlinedTextField(
                            value = replyDraft,
                            onValueChange = { replyDraft = it },
                            placeholder = { Text("Quick reply") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cockpit-reply-input"),
                        )
                        IconButton(
                            onClick = {
                                if (replyDraft.isNotBlank()) {
                                    onSendWhatsAppReply(primary.notificationKey, replyDraft)
                                    replyDraft = ""
                                    onToggleInlineReply(false)
                                }
                            },
                            modifier = Modifier
                                .defaultMinSize(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                                .testTag("cockpit-reply-send"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Send,
                                contentDescription = "Send WhatsApp reply",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            is CockpitLiveActivity.MapsNavActivity,
            is CockpitLiveActivity.TimerRecordingActivity,
            is CockpitLiveActivity.MediaActivity,
            is CockpitLiveActivity.CallActivity,
            is CockpitLiveActivity.ScreenRecordingActivity,
            is CockpitLiveActivity.IdleQuickDockActivity -> Unit
        }

        // 5-Control Tactile Glass Quick Deck (Android 17 Pixel Style):
        // Row 1: Wi-Fi (marquee SSID) + Mobile Data (monthly usage)
        // Row 2: Bluetooth (connected count) + Ringer Mode (3-state) + Icon-Only Flash button
        // Torch ON springs open full-width Liquid-Glass Pill Brightness Scrubber + SOS chip.
        QuickDeckSection(
            torchState = torchState,
            quickControls = quickControls,
            stackRow1 = largeFontStack,
            stackRow2 = compactWidthStack,
            reducedMotion = reducedMotion,
            onTapWifi = onTapWifi,
            onLongPressWifi = onLongPressWifi,
            onTapMobileData = onTapMobileData,
            onLongPressMobileData = onLongPressMobileData,
            onTapBluetooth = onTapBluetooth,
            onCycleRingerMode = onCycleRingerMode,
            onToggleTorch = onToggleTorch,
            onTorchBrightnessChange = onTorchBrightnessChange,
            onToggleTorchSos = onToggleTorchSos,
        )

        // Pinned Apps Quick Dock with pre-resolved real app icons.
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Snug)) {
            Text(
                text = "Pinned Apps",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pinnedApps.isEmpty()) {
                Text(
                    text = "Pin launcher apps in Cockpit Hub for 1-tap launch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    pinnedApps.forEach { app ->
                        val iconBmp = cachedAppIcons[app.packageName]
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                                .clip(Tokens.PillShape)
                                .background(GlassPalette.Well)
                                .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.PillShape)
                                .clickable(role = Role.Button, onClickLabel = "Open ${app.label}") {
                                    onLaunchPinnedApp(app.packageName)
                                }
                                .padding(horizontal = Tokens.Medium, vertical = Tokens.Small)
                                .testTag("cockpit-pinned-${app.packageName}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
                            ) {
                                if (iconBmp != null) {
                                    Image(
                                        bitmap = iconBmp,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(Tokens.IconSmall)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Apps,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(Tokens.IconSmall),
                                    )
                                }
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickDeckSection(
    torchState: TorchUiState,
    quickControls: CockpitQuickControlsState,
    stackRow1: Boolean,
    stackRow2: Boolean,
    reducedMotion: Boolean,
    onTapWifi: () -> Unit,
    onLongPressWifi: () -> Unit,
    onTapMobileData: () -> Unit,
    onLongPressMobileData: () -> Unit,
    onTapBluetooth: () -> Unit,
    onCycleRingerMode: () -> Unit,
    onToggleTorch: () -> Unit,
    onTorchBrightnessChange: (Int) -> Unit,
    onToggleTorchSos: () -> Unit,
) {
    val wifiActive = quickControls.wifi.isEnabled
    val wifiAccent = if (quickControls.wifi.isConnected) {
        MaterialTheme.colorScheme.primary
    } else if (wifiActive) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val dataActive = quickControls.mobileData.isEnabled
    val dataAccent = when {
        !quickControls.mobileData.hasUsageAccess -> MaterialTheme.colorScheme.secondary
        dataActive -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val btActive = quickControls.bluetooth.isEnabled
    val btAccent = when {
        !quickControls.bluetooth.hasPermission -> MaterialTheme.colorScheme.secondary
        quickControls.bluetooth.connectedCount > 0 -> MaterialTheme.colorScheme.primary
        btActive -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val (ringerIcon, ringerAccent) = when (quickControls.ringer.mode) {
        CockpitRingerMode.NORMAL -> Icons.Outlined.NotificationsActive to MaterialTheme.colorScheme.tertiary
        CockpitRingerMode.VIBRATE -> Icons.Outlined.Vibration to MaterialTheme.colorScheme.primary
        CockpitRingerMode.SILENT -> Icons.Outlined.NotificationsOff to MaterialTheme.colorScheme.secondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cockpit-quick-deck"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        // Row 1: Wi-Fi (left-aligned SSID with end ellipsis) + Mobile Data (monthly consumption)
        if (stackRow1) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                QuickDeckTile(
                    icon = if (wifiActive) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    title = "Wi-Fi",
                    subtitle = quickControls.wifi.label,
                    active = wifiActive,
                    accent = wifiAccent,
                    onClick = onTapWifi,
                    onLongClick = onLongPressWifi,
                    clickLabel = "Open internet controls",
                    longClickLabel = "Open Wi-Fi settings",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cockpit-tile-wifi"),
                )
                QuickDeckTile(
                    icon = Icons.Outlined.SignalCellularAlt,
                    title = "Mobile Data",
                    subtitle = quickControls.mobileData.label,
                    active = dataActive && quickControls.mobileData.hasUsageAccess,
                    accent = dataAccent,
                    isShimmerLoading = quickControls.mobileData.isLoadingInitial,
                    onClick = onTapMobileData,
                    onLongClick = onLongPressMobileData,
                    clickLabel = "Open mobile data controls",
                    longClickLabel = "Open data usage settings",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cockpit-tile-data"),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                QuickDeckTile(
                    icon = if (wifiActive) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    title = "Wi-Fi",
                    subtitle = quickControls.wifi.label,
                    active = wifiActive,
                    accent = wifiAccent,
                    onClick = onTapWifi,
                    onLongClick = onLongPressWifi,
                    clickLabel = "Open internet controls",
                    longClickLabel = "Open Wi-Fi settings",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cockpit-tile-wifi"),
                )
                QuickDeckTile(
                    icon = Icons.Outlined.SignalCellularAlt,
                    title = "Mobile Data",
                    subtitle = quickControls.mobileData.label,
                    active = dataActive && quickControls.mobileData.hasUsageAccess,
                    accent = dataAccent,
                    isShimmerLoading = quickControls.mobileData.isLoadingInitial,
                    onClick = onTapMobileData,
                    onLongClick = onLongPressMobileData,
                    clickLabel = "Open mobile data controls",
                    longClickLabel = "Open data usage settings",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cockpit-tile-data"),
                )
            }
        }

        // Row 2: Bluetooth + Ringer Mode + Icon-Only Flash Toggle
        if (stackRow2) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                QuickDeckTile(
                    icon = when {
                        !btActive -> Icons.Outlined.BluetoothDisabled
                        quickControls.bluetooth.connectedCount > 0 -> Icons.Outlined.BluetoothConnected
                        else -> Icons.Outlined.Bluetooth
                    },
                    title = "Bluetooth",
                    subtitle = quickControls.bluetooth.label,
                    active = btActive,
                    accent = btAccent,
                    onClick = onTapBluetooth,
                    clickLabel = "Open Bluetooth settings",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cockpit-tile-bluetooth"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuickDeckTile(
                        icon = ringerIcon,
                        title = "Sound",
                        subtitle = quickControls.ringer.label,
                        active = true,
                        accent = ringerAccent,
                        onClick = onCycleRingerMode,
                        clickLabel = "Cycle ringer mode",
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cockpit-tile-ringer"),
                    )
                    IconOnlyTorchTile(
                        torchState = torchState,
                        onToggleTorch = onToggleTorch,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickDeckTile(
                    icon = when {
                        !btActive -> Icons.Outlined.BluetoothDisabled
                        quickControls.bluetooth.connectedCount > 0 -> Icons.Outlined.BluetoothConnected
                        else -> Icons.Outlined.Bluetooth
                    },
                    title = "Bluetooth",
                    subtitle = quickControls.bluetooth.label,
                    active = btActive,
                    accent = btAccent,
                    onClick = onTapBluetooth,
                    clickLabel = "Open Bluetooth settings",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cockpit-tile-bluetooth"),
                )
                QuickDeckTile(
                    icon = ringerIcon,
                    title = "Sound",
                    subtitle = quickControls.ringer.label,
                    active = true,
                    accent = ringerAccent,
                    onClick = onCycleRingerMode,
                    clickLabel = "Cycle ringer mode",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cockpit-tile-ringer"),
                )
                IconOnlyTorchTile(
                    torchState = torchState,
                    onToggleTorch = onToggleTorch,
                )
            }
        }

        // Torch ON smoothly reveals Liquid-Glass Pill Brightness Scrubber + SOS chip.
        val sliderEnter = if (reducedMotion) {
            fadeIn(snap())
        } else {
            expandVertically(
                animationSpec = spring(
                    dampingRatio = Tokens.Cockpit.MORPH_SPRING_DAMPING,
                    stiffness = Tokens.Cockpit.MORPH_SPRING_STIFFNESS,
                ),
            ) + fadeIn()
        }
        val sliderExit = if (reducedMotion) {
            fadeOut(snap())
        } else {
            shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Tokens.Cockpit.MORPH_SPRING_DAMPING,
                    stiffness = Tokens.Cockpit.MORPH_SPRING_STIFFNESS,
                ),
            ) + fadeOut()
        }

        AnimatedVisibility(
            visible = torchState.isOn,
            enter = sliderEnter,
            exit = sliderExit,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cockpit-torch-controls-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                TorchBrightnessScrubber(
                    torchState = torchState,
                    onBrightnessChange = onTorchBrightnessChange,
                    modifier = Modifier.weight(1f),
                )
                TorchSosPillButton(
                    sosActive = torchState.sosActive,
                    enabled = torchState.available,
                    onClick = onToggleTorchSos,
                )
            }
        }

        // Subtle inline status confirmation pill.
        AnimatedVisibility(visible = !quickControls.statusConfirmation.isNullOrBlank()) {
            quickControls.statusConfirmation?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassPalette.HudElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Tokens.PillShape)
                        .background(GlassPalette.HudTileRecessed)
                        .border(Tokens.Hairline, GlassPalette.HudTrackRing, Tokens.PillShape)
                        .padding(horizontal = Tokens.Medium, vertical = Tokens.Tiny)
                        .testTag("cockpit-quick-status"),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickDeckTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    clickLabel: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    isShimmerLoading: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val reducedMotion = LocalReducedMotion.current
    val fillBrush = if (active) {
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = 0.22f),
                GlassPalette.HudTileRecessed,
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                GlassPalette.Well,
                GlassPalette.HudTileRecessed,
            ),
        )
    }
    val borderColor = if (active) accent.copy(alpha = 0.55f) else GlassPalette.WellEdge

    val interactiveMod = if (onLongClick != null) {
        Modifier.combinedClickable(
            role = Role.Button,
            onClickLabel = clickLabel,
            onLongClickLabel = longClickLabel,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        )
    } else {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = clickLabel,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = Tokens.Cockpit.QuickTileMinHeight)
            .clip(Tokens.Cockpit.QuickDeckShape)
            .background(fillBrush)
            .border(Tokens.Hairline, borderColor, Tokens.Cockpit.QuickDeckShape)
            .then(interactiveMod)
            .padding(horizontal = Tokens.Row, vertical = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.IconSmall),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isShimmerLoading) {
                ShimmerLoadingBar(reducedMotion = reducedMotion)
            } else {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ShimmerLoadingBar(reducedMotion: Boolean) {
    val shimmerAlpha = if (reducedMotion) {
        0.24f
    } else {
        val transition = rememberInfiniteTransition(label = "quick_deck_shimmer")
        val value by transition.animateFloat(
            initialValue = 0.14f,
            targetValue = 0.34f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = Tokens.Cockpit.SHIMMER_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "shimmer_alpha",
        )
        value
    }
    Box(
        modifier = Modifier
            .padding(top = Tokens.Tiny)
            .fillMaxWidth(0.75f)
            .height(Tokens.Small)
            .clip(Tokens.PillShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = shimmerAlpha)),
    )
}

@Composable
private fun IconOnlyTorchTile(
    torchState: TorchUiState,
    onToggleTorch: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val enabled = torchState.available
    val isOn = torchState.isOn && enabled
    val bgColor = when {
        !enabled -> GlassPalette.HudTileDisabled
        isOn -> GlassPalette.PrimaryCyan.copy(alpha = 0.24f)
        else -> GlassPalette.Well
    }
    val borderColor = when {
        !enabled -> GlassPalette.HudTileDisabledBorder
        isOn -> GlassPalette.PrimaryCyan
        else -> GlassPalette.WellEdge
    }
    val iconTint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        isOn -> GlassPalette.PrimaryCyan
        else -> MaterialTheme.colorScheme.onSurface
    }
    val contentDesc = when {
        !enabled -> "Flashlight unavailable while camera is in use"
        isOn -> "Turn flashlight off"
        else -> "Turn flashlight on"
    }

    Box(
        modifier = Modifier
            .size(Tokens.Cockpit.QuickTileMinHeight)
            .clip(Tokens.Cockpit.QuickDeckShape)
            .background(bgColor)
            .border(
                width = if (isOn) Tokens.Stroke else Tokens.Hairline,
                color = borderColor,
                shape = Tokens.Cockpit.QuickDeckShape,
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDesc,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggleTorch()
            }
            .testTag("cockpit-torch-toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
            contentDescription = contentDesc,
            tint = iconTint,
            modifier = Modifier.size(Tokens.Icon),
        )
    }
}

@Composable
private fun TorchBrightnessScrubber(
    torchState: TorchUiState,
    onBrightnessChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxLevel = torchState.maxLevel.coerceAtLeast(1)
    val interactive = torchState.supportsBrightnessSlider && maxLevel > 1
    val fraction = if (maxLevel <= 1) {
        1f
    } else {
        (torchState.brightnessLevel.toFloat() / maxLevel.toFloat()).coerceIn(0.05f, 1f)
    }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }

    fun fractionToLevel(xPx: Float): Int {
        val ratio = (xPx / trackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (1 + (ratio * (maxLevel - 1)).roundToInt()).coerceIn(1, maxLevel)
    }

    BoxWithConstraints(
        modifier = modifier
            .height(Tokens.Cockpit.LiquidSliderHeight)
            .clip(Tokens.PillShape)
            .background(GlassPalette.HudTileRecessed)
            .border(Tokens.Hairline, GlassPalette.PrimaryCyan.copy(alpha = 0.65f), Tokens.PillShape)
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = torchState.brightnessLevel.toFloat(),
                    range = 1f..maxLevel.toFloat(),
                )
                if (interactive) {
                    setProgress { target ->
                        onBrightnessChange(target.roundToInt().coerceIn(1, maxLevel))
                        true
                    }
                }
            }
            .then(
                if (interactive) {
                    Modifier
                        .pointerInput(maxLevel) {
                            detectTapGestures { offset ->
                                onBrightnessChange(fractionToLevel(offset.x))
                            }
                        }
                        .pointerInput(maxLevel) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                onBrightnessChange(fractionToLevel(change.position.x))
                            }
                        }
                } else {
                    Modifier
                },
            )
            .testTag("cockpit-torch-slider"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(Tokens.PillShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            GlassPalette.HudAuroraCyan,
                            GlassPalette.PrimaryCyan.copy(alpha = 0.68f),
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = Icons.Outlined.FlashlightOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(Tokens.IconSmall),
            )
            Text(
                text = "${torchState.brightnessPercent}%",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TorchSosPillButton(
    sosActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fgColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        sosActive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val surfaceModifier = when {
        !enabled -> Modifier
            .background(GlassPalette.HudTileDisabled, Tokens.PillShape)
            .border(Tokens.Hairline, GlassPalette.HudTileDisabledBorder, Tokens.PillShape)
        sosActive -> Modifier.destructiveGlowBorder(Tokens.PillShape)
        else -> Modifier
            .background(GlassPalette.Well, Tokens.PillShape)
            .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.PillShape)
    }

    Row(
        modifier = Modifier
            .defaultMinSize(minWidth = Tokens.Cockpit.SosPillMinWidth, minHeight = Tokens.Cockpit.LiquidSliderHeight)
            .clip(Tokens.PillShape)
            .then(surfaceModifier)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (sosActive) "Stop flashlight SOS" else "Start flashlight SOS",
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Medium)
            .testTag("cockpit-torch-sos"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Bolt,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(Tokens.IconSmall),
        )
        Spacer(Modifier.width(Tokens.Tiny))
        Text(
            text = "SOS",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = fgColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    tag: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    highlighted: Boolean = false,
) {
    val wellModifier = when {
        destructive -> Modifier.destructiveGlowBorder(CircleShape)
        highlighted -> Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), CircleShape)
            .border(Tokens.Hairline, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape)
        else -> Modifier
            .background(GlassPalette.Well, CircleShape)
            .border(Tokens.Hairline, GlassPalette.WellEdge, CircleShape)
    }
    Box(
        modifier = Modifier
            .size(Tokens.TouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Tokens.Cockpit.HeaderActionWell)
                .clip(CircleShape)
                .then(wellModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(Tokens.IconSmall),
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(Tokens.PillShape)
            .background(accent.copy(alpha = 0.14f))
            .border(Tokens.Hairline, accent.copy(alpha = 0.45f), Tokens.PillShape)
            .padding(horizontal = Tokens.Medium, vertical = Tokens.Snug),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun activityAccentPair(activity: CockpitLiveActivity): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (activity) {
        is CockpitLiveActivity.CallActivity ->
            if (activity.state == CallState.INCOMING) {
                CockpitLivePalette.Answer.toWideGamutP3() to CockpitLivePalette.Emerald.toWideGamutP3()
            } else {
                CockpitLivePalette.Emerald.toWideGamutP3() to CockpitLivePalette.Cyan.toWideGamutP3()
            }
        is CockpitLiveActivity.ScreenRecordingActivity ->
            CockpitLivePalette.Recording.toWideGamutP3() to CockpitLivePalette.Decline.toWideGamutP3()
        is CockpitLiveActivity.TransferActivity ->
            if (activity.isDesktopDrop) {
                scheme.tertiary.toWideGamutP3() to scheme.secondary.toWideGamutP3()
            } else {
                scheme.primary.toWideGamutP3() to scheme.tertiary.toWideGamutP3()
            }
        is CockpitLiveActivity.TimerRecordingActivity ->
            if (activity.isRecording) {
                scheme.error.toWideGamutP3() to scheme.primary.toWideGamutP3()
            } else {
                scheme.secondary.toWideGamutP3() to scheme.tertiary.toWideGamutP3()
            }
        is CockpitLiveActivity.MediaActivity ->
            scheme.secondary.toWideGamutP3() to scheme.tertiary.toWideGamutP3()
        is CockpitLiveActivity.MapsNavActivity ->
            scheme.tertiary.toWideGamutP3() to scheme.secondary.toWideGamutP3()
        is CockpitLiveActivity.WhatsAppAlertActivity ->
            scheme.primary.toWideGamutP3() to scheme.secondary.toWideGamutP3()
        is CockpitLiveActivity.IdleQuickDockActivity ->
            scheme.secondary.toWideGamutP3() to scheme.tertiary.toWideGamutP3()
    }
}

private fun activityIcon(activity: CockpitLiveActivity): ImageVector = when (activity) {
    is CockpitLiveActivity.CallActivity ->
        if (activity.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Call
    is CockpitLiveActivity.ScreenRecordingActivity -> Icons.Outlined.FiberManualRecord
    is CockpitLiveActivity.TransferActivity ->
        if (activity.isDesktopDrop) Icons.Outlined.Devices else Icons.Outlined.CloudUpload
    is CockpitLiveActivity.TimerRecordingActivity ->
        if (activity.isRecording) Icons.Outlined.Mic else Icons.Outlined.Timer
    is CockpitLiveActivity.MediaActivity -> Icons.Outlined.MusicNote
    is CockpitLiveActivity.MapsNavActivity -> when (activity.turnDirection) {
        MapsTurnDirection.LEFT -> Icons.Outlined.TurnLeft
        MapsTurnDirection.RIGHT -> Icons.Outlined.TurnRight
        MapsTurnDirection.UTURN -> Icons.Outlined.UTurnLeft
        MapsTurnDirection.ARRIVE -> Icons.Outlined.Navigation
        MapsTurnDirection.STRAIGHT -> Icons.Outlined.Directions
    }
    is CockpitLiveActivity.WhatsAppAlertActivity -> Icons.Outlined.Sms
    is CockpitLiveActivity.IdleQuickDockActivity -> Icons.Outlined.Apps
}

private fun activityTitle(activity: CockpitLiveActivity): String = when (activity) {
    is CockpitLiveActivity.CallActivity -> activity.callerName
    is CockpitLiveActivity.ScreenRecordingActivity -> "Screen Recording"
    is CockpitLiveActivity.TransferActivity -> activity.title
    is CockpitLiveActivity.TimerRecordingActivity -> activity.label
    is CockpitLiveActivity.MediaActivity -> activity.title
    is CockpitLiveActivity.MapsNavActivity -> activity.maneuverText
    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.senderName
    is CockpitLiveActivity.IdleQuickDockActivity -> "DriveLift Cockpit"
}

private fun activitySubtitle(
    activity: CockpitLiveActivity,
    telemetry: DriveLiftTelemetrySummary,
): String = when (activity) {
    is CockpitLiveActivity.CallActivity -> activity.sourceLabel
    is CockpitLiveActivity.ScreenRecordingActivity -> "Recording active"
    is CockpitLiveActivity.TransferActivity -> formatTransferActivitySubtitle(activity.subtitle, activity.speedText)
    is CockpitLiveActivity.TimerRecordingActivity ->
        if (activity.isRecording) "Recording active" else "Timer running"
    is CockpitLiveActivity.MediaActivity -> activity.artist
    is CockpitLiveActivity.MapsNavActivity ->
        listOf(activity.distanceText, activity.etaText).filter { it.isNotBlank() }.joinToString(" | ")
            .ifEmpty { "Navigation active" }
    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.messageSnippet
    is CockpitLiveActivity.IdleQuickDockActivity -> "DriveLift: ${telemetry.statusHeadline}"
}

private fun activityCollapsedLabel(activity: CockpitLiveActivity): String = when (activity) {
    is CockpitLiveActivity.CallActivity -> activity.callerName
    is CockpitLiveActivity.ScreenRecordingActivity -> "Recording"
    is CockpitLiveActivity.TransferActivity -> activity.title
    is CockpitLiveActivity.TimerRecordingActivity -> activity.label
    is CockpitLiveActivity.MediaActivity -> activity.title
    is CockpitLiveActivity.MapsNavActivity -> activity.maneuverText
    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.senderName
    is CockpitLiveActivity.IdleQuickDockActivity -> "Cockpit"
}

private fun activityCollapsedMetric(activity: CockpitLiveActivity): String = when (activity) {
    is CockpitLiveActivity.CallActivity -> if (activity.state == CallState.INCOMING) "Incoming" else "Call"
    is CockpitLiveActivity.ScreenRecordingActivity -> "Rec"
    is CockpitLiveActivity.TransferActivity -> activity.speedText
    is CockpitLiveActivity.TimerRecordingActivity -> activity.timeText
    is CockpitLiveActivity.MediaActivity -> "Playing"
    is CockpitLiveActivity.MapsNavActivity -> activity.distanceText.ifEmpty { activity.etaText }
    is CockpitLiveActivity.WhatsAppAlertActivity -> if (activity.canInlineReply) "Reply" else "Msg"
    is CockpitLiveActivity.IdleQuickDockActivity -> "Ready"
}
