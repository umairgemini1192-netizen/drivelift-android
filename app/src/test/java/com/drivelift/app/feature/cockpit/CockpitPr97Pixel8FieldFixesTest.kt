package com.drivelift.app.feature.cockpit

import android.content.Intent
import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.hardware.CockpitNetworkFormat
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.TelemetryHudSnapshot
import com.drivelift.app.feature.cockpit.overlay.CockpitWindowBoundsCoordinator
import com.drivelift.app.feature.cockpit.overlay.CutoutAnchor
import com.drivelift.app.feature.cockpit.overlay.CutoutAnchorCalculator
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.IdlePillLayoutMath
import com.drivelift.app.feature.cockpit.ui.IdleSpeedDisplayMode
import com.drivelift.app.feature.cockpit.ui.LocalIdleNetworkOverride
import com.drivelift.app.feature.cockpit.widget.BatteryCurrentRollingAverage
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies PR #97 Pixel 8 Pro (Android API 37) field-test fixes:
 * 1. Dynamic Island idle pill splits into left/right zones around a fixed cutout gap so upload and
 *    download speeds never slide behind the camera punch-hole, falling back to the larger direction
 *    when both figures do not fit.
 * 2. Home widget battery current tracks Ampere: 2 s sampling while screen is interactive and widget
 *    is bound, 3-sample rolling average (~6 s), >= 10 mA and >= 1 mAh partial redraws with no
 *    sub-2 s debounce bottleneck, and zero sampling while screen is off.
 * 3. Lock-screen Dynamic Island re-centres immediately on screen-off -> screen-on / user-present
 *    cycles by forcing updateViewLayout() with cutoutCenterOffsetXPx + calibration offset and
 *    falling back to the last known non-empty cutout anchor when keyguard insets are empty.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitPr97Pixel8FieldFixesTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
    }

    // ------------------------------------------------------------------ 1. Idle pill cutout zones

    @Test
    fun idlePillLayoutMathComputesCutoutGapAndSymmetricZonesForZeroOneAndTwoSpeeds() {
        // Gap width = max(0, measuredCutout + delta) + 2 * Tokens.Small (8dp).
        assertEquals(50f, IdlePillLayoutMath.computeCenterGapDp(34f, 0f), 0.01f)
        assertEquals(62f, IdlePillLayoutMath.computeCenterGapDp(34f, 12f), 0.01f)
        assertEquals(16f, IdlePillLayoutMath.computeCenterGapDp(34f, -40f), 0.01f)
        assertEquals(16f, IdlePillLayoutMath.computeCenterGapDp(0f, 0f), 0.01f)

        // 0 speed figures on Pixel 8 Pro (200dp inner content, 34dp cutout -> 50dp gap, max half = 75dp).
        val zeroSpeed = IdlePillLayoutMath.compute(
            maxContentWidthDp = 200f,
            minContentWidthDp = 124f,
            measuredCutoutWidthDp = 34f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = false,
            hasDownloadTraffic = false,
        )
        assertEquals(50f, zeroSpeed.centerGapDp, 0.01f)
        assertEquals(zeroSpeed.leftZoneWidthDp, zeroSpeed.rightZoneWidthDp, 0.01f)
        assertEquals(IdleSpeedDisplayMode.NONE, zeroSpeed.speedDisplayMode)
        assertTrue(zeroSpeed.totalContentWidthDp <= 200.01f)

        // 1 speed figure fits cleanly inside the right zone without crossing the 50dp cutout gap.
        val oneSpeed = IdlePillLayoutMath.compute(
            maxContentWidthDp = 200f,
            minContentWidthDp = 124f,
            measuredCutoutWidthDp = 34f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = false,
            hasDownloadTraffic = true,
            downloadBytesPerSec = 2_000_000L,
        )
        assertEquals(50f, oneSpeed.centerGapDp, 0.01f)
        assertEquals(oneSpeed.leftZoneWidthDp, oneSpeed.rightZoneWidthDp, 0.01f)
        assertEquals(IdleSpeedDisplayMode.DOWNLOAD_ONLY, oneSpeed.speedDisplayMode)
        assertTrue(oneSpeed.rightZoneWidthDp >= IdlePillLayoutMath.singleSpeedFigureWidthDp())
        assertTrue(oneSpeed.totalContentWidthDp <= 200.01f)

        // 2 speed figures beside Pixel 8 Pro 34dp cutout (75dp right zone):
        // Stacked layout needs only single compact figure width, so BOTH speeds fit at 1.0x and 1.3x!
        val twoSpeedsAt1x = IdlePillLayoutMath.compute(
            maxContentWidthDp = 200f,
            minContentWidthDp = 124f,
            measuredCutoutWidthDp = 34f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = true,
            hasDownloadTraffic = true,
            uploadBytesPerSec = 120_000L,
            downloadBytesPerSec = 4_800_000L,
            fontScale = 1.0f,
        )
        assertEquals(50f, twoSpeedsAt1x.centerGapDp, 0.01f)
        assertEquals(twoSpeedsAt1x.leftZoneWidthDp, twoSpeedsAt1x.rightZoneWidthDp, 0.01f)
        assertEquals(IdleSpeedDisplayMode.BOTH, twoSpeedsAt1x.speedDisplayMode)
        assertTrue(twoSpeedsAt1x.isStacked)
        assertTrue(twoSpeedsAt1x.totalContentWidthDp <= 200.01f)

        val twoSpeedsAt1_3x = IdlePillLayoutMath.compute(
            maxContentWidthDp = 200f,
            minContentWidthDp = 124f,
            measuredCutoutWidthDp = 34f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = true,
            hasDownloadTraffic = true,
            uploadBytesPerSec = 120_000L,
            downloadBytesPerSec = 4_800_000L,
            fontScale = 1.3f,
        )
        assertEquals(50f, twoSpeedsAt1_3x.centerGapDp, 0.01f)
        assertEquals(twoSpeedsAt1_3x.leftZoneWidthDp, twoSpeedsAt1_3x.rightZoneWidthDp, 0.01f)
        assertEquals(IdleSpeedDisplayMode.BOTH, twoSpeedsAt1_3x.speedDisplayMode)
        assertTrue(twoSpeedsAt1_3x.isStacked)
        assertTrue(twoSpeedsAt1_3x.totalContentWidthDp <= 200.01f)

        // At extreme font scale (2.0x), two stacked lines would exceed PillHeight vertically, so it
        // degrades cleanly to the larger single direction rather than overlapping, and drops the
        // connection text label first while keeping speeds visible (never NONE while traffic exists).
        val extremeDownloadLarger = IdlePillLayoutMath.compute(
            maxContentWidthDp = 200f,
            minContentWidthDp = 124f,
            measuredCutoutWidthDp = 34f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = true,
            hasDownloadTraffic = true,
            uploadBytesPerSec = 120_000L,
            downloadBytesPerSec = 4_800_000L,
            fontScale = 2.0f,
        )
        assertEquals(50f, extremeDownloadLarger.centerGapDp, 0.01f)
        assertEquals(extremeDownloadLarger.leftZoneWidthDp, extremeDownloadLarger.rightZoneWidthDp, 0.01f)
        assertEquals(IdleSpeedDisplayMode.DOWNLOAD_ONLY, extremeDownloadLarger.speedDisplayMode)
        assertFalse(extremeDownloadLarger.isStacked)
        assertFalse(extremeDownloadLarger.showConnectionLabel)
        assertTrue(extremeDownloadLarger.showConnectionIcon)

        val extremeUploadLarger = IdlePillLayoutMath.compute(
            maxContentWidthDp = 200f,
            minContentWidthDp = 124f,
            measuredCutoutWidthDp = 34f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = true,
            hasDownloadTraffic = true,
            uploadBytesPerSec = 9_500_000L,
            downloadBytesPerSec = 320_000L,
            fontScale = 2.0f,
        )
        assertEquals(IdleSpeedDisplayMode.UPLOAD_ONLY, extremeUploadLarger.speedDisplayMode)

        // Even under an extreme cutout where space is severely constrained, speeds NEVER return NONE
        // while traffic exists and the connection label is dropped before the icon.
        val tightCutout = IdlePillLayoutMath.compute(
            maxContentWidthDp = 160f,
            minContentWidthDp = 124f,
            measuredCutoutWidthDp = 80f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = true,
            hasDownloadTraffic = true,
            uploadBytesPerSec = 120_000L,
            downloadBytesPerSec = 4_800_000L,
            fontScale = 1.5f,
        )
        assertTrue(tightCutout.speedDisplayMode != IdleSpeedDisplayMode.NONE)
        assertFalse(tightCutout.showConnectionLabel)
        assertTrue(tightCutout.showConnectionIcon)
    }

    @Test
    fun compactNetworkRateTextNeverExceedsFourCharactersAcrossTypicalRange() {
        val sampleRates = listOf(
            0L,
            1_024L,
            12L * 1_024L,
            999L * 1_024L,
            1_000L * 1_024L,
            (1.4 * 1_024 * 1_024).toLong(),
            (9.9 * 1_024 * 1_024).toLong(),
            10L * 1_024L * 1_024L,
            999L * 1_024L * 1_024L,
        )
        for (rate in sampleRates) {
            val formatted = CockpitNetworkFormat.formatRate(rate)
            assertTrue(
                "Formatted rate '$formatted' for $rate B/s exceeded ${IdlePillLayoutMath.MAX_RATE_CHARS} chars",
                formatted.length <= IdlePillLayoutMath.MAX_RATE_CHARS,
            )
        }
    }

    @Test
    fun idlePillWithPixel8CutoutKeepsLabelAndSpeedOutsideCutoutGapAndShowsLargerDirection() {
        val traffic = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 12L * 1024L,
            downloadBytesPerSec = (1.4 * 1024 * 1024).toLong(),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalIdleNetworkOverride provides traffic) {
                                CollapsedSplitIslandRow(
                                    slotState = CockpitSlotState(),
                                    widthDeltaDp = 0,
                                    cutoutWidthDp = 34,
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

            val netBounds = compose.onNodeWithTag("cockpit-idle-network").assertIsDisplayed().getBoundsInRoot()
            val gapBounds = compose.onNodeWithTag("cockpit-idle-cutout-gap", useUnmergedTree = true).getBoundsInRoot()
            val speedBounds = compose.onNodeWithTag("cockpit-idle-speed").assertIsDisplayed().getBoundsInRoot()

            assertTrue(
                "Cutout gap must be at least 50dp (34 + 2*8), was ${gapBounds.right - gapBounds.left}",
                (gapBounds.right - gapBounds.left).value >= 49.5f,
            )
            assertTrue(
                "Connection label must end before cutout gap (net.right=${netBounds.right}, gap.left=${gapBounds.left})",
                netBounds.right <= gapBounds.left,
            )
            assertTrue(
                "Speed readout must start after cutout gap (speed.left=${speedBounds.left}, gap.right=${gapBounds.right})",
                speedBounds.left >= gapBounds.right,
            )

            // Both upload (12K) and download (1.4M) render stacked in the right zone beside the 34dp cutout.
            compose.onNodeWithText("12K").assertIsDisplayed()
            compose.onNodeWithText("1.4M").assertIsDisplayed()
        }
    }

    @Test
    fun idlePillRealPixel8ProGeometryShowsBothStackedSpeedsAt1xAnd1_3xAndLargerAt2x() {
        val traffic = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 12L * 1024L,
            downloadBytesPerSec = (1.4 * 1024 * 1024).toLong(),
        )
        for (scale in listOf(1.0f, 1.3f, 2.0f)) {
            RuntimeEnvironment.setQualifiers("w412dp-h915dp-xhdpi")
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                CompositionLocalProvider(LocalIdleNetworkOverride provides traffic) {
                                    CollapsedSplitIslandRow(
                                        slotState = CockpitSlotState(),
                                        widthDeltaDp = 0,
                                        cutoutWidthDp = 34,
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

                val netBounds = compose.onNodeWithTag("cockpit-idle-network").assertIsDisplayed().getBoundsInRoot()
                val gapBounds = compose.onNodeWithTag("cockpit-idle-cutout-gap", useUnmergedTree = true).getBoundsInRoot()
                val speedBounds = compose.onNodeWithTag("cockpit-idle-speed").assertIsDisplayed().getBoundsInRoot()

                assertTrue("Left zone must stay clear of cutout at ${scale}x", netBounds.right <= gapBounds.left)
                assertTrue("Speeds must stay clear of cutout at ${scale}x", speedBounds.left >= gapBounds.right)

                // Larger speed (1.4M) is ALWAYS visible across 1.0x, 1.3x, and 2.0x.
                compose.onNodeWithText("1.4M").assertIsDisplayed()
                if (scale <= 1.3f) {
                    // Upload (12K) is also visible (stacked above 1.4M) at 1.0x and 1.3x.
                    compose.onNodeWithText("12K").assertIsDisplayed()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 2. Widget 2s mA tracking

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun widgetBatteryCurrentSamplesEveryTwoSecondsWithTenMaAndOneMahThresholdsAndStopsScreenOff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var screenOn = true
        var nowMs = 1_000L
        var rawCurrentUa = -500_000L
        var chargeUah = 4_000_000L
        var pushCount = 0
        val averager = BatteryCurrentRollingAverage()
        val fixedRam = CockpitHudBitmapRenderer.formatRamHudMetric(
            availBytes = (4.0 * 1024 * 1024 * 1024).toLong(),
            totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
        )

        val monitor = CockpitTelemetryMonitor(
            context = app,
            scope = scope,
            isScreenInteractive = { screenOn },
            snapshotReader = {
                TelemetryHudSnapshot(
                    battery = BatteryHudMetric(
                        percent = 80,
                        fraction = 0.80f,
                        valueText = "80%",
                        isCharging = false,
                        current = averager.record(currentNowMicroAmps = rawCurrentUa, isCharging = false),
                        charge = CockpitWidgetModel.formatBatteryCharge(chargeUah),
                    ),
                    ram = fixedRam,
                    isLoadingInitial = false,
                )
            },
            widgetPusher = { pushCount++ },
            elapsedClock = { nowMs },
        )

        // Binding a widget starts the 2s screen-on ticker and pushes the initial snapshot.
        monitor.setWidgetBound(true)
        scope.runCurrent()
        assertTrue(monitor.isTelemetryTickerActive)
        assertEquals(1, pushCount)

        // 2s later: tiny < 10 mA drift does not redraw.
        nowMs += 2_000L
        rawCurrentUa = -506_000L
        scope.advanceTimeBy(2_000L)
        scope.runCurrent()
        assertEquals(1, pushCount)

        // 2s later: >= 10 mA change pushes immediately (no 5s debounce blocking 2s updates).
        nowMs += 2_000L
        rawCurrentUa = -650_000L
        scope.advanceTimeBy(2_000L)
        scope.runCurrent()
        assertEquals(2, pushCount)

        // Another 2s later: next >= 10 mA change pushes again without waiting 5s.
        nowMs += 2_000L
        rawCurrentUa = -850_000L
        scope.advanceTimeBy(2_000L)
        scope.runCurrent()
        assertEquals(3, pushCount)

        // Another 2s later: >= 1 mAh charge counter change alone triggers a redraw.
        nowMs += 2_000L
        chargeUah = 3_999_000L
        scope.advanceTimeBy(2_000L)
        scope.runCurrent()
        assertEquals(4, pushCount)

        // Verify partial battery-current-only classification and lightweight RemoteViews builder.
        val baseSnap = monitor.snapshot.value
        val maOnlySnap = baseSnap.copy(
            battery = baseSnap.battery.copy(
                current = CockpitWidgetModel.formatBatteryCurrentMilliAmps(950L, isCharging = false),
            ),
        )
        assertTrue(CockpitTelemetryMonitor.isBatteryCurrentOnlyChange(baseSnap, maOnlySnap))
        assertNotNull(CockpitGlanceWidget.buildBatteryCurrentPartialRemoteViews(app, maOnlySnap.battery))

        // Screen off stops sampling completely; even huge current/charge changes cause 0 pushes.
        screenOn = false
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_OFF))
        scope.runCurrent()
        assertFalse(monitor.isTelemetryTickerActive)

        nowMs += 30_000L
        rawCurrentUa = -1_800_000L
        chargeUah = 3_900_000L
        scope.advanceTimeBy(30_000L)
        scope.runCurrent()
        assertEquals(4, pushCount)

        // Screen on resumes 2s sampling and refreshes immediately.
        screenOn = true
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_ON))
        scope.runCurrent()
        assertTrue(monitor.isTelemetryTickerActive)
        assertEquals(5, pushCount)

        // Unbinding the last widget stops the telemetry ticker cleanly.
        monitor.setWidgetBound(false)
        scope.runCurrent()
        assertFalse(monitor.isTelemetryTickerActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun batteryCurrentOnlyUpdatesPreserveRenderedRamBaselineUntilCumulativeDeltaTriggersFullPush() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var nowMs = 1_000L
        var usedRamGb = 8.00
        var currentMagnitudeMa = 500L
        var pushCount = 0

        val monitor = CockpitTelemetryMonitor(
            context = app,
            scope = scope,
            isScreenInteractive = { true },
            snapshotReader = {
                TelemetryHudSnapshot(
                    battery = BatteryHudMetric(
                        percent = 80,
                        fraction = 0.80f,
                        valueText = "80%",
                        isCharging = false,
                        current = CockpitWidgetModel.formatBatteryCurrentMilliAmps(
                            magnitudeMa = currentMagnitudeMa,
                            isCharging = false,
                        ),
                        charge = CockpitWidgetModel.formatBatteryCharge(4_000_000L),
                    ),
                    ram = CockpitHudBitmapRenderer.formatRamHudMetric(
                        availBytes = ((12.0 - usedRamGb) * 1024 * 1024 * 1024).toLong(),
                        totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
                    ),
                    isLoadingInitial = false,
                )
            },
            widgetPusher = { pushCount++ },
            elapsedClock = { nowMs },
        )

        // Initial push renders full telemetry (RAM = 8.00 GB).
        assertTrue(monitor.evaluateNow(pushWidget = true, bypassDebounce = true))
        scope.runCurrent()
        assertEquals(1, pushCount)
        assertFalse(monitor.lastPushWasBatteryCurrentOnly)

        // Tick 1: +0.06 GB RAM (< 0.1 GB) alongside -50 mA current change uses current-only partial push
        // and preserves the 8.00 GB last-rendered RAM baseline.
        nowMs += 2_000L
        usedRamGb = 8.06
        currentMagnitudeMa = 550L
        assertTrue(monitor.evaluateNow(pushWidget = true, bypassDebounce = true))
        scope.runCurrent()
        assertEquals(2, pushCount)
        assertTrue(monitor.lastPushWasBatteryCurrentOnly)

        // Tick 2: another +0.06 GB RAM (8.12 GB total, +0.12 GB vs last rendered 8.00 GB) alongside
        // -50 mA current change escalates to a full telemetry update.
        nowMs += 2_000L
        usedRamGb = 8.12
        currentMagnitudeMa = 600L
        assertTrue(monitor.evaluateNow(pushWidget = true, bypassDebounce = true))
        scope.runCurrent()
        assertEquals(3, pushCount)
        assertFalse(monitor.lastPushWasBatteryCurrentOnly)
    }

    // ------------------------------------------------------------------ 3. Lock-screen relayout

    @Test
    fun boundsCoordinatorAlwaysForcesRelayoutAfterScreenOffOnCycleWithCutoutAndCalibrationOffset() {
        val applied = mutableListOf<Pair<Int, Int>>()
        val coordinator = CockpitWindowBoundsCoordinator { params ->
            applied += params.x to params.y
        }

        val cutoutCenterOffsetXPx = -12
        val calibrationOffsetXPx = 6
        val cutoutTopPx = 22
        val calibrationOffsetYPx = 4
        val expectedX = cutoutCenterOffsetXPx + calibrationOffsetXPx
        val expectedY = cutoutTopPx + calibrationOffsetYPx

        val params = coordinator.createBaseLayoutParams(
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            cutoutTopPx = cutoutTopPx,
            cutoutCenterOffsetXPx = cutoutCenterOffsetXPx,
        )

        // Initial apply sets x = cutoutCenterOffsetXPx + calibration offset.
        assertTrue(
            coordinator.onStateChanged(
                params = params,
                isExpanded = false,
                inlineReplyOpen = false,
                cutoutTopPx = cutoutTopPx,
                offsetXPx = calibrationOffsetXPx,
                offsetYPx = calibrationOffsetYPx,
                cutoutCenterOffsetXPx = cutoutCenterOffsetXPx,
            ),
        )
        assertEquals(1, applied.size)
        assertEquals(expectedX, params.x)
        assertEquals(expectedY, params.y)

        // Identical state while screen stays on is deduplicated.
        assertFalse(
            coordinator.onStateChanged(
                params = params,
                isExpanded = false,
                inlineReplyOpen = false,
                cutoutTopPx = cutoutTopPx,
                offsetXPx = calibrationOffsetXPx,
                offsetYPx = calibrationOffsetYPx,
                cutoutCenterOffsetXPx = cutoutCenterOffsetXPx,
            ),
        )
        assertEquals(1, applied.size)

        // Screen-off -> screen-on cycle MUST force updateViewLayout() even when signature inputs match.
        coordinator.onScreenOff()
        val relaidOutOnLockScreen = coordinator.onScreenOn(
            params = params,
            isExpanded = false,
            inlineReplyOpen = false,
            cutoutTopPx = cutoutTopPx,
            offsetXPx = calibrationOffsetXPx,
            offsetYPx = calibrationOffsetYPx,
            cutoutCenterOffsetXPx = cutoutCenterOffsetXPx,
        )
        assertTrue("Relayout must always be issued after screen-off -> screen-on", relaidOutOnLockScreen)
        assertEquals(2, applied.size)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(expectedX, params.x)
        assertEquals(expectedY, params.y)

        // Subsequent ACTION_USER_PRESENT unlock also forces a fresh relayout to settle post-keyguard insets.
        val relaidOutOnUnlock = coordinator.onScreenOn(
            params = params,
            isExpanded = false,
            inlineReplyOpen = false,
            cutoutTopPx = cutoutTopPx,
            offsetXPx = calibrationOffsetXPx,
            offsetYPx = calibrationOffsetYPx,
            cutoutCenterOffsetXPx = cutoutCenterOffsetXPx,
        )
        assertTrue(relaidOutOnUnlock)
        assertEquals(3, applied.size)
        assertEquals(expectedX, params.x)
        assertEquals(expectedY, params.y)
    }

    @Test
    fun cutoutAnchorCapturesMeasuredWidthAndFallsBackToLastKnownNonEmptyOnLockScreen() {
        val pixel8Cutout = DisplayCutout(
            Insets.of(0, 126, 0, 0),
            null,
            Rect(622, 26, 722, 126),
            null,
            null,
        )
        val insets = WindowInsets.Builder().setDisplayCutout(pixel8Cutout).build()
        val measured = CutoutAnchorCalculator.fromWindowInsets(
            windowInsets = insets,
            displayWidthPx = 1344,
            pillHeightPx = 108,
        )
        assertTrue(measured.hasCutout)
        assertEquals(0, measured.centerOffsetXPx)
        assertEquals(22, measured.topPx)
        assertEquals(100, measured.cutoutWidthPx)

        // Empty lock-screen / keyguard insets fall back to the last known non-empty cutout anchor.
        val emptyLockScreenAnchor = CutoutAnchorCalculator.fromWindowInsets(
            windowInsets = WindowInsets.Builder().build(),
            displayWidthPx = 1344,
            pillHeightPx = 108,
        )
        assertFalse(emptyLockScreenAnchor.hasCutout)
        assertEquals(0, emptyLockScreenAnchor.cutoutWidthPx)

        val resolved = CutoutAnchorCalculator.resolveWithFallback(
            current = emptyLockScreenAnchor,
            lastKnownNonEmpty = measured,
        )
        assertEquals(measured, resolved)

        // When no prior cutout was ever known, empty stays empty.
        assertEquals(
            CutoutAnchor(),
            CutoutAnchorCalculator.resolveWithFallback(emptyLockScreenAnchor, null),
        )
    }

    @Test
    fun viewRelativeCutoutInsetsTranslateBackToDisplaySpaceWithoutHorizontalDrift() {
        // Real display cutout on Pixel 8 Pro (1344px wide) is Rect(622, 26, 722, 126).
        // For a centered WRAP_CONTENT overlay window located at (447, 22), Android delivers
        // cutout bounds offset by (-447, -22) -> Rect(175, 4, 275, 104).
        val windowRelativeCutout = DisplayCutout(
            Insets.of(0, 104, 0, 0),
            null,
            Rect(175, 4, 275, 104),
            null,
            null,
        )
        val viewInsets = WindowInsets.Builder().setDisplayCutout(windowRelativeCutout).build()

        val translated = CutoutAnchorCalculator.fromViewWindowInsets(
            windowInsets = viewInsets,
            windowLeftPx = 447,
            windowTopPx = 22,
            displayWidthPx = 1344,
            pillHeightPx = 108,
        )
        assertTrue(translated.hasCutout)
        assertEquals(0, translated.centerOffsetXPx)
        assertEquals(22, translated.topPx)
        assertEquals(100, translated.cutoutWidthPx)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun screenWakeCountIncrementsOnBothScreenOnAndUserPresent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val monitor = CockpitTelemetryMonitor(
            context = app,
            scope = scope,
            isScreenInteractive = { true },
            snapshotReader = { TelemetryHudSnapshot(isLoadingInitial = false) },
            widgetPusher = {},
        )

        assertEquals(0L, monitor.screenWakeCount.value)
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_OFF))
        assertFalse(monitor.isScreenOn.value)
        assertEquals(0L, monitor.screenWakeCount.value)

        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_ON))
        assertTrue(monitor.isScreenOn.value)
        assertEquals(1L, monitor.screenWakeCount.value)

        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_USER_PRESENT))
        assertTrue(monitor.isScreenOn.value)
        assertEquals(2L, monitor.screenWakeCount.value)
    }
}
