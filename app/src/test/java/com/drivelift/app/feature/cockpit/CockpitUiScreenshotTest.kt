package com.drivelift.app.feature.cockpit

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.ui.CockpitMasterWidgetCard
import com.drivelift.app.feature.cockpit.ui.CockpitSetupContent
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.ExpandedCockpitCard
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
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
 * COMPACT (320dp) configurations, recording 12 screenshots under `build/outputs/screenshots/`.
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

    private val syntheticPinned = listOf(
        PinnedAppInfo("com.google.android.apps.maps", "Maps"),
        PinnedAppInfo("com.whatsapp", "WhatsApp"),
        PinnedAppInfo("com.google.android.deskclock", "Clock"),
    )

    private val syntheticTelemetry = DriveLiftTelemetrySummary(
        isDesktopDropRunning = true,
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
                compose.onNodeWithTag("cockpit-reply-input").assertExists()
                compose.onNodeWithTag("cockpit-torch-slider").assertExists()
                compose.onNodeWithTag("cockpit-torch-toggle").assertHeightIsAtLeast(Tokens.TouchTarget)
            },
        )
    }

    @Test
    fun homeScreenMasterWidgetPreview() {
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
                    )
                }
            },
            verify = {
                compose.onNodeWithTag("cockpit-master-widget-card").assertIsDisplayed()
                compose.onNodeWithText("11:15 PM").assertIsDisplayed()
                compose.onNodeWithText("Alarm 06:30 AM").assertExists()
                compose.onNodeWithText("Rings in 07:14:22").assertExists()
                compose.onNodeWithText("Drop 55.3 MB/s").assertExists()
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
                if (compose.onAllNodesWithTag("cockpit-perm-notifications").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("cockpit-perm-notifications").assertHeightIsAtLeast(Tokens.TouchTarget)
                }
            },
        )
    }

    companion object {
        private const val FIXED_TIME_MS = 1_795_284_900_000L
    }
}
