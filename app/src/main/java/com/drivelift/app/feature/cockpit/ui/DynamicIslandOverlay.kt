package com.drivelift.app.feature.cockpit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.UTurnLeft
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.cockpitSpecularRim
import com.drivelift.app.feature.cockpit.overlay.toWideGamutP3
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.Tokens

/**
 * System-wide HDR+ Tactile Glass Dynamic Island UI:
 * - Collapsed Single Pill or Simultaneous 2-Activity Split Pill (Primary Pill + Detached Bubble)
 * - Expanded HDR+ Glass Cockpit Card (Live Controls, WhatsApp Inline Reply, Hardware Torch Slider,
 *   Desktop Drop / Transfer Actions, and Pinned Apps Quick Dock)
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
) {
    val reducedMotion = LocalReducedMotion.current
    val expandSpec = remember(reducedMotion) {
        if (reducedMotion) snap<androidx.compose.ui.unit.IntSize>()
        else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    }

    AnimatedContent(
        targetState = slotState.isExpanded,
        modifier = modifier,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                (fadeIn() + expandVertically(animationSpec = expandSpec)) togetherWith
                    (fadeOut() + shrinkVertically(animationSpec = expandSpec))
            }
        },
        label = "dynamic_island_morph",
    ) { expanded ->
        if (expanded) {
            ExpandedCockpitCard(
                slotState = slotState,
                telemetry = telemetry,
                torchState = torchState,
                pinnedApps = pinnedApps,
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
                onToggleTorch = onToggleTorch,
                onTorchBrightnessChange = onTorchBrightnessChange,
                onToggleTorchSos = onToggleTorchSos,
                onLaunchPinnedApp = onLaunchPinnedApp,
            )
        } else {
            CollapsedSplitIslandRow(
                slotState = slotState,
                widthDeltaDp = widthDeltaDp,
                onTapPrimary = onTapPrimary,
                onTapSecondary = onTapSecondary,
                onLongPressExpand = onExpand,
                onCycleNext = onCycleNext,
                onCyclePrevious = onCyclePrevious,
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
) {
    val haptics = LocalHapticFeedback.current
    val primary = slotState.primary
    val secondary = slotState.secondary
    val (primaryAccent, secondaryAccent) = activityAccentPair(primary)
    val minPillWidth = (CockpitTokens.PillMinWidth + widthDeltaDp.dp).coerceAtLeast(CockpitTokens.PillMinWidth - Tokens.Header)
    val maxPillWidth = (CockpitTokens.PillMaxWidth + widthDeltaDp.dp).coerceAtLeast(minPillWidth)

    var horizontalDrag by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = modifier
            .testTag("cockpit-collapsed-row")
            .pointerInput(primary.id, slotState.visibleQueue.size) {
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> horizontalDrag += dragAmount },
                    onDragEnd = {
                        if (horizontalDrag <= -CockpitTokens.CYCLE_SWIPE_THRESHOLD_PX) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCycleNext()
                        } else if (horizontalDrag >= CockpitTokens.CYCLE_SWIPE_THRESHOLD_PX) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCyclePrevious()
                        }
                        horizontalDrag = 0f
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CockpitTokens.SplitGap),
    ) {
        // Primary Pill melting into the Pixel camera punch-hole with pure OLED #000000 core.
        Row(
            modifier = Modifier
                .widthIn(min = minPillWidth, max = maxPillWidth)
                .height(CockpitTokens.PillHeight)
                .clip(Tokens.PillShape)
                .background(MaterialTheme.colorScheme.scrim)
                .cockpitSpecularRim(
                    shape = Tokens.PillShape,
                    primaryAccent = primaryAccent,
                    secondaryAccent = secondaryAccent,
                    intensity = if (primary is CockpitLiveActivity.IdleQuickDockActivity) 0.55f else 1.0f,
                    animateOnActive = primary !is CockpitLiveActivity.IdleQuickDockActivity,
                )
                .combinedClickable(
                    onClickLabel = "Open ${activityTitle(primary)}",
                    onLongClickLabel = "Expand DriveLift Cockpit",
                    onClick = { onTapPrimary(primary) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPressExpand()
                    },
                )
                .padding(horizontal = Tokens.Medium)
                .testTag("cockpit-primary-pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
            ) {
                Icon(
                    imageVector = activityIcon(primary),
                    contentDescription = null,
                    tint = primaryAccent,
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
                color = primaryAccent,
                maxLines = 1,
            )
        }

        // Detached Secondary Circular Bubble for simultaneous 2-activity split mode.
        if (secondary != null) {
            val (bubblePrimary, bubbleSecondary) = activityAccentPair(secondary)
            Box(
                modifier = Modifier
                    .size(CockpitTokens.BubbleSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.scrim)
                    .cockpitSpecularRim(
                        shape = CircleShape,
                        primaryAccent = bubblePrimary,
                        secondaryAccent = bubbleSecondary,
                        intensity = 0.95f,
                        animateOnActive = true,
                    )
                    .combinedClickable(
                        onClickLabel = "Switch to ${activityTitle(secondary)}",
                        onLongClickLabel = "Expand DriveLift Cockpit",
                        onClick = { onTapSecondary(secondary) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPressExpand()
                        },
                    )
                    .testTag("cockpit-secondary-bubble"),
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
) {
    val haptics = LocalHapticFeedback.current
    val stackCompact = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
    val primary = slotState.primary
    val (accent, secondaryAccent) = activityAccentPair(primary)
    var verticalDrag by remember { mutableFloatStateOf(0f) }
    var horizontalDrag by remember { mutableFloatStateOf(0f) }
    var replyDraft by rememberSaveable(primary.id) { mutableStateOf("") }

    Column(
        modifier = modifier
            .widthIn(max = CockpitTokens.ExpandedMaxWidth)
            .fillMaxWidth()
            .clip(CockpitTokens.ExpandedCardShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.scrim.copy(alpha = 0.94f),
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                    ),
                ),
            )
            .cockpitSpecularRim(
                shape = CockpitTokens.ExpandedCardShape,
                primaryAccent = accent,
                secondaryAccent = secondaryAccent,
                intensity = 0.95f,
                animateOnActive = primary !is CockpitLiveActivity.IdleQuickDockActivity,
            )
            .pointerInput(primary.id) {
                detectVerticalDragGestures(
                    onDragStart = { verticalDrag = 0f },
                    onVerticalDrag = { _, delta -> verticalDrag += delta },
                    onDragEnd = {
                        if (verticalDrag <= CockpitTokens.EXPAND_SWIPE_COLLAPSE_PX) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCollapse()
                        }
                        verticalDrag = 0f
                    },
                )
            }
            .pointerInput(primary.id, slotState.visibleQueue.size) {
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDrag = 0f },
                    onHorizontalDrag = { _, delta -> horizontalDrag += delta },
                    onDragEnd = {
                        if (horizontalDrag <= -CockpitTokens.CYCLE_SWIPE_THRESHOLD_PX) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCycleNext()
                        } else if (horizontalDrag >= CockpitTokens.CYCLE_SWIPE_THRESHOLD_PX) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCyclePrevious()
                        }
                        horizontalDrag = 0f
                    },
                )
            }
            .padding(Tokens.Gutter)
            .testTag("cockpit-expanded-card"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
    ) {
        // Header row: active badge, title, optional queue switcher, hide & collapse controls.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Box(
                modifier = Modifier
                    .size(CockpitTokens.AppShortcutTile)
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
            if (primary !is CockpitLiveActivity.IdleQuickDockActivity) {
                IconButton(
                    onClick = { onSoftDismiss(primary.id) },
                    modifier = Modifier
                        .defaultMinSize(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                        .testTag("cockpit-soft-dismiss"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = "Hide activity from island",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = onCollapse,
                modifier = Modifier
                    .defaultMinSize(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                    .testTag("cockpit-collapse"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "Collapse Dynamic Island",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Queue switcher & soft-dismiss restore strip when multiple or hidden items exist.
        if (slotState.visibleQueue.size > 1 || slotState.dismissedCount > 0) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                if (slotState.visibleQueue.size > 1) {
                    OutlinedButton(
                        onClick = onCycleNext,
                        modifier = Modifier
                            .defaultMinSize(minHeight = Tokens.TouchTarget)
                            .testTag("cockpit-cycle-next"),
                    ) {
                        Text("Next activity (${slotState.visibleQueue.size})", maxLines = 1)
                    }
                }
                if (slotState.dismissedCount > 0) {
                    TextButton(
                        onClick = onRestoreDismissed,
                        modifier = Modifier
                            .defaultMinSize(minHeight = Tokens.TouchTarget)
                            .testTag("cockpit-restore-hidden"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(Tokens.IconSmall),
                        )
                        Spacer(Modifier.width(Tokens.Tiny))
                        Text("Restore (${slotState.dismissedCount})", maxLines = 1)
                    }
                }
            }
        }

        // Active Live Activity interactive body.
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
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    Button(
                        onClick = { onTapActivity(primary) },
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                    ) {
                        Text(if (primary.isDesktopDrop) "Open Drop" else "Open Transfers", maxLines = 1)
                    }
                    if (primary.canStop) {
                        OutlinedButton(
                            onClick = onStopDesktopDrop,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = Tokens.TouchTarget)
                                .testTag("cockpit-stop-drop"),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall))
                            Spacer(Modifier.width(Tokens.Tiny))
                            Text("Stop", maxLines = 1)
                        }
                    }
                }
            }

            is CockpitLiveActivity.WhatsAppAlertActivity -> {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    Button(
                        onClick = { onTapActivity(primary) },
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                    ) {
                        Text("Open Chat", maxLines = 1)
                    }
                    if (primary.canInlineReply) {
                        OutlinedButton(
                            onClick = { onToggleInlineReply(!slotState.inlineReplyOpen) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = Tokens.TouchTarget)
                                .testTag("cockpit-reply-toggle"),
                        ) {
                            Icon(Icons.Outlined.Sms, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall))
                            Spacer(Modifier.width(Tokens.Tiny))
                            Text(if (slotState.inlineReplyOpen) "Hide Reply" else "Reply", maxLines = 1)
                        }
                    }
                }
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

            is CockpitLiveActivity.MapsNavActivity -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (primary.distanceText.isNotEmpty()) {
                        StatusChip(label = primary.distanceText, accent = accent)
                    }
                    if (primary.etaText.isNotEmpty()) {
                        StatusChip(label = primary.etaText, accent = secondaryAccent)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onTapActivity(primary) },
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                    ) {
                        Text("Maps")
                    }
                }
            }

            is CockpitLiveActivity.TimerRecordingActivity -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatusChip(label = primary.timeText, accent = accent)
                    Button(
                        onClick = { onTapActivity(primary) },
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                    ) {
                        Text("Open")
                    }
                }
            }

            is CockpitLiveActivity.IdleQuickDockActivity -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatusChip(label = telemetry.statusHeadline, accent = accent)
                    OutlinedButton(
                        onClick = onOpenDriveLiftCockpit,
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                    ) {
                        Text("Cockpit Hub")
                        Spacer(Modifier.width(Tokens.Tiny))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(Tokens.IconSmall),
                        )
                    }
                }
            }
        }

        // Hardware Flashlight Strip (ON/OFF + API 33+ Brightness Slider + SOS strobe).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Tokens.PanelShape)
                .background(GlassPalette.Well)
                .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.PanelShape)
                .padding(horizontal = Tokens.Medium, vertical = Tokens.Small),
            verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            val torchLabelBlock: @Composable (Modifier) -> Unit = { colMod ->
                Column(colMod) {
                    Text(
                        text = if (torchState.sosActive) "Flashlight SOS" else if (torchState.isOn) "Flashlight on" else "Flashlight off",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = if (torchState.supportsBrightnessSlider) {
                            "Level ${torchState.brightnessLevel} of ${torchState.maxLevel}"
                        } else if (torchState.available) {
                            "Standard hardware toggle"
                        } else {
                            "No flash hardware detected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val sosButton: @Composable () -> Unit = {
                OutlinedButton(
                    onClick = onToggleTorchSos,
                    enabled = torchState.available,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                        .testTag("cockpit-torch-sos"),
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall))
                    Spacer(Modifier.width(Tokens.Tiny))
                    Text(if (torchState.sosActive) "SOS On" else "SOS", maxLines = 1)
                }
            }
            if (stackCompact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    IconButton(
                        onClick = onToggleTorch,
                        modifier = Modifier
                            .defaultMinSize(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                            .testTag("cockpit-torch-toggle"),
                    ) {
                        Icon(
                            imageVector = if (torchState.isOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                            contentDescription = if (torchState.isOn) "Turn flashlight off" else "Turn flashlight on",
                            tint = if (torchState.isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    torchLabelBlock(Modifier.weight(1f))
                }
                sosButton()
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    IconButton(
                        onClick = onToggleTorch,
                        modifier = Modifier
                            .defaultMinSize(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
                            .testTag("cockpit-torch-toggle"),
                    ) {
                        Icon(
                            imageVector = if (torchState.isOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                            contentDescription = if (torchState.isOn) "Turn flashlight off" else "Turn flashlight on",
                            tint = if (torchState.isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    torchLabelBlock(Modifier.weight(1f))
                    sosButton()
                }
            }
            if (torchState.supportsBrightnessSlider && torchState.maxLevel > 1) {
                Slider(
                    value = torchState.brightnessLevel.toFloat(),
                    onValueChange = { onTorchBrightnessChange(it.toInt().coerceIn(1, torchState.maxLevel)) },
                    valueRange = 1f..torchState.maxLevel.toFloat(),
                    steps = (torchState.maxLevel - 2).coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cockpit-torch-slider"),
                )
            }
        }

        // Pinned Apps Quick Dock.
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
                                Icon(
                                    imageVector = Icons.Outlined.Apps,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(Tokens.IconSmall),
                                )
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
        is CockpitLiveActivity.MapsNavActivity ->
            scheme.tertiary.toWideGamutP3() to scheme.secondary.toWideGamutP3()
        is CockpitLiveActivity.WhatsAppAlertActivity ->
            scheme.primary.toWideGamutP3() to scheme.secondary.toWideGamutP3()
        is CockpitLiveActivity.IdleQuickDockActivity ->
            scheme.secondary.toWideGamutP3() to scheme.tertiary.toWideGamutP3()
    }
}

private fun activityIcon(activity: CockpitLiveActivity): ImageVector = when (activity) {
    is CockpitLiveActivity.TransferActivity ->
        if (activity.isDesktopDrop) Icons.Outlined.Devices else Icons.Outlined.CloudUpload
    is CockpitLiveActivity.TimerRecordingActivity ->
        if (activity.isRecording) Icons.Outlined.Mic else Icons.Outlined.Timer
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
    is CockpitLiveActivity.TransferActivity -> activity.title
    is CockpitLiveActivity.TimerRecordingActivity -> activity.label
    is CockpitLiveActivity.MapsNavActivity -> activity.maneuverText
    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.senderName
    is CockpitLiveActivity.IdleQuickDockActivity -> "DriveLift Cockpit"
}

private fun activitySubtitle(
    activity: CockpitLiveActivity,
    telemetry: DriveLiftTelemetrySummary,
): String = when (activity) {
    is CockpitLiveActivity.TransferActivity -> "${activity.subtitle} (${activity.speedText})"
    is CockpitLiveActivity.TimerRecordingActivity ->
        if (activity.isRecording) "Recording active" else "Timer running"
    is CockpitLiveActivity.MapsNavActivity ->
        listOf(activity.distanceText, activity.etaText).filter { it.isNotBlank() }.joinToString(" | ")
            .ifEmpty { "Navigation active" }
    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.messageSnippet
    is CockpitLiveActivity.IdleQuickDockActivity -> "DriveLift: ${telemetry.statusHeadline}"
}

private fun activityCollapsedLabel(activity: CockpitLiveActivity): String = when (activity) {
    is CockpitLiveActivity.TransferActivity -> activity.title
    is CockpitLiveActivity.TimerRecordingActivity -> activity.label
    is CockpitLiveActivity.MapsNavActivity -> activity.maneuverText
    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.senderName
    is CockpitLiveActivity.IdleQuickDockActivity -> "Cockpit"
}

private fun activityCollapsedMetric(activity: CockpitLiveActivity): String = when (activity) {
    is CockpitLiveActivity.TransferActivity -> activity.speedText
    is CockpitLiveActivity.TimerRecordingActivity -> activity.timeText
    is CockpitLiveActivity.MapsNavActivity -> activity.distanceText.ifEmpty { activity.etaText }
    is CockpitLiveActivity.WhatsAppAlertActivity -> if (activity.canInlineReply) "Reply" else "Msg"
    is CockpitLiveActivity.IdleQuickDockActivity -> "Ready"
}
