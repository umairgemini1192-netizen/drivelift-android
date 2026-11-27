package com.drivelift.app.feature.cockpit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.alerts.CockpitBiometricStage
import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlert
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.BluetoothTileState
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsState
import com.drivelift.app.feature.cockpit.hardware.CockpitRingerMode
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.MobileDataTileState
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.hardware.RingerTileState
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.hardware.WifiTileState
import com.drivelift.app.feature.cockpit.notifications.CallAudioOutput
import com.drivelift.app.feature.cockpit.notifications.CallAudioUiState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.notifications.LiveNotificationParser
import com.drivelift.app.feature.cockpit.notifications.NotificationSnapshot
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.feature.cockpit.ui.CockpitMasterWidgetCard
import com.drivelift.app.feature.cockpit.ui.CockpitSetupContent
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.computeSafePillMaxWidthDp
import com.drivelift.app.feature.cockpit.ui.DynamicIslandOverlay
import com.drivelift.app.feature.cockpit.ui.LocalCockpitNowMs
import com.drivelift.app.feature.cockpit.ui.LocalIdleNetworkOverride
import com.drivelift.app.feature.cockpit.ui.ManeuverIconContrast
import com.drivelift.app.feature.cockpit.ui.ManeuverIconStyle
import com.drivelift.app.feature.cockpit.ui.PermissionRow
import com.drivelift.app.feature.cockpit.ui.ExpandedCockpitCard
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.appVersionLabel
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the four DriveLift Cockpit UI surfaces across STANDARD, LARGE_FONT (1.5x), and
 * COMPACT (320dp) configurations, recording 12 screenshots under `build/outputs/screenshots/` and the
 * Cockpit v3 live activity screenshots under `build/outputs/screenshots/v3/`, plus
 * interactive tests covering the 5-control quick deck, Torch OFF->ON scrubber reveal, disabled camera
 * state, and Charging/Low-Battery/Light-Dark widget contrast.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitUiScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val record = System.getProperty("roborazzi.test.record") == "true"

    private enum class Variant(val qualifiers: String, val fontScale: Float) {
        STANDARD("w360dp-h780dp-xhdpi", 1f),
        LARGE_FONT("w360dp-h780dp-xhdpi", 1.5f),
        COMPACT("w320dp-h640dp-xhdpi", 1f),
    }

    private val syntheticPinned by lazy {
        listOf(
            PinnedAppInfo("com.alfalah.mobile", "Alfa", iconBitmap = syntheticIconBitmap(0xFF34D399.toInt())),
            PinnedAppInfo("com.android.chrome", "Chrome", iconBitmap = syntheticIconBitmap(0xFF38BDF8.toInt())),
            PinnedAppInfo("com.google.android.apps.maps", "Maps", iconBitmap = syntheticIconBitmap(0xFFA596FF.toInt())),
        )
    }

    private val syntheticQuickControls = CockpitQuickControlsState(
        wifi = WifiTileState(
            isEnabled = true,
            isConnected = true,
            ssid = "StormFiber-46B6-5G",
            hasLocationPermission = true,
            label = "StormFiber-46B6-5G",
        ),
        mobileData = MobileDataTileState(
            isEnabled = true,
            hasUsageAccess = true,
            isLoadingInitial = false,
            monthlyBytes = (14.2 * 1024 * 1024 * 1024).toLong(),
            label = "14.2 GB / mo",
        ),
        bluetooth = BluetoothTileState(
            isEnabled = true,
            hasPermission = true,
            connectedCount = 2,
            label = "2 connected",
        ),
        ringer = RingerTileState(
            mode = CockpitRingerMode.NORMAL,
            hasDndAccess = true,
            label = "Ringer",
        ),
    )

    private val syntheticTelemetry = DriveLiftTelemetrySummary(
        isDesktopDropRunning = true,
        isDropAvailable = true,
        connectedPeerName = "Workstation",
        desktopDropSpeedBytesPerSec = 58_000_000L,
        desktopDropActiveCount = 2,
        queuedCount = 4,
        statusHeadline = "Drop 55.3 MB/s",
    )

    private val syntheticAlarm = NextAlarmUiModel(
        hasAlarm = true,
        formattedTime = "06:30 AM",
        countdownMode = AlarmCountdownMode.CHRONOMETER_UNDER_24H,
        chronometerBaseElapsedMs = 500_000L,
        countdownText = "Rings in 07:14:22",
        triggerAtMillis = FIXED_TIME_MS + 26_062_000L,
    )

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Graph.get(app).clock = { FIXED_TIME_MS }
    }

    @After
    fun tearDown() {
        Graph.get(app).clock = System::currentTimeMillis
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        pump(8)
    }

    private fun pump(times: Int = 8) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(10)
    }

    private fun launchAndAssert(
        name: String,
        content: @androidx.compose.runtime.Composable () -> Unit,
        verify: (Variant) -> Unit,
        directory: String = "",
    ) {
        val folder = if (directory.isEmpty()) "" else "$directory/"
        if (record && folder.isNotEmpty()) java.io.File("build/outputs/screenshots/$folder").mkdirs()
        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            pump(6)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                content()
                            }
                        }
                    }
                }
                pump(8)
                verify(variant)
                if (record) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/screenshots/$folder$name-${variant.name.lowercase()}.png",
                    )
                }
            }
        }
    }

    @Test
    fun collapsedSingleAndSimultaneousSplitPills() {
        val singleSlot = CockpitSlotState(
            primary = CockpitLiveActivity.TransferActivity(
                id = CockpitLiveActivity.ID_DESKTOP_DROP,
                title = "Workstation",
                subtitle = "2 active",
                speedText = "55.3 MB/s",
                progressFraction = 0.65f,
                isDesktopDrop = true,
                canStop = true,
            ),
            secondary = null,
        )
        val splitSlot = CockpitSlotState(
            primary = CockpitLiveActivity.MapsNavActivity(
                maneuverText = "Turn right",
                distanceText = "350 m",
                etaText = "12 min",
                turnDirection = MapsTurnDirection.RIGHT,
            ),
            secondary = CockpitLiveActivity.WhatsAppAlertActivity(
                id = "wa_1",
                senderName = "Amina",
                messageSnippet = "On my way",
                notificationKey = "k_1",
                canInlineReply = true,
            ),
            visibleQueue = emptyList(),
        )
        launchAndAssert(
            name = "cockpit-pill-split",
            content = {
                Column(
                    modifier = Modifier.fillMaxSize().padding(Tokens.Edge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Section),
                ) {
                    CollapsedSplitIslandRow(
                        slotState = singleSlot,
                        widthDeltaDp = 0,
                        onTapPrimary = {},
                        onTapSecondary = {},
                        onLongPressExpand = {},
                        onCycleNext = {},
                        onCyclePrevious = {},
                    )
                    CollapsedSplitIslandRow(
                        slotState = splitSlot,
                        widthDeltaDp = 0,
                        onTapPrimary = {},
                        onTapSecondary = {},
                        onLongPressExpand = {},
                        onCycleNext = {},
                        onCyclePrevious = {},
                    )
                }
            },
            verify = {
                compose.onNodeWithText("Workstation").assertIsDisplayed()
                compose.onNodeWithTag("cockpit-nav-distance", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("350 m").assertIsDisplayed()
                compose.onNodeWithTag("cockpit-secondary-bubble").assertIsDisplayed()
            },
        )
    }

    @Test
    fun expandedHdrGlassCardWithTorchAndPinnedDock() {
        val slotState = CockpitSlotState(
            primary = CockpitLiveActivity.WhatsAppAlertActivity(
                id = "wa_1",
                senderName = "Amina",
                messageSnippet = "Did the Drive backup finish before we leave?",
                notificationKey = "notif_wa_1",
                canInlineReply = true,
            ),
            secondary = CockpitLiveActivity.TimerRecordingActivity(
                id = "timer_1",
                label = "Timer",
                timeText = "04:12",
                isRecording = false,
                packageName = "com.google.android.deskclock",
            ),
            visibleQueue = listOf(
                CockpitLiveActivity.WhatsAppAlertActivity(
                    id = "wa_1",
                    senderName = "Amina",
                    messageSnippet = "Did the Drive backup finish before we leave?",
                    notificationKey = "notif_wa_1",
                    canInlineReply = true,
                ),
                CockpitLiveActivity.TimerRecordingActivity(
                    id = "timer_1",
                    label = "Timer",
                    timeText = "04:12",
                    isRecording = false,
                    packageName = "com.google.android.deskclock",
                ),
            ),
            isExpanded = true,
            inlineReplyOpen = true,
        )
        launchAndAssert(
            name = "cockpit-expanded-card",
            content = {
                Column(
                    modifier = Modifier.fillMaxSize().padding(Tokens.Edge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ExpandedCockpitCard(
                        slotState = slotState,
                        telemetry = syntheticTelemetry,
                        torchState = TorchUiState(
                            available = true,
                            isOn = true,
                            brightnessLevel = 4,
                            maxLevel = 5,
                            supportsBrightnessSlider = true,
                            sosActive = false,
                        ),
                        quickControls = syntheticQuickControls,
                        pinnedApps = syntheticPinned,
                        onCollapse = {},
                        onCycleNext = {},
                        onCyclePrevious = {},
                        onSoftDismiss = {},
                        onRestoreDismissed = {},
                        onTapActivity = {},
                        onStopDesktopDrop = {},
                        onOpenDriveLiftCockpit = {},
                        onToggleInlineReply = {},
                        onSendWhatsAppReply = { _, _ -> },
                        onToggleTorch = {},
                        onTorchBrightnessChange = {},
                        onToggleTorchSos = {},
                        onLaunchPinnedApp = {},
                    )
                }
            },
            verify = {
                compose.onNodeWithTag("cockpit-expanded-card").assertIsDisplayed()
                compose.onNodeWithTag("cockpit-quick-deck").assertIsDisplayed()
                compose.onNodeWithTag("cockpit-tile-wifi").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("cockpit-tile-data").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("cockpit-tile-bluetooth").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("cockpit-tile-ringer").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("cockpit-reply-input").assertExists()
                compose.onNodeWithTag("cockpit-torch-slider").assertExists()
                compose.onNodeWithText("80%").assertExists()
                compose.onNodeWithTag("cockpit-torch-sos").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("cockpit-torch-toggle").assertHeightIsAtLeast(Tokens.TouchTarget)
            },
        )
    }

    @Test
    fun homeScreenMasterWidgetPreview() {
        val chargingBattery = BatteryHudMetric(
            percent = 84,
            fraction = 0.84f,
            valueText = "84%",
            label = "CHRG",
            isCharging = true,
            isLow = false,
        )
        val ramContext = CockpitHudBitmapRenderer.formatRamHudMetric(
            availBytes = (1.5 * 1024 * 1024 * 1024).toLong(),
            totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
        )
        launchAndAssert(
            name = "cockpit-master-widget",
            content = {
                Column(
                    modifier = Modifier.fillMaxSize().padding(Tokens.Edge),
                    verticalArrangement = Arrangement.Center,
                ) {
                    CockpitMasterWidgetCard(
                        clockTimeLabel = "11:15 PM",
                        clockDateLabel = "Sat, Nov 21",
                        alarm = syntheticAlarm,
                        telemetry = syntheticTelemetry,
                        freeStorageLabel = "118 GB free",
                        batteryLabel = "84% batt",
                        torchOn = false,
                        islandOn = true,
                        pinnedApps = syntheticPinned,
                        batteryMetric = chargingBattery,
                        ramMetric = ramContext,
                    )
                }
            },
            verify = {
                compose.onNodeWithTag("cockpit-master-widget-card").assertIsDisplayed()
                compose.onNodeWithText("11:15 PM").assertIsDisplayed()
                compose.onNodeWithText("Alarm 06:30 AM").assertExists()
                compose.onNodeWithText("Rings in 07:14:22").assertExists()
                compose.onNodeWithText("DROP LIVE").assertExists()
                compose.onNodeWithText("Stop").assertExists()
                compose.onNodeWithTag("cockpit-widget-battery-gauge").assertExists()
                compose.onNodeWithTag("cockpit-widget-ram-gauge").assertExists()
                compose.onNodeWithTag("cockpit-widget-toggle-drop").assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight)
                compose.onNodeWithTag("cockpit-widget-toggle-torch").assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight)
                compose.onNodeWithTag("cockpit-widget-toggle-island").assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight)
                compose.onNodeWithTag("cockpit-widget-pinned-row").assertIsDisplayed()
            },
        )
    }

    @Test
    fun cockpitSetupAndCalibrationHub() {
        launchAndAssert(
            name = "cockpit-setup-hub",
            content = {
                CockpitSetupContent(
                    prefs = CockpitPrefsState(
                        masterEnabled = true,
                        cutoutOffsetX = 4,
                        cutoutOffsetY = 2,
                        cutoutWidthDelta = 12,
                        pinnedPackages = syntheticPinned.map { it.packageName },
                    ),
                    telemetry = syntheticTelemetry,
                    alarm = syntheticAlarm,
                    clockTimeLabel = "11:15 PM",
                    clockDateLabel = "Sat, Nov 21",
                    freeStorageLabel = "118 GB free",
                    batteryLabel = "84% batt",
                    torchOn = false,
                    hasOverlay = true,
                    hasNotifications = false,
                    hasBatteryExemption = true,
                    hasAccessibilityOverlay = false,
                    hasLocationPermission = false,
                    hasUsageAccess = false,
                    hasBluetoothPermission = true,
                    hasDndAccess = false,
                    onGrantAccessibilityOverlay = {},
                    quickControls = syntheticQuickControls,
                    launchableApps = syntheticPinned,
                    pinnedApps = syntheticPinned,
                    onBack = {},
                    onToggleMaster = {},
                    onToggleCategory = { _, _ -> },
                    onCalibrationChange = { _, _, _ -> },
                    onResetCalibration = {},
                    onTogglePinnedApp = {},
                    onGrantOverlay = {},
                    onGrantNotifications = {},
                    onGrantBattery = {},
                    onToggleTorch = {},
                    onToggleDrop = {},
                )
            },
            verify = {
                compose.onNodeWithTag("cockpit-setup-screen").assertIsDisplayed()
                compose.onNodeWithText("Cockpit Hub").assertIsDisplayed()
                compose.onNodeWithTag("cockpit-master-toggle").assertHeightIsAtLeast(Tokens.TouchTarget)
                if (compose.onAllNodesWithTag("cockpit-perm-accessibility").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("cockpit-perm-accessibility").assertHeightIsAtLeast(Tokens.TouchTarget)
                }
                if (compose.onAllNodesWithTag("cockpit-perm-notifications").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("cockpit-perm-notifications").assertHeightIsAtLeast(Tokens.TouchTarget)
                }
            },
        )
    }

    @Test
    fun expandedIslandTorchOffShowsIconOnlyAndRevealsLiquidSliderOnTap() {
        var torchOn by mutableStateOf(false)
        val slotState = CockpitSlotState(
            primary = CockpitLiveActivity.IdleQuickDockActivity(),
            isExpanded = true,
        )
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            ExpandedCockpitCard(
                                slotState = slotState,
                                telemetry = syntheticTelemetry,
                                torchState = TorchUiState(
                                    available = true,
                                    isOn = torchOn,
                                    brightnessLevel = 4,
                                    maxLevel = 5,
                                    supportsBrightnessSlider = true,
                                    sosActive = false,
                                ),
                                quickControls = syntheticQuickControls,
                                pinnedApps = syntheticPinned,
                                onCollapse = {},
                                onCycleNext = {},
                                onCyclePrevious = {},
                                onSoftDismiss = {},
                                onRestoreDismissed = {},
                                onTapActivity = {},
                                onStopDesktopDrop = {},
                                onOpenDriveLiftCockpit = {},
                                onToggleInlineReply = {},
                                onSendWhatsAppReply = { _, _ -> },
                                onToggleTorch = { torchOn = !torchOn },
                                onTorchBrightnessChange = {},
                                onToggleTorchSos = {},
                                onLaunchPinnedApp = {},
                            )
                        }
                    }
                }
            }
            pump(8)

            // 5-Control Quick Deck readouts visible.
            compose.onNodeWithText("StormFiber-46B6-5G").assertIsDisplayed()
            compose.onNodeWithText("14.2 GB / mo").assertIsDisplayed()
            compose.onNodeWithText("2 connected").assertIsDisplayed()
            compose.onNodeWithText("Ringer").assertIsDisplayed()

            // Torch OFF shows ONLY icon-only Flash button (zero text labels, zero slider, zero SOS).
            compose.onNodeWithTag("cockpit-torch-toggle").assertHeightIsAtLeast(Tokens.TouchTarget)
            assertTrue(compose.onAllNodesWithText("Flashlight off", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("Level ", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("cockpit-torch-slider").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("cockpit-torch-sos").fetchSemanticsNodes().isEmpty())

            // Tap icon-only Flash -> Torch ON smoothly reveals liquid-glass slider + % + SOS chip.
            compose.onNodeWithTag("cockpit-torch-toggle").performClick()
            pump(12)
            compose.onNodeWithTag("cockpit-torch-slider").assertIsDisplayed()
            compose.onNodeWithText("80%").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-torch-sos").assertIsDisplayed()

            // Tap Flash again -> Torch OFF collapses slider & SOS cleanly.
            compose.onNodeWithTag("cockpit-torch-toggle").performClick()
            pump(12)
            assertTrue(compose.onAllNodesWithTag("cockpit-torch-slider").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("cockpit-torch-sos").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun expandedIslandDisablesTorchWhenCameraBusy() {
        val slotState = CockpitSlotState(
            primary = CockpitLiveActivity.IdleQuickDockActivity(),
            isExpanded = true,
        )
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            ExpandedCockpitCard(
                                slotState = slotState,
                                telemetry = syntheticTelemetry,
                                torchState = TorchUiState(
                                    available = false,
                                    isOn = false,
                                    cameraInUse = true,
                                ),
                                quickControls = syntheticQuickControls,
                                pinnedApps = syntheticPinned,
                                onCollapse = {},
                                onCycleNext = {},
                                onCyclePrevious = {},
                                onSoftDismiss = {},
                                onRestoreDismissed = {},
                                onTapActivity = {},
                                onStopDesktopDrop = {},
                                onOpenDriveLiftCockpit = {},
                                onToggleInlineReply = {},
                                onSendWhatsAppReply = { _, _ -> },
                                onToggleTorch = {},
                                onTorchBrightnessChange = {},
                                onToggleTorchSos = {},
                                onLaunchPinnedApp = {},
                            )
                        }
                    }
                }
            }
            pump(8)

            compose.onNodeWithTag("cockpit-torch-toggle").assertIsNotEnabled()
            assertTrue(compose.onAllNodesWithTag("cockpit-torch-slider").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun collapsedPillTapAndLongPressExpandAndCollapse() {
        var expanded by mutableStateOf(false)
        var collapseFinishedCount = 0
        val slot = CockpitSlotState(
            primary = CockpitLiveActivity.TimerRecordingActivity(
                id = "sysrec_1",
                label = "Screen recording",
                timeText = "Active",
                isRecording = true,
                packageName = "com.android.systemui",
            ),
            secondary = null,
            isExpanded = false,
        )

        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            DynamicIslandOverlay(
                                slotState = slot.copy(isExpanded = expanded),
                                telemetry = syntheticTelemetry,
                                torchState = TorchUiState(available = true),
                                quickControls = syntheticQuickControls,
                                pinnedApps = syntheticPinned,
                                onTapPrimary = {},
                                onTapSecondary = { expanded = true },
                                onExpand = { expanded = true },
                                onCollapse = { expanded = false },
                                onCollapseAnimationFinished = { collapseFinishedCount++ },
                                onCycleNext = {},
                                onCyclePrevious = {},
                                onSoftDismiss = {},
                                onRestoreDismissed = {},
                                onStopDesktopDrop = {},
                                onOpenDriveLiftCockpit = {},
                                onToggleInlineReply = {},
                                onSendWhatsAppReply = { _, _ -> },
                                onToggleTorch = {},
                                onTorchBrightnessChange = {},
                                onToggleTorchSos = {},
                                onLaunchPinnedApp = {},
                            )
                        }
                    }
                }
            }
            pump(8)

            // Collapsed pill has >= 52dp hit target and expands on single tap.
            compose.onNodeWithTag("cockpit-primary-pill")
                .assertHeightIsAtLeast(Tokens.Cockpit.PillTouchTargetHeight)
                .performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-expanded-card").assertIsDisplayed()
            compose.onNodeWithText("Screen recording").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-expanded-open-app").assertIsDisplayed()

            // Collapse completes spring morph and invokes onCollapseAnimationFinished callback.
            compose.onNodeWithTag("cockpit-collapse").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-primary-pill").assertIsDisplayed()
            assertTrue(collapseFinishedCount >= 1)

            // Long-press also expands the card cleanly.
            compose.onNodeWithTag("cockpit-primary-pill").performTouchInput { longClick() }
            pump(18)
            compose.onNodeWithTag("cockpit-expanded-card").assertIsDisplayed()
        }
    }

    @Test
    fun masterWidgetKeepsControlsAndPinnedAppsRowsSeparateOnIslandToggle() {
        var islandOn by mutableStateOf(true)
        val apps = listOf(
            PinnedAppInfo("com.alfalah.mobile", "Alfa", iconBitmap = syntheticIconBitmap(0xFF34D399.toInt())),
            PinnedAppInfo("com.android.chrome", "Chrome", iconBitmap = syntheticIconBitmap(0xFF38BDF8.toInt())),
        )
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CockpitMasterWidgetCard(
                                clockTimeLabel = "11:15 PM",
                                clockDateLabel = "Sat, Nov 21",
                                alarm = syntheticAlarm,
                                telemetry = syntheticTelemetry,
                                freeStorageLabel = "118 GB free",
                                batteryLabel = "84% batt",
                                torchOn = false,
                                islandOn = islandOn,
                                pinnedApps = apps,
                                onToggleIsland = { islandOn = !islandOn },
                            )
                        }
                    }
                }
            }
            pump(8)

            // Verify pinned apps live in cockpit-widget-pinned-row, never cockpit-widget-controls-row.
            compose.onNodeWithTag("cockpit-widget-controls-row").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-widget-pinned-row").assertIsDisplayed()
            compose.onNode(
                hasText("Alfa") and hasAnyAncestor(hasTestTag("cockpit-widget-pinned-row")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            assertTrue(
                compose.onAllNodes(
                    hasText("Alfa") and hasAnyAncestor(hasTestTag("cockpit-widget-controls-row")),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            )

            // Toggle Island On -> Island and confirm Alfa stays strictly in cockpit-widget-pinned-row.
            compose.onNodeWithTag("cockpit-widget-toggle-island").performClick()
            pump(8)
            compose.onNodeWithTag("cockpit-widget-toggle-island").assert(hasText("Island"))
            compose.onNode(
                hasText("Alfa") and hasAnyAncestor(hasTestTag("cockpit-widget-pinned-row")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            assertTrue(
                compose.onAllNodes(
                    hasText("Alfa") and hasAnyAncestor(hasTestTag("cockpit-widget-controls-row")),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun persistedIslandDisabledBlocksNotificationRemountAndStampsBuildVersion() {
        val cockpit = Graph.get(app).cockpit
        runBlocking {
            cockpit.prefsRepo.setMasterEnabled(true)
            cockpit.prefsRepo.setMasterEnabled(false)
        }

        assertFalse(cockpit.prefsRepo.state.value.masterEnabled)
        assertFalse(cockpit.prefsRepo.isMasterEnabledMirrored())
        assertFalse(
            app.getSharedPreferences("cockpit_prefs_sync", Context.MODE_PRIVATE)
                .getBoolean("master_enabled", true),
        )
        runBlocking {
            assertFalse(cockpit.prefsRepo.readCurrentMasterEnabled())
        }

        cockpit.coordinator.upsertActivity(
            CockpitLiveActivity.WhatsAppAlertActivity(
                id = "wa_blocked",
                senderName = "Amina",
                messageSnippet = "Arrived",
                notificationKey = "k_blocked",
                canInlineReply = true,
            ),
        )
        assertFalse(
            CockpitOverlayService.shouldMountFallbackOverlay(
                masterEnabled = cockpit.prefsRepo.isMasterEnabledMirrored(),
                hasOverlayPermission = true,
                accessibilityOverlayActive = false,
            ),
        )

        assertEquals(
            "DriveLift 1.0.0-preview.41 (build 40)",
            appVersionLabel(app, buildStamp = ""),
        )
        assertEquals(
            "DriveLift 1.0.0-preview.41 (build 40 \u00B7 PR #78 @ a1d8b31)",
            appVersionLabel(app, buildStamp = "PR #78 @ a1d8b31"),
        )
    }

    @Test
    fun bentoHudRendersLowBatteryDisabledTilesAndLightContrastCleanly() {
        var dropOn by mutableStateOf(false)
        var torchOn by mutableStateOf(false)
        var islandOn by mutableStateOf(false)
        val noAlarm = NextAlarmUiModel(
            hasAlarm = false,
            formattedTime = "No alarm set",
            countdownMode = AlarmCountdownMode.NONE,
            chronometerBaseElapsedMs = null,
            countdownText = "No alarm set",
            triggerAtMillis = null,
        )
        val lowBattery = BatteryHudMetric(
            percent = 18,
            fraction = 0.18f,
            valueText = "18%",
            label = "LOW",
            isCharging = false,
            isLow = true,
        )
        val ramMetric = CockpitHudBitmapRenderer.formatRamHudMetric(
            availBytes = (1.5 * 1024 * 1024 * 1024).toLong(),
            totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
        )
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Medium)) {
                                // Light wallpaper contrast + disabled Torch/Drop state.
                                CockpitMasterWidgetCard(
                                    clockTimeLabel = "08:00 AM",
                                    clockDateLabel = "Sun, Nov 22",
                                    alarm = noAlarm,
                                    telemetry = DriveLiftTelemetrySummary(
                                        isDesktopDropRunning = false,
                                        isDropAvailable = false,
                                        statusHeadline = "Ready",
                                    ),
                                    freeStorageLabel = "118 GB free",
                                    batteryLabel = "18% batt",
                                    torchOn = false,
                                    islandOn = false,
                                    pinnedApps = syntheticPinned,
                                    torchAvailable = false,
                                    dropAvailable = false,
                                    batteryMetric = lowBattery,
                                    ramMetric = ramMetric,
                                    wallpaperGradientColors = listOf(Color(0xFFE8EAF0), Color(0xFFD0D6E4)),
                                )
                                // Dark wallpaper contrast + interactive active toggles.
                                CockpitMasterWidgetCard(
                                    clockTimeLabel = "08:00 AM",
                                    clockDateLabel = "Sun, Nov 22",
                                    alarm = syntheticAlarm,
                                    telemetry = DriveLiftTelemetrySummary(
                                        isDesktopDropRunning = dropOn,
                                        statusHeadline = if (dropOn) "Drop 42.0 MB/s" else "Ready",
                                    ),
                                    freeStorageLabel = "118 GB free",
                                    batteryLabel = "84% batt",
                                    torchOn = torchOn,
                                    islandOn = islandOn,
                                    pinnedApps = syntheticPinned,
                                    batteryMetric = BatteryHudMetric(84, 0.84f, "84%", "CHRG", isCharging = true),
                                    ramMetric = ramMetric,
                                    onToggleDrop = { dropOn = !dropOn },
                                    onToggleTorch = { torchOn = !torchOn },
                                    onToggleIsland = { islandOn = !islandOn },
                                )
                            }
                        }
                    }
                }
            }
            pump(8)

            compose.onNodeWithText("No alarm set").assertIsDisplayed()
            compose.onNodeWithText("Cam Busy").assertIsDisplayed()
            compose.onNodeWithText("No Wi-Fi").assertIsDisplayed()
            compose.onAllNodesWithText("READY")[0].assertIsDisplayed()
            compose.onAllNodesWithTag("cockpit-widget-toggle-drop")[0].assertIsNotEnabled()
            compose.onAllNodesWithTag("cockpit-widget-toggle-torch")[0].assertIsNotEnabled()

            compose.onAllNodesWithTag("cockpit-widget-toggle-drop")[1]
                .assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight)
                .assert(hasText("Drop"))
                .performClick()
            compose.onAllNodesWithTag("cockpit-widget-toggle-torch")[1]
                .assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight)
                .assert(hasText("Torch"))
                .performClick()
            compose.onAllNodesWithTag("cockpit-widget-toggle-island")[1]
                .assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight)
                .assert(hasText("Island"))
                .performClick()
            pump(8)

            compose.onNodeWithText("DROP LIVE").assertIsDisplayed()
            compose.onAllNodesWithTag("cockpit-widget-toggle-drop")[1].assert(hasText("Stop"))
            compose.onAllNodesWithTag("cockpit-widget-toggle-torch")[1].assert(hasText("Torch On"))
            compose.onAllNodesWithTag("cockpit-widget-toggle-island")[1].assert(hasText("Island On"))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Cockpit v3 live activities: call, screen recording, navigation and activity swapping.
    // Images go to build/outputs/screenshots/v3/ so the flat screenshot count CI checks is unchanged.
    // ------------------------------------------------------------------------------------------

    private val v3Directory = "v3"
    private val v3FixedNow: () -> Long = { FIXED_TIME_MS }

    private fun v3Call(state: CallState, isVideo: Boolean = false) = CockpitLiveActivity.CallActivity(
        id = "call_v3",
        callerName = "Amina Khan",
        sourceLabel = if (isVideo) "WhatsApp Video" else "WhatsApp Audio",
        isVideo = isVideo,
        state = state,
        startedAtMs = FIXED_TIME_MS - 134_000L,
        packageName = "com.whatsapp",
        notificationKey = "notif_call_v3",
    )

    private fun v3Recording() = CockpitLiveActivity.ScreenRecordingActivity(
        id = "screenrec_v3",
        startedAtMs = FIXED_TIME_MS - 6_000L,
        notificationKey = "notif_rec_v3",
    )

    private fun v3Navigation() = CockpitLiveActivity.MapsNavActivity(
        maneuverText = "North",
        distanceText = "90 ft",
        etaText = "12 min",
        turnDirection = MapsTurnDirection.STRAIGHT,
        roadText = "North",
        destinationText = "San Francisco",
        notificationKey = "notif_nav_v3",
        remainingTripKm = 8.4,
        droveTripKm = 3.2,
        speedKmh = 64,
    )

    private fun v3Slot(queue: List<CockpitLiveActivity>, index: Int = 0) = CockpitSlotState(
        primary = queue[index],
        secondary = if (queue.size > 1) queue[(index + 1) % queue.size] else null,
        visibleQueue = queue,
        isExpanded = false,
        activeIndex = index,
    )

    @androidx.compose.runtime.Composable
    private fun V3Section(caption: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Tokens.Snug),
        ) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }

    @androidx.compose.runtime.Composable
    private fun V3Pill(slot: CockpitSlotState) {
        CollapsedSplitIslandRow(
            slotState = slot,
            widthDeltaDp = 0,
            onTapPrimary = {},
            onTapSecondary = {},
            onLongPressExpand = {},
            onCycleNext = {},
            onCyclePrevious = {},
        )
    }

    @androidx.compose.runtime.Composable
    private fun V3Card(
        slot: CockpitSlotState,
        audio: CallAudioUiState = CallAudioUiState(),
        onCollapse: () -> Unit = {},
        onCycleNext: () -> Unit = {},
        onCyclePrevious: () -> Unit = {},
    ) {
        ExpandedCockpitCard(
            slotState = slot.copy(isExpanded = true),
            telemetry = syntheticTelemetry,
            torchState = TorchUiState(available = true),
            pinnedApps = syntheticPinned,
            onCollapse = onCollapse,
            onCycleNext = onCycleNext,
            onCyclePrevious = onCyclePrevious,
            onSoftDismiss = {},
            onRestoreDismissed = {},
            onTapActivity = {},
            onStopDesktopDrop = {},
            onOpenDriveLiftCockpit = {},
            onToggleInlineReply = {},
            onSendWhatsAppReply = { _, _ -> },
            onToggleTorch = {},
            onTorchBrightnessChange = {},
            onToggleTorchSos = {},
            onLaunchPinnedApp = {},
            callAudio = audio,
        )
    }

    @androidx.compose.runtime.Composable
    private fun V3Frame(content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(LocalCockpitNowMs provides v3FixedNow) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Tokens.Medium, vertical = Tokens.Edge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Tokens.Section),
            ) {
                content()
            }
        }
    }

    @Test
    fun v3IncomingCallBannerKeepsDeclineAndAnswerClearOfTheCallerName() {
        val slot = v3Slot(listOf(v3Call(CallState.INCOMING)))
        launchAndAssert(
            name = "cockpit-v3-call-incoming",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Collapsed ringing pill") { V3Pill(slot) }
                    V3Section("Auto-expanded incoming banner") { V3Card(slot) }
                }
            },
            verify = {
                compose.onNodeWithTag("cockpit-call-incoming").assertIsDisplayed()
                val decline = compose.onNodeWithTag("cockpit-call-decline")
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .getBoundsInRoot()
                val answer = compose.onNodeWithTag("cockpit-call-answer")
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .getBoundsInRoot()
                val caller = compose.onNodeWithTag("cockpit-call-caller").assertIsDisplayed().getBoundsInRoot()
                assertTrue("name must end before Decline", caller.right <= decline.left)
                assertTrue("Decline must end before Answer", decline.right <= answer.left)
                compose.onNodeWithText("WhatsApp Audio").assertIsDisplayed()
            },
        )
    }

    @Test
    fun v3OngoingCallShowsTimerWaveformAndFourEvenlySpacedCircularControls() {
        val slot = v3Slot(listOf(v3Call(CallState.ONGOING)))
        launchAndAssert(
            name = "cockpit-v3-call-ongoing",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Collapsed pill: timer and waveform") { V3Pill(slot) }
                    V3Section("Expanded card: speaker on, microphone live") {
                        V3Card(slot, audio = CallAudioUiState(isMuted = false, output = CallAudioOutput.SPEAKER))
                    }
                    V3Section("Expanded card: earpiece, microphone muted") {
                        V3Card(slot, audio = CallAudioUiState(isMuted = true, output = CallAudioOutput.EARPIECE))
                    }
                }
            },
            verify = {
                compose.onNodeWithTag("cockpit-call-timer", useUnmergedTree = true).assert(hasText("02:14"))
                val timer = compose.onNodeWithTag("cockpit-call-timer", useUnmergedTree = true).getBoundsInRoot()
                val waveform = compose.onNodeWithTag("cockpit-call-waveform", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
                assertTrue("timer must end before the waveform", timer.right <= waveform.left)

                compose.onAllNodesWithTag("cockpit-call-subtitle", useUnmergedTree = true)[0].assert(hasText("WhatsApp Audio · 02:14"))
                compose.onAllNodesWithTag("cockpit-call-info")[0].assertHeightIsAtLeast(Tokens.TouchTarget)

                for (cardIndex in 0..1) {
                    val tags = listOf("cockpit-call-audio", "cockpit-call-mute", "cockpit-call-open", "cockpit-call-hangup")
                    val card = compose.onAllNodesWithTag("cockpit-expanded-card")[cardIndex].getBoundsInRoot()
                    val buttons = tags.map { tag ->
                        compose.onAllNodesWithTag(tag)[cardIndex]
                            .assertHeightIsAtLeast(Tokens.TouchTarget)
                            .assertWidthIsAtLeast(Tokens.TouchTarget)
                            .getBoundsInRoot()
                    }
                    buttons.zipWithNext().forEach { (left, right) ->
                        assertTrue("controls must not overlap", left.right <= right.left)
                    }
                    buttons.forEach { bounds ->
                        assertTrue("control clipped on the left", bounds.left >= card.left)
                        assertTrue("control clipped on the right", bounds.right <= card.right)
                    }
                    val gaps = buttons.zipWithNext().map { (left, right) -> (right.left - left.right).value }
                    assertTrue("gaps must be balanced: $gaps", gaps.max() - gaps.min() < 1f)
                }
            },
        )
    }

    @Test
    fun v3DualActivityPillShowsASatelliteBubbleBesideThePrimaryPill() {
        val callAndRecording = v3Slot(listOf(v3Call(CallState.ONGOING), v3Recording()))
        val swiped = v3Slot(listOf(v3Call(CallState.ONGOING), v3Recording(), v3Navigation()), index = 1)
        launchAndAssert(
            name = "cockpit-v3-dual-activity",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Call with screen recording in the satellite") { V3Pill(callAndRecording) }
                    V3Section("Swiped once: recording leads, navigation follows") { V3Pill(swiped) }
                }
            },
            verify = {
                val pills = compose.onAllNodesWithTag("cockpit-primary-pill")
                val bubbles = compose.onAllNodesWithTag("cockpit-secondary-bubble")
                val root = compose.onRoot().getBoundsInRoot()
                for (index in 0..1) {
                    val pill = pills[index].assertIsDisplayed().getBoundsInRoot()
                    val bubble = bubbles[index].assertIsDisplayed().getBoundsInRoot()
                    assertTrue("satellite must sit beside the pill", pill.right <= bubble.left)
                    assertTrue("satellite must stay on screen", bubble.right <= root.right)
                    assertTrue("pill must stay on screen", pill.left >= root.left)
                }
            },
        )
    }

    @Test
    fun v3ScreenRecordingPillShowsTimerAndAWhiteRingedStopButton() {
        val slot = v3Slot(listOf(v3Recording()))
        launchAndAssert(
            name = "cockpit-v3-screen-recording",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Collapsed pill: pulsing dot and timer") { V3Pill(slot) }
                    V3Section("Expanded banner with Stop") { V3Card(slot) }
                }
            },
            verify = {
                compose.onNodeWithTag("cockpit-recording-timer", useUnmergedTree = true).assert(hasText("00:06"))
                compose.onNodeWithTag("cockpit-recording-clock", useUnmergedTree = true).assert(hasText("00:06"))
                compose.onNodeWithText("Screen Recording").assertIsDisplayed()
                val clock = compose.onNodeWithTag("cockpit-recording-clock", useUnmergedTree = true).getBoundsInRoot()
                val stop = compose.onNodeWithTag("cockpit-recording-stop")
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .getBoundsInRoot()
                assertTrue("Stop must sit right of the timer", clock.right <= stop.left)
            },
        )
    }

    @Test
    fun v3NavigationCardShowsDistanceRoadAndDestinationWithoutTruncation() {
        val slot = v3Slot(listOf(v3Navigation()))
        launchAndAssert(
            name = "cockpit-v3-navigation",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Collapsed pill: maneuver arrow and distance") { V3Pill(slot) }
                    V3Section("Expanded navigation card") { V3Card(slot) }
                }
            },
            verify = {
                compose.onNodeWithTag("cockpit-nav-distance", useUnmergedTree = true).assert(hasText("90 ft"))
                compose.onNodeWithTag("cockpit-nav-card-distance", useUnmergedTree = true).assert(hasText("90 ft")).assertIsDisplayed()
                compose.onNodeWithText("North").assertIsDisplayed()
                compose.onNodeWithText("San Francisco").assertIsDisplayed()
                compose.onNodeWithTag("cockpit-nav-card").assertIsDisplayed()

                // Bogus always-visible 4-arrow row is removed; single clean active turn icon leads.
                assertEquals(0, compose.onAllNodesWithTag("cockpit-nav-lanes", useUnmergedTree = true).fetchSemanticsNodes().size)
                compose.onNodeWithTag("cockpit-nav-turn-icon", useUnmergedTree = true).assertIsDisplayed()

                // Structured 4-metric Driving Navigation HUD: ETA, KM Left, Live Speed (km/h), KM Driven.
                val hudRow = compose.onNodeWithTag("cockpit-nav-hud-row", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
                compose.onNodeWithTag("cockpit-nav-hud-eta", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("cockpit-nav-hud-left", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("cockpit-nav-hud-speed", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("cockpit-nav-hud-driven", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("12 min", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("8.4 km", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("64", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("3.2 km", useUnmergedTree = true).assertExists()

                val header = compose.onNodeWithTag("cockpit-nav-card-distance", useUnmergedTree = true).getBoundsInRoot()
                val hairline = compose.onNodeWithTag("cockpit-nav-progress", useUnmergedTree = true).assertExists().getBoundsInRoot()
                assertTrue("HUD row sits below the turn header", hudRow.top >= header.bottom)
                assertTrue("hairline is the last line of the card", hairline.top >= hudRow.bottom)
                assertEquals("hairline is 2dp", 2f, (hairline.bottom - hairline.top).value, 0.6f)
            },
        )
    }

    @Test
    fun v3HorizontalSwipeSwapsAndVerticalSwipeCollapsesNeverBoth() {
        var nextCount = 0
        var previousCount = 0
        var collapseCount = 0
        val slot = v3Slot(listOf(v3Call(CallState.ONGOING), v3Recording()))

        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            V3Frame {
                                V3Card(
                                    slot = slot,
                                    onCollapse = { collapseCount++ },
                                    onCycleNext = { nextCount++ },
                                    onCyclePrevious = { previousCount++ },
                                )
                            }
                        }
                    }
                }
            }
            pump(8)

            // Swipe left cycles forward; nothing collapses.
            compose.onNodeWithTag("cockpit-expanded-card").performTouchInput { swipeLeft() }
            pump(4)
            assertEquals(1, nextCount)
            assertEquals(0, previousCount)
            assertEquals(0, collapseCount)

            // Swipe right cycles back.
            compose.onNodeWithTag("cockpit-expanded-card").performTouchInput { swipeRight() }
            pump(4)
            assertEquals(1, nextCount)
            assertEquals(1, previousCount)
            assertEquals(0, collapseCount)

            // Swipe up collapses; nothing is swapped.
            compose.onNodeWithTag("cockpit-expanded-card").performTouchInput { swipeUp() }
            pump(4)
            assertEquals(1, nextCount)
            assertEquals(1, previousCount)
            assertEquals(1, collapseCount)

            // A mostly horizontal diagonal swaps and does not collapse.
            compose.onNodeWithTag("cockpit-expanded-card").performTouchInput {
                swipe(
                    start = Offset(centerX, centerY),
                    end = Offset(centerX - 320f, centerY - 90f),
                    durationMillis = 240,
                )
            }
            pump(4)
            assertEquals(2, nextCount)
            assertEquals(1, collapseCount)

            // A mostly vertical diagonal collapses and does not swap.
            compose.onNodeWithTag("cockpit-expanded-card").performTouchInput {
                swipe(
                    start = Offset(centerX, centerY),
                    end = Offset(centerX - 70f, centerY - 320f),
                    durationMillis = 240,
                )
            }
            pump(4)
            assertEquals(2, nextCount)
            assertEquals(1, previousCount)
            assertEquals(2, collapseCount)
        }
    }

    @Test
    fun v3CollapsedPillSwipesBetweenActivitiesAndIgnoresVerticalDrags() {
        var nextCount = 0
        var previousCount = 0
        var satelliteTaps = 0
        val slot = v3Slot(listOf(v3Call(CallState.ONGOING), v3Recording()))

        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            V3Frame {
                                CollapsedSplitIslandRow(
                                    slotState = slot,
                                    widthDeltaDp = 0,
                                    onTapPrimary = {},
                                    onTapSecondary = { satelliteTaps++ },
                                    onLongPressExpand = {},
                                    onCycleNext = { nextCount++ },
                                    onCyclePrevious = { previousCount++ },
                                )
                            }
                        }
                    }
                }
            }
            pump(8)

            compose.onNodeWithTag("cockpit-collapsed-row").performTouchInput { swipeLeft() }
            pump(4)
            assertEquals(1, nextCount)
            compose.onNodeWithTag("cockpit-collapsed-row").performTouchInput { swipeRight() }
            pump(4)
            assertEquals(1, previousCount)

            // A vertical drag on the pill swaps nothing.
            compose.onNodeWithTag("cockpit-collapsed-row").performTouchInput { swipeUp() }
            pump(4)
            assertEquals(1, nextCount)
            assertEquals(1, previousCount)

            // Tapping the satellite bubble asks to swap it into the primary slot.
            compose.onNodeWithTag("cockpit-secondary-bubble").performClick()
            pump(4)
            assertEquals(1, satelliteTaps)
        }
    }

    @Test
    fun v3HubChipsSimulateEveryLiveActivityInThePreview() {
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CockpitSetupContent(
                                prefs = CockpitPrefsState(masterEnabled = true),
                                telemetry = syntheticTelemetry,
                                alarm = syntheticAlarm,
                                clockTimeLabel = "11:15 PM",
                                clockDateLabel = "Sat, Nov 21",
                                freeStorageLabel = "118 GB free",
                                batteryLabel = "84% batt",
                                torchOn = false,
                                hasOverlay = true,
                                hasNotifications = true,
                                hasBatteryExemption = true,
                                hasAccessibilityOverlay = true,
                                hasLocationPermission = true,
                                hasUsageAccess = true,
                                hasBluetoothPermission = true,
                                hasDndAccess = true,
                                onGrantAccessibilityOverlay = {},
                                quickControls = syntheticQuickControls,
                                launchableApps = syntheticPinned,
                                pinnedApps = syntheticPinned,
                                onBack = {},
                                onToggleMaster = {},
                                onToggleCategory = { _, _ -> },
                                onCalibrationChange = { _, _, _ -> },
                                onResetCalibration = {},
                                onTogglePinnedApp = {},
                                onGrantOverlay = {},
                                onGrantNotifications = {},
                                onGrantBattery = {},
                                onToggleTorch = {},
                                onToggleDrop = {},
                            )
                        }
                    }
                }
            }
            pump(8)

            compose.onNodeWithTag("cockpit-preview-chips").assertIsDisplayed()

            compose.onNodeWithTag("cockpit-preview-ongoing-call").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-call-timer", useUnmergedTree = true).assertIsDisplayed()

            // A ringing call opens the island on its own; Answer turns it into an ongoing pill.
            compose.onNodeWithTag("cockpit-preview-incoming-call").performClick()
            pump(24)
            // The expanded preview panel is clickable, so its children only exist in the unmerged tree.
            compose.onNodeWithTag("cockpit-call-incoming", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("cockpit-call-answer", useUnmergedTree = true).performClick()
            pump(24)
            compose.onNodeWithTag("cockpit-call-timer", useUnmergedTree = true).assertIsDisplayed()

            compose.onNodeWithTag("cockpit-preview-screen-recording").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-recording-timer", useUnmergedTree = true).assertIsDisplayed()

            compose.onNodeWithTag("cockpit-preview-navigation").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-nav-distance", useUnmergedTree = true).assertIsDisplayed()

            // Multi-activity: the satellite bubble appears and a tap swaps it into the primary slot.
            compose.onNodeWithTag("cockpit-preview-multi-swap").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-call-timer", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("cockpit-secondary-bubble").assertIsDisplayed().performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-recording-timer", useUnmergedTree = true).assertIsDisplayed()

            // Tapping the selected chip returns the preview to its default pair.
            compose.onNodeWithTag("cockpit-preview-multi-swap").performClick()
            pump(18)
            assertEquals(0, compose.onAllNodesWithTag("cockpit-call-timer", useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }


    // ------------------------------------------------------------------------------------------
    // Cockpit v3.2: transient system alerts on the island, the Hub controls and chips for them,
    // and the Google Maps fixes seen on a Pixel 8 Pro. Images go to build/outputs/screenshots/v3/.
    // ------------------------------------------------------------------------------------------

    private fun v3AlertSlot(
        alert: CockpitSystemAlert,
        queue: List<CockpitLiveActivity> = emptyList(),
    ): CockpitSlotState =
        if (queue.isEmpty()) CockpitSlotState(transientAlert = alert) else v3Slot(queue).copy(transientAlert = alert)

    /** Every pill stays on screen and never grows past the width that clears the status bar icons. */
    private fun assertPillsClearTheStatusIcons(count: Int) {
        val root = compose.onRoot().getBoundsInRoot()
        val pills = compose.onAllNodesWithTag("cockpit-primary-pill")
        val safeMaxWidth = computeSafePillMaxWidthDp(
            screenWidthDp = (root.right - root.left).value.toInt(),
            widthDeltaDp = 0,
            hasSatelliteBubble = false,
        )
        for (index in 0 until count) {
            val bounds = pills[index].assertIsDisplayed().getBoundsInRoot()
            assertTrue("pill $index stays on screen", bounds.left >= root.left && bounds.right <= root.right)
            assertTrue(
                "pill $index is at most ${safeMaxWidth}dp",
                (bounds.right - bounds.left).value <= safeMaxWidth + 0.5f,
            )
        }
    }

    private fun assertAlertTitle(index: Int, title: String) {
        compose.onAllNodesWithTag("cockpit-system-alert-title", useUnmergedTree = true)[index]
            .assert(hasText(title))
            .assertIsDisplayed()
    }

    private fun assertAlertDetail(index: Int, detail: String) {
        compose.onAllNodesWithTag("cockpit-system-alert-detail", useUnmergedTree = true)[index]
            .assert(hasText(detail))
            .assertIsDisplayed()
    }

    @Test
    fun v3BatteryAndEarbudsAlertsShowAsCrispPillsThatClearTheStatusIcons() {
        val alerts = listOf(
            "Charging, fast" to CockpitSystemAlert.BatteryCharging(82, isFastCharging = true, estimatedFullMinutes = 40L),
            "Battery full" to CockpitSystemAlert.BatteryFull(100),
            "Battery low" to CockpitSystemAlert.BatteryLow(15, isCritical = false),
            "Earbuds connected" to CockpitSystemAlert.EarbudsConnected("Pixel Buds Pro", batteryPercent = 76),
            "Earbuds low battery" to CockpitSystemAlert.EarbudsLowBattery("Pixel Buds Pro", batteryPercent = 12),
        )
        launchAndAssert(
            name = "cockpit-v3-alerts-battery-earbuds",
            directory = v3Directory,
            content = {
                V3Frame {
                    alerts.forEach { (caption, alert) -> V3Section(caption) { V3Pill(v3AlertSlot(alert)) } }
                }
            },
            verify = {
                assertAlertTitle(0, "Fast charging")
                assertAlertDetail(0, "82%")
                assertAlertTitle(1, "Battery full")
                assertAlertDetail(1, "100%")
                assertAlertTitle(2, "Battery low")
                assertAlertDetail(2, "15%")
                assertAlertTitle(3, "Pixel Buds Pro")
                assertAlertDetail(3, "76%")
                assertAlertTitle(4, "Pixel Buds Pro")
                assertAlertDetail(4, "12% low")
                assertPillsClearTheStatusIcons(alerts.size)
            },
        )
    }

    @Test
    fun v3AirplaneBiometricTransferRadarAndRingerAlertsShowTitleAndDetailWithoutClipping() {
        val longName = "IMG_20261123_083412_burst_042_original_edit.jpg"
        val alerts = listOf(
            "Airplane mode on" to CockpitSystemAlert.AirplaneModeChanged(enabled = true),
            "Biometric knock" to CockpitSystemAlert.BiometricFeedback(CockpitBiometricStage.SCANNING),
            "Transfer done, long file name" to CockpitSystemAlert.DriveLiftTransfer(longName, 100, isCompleted = true),
            "Desktop Drop radar" to CockpitSystemAlert.DriveLiftRadarConnected("Work laptop"),
            "Ringer silent" to CockpitSystemAlert.RingerModeChanged(
                com.drivelift.app.feature.cockpit.alerts.CockpitRingerMode.SILENT,
            ),
        )
        launchAndAssert(
            name = "cockpit-v3-alerts-airplane-biometric-drivelift-ringer",
            directory = v3Directory,
            content = {
                V3Frame {
                    alerts.forEach { (caption, alert) -> V3Section(caption) { V3Pill(v3AlertSlot(alert)) } }
                }
            },
            verify = {
                assertAlertTitle(0, "Airplane mode")
                assertAlertDetail(0, "On")
                assertAlertTitle(1, "Approve laptop")
                assertAlertDetail(1, "Scanning")
                // The long file name shrinks with an ellipsis; "Done" stays inside the pill.
                assertAlertDetail(2, "Done")
                assertAlertTitle(3, "Work laptop")
                assertAlertDetail(3, "Connected")
                assertAlertTitle(4, "Silent")
                assertEquals(
                    "Silent has no detail",
                    4,
                    compose.onAllNodesWithTag("cockpit-system-alert-detail", useUnmergedTree = true)
                        .fetchSemanticsNodes().size,
                )
                assertPillsClearTheStatusIcons(alerts.size)
                val pills = compose.onAllNodesWithTag("cockpit-primary-pill")
                val details = compose.onAllNodesWithTag("cockpit-system-alert-detail", useUnmergedTree = true)
                for (index in 0..3) {
                    val pill = pills[index].getBoundsInRoot()
                    val detail = details[index].getBoundsInRoot()
                    assertTrue("detail $index stays inside its pill", detail.right.value <= pill.right.value + 0.5f)
                }
            },
        )
    }

    @Test
    fun v3AlertOverACallKeepsTheCallAsTheSatelliteAndTheIslandReturnsToItAfterwards() {
        val queue = listOf(v3Call(CallState.ONGOING), v3Navigation())
        launchAndAssert(
            name = "cockpit-v3-alert-over-activity",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Battery low over an ongoing call") {
                        V3Pill(v3AlertSlot(CockpitSystemAlert.BatteryLow(15, isCritical = false), queue))
                    }
                    V3Section("The same island once the alert has ended") { V3Pill(v3Slot(queue)) }
                    V3Section("Charging over the idle island") {
                        V3Pill(v3AlertSlot(CockpitSystemAlert.BatteryCharging(82, true, 40L)))
                    }
                }
            },
            verify = {
                assertAlertTitle(0, "Battery low")
                assertAlertTitle(1, "Fast charging")
                // The call timer shows only in the second island: the alert took the first one's pill.
                assertEquals(
                    1,
                    compose.onAllNodesWithTag("cockpit-call-timer", useUnmergedTree = true)
                        .fetchSemanticsNodes().size,
                )
                // Over the call the satellite shows the call; afterwards it shows navigation; idle has none.
                val bubbles = compose.onAllNodesWithTag("cockpit-secondary-bubble")
                assertEquals(2, bubbles.fetchSemanticsNodes().size)
                val pills = compose.onAllNodesWithTag("cockpit-primary-pill")
                for (index in 0..1) {
                    val pill = pills[index].getBoundsInRoot()
                    val bubble = bubbles[index].assertIsDisplayed().getBoundsInRoot()
                    assertTrue("satellite $index sits beside its pill", pill.right <= bubble.left)
                }
            },
        )
    }

    /** A dark grey arrow on a transparent background, like the one Google Maps sends as its large icon. */
    private fun googleMapsDarkArrow(): Bitmap =
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            val arrow = android.graphics.Path().apply {
                moveTo(64f, 16f)
                lineTo(100f, 64f)
                lineTo(78f, 64f)
                lineTo(78f, 112f)
                lineTo(50f, 112f)
                lineTo(50f, 64f)
                lineTo(28f, 64f)
                close()
            }
            Canvas(this).drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3C4043.toInt() })
        }

    @Test
    fun v3GoogleMapsTowardsHeadlineShowsTimeAndDistanceWithAVisibleManeuverArrow() {
        val arrow = googleMapsDarkArrow()
        assertEquals(ManeuverIconStyle.WHITE_TINT, ManeuverIconContrast.classify(arrow))
        val nav = LiveNotificationParser.parse(
            NotificationSnapshot(
                key = "0|com.google.android.apps.maps|7|null|1020",
                packageName = LiveNotificationParser.PKG_MAPS,
                title = "towards Main University Rd",
                text = "38 min",
                subText = "20 km • 12:20 am",
                category = android.app.Notification.CATEGORY_NAVIGATION,
                isOngoing = true,
                updatedAtMs = FIXED_TIME_MS,
                maneuverIcon = arrow,
            ),
        ) as CockpitLiveActivity.MapsNavActivity
        val slot = v3Slot(listOf(nav))
        launchAndAssert(
            name = "cockpit-v3-maps-towards-headline",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Collapsed pill: time and distance, nothing dropped") { V3Pill(slot) }
                    V3Section("Expanded card: dark Maps arrow drawn white") { V3Card(slot) }
                }
            },
            verify = {
                val headline = "38 min · 20 km"
                compose.onNodeWithTag("cockpit-nav-distance", useUnmergedTree = true).assert(hasText(headline))
                compose.onNodeWithTag("cockpit-nav-card-distance", useUnmergedTree = true)
                    .assert(hasText(headline))
                    .assertIsDisplayed()
                assertEquals(
                    "the generic fallback must not appear",
                    0,
                    compose.onAllNodesWithText("Navigating", useUnmergedTree = true).fetchSemanticsNodes().size,
                )
                compose.onNodeWithText("Main University Rd").assertIsDisplayed()
                assertPillsClearTheStatusIcons(1)

                // The arrow's visibility is decided by the classification above (WHITE_TINT repaints it
                // white). Capturing the tile to pixels needs a redraw that never completes while the
                // test clock is paused, so the tile is only checked to be on screen.
                compose.onNodeWithTag("cockpit-nav-turn-icon", useUnmergedTree = true).assertIsDisplayed()
            },
        )
    }

    @androidx.compose.runtime.Composable
    private fun V3SetupHub(prefs: CockpitPrefsState) {
        CockpitSetupContent(
            prefs = prefs,
            telemetry = syntheticTelemetry,
            alarm = syntheticAlarm,
            clockTimeLabel = "11:15 PM",
            clockDateLabel = "Sat, Nov 21",
            freeStorageLabel = "118 GB free",
            batteryLabel = "84% batt",
            torchOn = false,
            hasOverlay = true,
            hasNotifications = true,
            hasBatteryExemption = true,
            hasAccessibilityOverlay = true,
            hasLocationPermission = true,
            hasUsageAccess = true,
            hasBluetoothPermission = true,
            hasDndAccess = true,
            onGrantAccessibilityOverlay = {},
            quickControls = syntheticQuickControls,
            launchableApps = syntheticPinned,
            pinnedApps = syntheticPinned,
            onBack = {},
            onToggleMaster = {},
            onToggleCategory = { _, _ -> },
            onCalibrationChange = { _, _, _ -> },
            onResetCalibration = {},
            onTogglePinnedApp = {},
            onGrantOverlay = {},
            onGrantNotifications = {},
            onGrantBattery = {},
            onToggleTorch = {},
            onToggleDrop = {},
        )
    }

    @Test
    fun v3HubSystemAlertsSectionOffersThreeSwitchesWithLedFlashOffByDefault() {
        launchAndAssert(
            name = "cockpit-v3-hub-system-alerts-section",
            directory = v3Directory,
            content = { V3SetupHub(CockpitPrefsState(masterEnabled = true)) },
            verify = {
                // Swipe the list up until the last switch is on screen. performScrollToNode waits for
                // idle, which never returns while the test clock is paused, so the list is swiped by hand.
                var swipes = 0
                while (swipes < 16 && runCatching {
                        compose.onNodeWithTag("cockpit-toggle-led-suppress").assertIsDisplayed()
                    }.isFailure
                ) {
                    compose.onNodeWithTag("cockpit-setup-list").performTouchInput {
                        swipe(
                            start = Offset(centerX, height * 0.8f),
                            end = Offset(centerX, height * 0.3f),
                            durationMillis = 200,
                        )
                    }
                    pump(8)
                    swipes += 1
                }
                compose.onNodeWithTag("cockpit-toggle-system-alerts").assertIsOn()
                compose.onNodeWithTag("cockpit-toggle-led-flash").assertIsOff()
                compose.onNodeWithTag("cockpit-toggle-led-suppress").assertIsOn()
                for (tag in listOf("cockpit-toggle-system-alerts", "cockpit-toggle-led-flash", "cockpit-toggle-led-suppress")) {
                    compose.onNodeWithTag(tag).assertHeightIsAtLeast(Tokens.TouchTarget)
                }
            },
        )
    }

    @Test
    fun v3HubChipsSimulateSystemAlertsOverThePreviewIsland() {
        // A taller phone keeps the island preview and both chip rows on screen together.
        RuntimeEnvironment.setQualifiers("w360dp-h1000dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            V3SetupHub(CockpitPrefsState(masterEnabled = true))
                        }
                    }
                }
            }
            pump(8)

            compose.onNodeWithTag("cockpit-preview-alert-chips").assertIsDisplayed()
            for (preview in com.drivelift.app.feature.cockpit.ui.CockpitPreviewAlert.entries) {
                compose.onNodeWithTag(preview.tag).assertExists()
            }

            compose.onNodeWithTag("cockpit-preview-alert-charging").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-system-alert-title", useUnmergedTree = true).assert(hasText("Fast charging"))
            compose.onNodeWithTag("cockpit-system-alert-detail", useUnmergedTree = true).assert(hasText("82%"))
            if (record) {
                java.io.File("build/outputs/screenshots/$v3Directory/").mkdirs()
                compose.onRoot().captureRoboImage(
                    "build/outputs/screenshots/$v3Directory/cockpit-v3-hub-system-alert-preview-standard.png",
                )
            }

            // A second chip replaces the first.
            compose.onNodeWithTag("cockpit-preview-alert-earbuds").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-system-alert-title", useUnmergedTree = true).assert(hasText("Pixel Buds Pro"))
            compose.onNodeWithTag("cockpit-system-alert-detail", useUnmergedTree = true).assert(hasText("76%"))

            compose.onNodeWithTag("cockpit-preview-alert-ringer").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-system-alert-title", useUnmergedTree = true).assert(hasText("Silent"))

            // The alert sits over a simulated activity, which moves to the satellite bubble.
            compose.onNodeWithTag("cockpit-preview-navigation").performClick()
            pump(18)
            compose.onNodeWithTag("cockpit-system-alert-title", useUnmergedTree = true).assert(hasText("Silent"))
            compose.onNodeWithTag("cockpit-secondary-bubble").assertIsDisplayed()

            // A ringing call takes the whole island, so the alert steps aside and returns afterwards.
            compose.onNodeWithTag("cockpit-preview-incoming-call").performClick()
            pump(24)
            assertEquals(
                0,
                compose.onAllNodesWithTag("cockpit-system-alert", useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            compose.onNodeWithTag("cockpit-preview-incoming-call").performClick()
            pump(24)
            compose.onNodeWithTag("cockpit-system-alert-title", useUnmergedTree = true).assert(hasText("Silent"))

            // Tapping the selected chip clears the alert.
            compose.onNodeWithTag("cockpit-preview-alert-ringer").performClick()
            pump(18)
            assertEquals(
                0,
                compose.onAllNodesWithTag("cockpit-system-alert", useUnmergedTree = true).fetchSemanticsNodes().size,
            )
        }
    }

    private val manyPinned by lazy {
        listOf(
            PinnedAppInfo("com.alfalah.mobile", "Alfa", iconBitmap = syntheticIconBitmap(0xFF34D399.toInt())),
            PinnedAppInfo("com.android.chrome", "Chrome", iconBitmap = syntheticIconBitmap(0xFF38BDF8.toInt())),
            PinnedAppInfo("com.google.android.apps.maps", "Maps", iconBitmap = syntheticIconBitmap(0xFFA596FF.toInt())),
            PinnedAppInfo("com.whatsapp", "WhatsApp", iconBitmap = syntheticIconBitmap(0xFF22D3EE.toInt())),
            PinnedAppInfo("com.google.android.apps.photos", "Photos", iconBitmap = syntheticIconBitmap(0xFFFBBF24.toInt())),
            PinnedAppInfo("com.google.android.deskclock", "Clock", iconBitmap = syntheticIconBitmap(0xFFFF6482.toInt())),
        )
    }

    @Test
    fun v3MasterWidgetShowsLargeClockLivePillStopTileCurrentAndPagedDock() {
        val draining = BatteryHudMetric(
            percent = 62,
            fraction = 0.62f,
            valueText = "62%",
            label = "BATT",
            isCharging = false,
            current = CockpitWidgetModel.formatBatteryCurrent(microAmps = -510_000L, isCharging = false),
            charge = CockpitWidgetModel.formatBatteryCharge(microAmpHours = 3_120_000L),
        )
        val charging = BatteryHudMetric(
            percent = 84,
            fraction = 0.84f,
            valueText = "84%",
            label = "CHRG",
            isCharging = true,
            current = CockpitWidgetModel.formatBatteryCurrent(microAmps = 2_450_000L, isCharging = true),
            charge = CockpitWidgetModel.formatBatteryCharge(microAmpHours = 4_210_000L),
        )
        val ram = CockpitHudBitmapRenderer.formatRamHudMetric(
            availBytes = (1.8 * 1024 * 1024 * 1024).toLong(),
            totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
        )
        launchAndAssert(
            name = "cockpit-v3-master-widget",
            directory = v3Directory,
            content = {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = Tokens.Medium, vertical = Tokens.Edge),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
                ) {
                    // Idle: READY pill, Drop tile, battery draining.
                    CockpitMasterWidgetCard(
                        clockTimeLabel = "05:34 PM",
                        clockDateLabel = "Sun \u00B7 Nov 22",
                        alarm = syntheticAlarm,
                        telemetry = DriveLiftTelemetrySummary(isDesktopDropRunning = false, isDropAvailable = true),
                        freeStorageLabel = "118 GB free",
                        batteryLabel = "62% batt",
                        torchOn = false,
                        islandOn = true,
                        pinnedApps = manyPinned,
                        batteryMetric = draining,
                        ramMetric = ram,
                    )
                    // Drop live: DROP LIVE pill, Stop tile, battery charging.
                    CockpitMasterWidgetCard(
                        clockTimeLabel = "05:34 PM",
                        clockDateLabel = "Sun \u00B7 Nov 22",
                        alarm = syntheticAlarm,
                        telemetry = syntheticTelemetry,
                        freeStorageLabel = "118 GB free",
                        batteryLabel = "84% batt",
                        torchOn = false,
                        islandOn = true,
                        pinnedApps = manyPinned,
                        batteryMetric = charging,
                        ramMetric = ram,
                    )
                }
            },
            verify = {
                compose.onAllNodesWithText("READY")[0].assertIsDisplayed()
                compose.onAllNodesWithText("DROP LIVE")[0].assertExists()
                compose.onAllNodesWithTag("cockpit-widget-toggle-drop")[0].assert(hasText("Drop"))
                compose.onAllNodesWithTag("cockpit-widget-toggle-drop")[1].assert(hasText("Stop"))
                compose.onAllNodesWithTag("cockpit-widget-battery-current")[0].assert(hasText("-510 mA"))
                compose.onAllNodesWithTag("cockpit-widget-battery-current")[1].assert(hasText("+2,450 mA"))

                // Nothing on the first card may leave it.
                val card = compose.onAllNodesWithTag("cockpit-master-widget-card")[0].getBoundsInRoot()
                for (tag in listOf(
                    "cockpit-widget-status-badge",
                    "cockpit-widget-battery-gauge",
                    "cockpit-widget-battery-current",
                    "cockpit-widget-ram-gauge",
                    "cockpit-widget-ram-used",
                    "cockpit-widget-ram-free",
                    "cockpit-widget-alarm-pill",
                    "cockpit-widget-pinned-row",
                )) {
                    val bounds = compose.onAllNodesWithTag(tag)[0].assertExists().getBoundsInRoot()
                    assertTrue("$tag clipped on the left", bounds.left >= card.left)
                    assertTrue("$tag clipped on the right", bounds.right <= card.right)
                    assertTrue("$tag clipped at the bottom", bounds.bottom <= card.bottom)
                }

                // Six pinned apps make two pages of four; the arrows are real 48dp targets.
                compose.onAllNodesWithTag("cockpit-widget-pinned-prev")[0]
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
                    .assertExists()
                compose.onAllNodesWithTag("cockpit-widget-pinned-next")[0]
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
                    .assertExists()
                compose.onAllNodesWithTag("cockpit-widget-pinned-dots")[0].assertExists()
                compose.onAllNodesWithText("Alfa")[0].assertExists()
                // Tapping needs the dock on screen, which only the standard layout guarantees.
                if (it == Variant.STANDARD) {
                    compose.onAllNodesWithTag("cockpit-widget-pinned-next")[0].performClick()
                    pump(6)
                    compose.onAllNodesWithText("Photos")[0].assertExists()
                    compose.onAllNodesWithTag("cockpit-widget-pinned-prev")[0].performClick()
                    pump(6)
                    compose.onAllNodesWithText("Alfa")[0].assertExists()
                }
            },
        )
    }

    @Test
    fun v3AccessibilityRowShowsNeedsReenableWhenTheServiceIsListedButNotConnected() {
        launchAndAssert(
            name = "cockpit-v3-accessibility-health",
            directory = v3Directory,
            content = {
                V3Frame {
                    GlassPanel(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            PermissionRow(
                                title = "Accessibility overlay: connected",
                                body = "Enabled in Settings and bound by Android.",
                                granted = true,
                                onGrant = {},
                                tag = "perm-connected",
                            )
                            PermissionRow(
                                title = "Accessibility overlay: suspended",
                                body = "Enabled in Settings but Android is not running it.",
                                granted = false,
                                onGrant = {},
                                tag = "perm-suspended",
                                needsReenable = true,
                            )
                            PermissionRow(
                                title = "Accessibility overlay: off",
                                body = "Not enabled in Settings.",
                                granted = false,
                                onGrant = {},
                                tag = "perm-off",
                            )
                        }
                    }
                }
            },
            verify = {
                compose.onNodeWithText("Granted").assertIsDisplayed()
                compose.onNodeWithText("Needs Re-enable").assertIsDisplayed()
                compose.onNodeWithTag("perm-suspended-needs-reenable").assertIsDisplayed()
                compose.onNodeWithTag("perm-suspended").assertHeightIsAtLeast(Tokens.TouchTarget).assertIsDisplayed()
                compose.onNodeWithTag("perm-off").assertHeightIsAtLeast(Tokens.TouchTarget)
                assertEquals(1, compose.onAllNodesWithText("Open Accessibility settings").fetchSemanticsNodes().size)
            },
        )
    }

    @Test
    fun v3IdlePillShowsNetworkOnTheLeftAndLiveSpeedOnTheRight() {
        val idle = CockpitSlotState()
        val wifi = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 12L * 1024L,
            downloadBytesPerSec = (1.4 * 1024 * 1024).toLong(),
        )
        val cellular = IdleNetworkSnapshot(
            kind = IdleNetworkKind.CELLULAR,
            label = "5G",
            uploadBytesPerSec = 640L * 1024L,
            downloadBytesPerSec = 18L * 1024L * 1024L,
        )
        val offline = IdleNetworkSnapshot(kind = IdleNetworkKind.OFFLINE, label = "Offline")
        launchAndAssert(
            name = "cockpit-v3-idle-pill",
            directory = v3Directory,
            content = {
                V3Frame {
                    V3Section("Wi-Fi, 12K up, 1.4M down") {
                        CompositionLocalProvider(LocalIdleNetworkOverride provides wifi) { V3Pill(idle) }
                    }
                    V3Section("Mobile data, 5G") {
                        CompositionLocalProvider(LocalIdleNetworkOverride provides cellular) { V3Pill(idle) }
                    }
                    V3Section("Offline") {
                        CompositionLocalProvider(LocalIdleNetworkOverride provides offline) { V3Pill(idle) }
                    }
                }
            },
            verify = {
                compose.onAllNodesWithText("Wi-Fi")[0].assertIsDisplayed()
                compose.onAllNodesWithText("5G")[0].assertIsDisplayed()
                compose.onAllNodesWithText("Offline")[0].assertIsDisplayed()
                compose.onAllNodesWithText("12K")[0].assertIsDisplayed()
                compose.onAllNodesWithText("1.4M")[0].assertIsDisplayed()
                compose.onAllNodesWithText("640K")[0].assertIsDisplayed()
                compose.onAllNodesWithText("18M")[0].assertIsDisplayed()
                assertEquals(0, compose.onAllNodesWithText("Cockpit").fetchSemanticsNodes().size)
                assertEquals(0, compose.onAllNodesWithText("Ready").fetchSemanticsNodes().size)

                // The connection stays left of the speeds, and both stay inside the pill.
                val pills = compose.onAllNodesWithTag("cockpit-primary-pill")
                for (index in 0..1) {
                    val pill = pills[index].assertIsDisplayed().getBoundsInRoot()
                    val net = compose.onAllNodesWithTag("cockpit-idle-network")[index].assertIsDisplayed().getBoundsInRoot()
                    val speed = compose.onAllNodesWithTag("cockpit-idle-speed")[index].assertIsDisplayed().getBoundsInRoot()
                    assertTrue("connection ends before the speeds start", net.right <= speed.left)
                    assertTrue("connection stays inside the pill", net.left >= pill.left)
                    assertTrue("speeds stay inside the pill", speed.right <= pill.right)
                }
            },
        )
    }

    @Test
    fun pr99IdlePillStackedSpeedsAndExpandedDesktopDropCard() {
        val idle = CockpitSlotState()
        val dropSlot = CockpitSlotState(
            primary = CockpitLiveActivity.TransferActivity(
                id = CockpitLiveActivity.ID_DESKTOP_DROP,
                title = "Desktop Drop",
                subtitle = "Waiting for transfer",
                speedText = "LAN ready",
                progressFraction = null,
                isDesktopDrop = true,
                canStop = true,
            ),
            isExpanded = false,
        )
        val wifiTraffic = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 12L * 1024L,
            downloadBytesPerSec = (1.4 * 1024 * 1024).toLong(),
        )
        val dropTelemetry = syntheticTelemetry.copy(
            desktopDropUrl = "http://192.168.1.42:8080",
            desktopDropPairingCode = "74-29",
            desktopDropUploadBytesPerSec = 24L * 1024L * 1024L,
            desktopDropDownloadBytesPerSec = 12L * 1024L * 1024L,
            desktopDropSentCount = 3,
            desktopDropReceivedCount = 2,
        )
        launchAndAssert(
            name = "cockpit-idle-stacked-speeds",
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Tokens.Medium, vertical = Tokens.Edge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
                ) {
                    CompositionLocalProvider(LocalIdleNetworkOverride provides wifiTraffic) {
                        CollapsedSplitIslandRow(
                            slotState = idle,
                            widthDeltaDp = 0,
                            cutoutWidthDp = 34,
                            onTapPrimary = {},
                            onTapSecondary = {},
                            onLongPressExpand = {},
                            onCycleNext = {},
                            onCyclePrevious = {},
                        )
                        CollapsedSplitIslandRow(
                            slotState = dropSlot,
                            widthDeltaDp = 0,
                            cutoutWidthDp = 34,
                            onTapPrimary = {},
                            onTapSecondary = {},
                            onLongPressExpand = {},
                            onCycleNext = {},
                            onCyclePrevious = {},
                        )
                        ExpandedCockpitCard(
                            slotState = dropSlot.copy(isExpanded = true),
                            telemetry = dropTelemetry,
                            torchState = TorchUiState(available = true, isOn = false),
                            quickControls = syntheticQuickControls,
                            pinnedApps = manyPinned,
                            onCollapse = {},
                            onCycleNext = {},
                            onCyclePrevious = {},
                            onSoftDismiss = {},
                            onRestoreDismissed = {},
                            onTapActivity = {},
                            onStopDesktopDrop = {},
                            onOpenDriveLiftCockpit = {},
                            onToggleInlineReply = {},
                            onSendWhatsAppReply = { _, _ -> },
                            onToggleTorch = {},
                            onTorchBrightnessChange = {},
                            onToggleTorchSos = {},
                            onLaunchPinnedApp = {},
                        )
                    }
                }
            },
            verify = {
                compose.onAllNodesWithText("12K")[0].assertIsDisplayed()
                compose.onAllNodesWithText("1.4M")[0].assertIsDisplayed()
                compose.onNodeWithTag("cockpit-expanded-info-strip").assertExists()
                compose.onNodeWithText("http://192.168.1.42:8080").assertExists()
                compose.onNodeWithText("PIN 74-29").assertExists()
                compose.onNodeWithText("Torch").assertExists()
            },
        )
    }

    private fun syntheticIconBitmap(argb: Int): Bitmap =
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawCircle(24f, 24f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb })
        }

    companion object {
        private const val FIXED_TIME_MS = 1_795_284_900_000L
    }
}
