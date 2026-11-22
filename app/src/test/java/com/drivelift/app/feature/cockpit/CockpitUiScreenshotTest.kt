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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.BluetoothTileState
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsState
import com.drivelift.app.feature.cockpit.hardware.CockpitRingerMode
import com.drivelift.app.feature.cockpit.hardware.MobileDataTileState
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.hardware.RingerTileState
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.hardware.WifiTileState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.feature.cockpit.ui.CockpitMasterWidgetCard
import com.drivelift.app.feature.cockpit.ui.CockpitSetupContent
import com.drivelift.app.feature.cockpit.ui.CockpitTokens
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.DynamicIslandOverlay
import com.drivelift.app.feature.cockpit.ui.ExpandedCockpitCard
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
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
 * COMPACT (320dp) configurations, recording 12 screenshots under `build/outputs/screenshots/`, plus
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
    ) {
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
                        "build/outputs/screenshots/$name-${variant.name.lowercase()}.png",
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
                compose.onNodeWithText("Turn right").assertIsDisplayed()
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
                compose.onNodeWithText("Drop 55.3 MB/s").assertExists()
                compose.onNodeWithTag("cockpit-widget-battery-gauge").assertExists()
                compose.onNodeWithTag("cockpit-widget-ram-gauge").assertExists()
                compose.onNodeWithTag("cockpit-widget-toggle-drop").assertHeightIsAtLeast(CockpitTokens.HudControlHeight)
                compose.onNodeWithTag("cockpit-widget-toggle-torch").assertHeightIsAtLeast(CockpitTokens.HudControlHeight)
                compose.onNodeWithTag("cockpit-widget-toggle-island").assertHeightIsAtLeast(CockpitTokens.HudControlHeight)
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
                .assertHeightIsAtLeast(CockpitTokens.PillTouchTargetHeight)
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
            "DriveLift 1.0.0-preview.37 (build 36)",
            appVersionLabel(app, buildStamp = ""),
        )
        assertEquals(
            "DriveLift 1.0.0-preview.37 (build 36 \u00B7 PR #78 @ a1d8b31)",
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
                .assertHeightIsAtLeast(CockpitTokens.HudControlHeight)
                .assert(hasText("Drop"))
                .performClick()
            compose.onAllNodesWithTag("cockpit-widget-toggle-torch")[1]
                .assertHeightIsAtLeast(CockpitTokens.HudControlHeight)
                .assert(hasText("Torch"))
                .performClick()
            compose.onAllNodesWithTag("cockpit-widget-toggle-island")[1]
                .assertHeightIsAtLeast(CockpitTokens.HudControlHeight)
                .assert(hasText("Island"))
                .performClick()
            pump(8)

            compose.onNodeWithText("Drop 42.0 MB/s").assertIsDisplayed()
            compose.onAllNodesWithTag("cockpit-widget-toggle-drop")[1].assert(hasText("Drop On"))
            compose.onAllNodesWithTag("cockpit-widget-toggle-torch")[1].assert(hasText("Torch On"))
            compose.onAllNodesWithTag("cockpit-widget-toggle-island")[1].assert(hasText("Island On"))
        }
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
