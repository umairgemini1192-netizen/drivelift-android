package com.drivelift.app.feature.cockpit.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.data.CockpitActivityCategory
import com.drivelift.app.feature.cockpit.data.CockpitPreferencesRepository
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsState
import com.drivelift.app.feature.cockpit.hardware.NextAlarmFormatter
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.notifications.CallAudioOutput
import com.drivelift.app.feature.cockpit.notifications.CallAudioUiState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.AccessibilityHealth
import com.drivelift.app.feature.cockpit.overlay.CockpitAccessibilityService
import com.drivelift.app.feature.cockpit.widget.BatteryCurrentReading
import com.drivelift.app.feature.cockpit.widget.BatteryCurrentTone
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.feature.cockpit.widget.RamHudMetric
import com.drivelift.app.feature.cockpit.widget.WidgetStatusTone
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.delay

@Composable
fun CockpitSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val graph = remember(context) { Graph.get(context) }
    val cockpit = remember(graph) { graph.cockpit }
    val prefs by cockpit.prefsRepo.state.collectAsStateWithLifecycle()
    val telemetry by cockpit.telemetryAdapter.summary.collectAsStateWithLifecycle()
    val torchState by cockpit.torchController.state.collectAsStateWithLifecycle()
    val quickControls by cockpit.quickControlsController.state.collectAsStateWithLifecycle()
    val hudSnapshot by cockpit.telemetryMonitor.snapshot.collectAsStateWithLifecycle()
    val dropSnapshot by graph.desktopDrop.controller.snapshot.collectAsStateWithLifecycle()

    var hasAccessibilityOverlay by remember { mutableStateOf(cockpit.hasAccessibilityOverlay()) }
    var accessibilityHealth by remember { mutableStateOf(CockpitAccessibilityService.health(context)) }
    var hasOverlay by remember { mutableStateOf(cockpit.hasOverlayPermission()) }
    var hasNotifications by remember { mutableStateOf(cockpit.hasNotificationAccess()) }
    var hasBatteryExemption by remember { mutableStateOf(cockpit.isIgnoringBatteryOptimizations()) }
    var hasLocationPermission by remember {
        mutableStateOf(CockpitQuickControlsController.hasLocationPermission(context))
    }
    var hasUsageAccess by remember {
        mutableStateOf(CockpitQuickControlsController.hasUsageStatsPermission(context))
    }
    var hasBluetoothPermission by remember {
        mutableStateOf(CockpitQuickControlsController.hasBluetoothConnectPermission(context))
    }
    var hasDndAccess by remember {
        mutableStateOf(CockpitQuickControlsController.hasDndPolicyAccess(context))
    }
    var nowMs by remember(context) { mutableLongStateOf(graph.clock()) }
    var alarmModel by remember { mutableStateOf(NextAlarmFormatter.readFromSystem(context, nowMillis = nowMs)) }
    var freeStorageLabel by remember { mutableStateOf(CockpitGlanceWidget.readFreeStorageLabel()) }
    var warmBatteryMetric by remember { mutableStateOf(CockpitGlanceWidget.readBatteryMetric(context)) }
    var warmRamMetric by remember { mutableStateOf(CockpitGlanceWidget.readRamMetric(context)) }
    val launchableApps = remember(cockpit) { cockpit.pinnedAppsRepo.listLaunchableApps() }

    val locationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasLocationPermission = granted || CockpitQuickControlsController.hasLocationPermission(context)
        cockpit.quickControlsController.refreshAll()
        if (!hasLocationPermission) {
            openAppDetailsSettings(context)
        }
    }

    val bluetoothPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasBluetoothPermission = granted || CockpitQuickControlsController.hasBluetoothConnectPermission(context)
        cockpit.quickControlsController.refreshAll()
        if (!hasBluetoothPermission) {
            openAppDetailsSettings(context)
        }
    }

    DisposableEffect(cockpit) {
        cockpit.quickControlsController.start()
        cockpit.telemetryMonitor.acquire()
        onDispose {
            cockpit.telemetryMonitor.release()
            cockpit.quickControlsController.stop()
        }
    }

    LaunchedEffect(context) {
        CockpitGlanceWidget.registerGeneratedPreviewsIfSupported(context)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val current = graph.clock()
        nowMs = current
        hasAccessibilityOverlay = cockpit.hasAccessibilityOverlay()
        accessibilityHealth = CockpitAccessibilityService.health(context)
        hasOverlay = cockpit.hasOverlayPermission()
        hasNotifications = cockpit.hasNotificationAccess()
        hasBatteryExemption = cockpit.isIgnoringBatteryOptimizations()
        hasLocationPermission = CockpitQuickControlsController.hasLocationPermission(context)
        hasUsageAccess = CockpitQuickControlsController.hasUsageStatsPermission(context)
        hasBluetoothPermission = CockpitQuickControlsController.hasBluetoothConnectPermission(context)
        hasDndAccess = CockpitQuickControlsController.hasDndPolicyAccess(context)
        cockpit.quickControlsController.refreshAll()
        cockpit.telemetryMonitor.evaluateNow(pushWidget = false)
        alarmModel = NextAlarmFormatter.readFromSystem(context, nowMillis = current)
        freeStorageLabel = CockpitGlanceWidget.readFreeStorageLabel()
        warmBatteryMetric = CockpitGlanceWidget.readBatteryMetric(context)
        warmRamMetric = CockpitGlanceWidget.readRamMetric(context)
    }

    LaunchedEffect(lifecycleOwner, context) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = graph.clock()
                nowMs = current
                alarmModel = NextAlarmFormatter.readFromSystem(context, nowMillis = current)
                // The service can connect or be suspended while this screen is open.
                accessibilityHealth = CockpitAccessibilityService.health(context)
                delay(1_000L)
            }
        }
    }

    val effectiveBattery = if (!hudSnapshot.isLoadingInitial && hudSnapshot.battery.valueText != "--%") {
        hudSnapshot.battery
    } else {
        warmBatteryMetric
    }
    val effectiveRam = if (!hudSnapshot.isLoadingInitial && hudSnapshot.ram.valueText != "--") {
        hudSnapshot.ram
    } else {
        warmRamMetric
    }
    val dropAvailable = CockpitGlanceWidget.isDropToggleAvailable(
        context = context,
        telemetry = telemetry,
        mode = dropSnapshot.mode,
    )

    val pinnedApps = remember(prefs.pinnedPackages, launchableApps) {
        cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages)
    }
    val clockTimeLabel = remember(nowMs) {
        java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US).format(java.util.Date(nowMs))
    }
    val clockDateLabel = remember(nowMs) {
        java.text.SimpleDateFormat("EEE \u00B7 MMM d", java.util.Locale.US).format(java.util.Date(nowMs))
    }

    CockpitSetupContent(
        prefs = prefs,
        telemetry = telemetry,
        alarm = alarmModel,
        clockTimeLabel = clockTimeLabel,
        clockDateLabel = clockDateLabel,
        freeStorageLabel = freeStorageLabel,
        batteryLabel = if (effectiveBattery.valueText == "--%") "Batt --" else "${effectiveBattery.valueText} batt",
        batteryMetric = effectiveBattery,
        ramMetric = effectiveRam,
        torchOn = torchState.isOn,
        torchState = torchState,
        torchAvailable = torchState.available,
        dropAvailable = dropAvailable,
        quickControls = quickControls,
        isInitialTelemetryLoading = false,
        hasAccessibilityOverlay = hasAccessibilityOverlay,
        accessibilityHealth = accessibilityHealth,
        hasOverlay = hasOverlay,
        hasNotifications = hasNotifications,
        hasBatteryExemption = hasBatteryExemption,
        hasLocationPermission = hasLocationPermission,
        hasUsageAccess = hasUsageAccess,
        hasBluetoothPermission = hasBluetoothPermission,
        hasDndAccess = hasDndAccess,
        launchableApps = launchableApps,
        pinnedApps = pinnedApps,
        onBack = onBack,
        onToggleMaster = { enabled ->
            if (!cockpit.setDynamicIslandEnabled(enabled) && enabled) {
                openAccessibilityOverlaySettings(context)
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
        onGrantAccessibilityOverlay = { openAccessibilityOverlaySettings(context) },
        onGrantOverlay = { openOverlayPermissionSettings(context) },
        onGrantNotifications = { openNotificationAccessSettings(context) },
        onGrantBattery = { openBatteryExemptionSettings(context) },
        onGrantLocation = { locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
        onGrantUsageAccess = { cockpit.quickControlsController.openUsageAccessSettings() },
        onGrantBluetooth = {
            if (Build.VERSION.SDK_INT >= 31) {
                bluetoothPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                cockpit.quickControlsController.openBluetoothSettings()
            }
        },
        onGrantDndAccess = { cockpit.quickControlsController.openDndPolicySettings() },
        onToggleTorch = { cockpit.torchController.toggleTorch() },
        onTorchBrightnessChange = { lvl -> cockpit.torchController.setBrightnessLevel(lvl) },
        onToggleTorchSos = { cockpit.torchController.toggleSos() },
        onToggleDrop = { toggleDropFromCockpit(context) },
        onTapWifi = { cockpit.quickControlsController.openInternetPanel() },
        onLongPressWifi = { cockpit.quickControlsController.openWifiSettings() },
        onTapMobileData = { cockpit.quickControlsController.onMobileDataTap() },
        onLongPressMobileData = { cockpit.quickControlsController.openDataUsageSettings() },
        onTapBluetooth = { cockpit.quickControlsController.openBluetoothSettings() },
        onCycleRingerMode = { cockpit.quickControlsController.cycleRingerMode() },
        onTapAlarm = { CockpitGlanceWidget.launchGoogleClockAlarms(context) },
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
    hasAccessibilityOverlay: Boolean = false,
    onGrantAccessibilityOverlay: () -> Unit = {},
    accessibilityHealth: AccessibilityHealth =
        if (hasAccessibilityOverlay) AccessibilityHealth.CONNECTED else AccessibilityHealth.NOT_GRANTED,
    hasLocationPermission: Boolean = false,
    hasUsageAccess: Boolean = false,
    hasBluetoothPermission: Boolean = false,
    hasDndAccess: Boolean = false,
    onGrantLocation: () -> Unit = {},
    onGrantUsageAccess: () -> Unit = {},
    onGrantBluetooth: () -> Unit = {},
    onGrantDndAccess: () -> Unit = {},
    batteryMetric: BatteryHudMetric = BatteryHudMetric(84, 0.84f, "84%"),
    ramMetric: RamHudMetric = CockpitHudBitmapRenderer.formatRamHudMetric(
        availBytes = (1.5 * 1024 * 1024 * 1024).toLong(),
        totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
    ),
    torchState: TorchUiState = TorchUiState(
        available = true,
        isOn = torchOn,
        brightnessLevel = 3,
        maxLevel = 5,
        supportsBrightnessSlider = false,
    ),
    torchAvailable: Boolean = torchState.available,
    dropAvailable: Boolean = telemetry.isDropAvailable,
    quickControls: CockpitQuickControlsState = CockpitQuickControlsState(),
    isInitialTelemetryLoading: Boolean = false,
    onTorchBrightnessChange: (Int) -> Unit = {},
    onToggleTorchSos: () -> Unit = {},
    onTapWifi: () -> Unit = {},
    onLongPressWifi: () -> Unit = {},
    onTapMobileData: () -> Unit = {},
    onLongPressMobileData: () -> Unit = {},
    onTapBluetooth: () -> Unit = {},
    onCycleRingerMode: () -> Unit = {},
    onTapAlarm: () -> Unit = {},
) {
    var previewExpanded by rememberSaveable { mutableStateOf(false) }
    var previewScenario by rememberSaveable { mutableStateOf(CockpitPreviewScenario.DEFAULT) }
    var previewIndex by rememberSaveable { mutableStateOf(0) }
    var previewDirection by remember { mutableStateOf(0) }
    var previewAudio by remember { mutableStateOf(CallAudioUiState()) }
    val selectPreviewScenario: (CockpitPreviewScenario) -> Unit = { target ->
        previewScenario = target
        previewIndex = 0
        previewDirection = 0
        previewAudio = CallAudioUiState()
        // A ringing call opens the island on its own, like a real one does.
        previewExpanded = target == CockpitPreviewScenario.INCOMING_CALL
    }

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
                val previewPrimary = remember(telemetry) {
                    CockpitLiveActivity.TransferActivity(
                        title = telemetry.connectedPeerName ?: "Desktop Drop",
                        subtitle = telemetry.statusHeadline,
                        speedText = if (telemetry.isDesktopDropRunning) "Live" else "Ready",
                        progressFraction = null,
                        isDesktopDrop = true,
                        canStop = true,
                    )
                }
                val previewSecondary = remember {
                    CockpitLiveActivity.TimerRecordingActivity(
                        id = "preview_timer",
                        label = "Timer",
                        timeText = "04:12",
                        isRecording = false,
                        packageName = "com.google.android.deskclock",
                    )
                }
                val previewStartMs = remember(previewScenario) { System.currentTimeMillis() }
                val previewQueue = remember(previewScenario, previewStartMs, previewPrimary, previewSecondary) {
                    CockpitPreviewActivities.queue(
                        scenario = previewScenario,
                        nowMs = previewStartMs,
                        fallback = listOf(previewPrimary, previewSecondary),
                    )
                }
                val previewActive = previewIndex.coerceIn(0, previewQueue.lastIndex)
                val previewActions = remember {
                    CockpitActivityActions(
                        onAnswerCall = { selectPreviewScenario(CockpitPreviewScenario.ONGOING_CALL) },
                        onDeclineCall = { selectPreviewScenario(CockpitPreviewScenario.DEFAULT) },
                        onHangUpCall = { selectPreviewScenario(CockpitPreviewScenario.DEFAULT) },
                        onToggleMute = { previewAudio = previewAudio.copy(isMuted = !previewAudio.isMuted) },
                        onCycleAudioOutput = {
                            val outputs = CallAudioOutput.entries
                            previewAudio = previewAudio.copy(
                                output = outputs[(previewAudio.output.ordinal + 1) % outputs.size],
                            )
                        },
                        onOpenCall = { previewExpanded = false },
                        onStopRecording = { selectPreviewScenario(CockpitPreviewScenario.DEFAULT) },
                        onOpenNavigation = { previewExpanded = false },
                    )
                }
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = if (previewExpanded) {
                        { previewExpanded = false }
                    } else {
                        null
                    },
                    onClickLabel = "Collapse preview",
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = CockpitTokens.PreviewBackdropHeight)
                            .animateContentSize()
                            .padding(Tokens.Small),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        DynamicIslandOverlay(
                            slotState = CockpitSlotState(
                                primary = previewQueue[previewActive],
                                secondary = if (previewQueue.size > 1) {
                                    previewQueue[(previewActive + 1) % previewQueue.size]
                                } else {
                                    null
                                },
                                visibleQueue = previewQueue,
                                isExpanded = previewExpanded,
                                activeIndex = previewActive,
                                swapDirection = previewDirection,
                            ),
                            telemetry = telemetry,
                            torchState = torchState.copy(isOn = torchOn, available = torchAvailable),
                            quickControls = quickControls,
                            pinnedApps = pinnedApps,
                            widthDeltaDp = prefs.cutoutWidthDelta,
                            onTapPrimary = { previewExpanded = false },
                            onTapSecondary = {
                                previewDirection = 1
                                previewIndex = (previewActive + 1) % previewQueue.size
                            },
                            onExpand = { previewExpanded = true },
                            onCollapse = { previewExpanded = false },
                            onCycleNext = {
                                previewDirection = 1
                                previewIndex = (previewActive + 1) % previewQueue.size
                            },
                            onCyclePrevious = {
                                previewDirection = -1
                                previewIndex = (previewActive - 1 + previewQueue.size) % previewQueue.size
                            },
                            onSoftDismiss = { previewExpanded = false },
                            onRestoreDismissed = {},
                            onStopDesktopDrop = onToggleDrop,
                            onOpenDriveLiftCockpit = { previewExpanded = false },
                            onToggleInlineReply = {},
                            onSendWhatsAppReply = { _, _ -> },
                            onTapWifi = onTapWifi,
                            onLongPressWifi = onLongPressWifi,
                            onTapMobileData = onTapMobileData,
                            onLongPressMobileData = onLongPressMobileData,
                            onTapBluetooth = onTapBluetooth,
                            onCycleRingerMode = onCycleRingerMode,
                            onToggleTorch = onToggleTorch,
                            onTorchBrightnessChange = onTorchBrightnessChange,
                            onToggleTorchSos = onToggleTorchSos,
                            onLaunchPinnedApp = { previewExpanded = false },
                            callAudio = previewAudio,
                            activityActions = previewActions,
                            modifier = Modifier.offset(
                                x = prefs.cutoutOffsetX.dp,
                                y = prefs.cutoutOffsetY.dp,
                            ),
                        )
                    }
                }
            }

            item(key = "preview-scenarios") {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Text(
                        text = "Simulate a live activity",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CockpitPreviewScenarioChips(
                        selected = previewScenario,
                        onSelect = selectPreviewScenario,
                    )
                }
            }

            item(key = "master-toggle") {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = Tokens.Gutter)) {
                        CockpitSwitchRow(
                            title = "System-wide Dynamic Island",
                            subtitle = when {
                                accessibilityHealth == AccessibilityHealth.CONNECTED -> "Active via Accessibility overlay above the camera cutout with full tap and long-press support."
                                hasOverlay -> "Fallback overlay mode: enable Accessibility overlay below so top cutout taps clear Pixel Status Bar."
                                else -> "Grant Accessibility overlay or Display over other apps below before enabling."
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
                            title = "Accessibility overlay (for cutout taps)",
                            body = "Mounts the pill via TYPE_ACCESSIBILITY_OVERLAY above the status bar for reliable cutout taps. Reads zero screen content.",
                            granted = accessibilityHealth == AccessibilityHealth.CONNECTED,
                            onGrant = onGrantAccessibilityOverlay,
                            tag = "cockpit-perm-accessibility",
                            needsReenable = accessibilityHealth == AccessibilityHealth.NEEDS_REENABLE,
                        )
                        PermissionRow(
                            title = "Display over other apps",
                            body = "Fallback system overlay permission when Accessibility overlay is off.",
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
                        PermissionRow(
                            title = "Location (Wi-Fi SSID)",
                            body = "Optional permission to show live Wi-Fi network names inside the expanded Island.",
                            granted = hasLocationPermission,
                            onGrant = onGrantLocation,
                            tag = "cockpit-perm-location",
                        )
                        PermissionRow(
                            title = "Usage Access (Monthly Data)",
                            body = "Optional access to display this month's cellular consumption on the Mobile Data tile.",
                            granted = hasUsageAccess,
                            onGrant = onGrantUsageAccess,
                            tag = "cockpit-perm-usage",
                        )
                        PermissionRow(
                            title = "Nearby Devices / Bluetooth",
                            body = "Optional permission to show live connected Bluetooth device count.",
                            granted = hasBluetoothPermission,
                            onGrant = onGrantBluetooth,
                            tag = "cockpit-perm-bluetooth",
                        )
                        PermissionRow(
                            title = "Do Not Disturb Access (Silent Ringer)",
                            body = "Optional policy access allowing direct Silent mode switching from the Island.",
                            granted = hasDndAccess,
                            onGrant = onGrantDndAccess,
                            tag = "cockpit-perm-dnd",
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
                    onTapAlarm = onTapAlarm,
                    torchAvailable = torchAvailable,
                    dropAvailable = dropAvailable,
                    isInitialTelemetryLoading = isInitialTelemetryLoading,
                    batteryMetric = batteryMetric,
                    ramMetric = ramMetric,
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
 * 1:1 in-app Compose parity view of the 4-Zone Cyber-Glass Bento HUD Master Widget, powered by the
 * exact same [CockpitHudBitmapRenderer] bitmaps used by [CockpitGlanceWidget] over an atmospheric
 * wallpaper backdrop, rendered both inside Cockpit Hub and in Roborazzi screenshot tests.
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
    batteryMetric: BatteryHudMetric = BatteryHudMetric(84, 0.84f, "84%"),
    ramMetric: RamHudMetric = CockpitHudBitmapRenderer.formatRamHudMetric(
        availBytes = (1.5 * 1024 * 1024 * 1024).toLong(),
        totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
    ),
    torchAvailable: Boolean = true,
    dropAvailable: Boolean = telemetry.isDropAvailable,
    isInitialTelemetryLoading: Boolean = false,
    onTapAlarm: () -> Unit = {},
    wallpaperGradientColors: List<Color> = listOf(GlassPalette.SkyTop, GlassPalette.SkyMid, GlassPalette.SkyBottom),
) {
    val density = LocalDensity.current.density.coerceAtLeast(2f)
    val reducedMotion = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val stackTopRow = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP

    val chargingPulseAlpha = if (batteryMetric.isCharging && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "hud_charging_pulse")
        val value by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = CockpitTokens.SHIMMER_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "charging_halo_alpha",
        )
        value
    } else {
        1f
    }

    val shellBitmap = remember(density) {
        CockpitHudBitmapRenderer.renderShellBitmap(
            widthPx = (340f * density).toInt(),
            heightPx = (220f * density).toInt(),
            cornerRadiusDp = 26f,
            density = density,
        ).asImageBitmap()
    }
    val statusPill = CockpitWidgetModel.statusPill(
        dropLive = telemetry.isDesktopDropRunning,
        torchOn = torchOn && torchAvailable,
    )
    val statusAccent = when (statusPill.tone) {
        WidgetStatusTone.READY -> GlassPalette.HudEmerald
        WidgetStatusTone.DROP_LIVE -> GlassPalette.HudElectricCyan
        WidgetStatusTone.TORCH_ON -> GlassPalette.HudAmber
    }
    val dropTile = CockpitWidgetModel.dropTile(
        dropLive = telemetry.isDesktopDropRunning,
        available = dropAvailable,
    )
    val statusPillBitmap = remember(statusPill.tone, density) {
        CockpitHudBitmapRenderer.renderStatusPillBitmap(
            widthPx = (140f * density).toInt(),
            heightPx = (28f * density).toInt(),
            active = statusPill.tone != WidgetStatusTone.READY,
            accentColor = statusAccent,
            density = density,
        ).asImageBitmap()
    }
    var pinnedPage by remember { mutableIntStateOf(0) }
    val pinnedPageCount = CockpitWidgetModel.pageCount(pinnedApps.size)
    val currentPinnedPage = CockpitWidgetModel.wrapPage(pinnedPage, pinnedPageCount)
    val pagedApps = CockpitWidgetModel.pageSlice(pinnedApps, currentPinnedPage)
    val batteryGaugeBitmap = remember(batteryMetric, density) {
        CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = (58f * density).toInt(),
            fraction = batteryMetric.fraction,
            valueText = batteryMetric.valueText,
            subLabel = batteryMetric.label,
            accentColor = CockpitHudBitmapRenderer.batteryArcColor(batteryMetric.percent, batteryMetric.isCharging),
            density = density,
            isCharging = batteryMetric.isCharging,
        ).asImageBitmap()
    }
    val ramGaugeBitmap = remember(ramMetric, density) {
        CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = (58f * density).toInt(),
            fraction = ramMetric.fraction,
            valueText = ramMetric.valueText,
            subLabel = ramMetric.label,
            accentColor = GlassPalette.HudElectricCyan,
            density = density,
        ).asImageBitmap()
    }
    val dropTileBitmap = remember(telemetry.isDesktopDropRunning, dropAvailable, density) {
        CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = (104f * density).toInt(),
            heightPx = (50f * density).toInt(),
            active = telemetry.isDesktopDropRunning,
            accentColor = GlassPalette.HudCoral,
            density = density,
            enabled = dropAvailable,
        ).asImageBitmap()
    }
    val torchTileBitmap = remember(torchOn, torchAvailable, density) {
        CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = (104f * density).toInt(),
            heightPx = (50f * density).toInt(),
            active = torchOn && torchAvailable,
            accentColor = GlassPalette.HudAmber,
            density = density,
            enabled = torchAvailable,
        ).asImageBitmap()
    }
    val islandTileBitmap = remember(islandOn, density) {
        CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = (104f * density).toInt(),
            heightPx = (50f * density).toInt(),
            active = islandOn,
            accentColor = GlassPalette.HudVioletBlue,
            density = density,
            enabled = true,
        ).asImageBitmap()
    }
    val dockTrayBitmap = remember(density) {
        CockpitHudBitmapRenderer.renderDockTrayBitmap(
            widthPx = (312f * density).toInt(),
            heightPx = (44f * density).toInt(),
            density = density,
        ).asImageBitmap()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CockpitTokens.WidgetCardShape)
            .background(Brush.linearGradient(colors = wallpaperGradientColors))
            .padding(Tokens.Small),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CockpitTokens.HudOuterShape)
                .testTag("cockpit-master-widget-card"),
        ) {
            Image(
                bitmap = shellBitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Tokens.Medium),
                verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
            ) {
                // Top Bento Split: Zone 1 (Clock + Alarm Pill) | Zone 2 (Status Pill + Dual Neon Arc Gauges)
                val zone1Content: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Snug)) {
                        ClockHeroBlock(clockTimeLabel, clockDateLabel)
                        AlarmBadgeBlock(alarm = alarm, onTapAlarm = onTapAlarm)
                    }
                }
                val zone2Content: @Composable () -> Unit = {
                    Column(
                        horizontalAlignment = if (stackTopRow) Alignment.Start else Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(Tokens.Snug),
                    ) {
                        Box(
                            modifier = Modifier
                                .wrapContentWidth()
                                .clip(CockpitTokens.HudTileShape)
                                .testTag("cockpit-widget-status-badge"),
                        ) {
                            Image(
                                bitmap = statusPillBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.matchParentSize(),
                            )
                            Row(
                                modifier = Modifier.padding(horizontal = Tokens.Small, vertical = Tokens.Tiny),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(CockpitTokens.StatusDot)
                                        .clip(Tokens.PillShape)
                                        .background(statusAccent),
                                )
                                Text(
                                    text = statusPill.text,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = statusAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(CockpitTokens.HudPillSpacing),
                            verticalAlignment = Alignment.Top,
                        ) {
                            if (isInitialTelemetryLoading) {
                                GaugeShimmerPlaceholder(reducedMotion = reducedMotion)
                                GaugeShimmerPlaceholder(reducedMotion = reducedMotion)
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(CockpitTokens.HudGaugeSize)
                                        .then(
                                            if (batteryMetric.isCharging) {
                                                Modifier
                                                    .clip(CircleShape)
                                                    .border(
                                                        width = Tokens.Stroke,
                                                        color = GlassPalette.HudEmerald.copy(alpha = 0.55f * chargingPulseAlpha),
                                                        shape = CircleShape,
                                                    )
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .testTag("cockpit-widget-battery-gauge"),
                                ) {
                                    Image(
                                        bitmap = batteryGaugeBitmap,
                                        contentDescription = "Battery ${batteryMetric.valueText}",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                BatteryCurrentLabel(batteryMetric.current)
                                }
                                Image(
                                    bitmap = ramGaugeBitmap,
                                    contentDescription = "RAM ${ramMetric.valueText}",
                                    modifier = Modifier
                                        .size(CockpitTokens.HudGaugeSize)
                                        .testTag("cockpit-widget-ram-gauge"),
                                )
                            }
                        }
                    }
                }

                if (stackTopRow) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        zone1Content()
                        zone2Content()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(Modifier.weight(1f)) {
                            zone1Content()
                        }
                        Spacer(Modifier.width(Tokens.Small))
                        zone2Content()
                    }
                }

                // Zone 3: Middle Control Deck (Stateful ON/OFF/Disabled sculptural tiles)
                val controlsContent: @Composable (Modifier) -> Unit = { tileMod ->
                    HudControlTileButton(
                        painter = painterResource(
                            if (dropTile.active) R.drawable.ic_cockpit_stop else R.drawable.ic_cockpit_devices,
                        ),
                        label = dropTile.label,
                        active = dropTile.active,
                        enabled = dropTile.enabled,
                        accentColor = GlassPalette.HudCoral,
                        plateBitmap = dropTileBitmap,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleDrop()
                        },
                        modifier = tileMod.testTag("cockpit-widget-toggle-drop"),
                    )
                    HudControlTileButton(
                        painter = painterResource(
                            if (torchOn && torchAvailable) R.drawable.ic_cockpit_torch_on else R.drawable.ic_cockpit_torch_off,
                        ),
                        label = when {
                            !torchAvailable -> "Cam Busy"
                            torchOn -> "Torch On"
                            else -> "Torch"
                        },
                        active = torchOn && torchAvailable,
                        enabled = torchAvailable,
                        accentColor = GlassPalette.HudAmber,
                        plateBitmap = torchTileBitmap,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleTorch()
                        },
                        modifier = tileMod.testTag("cockpit-widget-toggle-torch"),
                    )
                    HudControlTileButton(
                        painter = painterResource(R.drawable.ic_cockpit_tile),
                        label = if (islandOn) "Island On" else "Island",
                        active = islandOn,
                        enabled = true,
                        accentColor = GlassPalette.HudVioletBlue,
                        plateBitmap = islandTileBitmap,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleIsland()
                        },
                        modifier = tileMod.testTag("cockpit-widget-toggle-island"),
                    )
                }

                if (stackTopRow) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cockpit-widget-controls-row"),
                        horizontalArrangement = Arrangement.spacedBy(CockpitTokens.HudPillSpacing),
                        verticalArrangement = Arrangement.spacedBy(CockpitTokens.HudPillSpacing),
                    ) {
                        controlsContent(Modifier.widthIn(min = CockpitTokens.PillMinWidth / 2))
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cockpit-widget-controls-row"),
                        horizontalArrangement = Arrangement.spacedBy(CockpitTokens.HudPillSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        controlsContent(Modifier.weight(1f))
                    }
                }

                // Zone 4: Bottom Recessed App Dock
                if (pinnedApps.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CockpitTokens.HudInnerShape)
                            .testTag("cockpit-widget-pinned-row"),
                    ) {
                        Image(
                            bitmap = dockTrayBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.matchParentSize(),
                        )
                        val paged = pinnedPageCount > 1
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Tokens.Small, vertical = Tokens.Tiny),
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (paged) {
                                    HudPagerArrow(
                                        glyph = "\u2039",
                                        label = "Previous pinned apps",
                                        tag = "cockpit-widget-pinned-prev",
                                        onClick = { pinnedPage = CockpitWidgetModel.wrapPage(currentPinnedPage - 1, pinnedPageCount) },
                                    )
                                }
                                if (stackTopRow) {
                                    FlowRow(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                                        verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                                    ) {
                                        pagedApps.forEach { app ->
                                            HudPinnedAppDockItem(app = app, density = density)
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        pagedApps.forEach { app ->
                                            HudPinnedAppDockItem(
                                                app = app,
                                                density = density,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                if (paged) {
                                    HudPagerArrow(
                                        glyph = "\u203A",
                                        label = "Next pinned apps",
                                        tag = "cockpit-widget-pinned-next",
                                        onClick = { pinnedPage = CockpitWidgetModel.wrapPage(currentPinnedPage + 1, pinnedPageCount) },
                                    )
                                }
                            }
                            if (paged) {
                                HudPagerDots(count = pinnedPageCount, current = currentPinnedPage)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GaugeShimmerPlaceholder(reducedMotion: Boolean) {
    val alpha = if (reducedMotion) {
        0.2f
    } else {
        val transition = rememberInfiniteTransition(label = "hud_gauge_shimmer")
        val animated by transition.animateFloat(
            initialValue = 0.12f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = CockpitTokens.SHIMMER_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "gauge_shimmer_alpha",
        )
        animated
    }
    Box(
        modifier = Modifier
            .size(CockpitTokens.HudGaugeSize)
            .clip(CircleShape)
            .background(GlassPalette.HudTrackRecessed)
            .border(
                width = Tokens.Stroke,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                shape = CircleShape,
            ),
    )
}

@Composable
private fun HudControlTileButton(
    painter: Painter,
    label: String,
    active: Boolean,
    enabled: Boolean,
    accentColor: Color,
    plateBitmap: androidx.compose.ui.graphics.ImageBitmap,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        active -> accentColor
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = CockpitTokens.HudControlHeight)
            .clip(CockpitTokens.HudTileShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.72f },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = plateBitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            modifier = Modifier.padding(horizontal = Tokens.Tiny, vertical = Tokens.Tiny),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(Tokens.IconSmall),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClockHeroBlock(timeLabel: String, dateLabel: String) {
    val parts = timeLabel.trim().split(' ')
    val mainPart = parts.getOrElse(0) { timeLabel }
    val amPmPart = parts.getOrNull(1).orEmpty()
    val formattedClock = buildAnnotatedString {
        append(mainPart)
        if (amPmPart.isNotEmpty()) {
            append(" ")
            withStyle(
                SpanStyle(
                    fontSize = MaterialTheme.typography.labelLarge.fontSize,
                    color = GlassPalette.HudElectricCyan,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(amPmPart)
            }
        }
    }
    Column {
        Text(
            text = formattedClock,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = CockpitTokens.WidgetClockTextSize,
                fontWeight = FontWeight.ExtraBold,
                fontFeatureSettings = "tnum",
            ),
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
private fun AlarmBadgeBlock(
    alarm: NextAlarmUiModel,
    onTapAlarm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .defaultMinSize(minHeight = Tokens.TouchTarget)
            .clip(Tokens.ThumbShape)
            .background(GlassPalette.HudTileRecessed)
            .border(Tokens.Hairline, GlassPalette.HudTrackRing, Tokens.ThumbShape)
            .clickable(role = Role.Button, onClickLabel = "Open Google Clock alarms", onClick = onTapAlarm)
            .padding(horizontal = Tokens.Small, vertical = Tokens.Snug)
            .testTag("cockpit-widget-alarm-pill"),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            Icon(
                imageVector = Icons.Outlined.Alarm,
                contentDescription = null,
                tint = if (alarm.hasAlarm) GlassPalette.HudElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CockpitTokens.WidgetBadgeIconSize),
            )
            Text(
                text = if (alarm.hasAlarm) "Alarm ${alarm.formattedTime}" else "No alarm set",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        if (alarm.hasAlarm) {
            Text(
                text = alarm.countdownText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = GlassPalette.HudElectricCyan,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BatteryCurrentLabel(reading: BatteryCurrentReading) {
    val color = when (reading.tone) {
        BatteryCurrentTone.CHARGING -> GlassPalette.HudEmerald
        BatteryCurrentTone.DISCHARGING -> GlassPalette.HudAmber
        BatteryCurrentTone.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = reading.text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
        ),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .testTag("cockpit-widget-battery-current")
            .semantics { contentDescription = "Battery current ${reading.text}" },
    )
}

@Composable
private fun HudPagerArrow(glyph: String, label: String, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = CockpitTokens.HudPagerArrowWidth, height = Tokens.TouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = GlassPalette.HudElectricCyan,
        )
    }
}

@Composable
private fun HudPagerDots(count: Int, current: Int) {
    Row(
        modifier = Modifier
            .padding(bottom = Tokens.Tiny)
            .testTag("cockpit-widget-pinned-dots"),
        horizontalArrangement = Arrangement.spacedBy(CockpitTokens.HudPagerDotGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { page ->
            Box(
                modifier = Modifier
                    .size(CockpitTokens.PagerDot)
                    .clip(CircleShape)
                    .background(
                        if (page == current) GlassPalette.HudElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
    }
}

@Composable
private fun HudPinnedAppDockItem(
    app: PinnedAppInfo,
    density: Float,
    modifier: Modifier = Modifier,
) {
    val haloBitmap = remember(app.packageName, app.iconBitmap, density) {
        CockpitHudBitmapRenderer.renderAppHaloBitmap(
            sizePx = (28f * density).toInt(),
            appIcon = app.iconBitmap,
            accentColor = GlassPalette.HudElectricCyan,
            density = density,
        ).asImageBitmap()
    }
    Row(
        modifier = modifier.padding(horizontal = Tokens.Stroke, vertical = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        Box(
            modifier = Modifier.size(CockpitTokens.HudDockIconHaloSize),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = haloBitmap,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
            if (app.iconBitmap == null) {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = GlassPalette.HudElectricCyan,
                    modifier = Modifier.size(CockpitTokens.WidgetBadgeIconSize),
                )
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
internal fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    onGrant: () -> Unit,
    tag: String,
    /** Android lists the service as enabled but it is not connected (for example suspended after a crash). */
    needsReenable: Boolean = false,
) {
    val stack = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP

    val badge: @Composable () -> Unit = {
        if (needsReenable) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Row(
                    modifier = Modifier
                        .clip(Tokens.PillShape)
                        .background(GlassPalette.HudAmberGlow)
                        .padding(horizontal = Tokens.Medium, vertical = Tokens.Snug)
                        .testTag("$tag-needs-reenable"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = GlassPalette.HudAmber,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Text(
                        text = "Needs Re-enable",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassPalette.HudAmber,
                    )
                }
                Button(
                    onClick = onGrant,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                        .testTag(tag),
                ) {
                    Text("Open Accessibility settings")
                }
            }
        } else if (granted) {
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

private fun openAccessibilityOverlaySettings(context: Context) {
    val component = ComponentName(context, CockpitAccessibilityService::class.java).flattenToString()
    val args = Bundle().apply {
        putString(":settings:fragment_args_key", component)
    }
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(":settings:fragment_args_key", component)
        .putExtra(":settings:show_fragment_args", args)
    runCatching { context.startActivity(intent) }
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

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
