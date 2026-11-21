package com.drivelift.app.feature.cockpit.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.data.CockpitActivityCategory
import com.drivelift.app.feature.cockpit.data.CockpitPreferencesRepository
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.NextAlarmFormatter
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens

@Composable
fun CockpitSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cockpit = remember(context) { Graph.get(context).cockpit }
    val prefs by cockpit.prefsRepo.state.collectAsStateWithLifecycle()
    val telemetry by cockpit.telemetryAdapter.summary.collectAsStateWithLifecycle()
    val torchState by cockpit.torchController.state.collectAsStateWithLifecycle()

    var hasOverlay by remember { mutableStateOf(cockpit.hasOverlayPermission()) }
    var hasNotifications by remember { mutableStateOf(cockpit.hasNotificationAccess()) }
    var hasBatteryExemption by remember { mutableStateOf(cockpit.isIgnoringBatteryOptimizations()) }
    var alarmModel by remember { mutableStateOf(NextAlarmFormatter.readFromSystem(context)) }
    var freeStorageLabel by remember { mutableStateOf(CockpitGlanceWidget.readFreeStorageLabel()) }
    var batteryLabel by remember { mutableStateOf(CockpitGlanceWidget.readBatteryLabel(context)) }
    val launchableApps = remember(cockpit) { cockpit.pinnedAppsRepo.listLaunchableApps() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasOverlay = cockpit.hasOverlayPermission()
        hasNotifications = cockpit.hasNotificationAccess()
        hasBatteryExemption = cockpit.isIgnoringBatteryOptimizations()
        alarmModel = NextAlarmFormatter.readFromSystem(context)
        freeStorageLabel = CockpitGlanceWidget.readFreeStorageLabel()
        batteryLabel = CockpitGlanceWidget.readBatteryLabel(context)
    }

    val pinnedApps = remember(prefs.pinnedPackages, launchableApps) {
        cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages)
    }
    val nowMs = remember(context) { Graph.get(context).clock() }
    val clockTimeLabel = remember(nowMs) {
        java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US).format(java.util.Date(nowMs))
    }
    val clockDateLabel = remember(nowMs) {
        java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US).format(java.util.Date(nowMs))
    }

    CockpitSetupContent(
        prefs = prefs,
        telemetry = telemetry,
        alarm = alarmModel,
        clockTimeLabel = clockTimeLabel,
        clockDateLabel = clockDateLabel,
        freeStorageLabel = freeStorageLabel,
        batteryLabel = batteryLabel,
        torchOn = torchState.isOn,
        hasOverlay = hasOverlay,
        hasNotifications = hasNotifications,
        hasBatteryExemption = hasBatteryExemption,
        launchableApps = launchableApps,
        pinnedApps = pinnedApps,
        onBack = onBack,
        onToggleMaster = { enabled ->
            if (!cockpit.setDynamicIslandEnabled(enabled) && enabled) {
                openOverlayPermissionSettings(context)
            }
        },
        onToggleCategory = { cat, enabled ->
            cockpit.prefsRepo.setActivityEnabledAsync(cat, enabled)
        },
        onCalibrationChange = { dx, dy, dw ->
            cockpit.prefsRepo.setCutoutCalibrationAsync(dx, dy, dw)
        },
        onResetCalibration = {
            cockpit.prefsRepo.setCutoutCalibrationAsync(0, 0, 0)
        },
        onTogglePinnedApp = { pkg ->
            cockpit.prefsRepo.togglePinnedPackageAsync(pkg)
        },
        onGrantOverlay = { openOverlayPermissionSettings(context) },
        onGrantNotifications = { openNotificationAccessSettings(context) },
        onGrantBattery = { openBatteryExemptionSettings(context) },
        onToggleTorch = { cockpit.torchController.toggleTorch() },
        onToggleDrop = { toggleDropFromCockpit(context) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CockpitSetupContent(
    prefs: CockpitPrefsState,
    telemetry: DriveLiftTelemetrySummary,
    alarm: NextAlarmUiModel,
    clockTimeLabel: String,
    clockDateLabel: String,
    freeStorageLabel: String,
    batteryLabel: String,
    torchOn: Boolean,
    hasOverlay: Boolean,
    hasNotifications: Boolean,
    hasBatteryExemption: Boolean,
    launchableApps: List<PinnedAppInfo>,
    pinnedApps: List<PinnedAppInfo>,
    onBack: () -> Unit,
    onToggleMaster: (Boolean) -> Unit,
    onToggleCategory: (CockpitActivityCategory, Boolean) -> Unit,
    onCalibrationChange: (Int, Int, Int) -> Unit,
    onResetCalibration: () -> Unit,
    onTogglePinnedApp: (String) -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantNotifications: () -> Unit,
    onGrantBattery: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("cockpit-setup-screen")) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            title = { Text("Cockpit Hub") },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("cockpit-setup-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = Tokens.ReadableWidth)
                .testTag("cockpit-setup-list"),
            contentPadding = PaddingValues(
                start = Tokens.Edge,
                end = Tokens.Edge,
                bottom = Tokens.Section,
            ),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            item(key = "preview-header") {
                SectionLabel("Cutout Alignment Preview")
            }

            item(key = "pill-preview") {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CockpitTokens.PreviewBackdropHeight)
                            .padding(Tokens.Small),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        CollapsedSplitIslandRow(
                            slotState = CockpitSlotState(
                                primary = CockpitLiveActivity.TransferActivity(
                                    title = telemetry.connectedPeerName ?: "Desktop Drop",
                                    subtitle = telemetry.statusHeadline,
                                    speedText = if (telemetry.isDesktopDropRunning) "Live" else "Ready",
                                    progressFraction = null,
                                    isDesktopDrop = true,
                                    canStop = true,
                                ),
                                secondary = CockpitLiveActivity.TimerRecordingActivity(
                                    id = "preview_timer",
                                    label = "Timer",
                                    timeText = "04:12",
                                    isRecording = false,
                                    packageName = "com.google.android.deskclock",
                                ),
                            ),
                            widthDeltaDp = prefs.cutoutWidthDelta,
                            onTapPrimary = {},
                            onTapSecondary = {},
                            onLongPressExpand = {},
                            onCycleNext = {},
                            onCyclePrevious = {},
                            modifier = Modifier.offset(
                                x = prefs.cutoutOffsetX.dp,
                                y = prefs.cutoutOffsetY.dp,
                            ),
                        )
                    }
                }
            }

            item(key = "master-toggle") {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = Tokens.Gutter)) {
                        CockpitSwitchRow(
                            title = "System-wide Dynamic Island",
                            subtitle = if (hasOverlay) {
                                "Active over Home Screen and all apps around the camera cutout."
                            } else {
                                "Grant Display over other apps below before enabling."
                            },
                            checked = prefs.masterEnabled,
                            onCheckedChange = onToggleMaster,
                            tag = "cockpit-master-toggle",
                        )
                    }
                }
            }

            item(key = "permissions-header") {
                SectionLabel("Permissions and Reliability")
            }

            item(key = "permissions-panel") {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        PermissionRow(
                            title = "Display over other apps",
                            body = "Required to render the Dynamic Island overlay system-wide.",
                            granted = hasOverlay,
                            onGrant = onGrantOverlay,
                            tag = "cockpit-perm-overlay",
                        )
                        PermissionRow(
                            title = "Notification access",
                            body = "Reads WhatsApp alerts, Google Maps turns, timers, and recordings locally.",
                            granted = hasNotifications,
                            onGrant = onGrantNotifications,
                            tag = "cockpit-perm-notifications",
                        )
                        PermissionRow(
                            title = "Unrestricted battery",
                            body = "Keeps the cutout pill responsive across idle states.",
                            granted = hasBatteryExemption,
                            onGrant = onGrantBattery,
                            tag = "cockpit-perm-battery",
                        )
                    }
                }
            }

            item(key = "activities-header") {
                SectionLabel("Live Activity Sources")
            }

            item(key = "activities-panel") {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = Tokens.Gutter)) {
                        CockpitSwitchRow(
                            title = "DriveLift transfers and Desktop Drop",
                            subtitle = "Upload progress, peer status, and 1-tap stop.",
                            checked = prefs.transfersEnabled,
                            onCheckedChange = { onToggleCategory(CockpitActivityCategory.TRANSFERS_AND_DROP, it) },
                            tag = "cockpit-toggle-transfers",
                        )
                        CockpitSwitchRow(
                            title = "Timers, stopwatches, and recording",
                            subtitle = "Android Clock timers, voice memos, and screen recording.",
                            checked = prefs.timersEnabled,
                            onCheckedChange = { onToggleCategory(CockpitActivityCategory.TIMERS_AND_RECORDING, it) },
                            tag = "cockpit-toggle-timers",
                        )
                        CockpitSwitchRow(
                            title = "Google Maps turn-by-turn",
                            subtitle = "Next maneuver, distance, and ETA.",
                            checked = prefs.mapsEnabled,
                            onCheckedChange = { onToggleCategory(CockpitActivityCategory.MAPS_NAVIGATION, it) },
                            tag = "cockpit-toggle-maps",
                        )
                        CockpitSwitchRow(
                            title = "WhatsApp live alerts",
                            subtitle = "Message preview with inline quick reply.",
                            checked = prefs.whatsappEnabled,
                            onCheckedChange = { onToggleCategory(CockpitActivityCategory.WHATSAPP_ALERTS, it) },
                            tag = "cockpit-toggle-whatsapp",
                        )
                    }
                }
            }

            item(key = "calibration-header") {
                SectionLabel("Cutout Position and Size Calibration")
            }

            item(key = "calibration-panel") {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(Tokens.Gutter),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        CalibrationSlider(
                            label = "Horizontal offset",
                            valueText = "${prefs.cutoutOffsetX} dp",
                            value = prefs.cutoutOffsetX.toFloat(),
                            range = CockpitPreferencesRepository.MIN_OFFSET_X.toFloat()..
                                CockpitPreferencesRepository.MAX_OFFSET_X.toFloat(),
                            onValueChange = { onCalibrationChange(it.toInt(), prefs.cutoutOffsetY, prefs.cutoutWidthDelta) },
                            tag = "cockpit-cal-x",
                        )
                        CalibrationSlider(
                            label = "Vertical offset",
                            valueText = "${prefs.cutoutOffsetY} dp",
                            value = prefs.cutoutOffsetY.toFloat(),
                            range = CockpitPreferencesRepository.MIN_OFFSET_Y.toFloat()..
                                CockpitPreferencesRepository.MAX_OFFSET_Y.toFloat(),
                            onValueChange = { onCalibrationChange(prefs.cutoutOffsetX, it.toInt(), prefs.cutoutWidthDelta) },
                            tag = "cockpit-cal-y",
                        )
                        CalibrationSlider(
                            label = "Pill width",
                            valueText = "${prefs.cutoutWidthDelta} dp",
                            value = prefs.cutoutWidthDelta.toFloat(),
                            range = CockpitPreferencesRepository.MIN_WIDTH_DELTA.toFloat()..
                                CockpitPreferencesRepository.MAX_WIDTH_DELTA.toFloat(),
                            onValueChange = { onCalibrationChange(prefs.cutoutOffsetX, prefs.cutoutOffsetY, it.toInt()) },
                            tag = "cockpit-cal-width",
                        )
                        TextButton(
                            onClick = onResetCalibration,
                            modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                        ) {
                            Text("Reset calibration")
                        }
                    }
                }
            }

            item(key = "widget-preview-header") {
                SectionLabel("Home Screen Master Widget Preview")
            }

            item(key = "widget-preview") {
                CockpitMasterWidgetCard(
                    clockTimeLabel = clockTimeLabel,
                    clockDateLabel = clockDateLabel,
                    alarm = alarm,
                    telemetry = telemetry,
                    freeStorageLabel = freeStorageLabel,
                    batteryLabel = batteryLabel,
                    torchOn = torchOn,
                    islandOn = prefs.masterEnabled,
                    pinnedApps = pinnedApps,
                    onToggleDrop = onToggleDrop,
                    onToggleTorch = onToggleTorch,
                    onToggleIsland = { onToggleMaster(!prefs.masterEnabled) },
                )
            }

            item(key = "pinned-header") {
                SectionLabel("Pin Any App (${prefs.pinnedPackages.size}/${CockpitPreferencesRepository.MAX_PINNED_APPS})")
            }

            if (launchableApps.isEmpty()) {
                item(key = "pinned-empty") {
                    GlassPanel(Modifier.fillMaxWidth()) {
                        Text(
                            text = "No launcher apps available in this environment.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Tokens.Gutter),
                        )
                    }
                }
            } else {
                items(launchableApps, key = { it.packageName }) { app ->
                    val isPinned = app.packageName in prefs.pinnedPackages
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cockpit-app-row-${app.packageName}"),
                        onClick = { onTogglePinnedApp(app.packageName) },
                        onClickLabel = if (isPinned) "Unpin ${app.label}" else "Pin ${app.label}",
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = Tokens.TouchTarget)
                                .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Apps,
                                contentDescription = null,
                                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            FilterChip(
                                selected = isPinned,
                                onClick = { onTogglePinnedApp(app.packageName) },
                                label = { Text(if (isPinned) "Pinned" else "Pin") },
                                leadingIcon = if (isPinned) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall)) }
                                } else {
                                    { Icon(Icons.Outlined.Pin, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall)) }
                                },
                                modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared Compose representation of the All-in-One Home Screen Glass Master Widget, rendered both
 * inside the Cockpit Setup Hub preview and in Roborazzi UI screenshot tests across all variants.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CockpitMasterWidgetCard(
    clockTimeLabel: String,
    clockDateLabel: String,
    alarm: NextAlarmUiModel,
    telemetry: DriveLiftTelemetrySummary,
    freeStorageLabel: String,
    batteryLabel: String,
    torchOn: Boolean,
    islandOn: Boolean,
    pinnedApps: List<PinnedAppInfo>,
    onToggleDrop: () -> Unit = {},
    onToggleTorch: () -> Unit = {},
    onToggleIsland: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val stackTopRow = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP

    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cockpit-master-widget-card"),
        shape = CockpitTokens.WidgetCardShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            // Top Row: Live Clock + Next Alarm & Live Countdown
            if (stackTopRow) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    ClockHeroBlock(clockTimeLabel, clockDateLabel)
                    AlarmBadgeBlock(alarm)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ClockHeroBlock(clockTimeLabel, clockDateLabel)
                    AlarmBadgeBlock(alarm)
                }
            }

            // Middle Row: Live DriveLift & Device Telemetry Strip
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                WidgetTelemetryChip(
                    icon = Icons.Outlined.Devices,
                    label = telemetry.statusHeadline,
                    tint = if (telemetry.isDesktopDropRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                WidgetTelemetryChip(
                    icon = Icons.Outlined.Storage,
                    label = freeStorageLabel,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                WidgetTelemetryChip(
                    icon = Icons.Outlined.BatteryChargingFull,
                    label = batteryLabel,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Bottom Row: Quick Controls + Pinned Apps Dock
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                OutlinedButton(
                    onClick = onToggleDrop,
                    modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                ) {
                    Text(if (telemetry.isDesktopDropRunning) "Drop On" else "Drop")
                }
                OutlinedButton(
                    onClick = onToggleTorch,
                    modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                ) {
                    Icon(
                        imageVector = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                        contentDescription = null,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Spacer(Modifier.width(Tokens.Tiny))
                    Text(if (torchOn) "Torch On" else "Torch")
                }
                OutlinedButton(
                    onClick = onToggleIsland,
                    modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                ) {
                    Text(if (islandOn) "Island On" else "Island")
                }
                pinnedApps.take(3).forEach { app ->
                    WidgetTelemetryChip(
                        icon = Icons.Outlined.Apps,
                        label = app.label,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClockHeroBlock(timeLabel: String, dateLabel: String) {
    Column {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlarmBadgeBlock(alarm: NextAlarmUiModel) {
    Column(
        modifier = Modifier
            .clip(Tokens.ThumbShape)
            .background(GlassPalette.Well)
            .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.ThumbShape)
            .padding(horizontal = Tokens.Medium, vertical = Tokens.Small),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            Icon(
                imageVector = Icons.Outlined.Alarm,
                contentDescription = null,
                tint = if (alarm.hasAlarm) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.IconSmall),
            )
            Text(
                text = if (alarm.hasAlarm) "Alarm ${alarm.formattedTime}" else "No alarm set",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (alarm.hasAlarm) {
            Text(
                text = alarm.countdownText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun WidgetTelemetryChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
) {
    Row(
        modifier = Modifier
            .clip(Tokens.PillShape)
            .background(GlassPalette.Well)
            .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.PillShape)
            .padding(horizontal = Tokens.Medium, vertical = Tokens.Snug),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(Tokens.IconSmall))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Tokens.Small),
    )
}

@Composable
private fun CockpitSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Tokens.TouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Tokens.Row)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Tokens.Gutter)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    onGrant: () -> Unit,
    tag: String,
) {
    val stack = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP

    val badge: @Composable () -> Unit = {
        if (granted) {
            Row(
                modifier = Modifier
                    .clip(Tokens.PillShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = Tokens.Medium, vertical = Tokens.Snug),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        } else {
            Button(
                onClick = onGrant,
                modifier = Modifier
                    .defaultMinSize(minHeight = Tokens.TouchTarget)
                    .testTag(tag),
            ) {
                Text("Grant")
            }
        }
    }

    if (stack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Tiny),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            badge()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Tiny),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            badge()
        }
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    tag: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

private fun toggleDropFromCockpit(context: Context) {
    val controller = Graph.get(context).desktopDrop.controller
    val status = controller.snapshot.value.status
    if (status == ServerStatus.RUNNING || status == ServerStatus.STARTING) {
        controller.stopServer("Stopped from Cockpit")
    } else {
        controller.startServer()
    }
}

private fun openOverlayPermissionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openNotificationAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openBatteryExemptionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
}
