package com.drivelift.app.feature.cockpit.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LocationOn
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitDurationFormat
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.activities.TripDistanceFormat
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.CockpitHaptics
import com.drivelift.app.feature.cockpit.hardware.CockpitNetworkFormat
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsState
import com.drivelift.app.feature.cockpit.hardware.CockpitRingerMode
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.notifications.CallAudioOutput
import com.drivelift.app.feature.cockpit.notifications.CallAudioUiState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.cockpitSpecularRim
import com.drivelift.app.feature.cockpit.overlay.toWideGamutP3
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.GlassLevel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.TABULAR_DIGITS
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
    val sideMargin = if (screenWidthDp < Tokens.COMPACT_WIDTH_DP) {
        Tokens.Cockpit.StatusBarCompactSideMargin.value.toInt()
    } else {
        Tokens.Cockpit.StatusBarSafeSideMargin.value.toInt()
    }
    val satelliteReserve = if (hasSatelliteBubble) {
        // Reserve both the end satellite touch container + gap AND the symmetric start
        // counterweight + gap so the centred split row stays within safe status-bar margins.
        ((Tokens.TouchTarget + Tokens.Cockpit.SplitGap).value.toInt()) * 2
    } else {
        0
    }
    val minFloor = if (hasSatelliteBubble) {
        (Tokens.Cockpit.PillMinWidth - Tokens.Header - Tokens.Small).value.toInt()
    } else {
        (Tokens.Cockpit.PillMinWidth - Tokens.Header).value.toInt()
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
    cutoutWidthDp: Int = 0,
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
    onTapWifiLocationHint: () -> Unit = {},
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
            cutoutWidthDp = cutoutWidthDp,
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
                    cutoutWidthDp = cutoutWidthDp,
                    widthDeltaDp = widthDeltaDp,
                    minContentWidth = (minPillWidth - Tokens.Medium * 2).coerceAtLeast(Tokens.NoInset),
                    maxContentWidth = (currentWidth - Tokens.Medium * 2).coerceAtLeast(Tokens.NoInset),
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
                    onTapWifiLocationHint = onTapWifiLocationHint,
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
    cutoutWidthDp: Int = 0,
    widthDeltaDp: Int = 0,
    minContentWidth: Dp = Tokens.Cockpit.PillMinWidth - (Tokens.Medium * 2),
    maxContentWidth: Dp = Tokens.Cockpit.PillMaxWidth - (Tokens.Medium * 2),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Cockpit.PillHeight)
            .padding(horizontal = Tokens.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CollapsedPillBody(
            primary = primary,
            accent = primaryAccent,
            cutoutWidthDp = cutoutWidthDp,
            widthDeltaDp = widthDeltaDp,
            minContentWidth = minContentWidth,
            maxContentWidth = maxContentWidth,
        )
    }
}

/**
 * Collapsed pill body for every activity state:
 * - Idle delegates directly to [CollapsedIdleNetworkContent].
 * - Active activities obey the exact same status-pill width cap and symmetric cutout centring as
 *   the idle pill when a physical cutout is present or live traffic is flowing.
 * - Whenever an activity is live AND network traffic >= 1K exists, live speeds take priority in
 *   the right zone over static text such as "LAN ready".
 */
@Composable
private fun RowScope.CollapsedPillBody(
    primary: CockpitLiveActivity,
    accent: Color,
    cutoutWidthDp: Int = 0,
    widthDeltaDp: Int = 0,
    minContentWidth: Dp = Tokens.Cockpit.PillMinWidth - (Tokens.Medium * 2),
    maxContentWidth: Dp = Tokens.Cockpit.PillMaxWidth - (Tokens.Medium * 2),
) {
    val net = rememberIdleNetworkSnapshot()
    if (primary is CockpitLiveActivity.IdleQuickDockActivity) {
        CollapsedIdleNetworkContent(
            cutoutWidthDp = cutoutWidthDp,
            widthDeltaDp = widthDeltaDp,
            minContentWidth = minContentWidth,
            maxContentWidth = maxContentWidth,
            networkSnapshot = net,
        )
        return
    }

    val fontScale = LocalDensity.current.fontScale
    val showLiveSpeeds = net.hasActiveTraffic
    val useCutoutZones = cutoutWidthDp > 0 || showLiveSpeeds

    if (useCutoutZones) {
        val layout = remember(
            cutoutWidthDp,
            widthDeltaDp,
            minContentWidth,
            maxContentWidth,
            net.hasUploadTraffic,
            net.hasDownloadTraffic,
            net.uploadBytesPerSec,
            net.downloadBytesPerSec,
            fontScale,
        ) {
            IdlePillLayoutMath.compute(
                maxContentWidthDp = maxContentWidth.value,
                minContentWidthDp = minContentWidth.value,
                measuredCutoutWidthDp = cutoutWidthDp.toFloat(),
                cutoutWidthDeltaDp = widthDeltaDp.toFloat(),
                hasUploadTraffic = net.hasUploadTraffic,
                hasDownloadTraffic = net.hasDownloadTraffic,
                uploadBytesPerSec = net.uploadBytesPerSec,
                downloadBytesPerSec = net.downloadBytesPerSec,
                fontScale = fontScale,
            )
        }

        Box(
            modifier = Modifier
                .width(layout.leftZoneWidthDp.dp)
                .clipToBounds(),
            contentAlignment = Alignment.CenterStart,
        ) {
            CollapsedActivityLeftCluster(
                primary = primary,
                accent = accent,
                showLabel = layout.showConnectionLabel,
            )
        }

        Spacer(
            modifier = Modifier
                .width(layout.centerGapDp.dp)
                .height(Tokens.IconSmall)
                .testTag("cockpit-pill-cutout-gap"),
        )

        Box(
            modifier = Modifier
                .width(layout.rightZoneWidthDp.dp)
                .clipToBounds(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (showLiveSpeeds) {
                CollapsedLiveSpeedCluster(net = net, layout = layout)
            } else {
                CollapsedActivityRightCluster(primary = primary, accent = accent)
            }
        }
        return
    }

    when (primary) {
        is CockpitLiveActivity.CallActivity -> CollapsedCallContent(primary)
        is CockpitLiveActivity.ScreenRecordingActivity -> CollapsedRecordingContent(primary)
        is CockpitLiveActivity.MapsNavActivity -> CollapsedNavigationContent(primary, accent)
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
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CollapsedActivityLeftCluster(
    primary: CockpitLiveActivity,
    accent: Color,
    showLabel: Boolean,
) {
    when (primary) {
        is CockpitLiveActivity.CallActivity -> {
            val ringing = primary.state == CallState.INCOMING
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Call,
                    contentDescription = null,
                    tint = if (ringing) CockpitLivePalette.Answer else CockpitLivePalette.Emerald,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                if (showLabel) {
                    if (ringing) {
                        Text(
                            text = primary.callerName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        val now by rememberTickingNowMs()
                        Text(
                            text = CockpitDurationFormat.clock(now - primary.startedAtMs),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.testTag("cockpit-call-timer"),
                        )
                    }
                }
            }
        }
        is CockpitLiveActivity.ScreenRecordingActivity -> {
            val now by rememberTickingNowMs()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                PulsingDot(color = CockpitLivePalette.Recording)
                if (showLabel) {
                    Text(
                        text = CockpitDurationFormat.clock(now - primary.startedAtMs),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = CockpitLivePalette.Recording,
                        maxLines = 1,
                        modifier = Modifier.testTag("cockpit-recording-timer"),
                    )
                }
            }
        }
        is CockpitLiveActivity.MapsNavActivity -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                Icon(
                    imageVector = navManeuverIcon(primary.turnDirection),
                    contentDescription = "Next maneuver",
                    tint = accent,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                if (showLabel) {
                    Text(
                        text = primary.distanceText.ifEmpty { primary.etaText },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = CockpitLivePalette.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("cockpit-nav-distance"),
                    )
                }
            }
        }
        else -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                Icon(
                    imageVector = activityIcon(primary),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                if (showLabel) {
                    Text(
                        text = activityCollapsedLabel(primary),
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

@Composable
private fun CollapsedActivityRightCluster(
    primary: CockpitLiveActivity,
    accent: Color,
) {
    when (primary) {
        is CockpitLiveActivity.CallActivity -> {
            if (primary.state == CallState.INCOMING) {
                Text(
                    text = "Incoming",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = CockpitLivePalette.Answer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                VoiceWaveform(modifier = Modifier.testTag("cockpit-call-waveform"))
            }
        }
        is CockpitLiveActivity.ScreenRecordingActivity -> {
            Box(
                modifier = Modifier
                    .size(Tokens.Cockpit.RecordingStopBox)
                    .clip(RoundedCornerShape(Tokens.Tiny))
                    .background(CockpitLivePalette.Recording)
                    .semantics { contentDescription = "Screen recording in progress" },
            )
        }
        is CockpitLiveActivity.MapsNavActivity -> {
            Text(
                text = primary.etaText.ifEmpty { primary.distanceText },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = CockpitLivePalette.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        else -> {
            Text(
                text = activityCollapsedMetric(primary),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    cutoutWidthDp: Int = 0,
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
    val minFloorWidth = minOf(Tokens.Cockpit.PillMinWidth - Tokens.Header, maxPillWidth)
    val minPillWidth = (Tokens.Cockpit.PillMinWidth + widthDeltaDp.dp)
        .coerceIn(minFloorWidth, maxPillWidth)
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
                verticalThresholdPx = Tokens.Cockpit.COLLAPSED_SWIPE_DISMISS_PX,
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
        // Symmetric counterweight when a satellite bubble is visible so the primary pill remains
        // strictly centred on the camera punch-hole cutout rather than shifting left.
        if (secondary != null) {
            Spacer(
                modifier = Modifier
                    .width(Tokens.TouchTarget)
                    .height(Tokens.Cockpit.PillHeight),
            )
        }

        // Primary Pill centered strictly on WindowInsets.displayCutout with zero hardcoded top offset.
        // Outer hit box meets >= 52 dp height below the cutout to clear Pixel status-bar dead zones.
        val minContentWidth = (minPillWidth - Tokens.Medium * 2).coerceAtLeast(Tokens.NoInset)
        val maxContentWidth = (maxPillWidth - Tokens.Medium * 2).coerceAtLeast(Tokens.NoInset)
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
                        modifier = Modifier.widthIn(min = minContentWidth, max = maxContentWidth),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CollapsedPillBody(
                            primary = primary,
                            accent = primaryAccent,
                            cutoutWidthDp = cutoutWidthDp,
                            widthDeltaDp = widthDeltaDp,
                            minContentWidth = minContentWidth,
                            maxContentWidth = maxContentWidth,
                        )
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
@OptIn(ExperimentalLayoutApi::class)
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
    onTapWifiLocationHint: () -> Unit = {},
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
            onTapWifiLocationHint = onTapWifiLocationHint,
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
    onTapWifiLocationHint: () -> Unit,
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
    val hasActiveDismiss = primary !is CockpitLiveActivity.IdleQuickDockActivity
    val hasRestore = slotState.dismissedCount > 0
    val liveNet = rememberIdleNetworkSnapshot()

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
        // Header: full-width title + single-line subtitle so nothing wraps awkwardly.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Box(
                modifier = Modifier
                    .size(Tokens.Cockpit.HeaderActionWell)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f))
                    .border(Tokens.Hairline, accent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = activityIcon(primary),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(Tokens.IconSmall),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("cockpit-expanded-subtitle"),
                )
            }
        }

        // Compact icon-only action row (Open, Stop/Reply, Swap, Restore, Close, Collapse).
        // Uses FlowRow so normal widths stay on one compact row while narrow 320 dp phones with
        // all six buttons wrap cleanly instead of fighting parent horizontal swipe gestures.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cockpit-header-actions"),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            if (canSwap) {
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
            if ((primary is CockpitLiveActivity.TransferActivity && primary.canStop) ||
                (primary is CockpitLiveActivity.IdleQuickDockActivity && telemetry.isDesktopDropRunning)
            ) {
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

        // Live, activity-specific info strip (Desktop Drop URL + Copy + PIN + speed + counts,
        // Navigation ETA/km/speed, Calls duration/audio route, or Default live up/down speed + connection).
        ExpandedActivityInfoStrip(
            primary = primary,
            telemetry = telemetry,
            callAudio = callAudio,
            liveNet = liveNet,
            accent = accent,
        )

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

        // Aligned 2-column Quick Deck Grid:
        // Row 1: Wi-Fi (marquee SSID) + Mobile Data (monthly usage)
        // Row 2: Bluetooth (connected count) + Sound (3-state ringer)
        // Row 3: Labelled Torch tile spanning the same grid edges (expands brightness slider + SOS when ON)
        QuickDeckSection(
            torchState = torchState,
            quickControls = quickControls,
            stackTiles = compactWidthStack,
            reducedMotion = reducedMotion,
            onTapWifi = onTapWifi,
            onLongPressWifi = onLongPressWifi,
            onTapWifiLocationHint = onTapWifiLocationHint,
            onTapMobileData = onTapMobileData,
            onLongPressMobileData = onLongPressMobileData,
            onTapBluetooth = onTapBluetooth,
            onCycleRingerMode = onCycleRingerMode,
            onToggleTorch = onToggleTorch,
            onTorchBrightnessChange = onTorchBrightnessChange,
            onToggleTorchSos = onToggleTorchSos,
        )

        // Pinned Apps Quick Dock with edge fade + scroll chevron whenever items overflow.
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
                val pinnedScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cockpit-pinned-apps-row"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(pinnedScroll)
                            .padding(end = if (pinnedScroll.canScrollForward) Tokens.Section else Tokens.NoInset),
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
                    if (pinnedScroll.canScrollBackward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(Tokens.Cockpit.PinnedScrollFadeWidth)
                                .height(Tokens.TouchTarget)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(GlassPalette.HudDockFill, Color.Transparent),
                                    ),
                                ),
                        )
                    }
                    if (pinnedScroll.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(Tokens.Cockpit.PinnedScrollFadeWidth)
                                .height(Tokens.TouchTarget)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color.Transparent, GlassPalette.HudDockFill),
                                    ),
                                )
                                .testTag("cockpit-pinned-scroll-hint"),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = "Scroll for more pinned apps",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(Tokens.IconSmall),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedActivityInfoStrip(
    primary: CockpitLiveActivity,
    telemetry: DriveLiftTelemetrySummary,
    callAudio: CallAudioUiState,
    liveNet: IdleNetworkSnapshot,
    accent: Color,
) {
    val context = LocalContext.current
    var copiedUrl by remember(telemetry.desktopDropUrl) { mutableStateOf(false) }
    val showDesktopDropStrip = (primary is CockpitLiveActivity.TransferActivity && primary.isDesktopDrop) ||
        (primary is CockpitLiveActivity.IdleQuickDockActivity && telemetry.isDesktopDropRunning)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Tokens.Cockpit.QuickDeckShape)
            .background(GlassPalette.Well)
            .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.Cockpit.QuickDeckShape)
            .padding(horizontal = Tokens.Row, vertical = Tokens.Small)
            .testTag("cockpit-expanded-info-strip"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        when {
            showDesktopDropStrip -> {
                val url = telemetry.desktopDropUrl
                if (!url.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = TABULAR_DIGITS,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cockpit-drop-url"),
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                                .clip(Tokens.PillShape)
                                .clickable(role = Role.Button, onClickLabel = "Copy Desktop Drop address") {
                                    runCatching {
                                        val cm = context.getSystemService(ClipboardManager::class.java)
                                        cm?.setPrimaryClip(ClipData.newPlainText("Desktop Drop URL", url))
                                        copiedUrl = true
                                    }
                                }
                                .padding(horizontal = Tokens.Small)
                                .testTag("cockpit-drop-copy-url"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = if (copiedUrl) "Address copied" else "Copy address",
                                    tint = if (copiedUrl) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(Tokens.IconSmall),
                                )
                                Text(
                                    text = if (copiedUrl) "Copied" else "Copy",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (copiedUrl) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                val upRate = if (telemetry.desktopDropUploadBytesPerSec > 0L) {
                    telemetry.desktopDropUploadBytesPerSec
                } else {
                    liveNet.uploadBytesPerSec
                }
                val downRate = if (telemetry.desktopDropDownloadBytesPerSec > 0L) {
                    telemetry.desktopDropDownloadBytesPerSec
                } else {
                    liveNet.downloadBytesPerSec
                }
                val stackMetrics = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
                val countsAndPinRow: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        if (!telemetry.desktopDropPairingCode.isNullOrBlank()) {
                            Text(
                                text = "PIN ${telemetry.desktopDropPairingCode}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = TABULAR_DIGITS,
                                ),
                                color = accent,
                                maxLines = 1,
                                modifier = Modifier.testTag("cockpit-drop-pin"),
                            )
                        }
                        Text(
                            text = "Sent ${telemetry.desktopDropSentCount} · Recv ${telemetry.desktopDropReceivedCount}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_DIGITS),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("cockpit-drop-counts"),
                        )
                    }
                }
                val speedsRow: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
                    ) {
                        SpeedFigure(
                            text = CockpitNetworkFormat.formatRate(upRate),
                            up = true,
                            tint = CockpitLivePalette.Cyan,
                            compact = true,
                        )
                        SpeedFigure(
                            text = CockpitNetworkFormat.formatRate(downRate),
                            up = false,
                            tint = CockpitLivePalette.Emerald,
                            compact = true,
                        )
                    }
                }
                if (stackMetrics) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                    ) {
                        countsAndPinRow()
                        speedsRow()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(Modifier.weight(1f)) { countsAndPinRow() }
                        Spacer(Modifier.width(Tokens.Snug))
                        speedsRow()
                    }
                }
            }
            primary is CockpitLiveActivity.MapsNavActivity -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    InfoStripMetric(label = "ETA", value = primary.etaText.ifBlank { "--" }, tint = accent)
                    InfoStripMetric(
                        label = "Left",
                        value = TripDistanceFormat.hudValue(primary.remainingTripKm, primary.tripUsesMiles),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    InfoStripMetric(
                        label = "Speed",
                        value = "${TripDistanceFormat.speedValue(primary.speedKmh)} km/h",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            primary is CockpitLiveActivity.CallActivity -> {
                val now by rememberTickingNowMs()
                val routeLabel = when (callAudio.output) {
                    CallAudioOutput.BLUETOOTH -> "Bluetooth"
                    CallAudioOutput.WIRED -> "Headset"
                    CallAudioOutput.SPEAKER -> "Speaker"
                    CallAudioOutput.EARPIECE -> "Earpiece"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    InfoStripMetric(
                        label = "Duration",
                        value = CockpitDurationFormat.clock(now - primary.startedAtMs),
                        tint = accent,
                    )
                    InfoStripMetric(
                        label = "Audio",
                        value = routeLabel,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            else -> {
                val connIcon = when (liveNet.kind) {
                    IdleNetworkKind.WIFI -> Icons.Outlined.Wifi
                    IdleNetworkKind.CELLULAR -> Icons.Outlined.SignalCellularAlt
                    IdleNetworkKind.OFFLINE -> Icons.Outlined.WifiOff
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
                    ) {
                        Icon(
                            imageVector = connIcon,
                            contentDescription = null,
                            tint = if (liveNet.kind == IdleNetworkKind.OFFLINE) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(Tokens.IconSmall),
                        )
                        Text(
                            text = liveNet.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                        modifier = Modifier.testTag("cockpit-expanded-net-speeds"),
                    ) {
                        SpeedFigure(
                            text = liveNet.uploadText,
                            up = true,
                            tint = CockpitLivePalette.Cyan,
                            compact = true,
                        )
                        SpeedFigure(
                            text = liveNet.downloadText,
                            up = false,
                            tint = CockpitLivePalette.Emerald,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoStripMetric(label: String, value: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = TABULAR_DIGITS,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickDeckSection(
    torchState: TorchUiState,
    quickControls: CockpitQuickControlsState,
    stackTiles: Boolean,
    reducedMotion: Boolean,
    onTapWifi: () -> Unit,
    onLongPressWifi: () -> Unit,
    onTapWifiLocationHint: () -> Unit,
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

    val torchEnabled = torchState.available
    val torchIsOn = torchState.isOn && torchEnabled
    val torchSubtitle = when {
        !torchEnabled -> "Cam Busy"
        torchIsOn -> "On"
        else -> "Off"
    }
    val torchClickLabel = when {
        !torchEnabled -> "Torch unavailable while camera is in use"
        torchIsOn -> "Turn torch off"
        else -> "Turn torch on"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cockpit-quick-deck"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        // Row 1: Wi-Fi (marquee SSID respecting reduced motion) + Mobile Data (monthly usage)
        if (stackTiles) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                QuickDeckTile(
                    icon = if (wifiActive) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    title = "Wi-Fi",
                    subtitle = quickControls.wifi.label,
                    active = wifiActive,
                    accent = wifiAccent,
                    marqueeSubtitle = true,
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
                    marqueeSubtitle = true,
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

        // One-tap hint when connected to Wi-Fi without location permission or location services.
        val locationHint = quickControls.wifi.locationHint
        if (quickControls.wifi.needsLocationHint && !locationHint.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Tokens.TouchTarget)
                    .clip(Tokens.Cockpit.QuickDeckShape)
                    .background(GlassPalette.Well)
                    .border(Tokens.Hairline, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), Tokens.Cockpit.QuickDeckShape)
                    .clickable(role = Role.Button, onClickLabel = locationHint, onClick = onTapWifiLocationHint)
                    .padding(horizontal = Tokens.Row, vertical = Tokens.Small)
                    .testTag("cockpit-wifi-location-hint"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                Text(
                    text = locationHint,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Row 2: Bluetooth + Sound sharing the exact same 2-column grid edges as Row 1.
        if (stackTiles) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
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
                QuickDeckTile(
                    icon = ringerIcon,
                    title = "Sound",
                    subtitle = quickControls.ringer.label,
                    active = true,
                    accent = ringerAccent,
                    onClick = onCycleRingerMode,
                    clickLabel = "Cycle ringer mode",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cockpit-tile-ringer"),
                )
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
            }
        }

        // Row 3: Full-width labelled Torch tile aligned with the exact outer grid edges (no lone odd square).
        QuickDeckTile(
            icon = if (torchIsOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
            title = "Torch",
            subtitle = torchSubtitle,
            active = torchIsOn,
            enabled = torchEnabled,
            accent = GlassPalette.PrimaryCyan,
            onClick = onToggleTorch,
            clickLabel = torchClickLabel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cockpit-torch-toggle"),
        )

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
    enabled: Boolean = true,
    marqueeSubtitle: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    isShimmerLoading: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val reducedMotion = LocalReducedMotion.current
    val fillBrush = when {
        !enabled -> SolidColor(GlassPalette.HudTileDisabled)
        active -> Brush.verticalGradient(
            listOf(
                accent.copy(alpha = 0.22f),
                GlassPalette.HudTileRecessed,
            ),
        )
        else -> Brush.verticalGradient(
            listOf(
                GlassPalette.Well,
                GlassPalette.HudTileRecessed,
            ),
        )
    }
    val borderColor = when {
        !enabled -> GlassPalette.HudTileDisabledBorder
        active -> accent.copy(alpha = 0.55f)
        else -> GlassPalette.WellEdge
    }

    val interactiveMod = if (onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
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
            enabled = enabled,
            role = Role.Button,
            onClickLabel = clickLabel,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    }

    val useMarquee = marqueeSubtitle && !reducedMotion
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = Tokens.Cockpit.QuickTileMinHeight)
            .clip(Tokens.Cockpit.QuickDeckShape)
            .background(fillBrush)
            .border(Tokens.Hairline, borderColor, Tokens.Cockpit.QuickDeckShape)
            .then(interactiveMod)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $subtitle"
            }
            .padding(horizontal = Tokens.Row, vertical = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                active -> accent
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
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
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        active -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (useMarquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (useMarquee) {
                                Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    velocity = Tokens.Cockpit.WifiSsidMarqueeVelocity,
                                )
                            } else {
                                Modifier
                            },
                        ),
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
