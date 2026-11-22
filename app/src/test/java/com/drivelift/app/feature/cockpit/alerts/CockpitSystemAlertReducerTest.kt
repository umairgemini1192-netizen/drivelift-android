package com.drivelift.app.feature.cockpit.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitSystemAlertReducerTest {

    private class FakeClock(var nowMs: Long = 1_000L) {
        operator fun invoke(): Long = nowMs
        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    @Test
    fun batteryLowAndCriticalHysteresisPreventsSpamUntilReset() {
        val clock = FakeClock(1_000L)
        val reducer = CockpitSystemAlertReducer(clockMs = clock::invoke)

        // Normal discharging at 25% -> no alert
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 25, isCharging = false))
                .emittedAlert,
        )

        // Crossing <= 20% -> fires BatteryLow(isCritical = false) once
        val low1 = reducer.reduce(
            CockpitSystemAlertEvent.BatteryChanged(percent = 20, isCharging = false),
        ).emittedAlert as? CockpitSystemAlert.BatteryLow
        assertNotNull(low1)
        assertEquals(20, low1?.percent)
        assertFalse(low1?.isCritical ?: true)

        // Fluctuating 19% -> 22% -> 18% (below 23% reset) must NOT re-fire
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 19, isCharging = false))
                .emittedAlert,
        )
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 22, isCharging = false))
                .emittedAlert,
        )
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 18, isCharging = false))
                .emittedAlert,
        )

        // Crossing <= 15% -> fires BatteryLow(isCritical = true) once
        val critical1 = reducer.reduce(
            CockpitSystemAlertEvent.BatteryChanged(percent = 15, isCharging = false),
        ).emittedAlert as? CockpitSystemAlert.BatteryLow
        assertNotNull(critical1)
        assertEquals(15, critical1?.percent)
        assertTrue(critical1?.isCritical == true)

        // Staying <= 15% does not re-fire
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 14, isCharging = false))
                .emittedAlert,
        )

        // Rising >= 23% resets hysteresis; dropping back to 20% fires again
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 23, isCharging = false))
                .emittedAlert,
        )
        val low2 = reducer.reduce(
            CockpitSystemAlertEvent.BatteryChanged(percent = 20, isCharging = false),
        ).emittedAlert as? CockpitSystemAlert.BatteryLow
        assertNotNull(low2)
        assertFalse(low2?.isCritical ?: true)
    }

    @Test
    fun batteryChargingAndFullHysteresis() {
        val clock = FakeClock(2_000L)
        val reducer = CockpitSystemAlertReducer(clockMs = clock::invoke)

        // Initial sticky charging broadcast on boot is suppressed
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 60, isCharging = true))
                .emittedAlert,
        )

        // Unplug then plug back in -> fires BatteryCharging once
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 60, isCharging = false))
                .emittedAlert,
        )
        val charging = reducer.reduce(
            CockpitSystemAlertEvent.BatteryChanged(
                percent = 61,
                isCharging = true,
                isFastCharging = true,
                estimatedFullMinutes = 35L,
            ),
        ).emittedAlert as? CockpitSystemAlert.BatteryCharging
        assertNotNull(charging)
        assertEquals(61, charging?.percent)
        assertTrue(charging?.isFastCharging == true)
        assertEquals(35L, charging?.estimatedFullMinutes)

        // Subsequent charging updates do not re-fire BatteryCharging
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 80, isCharging = true))
                .emittedAlert,
        )

        // Reaching 100% fires BatteryFull once
        val full1 = reducer.reduce(
            CockpitSystemAlertEvent.BatteryChanged(percent = 100, isCharging = true, isStatusFull = true),
        ).emittedAlert as? CockpitSystemAlert.BatteryFull
        assertNotNull(full1)
        assertEquals(100, full1?.percent)

        // Trickle fluctuation 99% -> 100% while still plugged in does NOT re-fire BatteryFull
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 99, isCharging = true))
                .emittedAlert,
        )
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 100, isCharging = true))
                .emittedAlert,
        )

        // Dropping < 98% resets full flag, so reaching 100% again fires BatteryFull
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.BatteryChanged(percent = 97, isCharging = true))
                .emittedAlert,
        )
        val full2 = reducer.reduce(
            CockpitSystemAlertEvent.BatteryChanged(percent = 100, isCharging = true),
        ).emittedAlert as? CockpitSystemAlert.BatteryFull
        assertNotNull(full2)
    }

    @Test
    fun earbudsConnectAndLowBatteryHysteresis() {
        val clock = FakeClock(5_000L)
        val reducer = CockpitSystemAlertReducer(clockMs = clock::invoke)

        // First connect emits EarbudsConnected
        val connected = reducer.reduce(
            CockpitSystemAlertEvent.EarbudsConnectionChanged(
                deviceKey = "AA:BB:CC:DD",
                deviceName = "Pixel Buds Pro",
                isConnected = true,
                batteryPercent = 85,
            ),
        ).emittedAlert as? CockpitSystemAlert.EarbudsConnected
        assertNotNull(connected)
        assertEquals("Pixel Buds Pro", connected?.deviceName)
        assertEquals(85, connected?.batteryPercent)

        // Duplicate connect broadcast does not spam
        assertNull(
            reducer.reduce(
                CockpitSystemAlertEvent.EarbudsConnectionChanged(
                    deviceKey = "AA:BB:CC:DD",
                    deviceName = "Pixel Buds Pro",
                    isConnected = true,
                    batteryPercent = 85,
                ),
            ).emittedAlert,
        )

        // Drop <= 20% emits EarbudsLowBattery once
        val low = reducer.reduce(
            CockpitSystemAlertEvent.EarbudsBatteryChanged(
                deviceKey = "AA:BB:CC:DD",
                deviceName = "Pixel Buds Pro",
                batteryPercent = 18,
            ),
        ).emittedAlert as? CockpitSystemAlert.EarbudsLowBattery
        assertNotNull(low)
        assertEquals(18, low?.batteryPercent)

        // Staying <= 24% does not re-fire
        assertNull(
            reducer.reduce(
                CockpitSystemAlertEvent.EarbudsBatteryChanged(
                    deviceKey = "AA:BB:CC:DD",
                    deviceName = "Pixel Buds Pro",
                    batteryPercent = 22,
                ),
            ).emittedAlert,
        )

        // Recovering >= 25% resets; dropping <= 20% again fires
        assertNull(
            reducer.reduce(
                CockpitSystemAlertEvent.EarbudsBatteryChanged(
                    deviceKey = "AA:BB:CC:DD",
                    deviceName = "Pixel Buds Pro",
                    batteryPercent = 25,
                ),
            ).emittedAlert,
        )
        assertNotNull(
            reducer.reduce(
                CockpitSystemAlertEvent.EarbudsBatteryChanged(
                    deviceKey = "AA:BB:CC:DD",
                    deviceName = "Pixel Buds Pro",
                    batteryPercent = 19,
                ),
            ).emittedAlert as? CockpitSystemAlert.EarbudsLowBattery,
        )

        // Disconnect resets both connection and low-battery state
        assertNull(
            reducer.reduce(
                CockpitSystemAlertEvent.EarbudsConnectionChanged(
                    deviceKey = "AA:BB:CC:DD",
                    deviceName = "Pixel Buds Pro",
                    isConnected = false,
                ),
            ).emittedAlert,
        )
        assertNotNull(
            reducer.reduce(
                CockpitSystemAlertEvent.EarbudsConnectionChanged(
                    deviceKey = "AA:BB:CC:DD",
                    deviceName = "Pixel Buds Pro",
                    isConnected = true,
                    batteryPercent = 15,
                ),
            ).emittedAlert as? CockpitSystemAlert.EarbudsConnected,
        )
    }

    @Test
    fun stickyRingerAndAirplaneModesAreSuppressedOnStartup() {
        val clock = FakeClock(1_000L)
        val reducer = CockpitSystemAlertReducer(clockMs = clock::invoke)

        // Initial sticky broadcasts ignored
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.RingerModeChanged(CockpitRingerMode.NORMAL))
                .emittedAlert,
        )
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.AirplaneModeChanged(enabled = false))
                .emittedAlert,
        )

        // Real user changes emit
        val ringer = reducer.reduce(
            CockpitSystemAlertEvent.RingerModeChanged(CockpitRingerMode.VIBRATE),
        ).emittedAlert as? CockpitSystemAlert.RingerModeChanged
        assertEquals(CockpitRingerMode.VIBRATE, ringer?.mode)

        val airplane = reducer.reduce(
            CockpitSystemAlertEvent.AirplaneModeChanged(enabled = true),
        ).emittedAlert as? CockpitSystemAlert.AirplaneModeChanged
        assertEquals(true, airplane?.enabled)

        // Redundant same-value event ignored
        assertNull(
            reducer.reduce(CockpitSystemAlertEvent.RingerModeChanged(CockpitRingerMode.VIBRATE))
                .emittedAlert,
        )
    }

    @Test
    fun priorityPreemptionAndClockDrivenExpiry() {
        val clock = FakeClock(10_000L)
        val reducer = CockpitSystemAlertReducer(clockMs = clock::invoke)

        // Seed initial ringer mode, then emit LOW-priority RingerModeChanged (duration 2500ms)
        reducer.reduce(CockpitSystemAlertEvent.RingerModeChanged(CockpitRingerMode.NORMAL))
        val lowPriority = reducer.reduce(
            CockpitSystemAlertEvent.RingerModeChanged(CockpitRingerMode.SILENT),
        )
        assertTrue(lowPriority.activeAlert is CockpitSystemAlert.RingerModeChanged)
        assertEquals(12_500L, lowPriority.activeAlert?.expiresAtMs)

        // Advance 500ms and emit CRITICAL BiometricFeedback(ERROR) (duration 3500ms) -> preempts!
        clock.advanceBy(500L)
        val critical = reducer.reduce(
            CockpitSystemAlertEvent.BiometricFeedback(CockpitBiometricStage.ERROR),
        )
        assertTrue(critical.activeAlert is CockpitSystemAlert.BiometricFeedback)
        assertEquals(14_000L, critical.activeAlert?.expiresAtMs)

        // Advance past 14_000L -> critical expires and queued RingerModeChanged promotes
        clock.nowMs = 14_000L
        val promoted = reducer.expireIfNeeded()
        assertTrue(promoted is CockpitSystemAlert.RingerModeChanged)
        assertEquals(16_500L, promoted?.expiresAtMs)

        // Advance past 16_500L -> expires to null
        clock.nowMs = 16_500L
        assertNull(reducer.expireIfNeeded())
    }
}
