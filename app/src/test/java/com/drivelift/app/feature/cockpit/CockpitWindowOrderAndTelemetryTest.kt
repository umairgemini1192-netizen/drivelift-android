package com.drivelift.app.feature.cockpit

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitRingerMode
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.TelemetryHudSnapshot
import com.drivelift.app.feature.cockpit.overlay.CockpitWindowBoundsCoordinator
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CockpitWindowOrderAndTelemetryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun windowExpandsOnceAndShrinksOnlyAfterCollapseAnimationCompletes() {
        val applyLog = mutableListOf<Triple<Int, Int, Int>>()
        val coordinator = CockpitWindowBoundsCoordinator { params ->
            applyLog.add(Triple(params.width, params.height, params.blurBehindRadius))
        }
        val params = coordinator.createBaseLayoutParams(
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            cutoutTopPx = 96,
        )

        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(CockpitWindowBoundsCoordinator.WindowSpan.COLLAPSED_WRAP, coordinator.currentSpan)

        // Expand immediately promotes window ONCE to MATCH_PARENT x MATCH_PARENT at cutoutTopPx.
        val expandedChanged = coordinator.onStateChanged(
            params = params,
            isExpanded = true,
            inlineReplyOpen = false,
            cutoutTopPx = 96,
            offsetXPx = 12,
            offsetYPx = 8,
        )
        assertTrue(expandedChanged)
        assertEquals(1, applyLog.size)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(96, params.y)
        assertEquals(CockpitWindowBoundsCoordinator.WindowSpan.FULL_SCREEN_OVERLAY, coordinator.currentSpan)

        // No redundant layout updates during animation frames.
        assertFalse(
            coordinator.onStateChanged(
                params = params,
                isExpanded = true,
                inlineReplyOpen = false,
                cutoutTopPx = 96,
                offsetXPx = 12,
                offsetYPx = 8,
            ),
        )
        assertEquals(1, applyLog.size)

        // Requesting collapse MUST NOT shrink WindowManager bounds before the spring finishes.
        coordinator.onStateChanged(
            params = params,
            isExpanded = false,
            inlineReplyOpen = false,
            cutoutTopPx = 96,
            offsetXPx = 12,
            offsetYPx = 8,
        )
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(CockpitWindowBoundsCoordinator.WindowSpan.FULL_SCREEN_OVERLAY, coordinator.currentSpan)

        // Only after Compose collapse animation finishes does window shrink back to WRAP_CONTENT.
        val shrunk = coordinator.onCollapseAnimationFinished(
            params = params,
            cutoutTopPx = 96,
            offsetXPx = 12,
            offsetYPx = 8,
        )
        assertTrue(shrunk)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(0, params.blurBehindRadius)
        assertEquals(CockpitWindowBoundsCoordinator.WindowSpan.COLLAPSED_WRAP, coordinator.currentSpan)
    }

    @Test
    fun telemetryDeltaFilterRequiresMeaningfulBatteryChargingOrRamChange() {
        val base = TelemetryHudSnapshot(
            battery = BatteryHudMetric(percent = 72, fraction = 0.72f, valueText = "72%", isCharging = false),
            ram = CockpitHudBitmapRenderer.formatRamHudMetric(
                availBytes = (4.0 * 1024 * 1024 * 1024).toLong(),
                totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
            ),
            isLoadingInitial = false,
        )
        assertTrue(CockpitTelemetryMonitor.shouldPushWidgetUpdate(null, base))
        assertFalse(CockpitTelemetryMonitor.shouldPushWidgetUpdate(base, base.copy()))

        // Small RAM fluctuation (< 0.1 GB) does not wake widget.
        val tinyRamDrift = base.copy(
            ram = base.ram.copy(usedGb = base.ram.usedGb + 0.04),
        )
        assertFalse(CockpitTelemetryMonitor.shouldPushWidgetUpdate(base, tinyRamDrift))

        // RAM delta >= 0.1 GB triggers push.
        val realRamDelta = base.copy(
            ram = base.ram.copy(usedGb = base.ram.usedGb + 0.15),
        )
        assertTrue(CockpitTelemetryMonitor.shouldPushWidgetUpdate(base, realRamDelta))

        // Battery % or charging toggle triggers push immediately.
        assertTrue(
            CockpitTelemetryMonitor.shouldPushWidgetUpdate(
                base,
                base.copy(battery = base.battery.copy(percent = 71, valueText = "71%")),
            ),
        )
        assertTrue(
            CockpitTelemetryMonitor.shouldPushWidgetUpdate(
                base,
                base.copy(battery = base.battery.copy(isCharging = true, label = "CHRG")),
            ),
        )

        // Charge counter (mAh) delta >= 5 mAh triggers push; smaller drift does not.
        val withCharge = base.copy(
            battery = base.battery.copy(charge = CockpitWidgetModel.formatBatteryCharge(4_000_000L)),
        )
        assertFalse(
            CockpitTelemetryMonitor.shouldPushWidgetUpdate(
                withCharge,
                withCharge.copy(
                    battery = withCharge.battery.copy(
                        charge = CockpitWidgetModel.formatBatteryCharge(4_002_000L),
                    ),
                ),
            ),
        )
        assertTrue(
            CockpitTelemetryMonitor.shouldPushWidgetUpdate(
                withCharge,
                withCharge.copy(
                    battery = withCharge.battery.copy(
                        charge = CockpitWidgetModel.formatBatteryCharge(4_010_000L),
                    ),
                ),
            ),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun telemetryMonitorPausesTickerWhenScreenOffAndResumesOnScreenOn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var screenOn = true
        var currentRamUsed = 8.0
        var batteryPct = 80
        var charging = false
        var pushCount = 0

        val monitor = CockpitTelemetryMonitor(
            context = context,
            scope = scope,
            isScreenInteractive = { screenOn },
            snapshotReader = { intent ->
                val chargingFlag = CockpitTelemetryMonitor.extractBatteryChargingFromIntent(intent)
                if (chargingFlag != null) charging = chargingFlag
                val pctFromIntent = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, batteryPct) ?: batteryPct
                batteryPct = pctFromIntent
                TelemetryHudSnapshot(
                    battery = BatteryHudMetric(
                        percent = batteryPct,
                        fraction = batteryPct / 100f,
                        valueText = "$batteryPct%",
                        label = CockpitHudBitmapRenderer.batterySubLabel(batteryPct, charging),
                        isCharging = charging,
                    ),
                    ram = CockpitHudBitmapRenderer.formatRamHudMetric(
                        availBytes = ((12.0 - currentRamUsed) * 1024 * 1024 * 1024).toLong(),
                        totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
                    ),
                    isLoadingInitial = false,
                )
            },
            widgetPusher = { pushCount++ },
        )

        monitor.acquire()
        scope.runCurrent()
        assertEquals(1, pushCount)

        // 20s tick with unchanged values produces zero extra widget pushes.
        scope.advanceTimeBy(20_000L)
        scope.runCurrent()
        assertEquals(1, pushCount)

        // 20s tick while screen is on and RAM changed >= 0.1 GB pushes widget update.
        currentRamUsed = 8.4
        scope.advanceTimeBy(20_000L)
        scope.runCurrent()
        assertEquals(2, pushCount)

        // Screen turns off -> ticker stops even if RAM changes while asleep.
        screenOn = false
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_OFF))
        currentRamUsed = 9.5
        scope.advanceTimeBy(60_000L)
        scope.runCurrent()
        assertEquals(2, pushCount)

        // Power connected event wakes instant battery update even before ticker fires.
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_POWER_CONNECTED))
        scope.runCurrent()
        assertEquals(3, pushCount)

        // Screen on refreshes immediately & restarts ticker.
        screenOn = true
        batteryPct = 82
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_ON))
        scope.runCurrent()
        assertEquals(4, pushCount)

        monitor.release()
    }

    @Test
    fun ringerModeStateMachineMonthlyFormatterSsidAndBluetoothHelpersWorkCleanly() {
        assertEquals(CockpitRingerMode.VIBRATE, CockpitQuickControlsController.nextRingerMode(CockpitRingerMode.NORMAL))
        assertEquals(CockpitRingerMode.SILENT, CockpitQuickControlsController.nextRingerMode(CockpitRingerMode.VIBRATE))
        assertEquals(CockpitRingerMode.NORMAL, CockpitQuickControlsController.nextRingerMode(CockpitRingerMode.SILENT))

        assertEquals("0 MB / mo", CockpitQuickControlsController.formatMonthlyDataUsage(0L))
        assertEquals("420 MB / mo", CockpitQuickControlsController.formatMonthlyDataUsage(420L * 1024L * 1024L))
        val gbBytes = (14.2 * 1024.0 * 1024.0 * 1024.0).toLong()
        assertEquals("14.2 GB / mo", CockpitQuickControlsController.formatMonthlyDataUsage(gbBytes))

        assertEquals(
            "StormFiber-46B6-5G",
            CockpitQuickControlsController.sanitizeSsid("\"StormFiber-46B6-5G\"", true, true, true),
        )
        assertEquals(
            "Wi-Fi On",
            CockpitQuickControlsController.sanitizeSsid("<unknown ssid>", true, true, true),
        )
        assertEquals(
            "Wi-Fi On",
            CockpitQuickControlsController.sanitizeSsid("\"StormFiber\"", true, true, false),
        )
        assertEquals(
            "Wi-Fi Off",
            CockpitQuickControlsController.sanitizeSsid("\"StormFiber\"", false, false, true),
        )
        assertEquals(
            "Wi-Fi Off",
            CockpitQuickControlsController.sanitizeSsid("\"StormFiber\"", true, false, true),
        )

        assertEquals("2 connected", CockpitQuickControlsController.formatBluetoothStatus(true, true, 2))
        assertEquals("1 connected", CockpitQuickControlsController.formatBluetoothStatus(true, true, 1))
        assertEquals("On", CockpitQuickControlsController.formatBluetoothStatus(true, true, 0))
        assertEquals("Off", CockpitQuickControlsController.formatBluetoothStatus(false, true, 2))
        assertEquals("Grant access", CockpitQuickControlsController.formatBluetoothStatus(true, false, 1))
    }
}
