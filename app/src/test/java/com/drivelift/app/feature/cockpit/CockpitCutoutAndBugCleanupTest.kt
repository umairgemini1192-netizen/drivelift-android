package com.drivelift.app.feature.cockpit

import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.CockpitWindowBoundsCoordinator
import com.drivelift.app.feature.cockpit.overlay.CutoutAnchorCalculator
import com.drivelift.app.feature.cockpit.ui.CockpitSetupContent
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.LocalIdleNetworkOverride
import com.drivelift.app.feature.cockpit.ui.computeSafePillMaxWidthDp
import com.drivelift.app.feature.cockpit.ui.formatTransferActivitySubtitle
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies PR #92 visible bug fixes:
 * - Dynamic Island centering strictly on WindowInsets.displayCutout bounds with zero hardcoded pixel offsets
 * - Status pill width cap preventing collisions with system status-bar icons
 * - Deduplicated TransferActivity status text (no "Ready (Ready)")
 * - Idle network pill hides "0K up / 0K down" when throughput is idle
 * - Cockpit Hub simulator buttons hidden when showSimulatorControls is false and plain accessibility copy
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitCutoutAndBugCleanupTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test
    fun cutoutAnchorCentersStrictlyOnWindowInsetsDisplayCutoutWithZeroHardcodedOffsets() {
        // Centered Pixel 8 Pro style cutout: display 1344px wide, cutout [622, 26, 722, 126], pill height 108px.
        // cutoutCenterX = 672 == displayWidth / 2 -> offsetX = 0.
        // cutoutCenterY = 76 -> topPx = 76 - 54 = 22.
        val centeredCutout = DisplayCutout(
            Insets.of(0, 126, 0, 0),
            null,
            Rect(622, 26, 722, 126),
            null,
            null,
        )
        val centeredInsets = WindowInsets.Builder().setDisplayCutout(centeredCutout).build()
        val centeredAnchor = CutoutAnchorCalculator.fromWindowInsets(
            windowInsets = centeredInsets,
            displayWidthPx = 1344,
            pillHeightPx = 108,
        )
        assertEquals(0, centeredAnchor.centerOffsetXPx)
        assertEquals(22, centeredAnchor.topPx)

        // Left-aligned punch-hole camera: [60, 20, 140, 100] on 1080px screen -> centerX=100 vs screenCenterX=540.
        val leftAnchor = CutoutAnchorCalculator.fromCutoutBounds(
            cutoutTopRect = Rect(60, 20, 140, 100),
            displayWidthPx = 1080,
            pillHeightPx = 72,
        )
        assertEquals(-440, leftAnchor.centerOffsetXPx)
        assertEquals(24, leftAnchor.topPx)

        // Right-aligned cutout: [900, 12, 980, 60] on 1080px screen -> centerX=940, centerY=36, pillHeight=108 -> clamp topPx=0.
        val rightAnchor = CutoutAnchorCalculator.fromCutoutBounds(
            cutoutTopRect = Rect(900, 12, 980, 60),
            displayWidthPx = 1080,
            pillHeightPx = 108,
        )
        assertEquals(400, rightAnchor.centerOffsetXPx)
        assertEquals(0, rightAnchor.topPx)

        // No cutout -> strictly (0, 0) with zero hardcoded pixel offsets.
        val noCutoutAnchor = CutoutAnchorCalculator.fromWindowInsets(
            windowInsets = WindowInsets.Builder().build(),
            displayWidthPx = 1080,
            pillHeightPx = 108,
        )
        assertEquals(0, noCutoutAnchor.centerOffsetXPx)
        assertEquals(0, noCutoutAnchor.topPx)

        // Bounds coordinator applies cutoutCenterOffsetXPx + user offsetX in collapsed mode.
        val coordinator = CockpitWindowBoundsCoordinator {}
        val params = coordinator.createBaseLayoutParams(
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            cutoutTopPx = leftAnchor.topPx,
            cutoutCenterOffsetXPx = leftAnchor.centerOffsetXPx,
        )
        assertEquals(-440, params.x)
        assertEquals(24, params.y)
    }

    @Test
    fun statusPillMaxWidthIsStrictlyCappedToAvoidStatusBarCollisions() {
        val hardCap = Tokens.Cockpit.PillHardMaxWidth.value.toInt()
        val satelliteReserve = (Tokens.Cockpit.BubbleSize + Tokens.Cockpit.SplitGap).value.toInt()

        // Even with +64dp user calibration delta on a wide phone, pill width never exceeds hardCap.
        val wideSingle = computeSafePillMaxWidthDp(screenWidthDp = 412, widthDeltaDp = 64, hasSatelliteBubble = false)
        assertTrue("Expected <= $hardCap but was $wideSingle", wideSingle <= hardCap)

        // With a satellite bubble, primary pill + gap + bubble still respects hardCap.
        val wideSplit = computeSafePillMaxWidthDp(screenWidthDp = 412, widthDeltaDp = 64, hasSatelliteBubble = true)
        assertTrue(
            "Combined split width ${wideSplit + satelliteReserve} must stay <= $hardCap",
            wideSplit + satelliteReserve <= hardCap,
        )

        // Compact 320dp phone keeps symmetric clearance on both sides for clock and battery icons.
        val compactSingle = computeSafePillMaxWidthDp(screenWidthDp = 320, widthDeltaDp = 0, hasSatelliteBubble = false)
        assertTrue("Compact pill $compactSingle leaves < 48dp per side", compactSingle <= 320 - 96)
    }

    @Test
    fun transferSubtitleDeduplicatesIdenticalReadyText() {
        assertEquals("Ready", formatTransferActivitySubtitle("Ready", "Ready"))
        assertEquals("Ready", formatTransferActivitySubtitle("Ready", "ready"))
        assertEquals("Drop listening (Live)", formatTransferActivitySubtitle("Drop listening", "Live"))
        assertEquals("Ready", formatTransferActivitySubtitle("Ready", ""))
        assertEquals("Live", formatTransferActivitySubtitle("", "Live"))
    }

    @Test
    fun idleNetworkSnapshotHidesZeroRatesWhenIdle() {
        val idleWifi = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 0L,
            downloadBytesPerSec = 512L,
        )
        assertFalse(idleWifi.hasUploadTraffic)
        assertFalse(idleWifi.hasDownloadTraffic)
        assertFalse(idleWifi.hasActiveTraffic)
        assertEquals("Wi-Fi", idleWifi.contentDescription)

        val downloadOnly = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 0L,
            downloadBytesPerSec = 24L * 1024L,
        )
        assertFalse(downloadOnly.hasUploadTraffic)
        assertTrue(downloadOnly.hasDownloadTraffic)
        assertTrue(downloadOnly.hasActiveTraffic)
        assertTrue(downloadOnly.contentDescription.contains("downloading"))
        assertFalse(downloadOnly.contentDescription.contains("uploading"))
    }

    @Test
    fun idleWifiPillDoesNotRenderZeroKRatesWhenIdle() {
        val idleWifi = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 0L,
            downloadBytesPerSec = 0L,
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalIdleNetworkOverride provides idleWifi) {
                                CollapsedSplitIslandRow(
                                    slotState = CockpitSlotState(),
                                    widthDeltaDp = 0,
                                    onTapPrimary = {},
                                    onTapSecondary = {},
                                    onLongPressExpand = {},
                                    onCycleNext = {},
                                    onCyclePrevious = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("cockpit-idle-network").assertIsDisplayed()
            compose.onNodeWithText("Wi-Fi").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTag("cockpit-idle-speed").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("0K", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun cockpitHubHidesSimulatorChipsInReleaseModeAndUsesPlainCopyWithoutDuplicateReady() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CockpitSetupContent(
                                prefs = CockpitPrefsState(masterEnabled = true),
                                telemetry = DriveLiftTelemetrySummary(
                                    isDesktopDropRunning = false,
                                    statusHeadline = "Ready",
                                ),
                                alarm = NextAlarmUiModel(
                                    hasAlarm = false,
                                    formattedTime = "",
                                    countdownMode = AlarmCountdownMode.NONE,
                                    chronometerBaseElapsedMs = null,
                                    countdownText = "",
                                    triggerAtMillis = null,
                                ),
                                clockTimeLabel = "11:15 PM",
                                clockDateLabel = "Sun \u00B7 Nov 22",
                                freeStorageLabel = "118 GB free",
                                batteryLabel = "84% batt",
                                torchOn = false,
                                hasOverlay = true,
                                hasNotifications = true,
                                hasBatteryExemption = true,
                                launchableApps = emptyList(),
                                pinnedApps = emptyList(),
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
                                showSimulatorControls = false,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            assertEquals(0, compose.onAllNodesWithTag("cockpit-preview-chips").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("Simulate a live activity").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("TYPE_ACCESSIBILITY_OVERLAY", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("Ready (Ready)", substring = true).fetchSemanticsNodes().size)
        }
    }
}
