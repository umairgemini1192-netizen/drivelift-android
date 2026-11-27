package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.alerts.CockpitBiometricStage
import com.drivelift.app.feature.cockpit.alerts.CockpitRingerMode
import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlert
import com.drivelift.app.feature.cockpit.ui.CockpitPreviewAlert
import com.drivelift.app.feature.cockpit.ui.CockpitPreviewAlerts
import com.drivelift.app.feature.cockpit.ui.presentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitSystemAlertPresentationTest {

    private val emDash = '—'
    private val enDash = '–'

    @Test
    fun everyAlertCopyIsPlainSpecificAndFreeOfDashesAndBlanks() {
        val alerts = CockpitPreviewAlert.entries.map { CockpitPreviewAlerts.alert(it) } + listOf(
            CockpitSystemAlert.BatteryLow(percent = 5, isCritical = true),
            CockpitSystemAlert.RingerModeChanged(CockpitRingerMode.VIBRATE),
            CockpitSystemAlert.RingerModeChanged(CockpitRingerMode.NORMAL),
            CockpitSystemAlert.AirplaneModeChanged(enabled = false),
            CockpitSystemAlert.BiometricFeedback(CockpitBiometricStage.SUCCESS),
            CockpitSystemAlert.BiometricFeedback(CockpitBiometricStage.ERROR),
            CockpitSystemAlert.BiometricFeedback(CockpitBiometricStage.DEVICE_UNLOCKED),
            CockpitSystemAlert.DriveLiftTransfer("IMG_1.jpg", progressPercent = 40, isCompleted = false),
        )
        for (alert in alerts) {
            val text = alert.presentation()
            assertTrue("title for $alert", text.title.isNotBlank())
            assertTrue("description for $alert", text.description.isNotBlank())
            val all = text.title + text.detail + text.description
            assertFalse("no em dash in $alert", all.contains(emDash))
            assertFalse("no en dash in $alert", all.contains(enDash))
        }
    }

    @Test
    fun batteryAlertsNameTheStateAndThePercentage() {
        val charging = CockpitSystemAlert.BatteryCharging(82, isFastCharging = true, estimatedFullMinutes = 40L)
        assertEquals("Fast charging", charging.presentation().title)
        assertEquals("82%", charging.presentation().detail)
        assertTrue(charging.presentation().description.contains("full in 40 minutes"))

        assertEquals("Charging", CockpitSystemAlert.BatteryCharging(30, false, null).presentation().title)
        assertEquals("Battery full", CockpitSystemAlert.BatteryFull(100).presentation().title)
        assertEquals("Battery low", CockpitSystemAlert.BatteryLow(15, isCritical = false).presentation().title)
        assertEquals("Battery critical", CockpitSystemAlert.BatteryLow(4, isCritical = true).presentation().title)
    }

    @Test
    fun earbudsAlertsShowTheDeviceNameAndBatteryOrConnectedState() {
        val connected = CockpitSystemAlert.EarbudsConnected("Pixel Buds Pro", batteryPercent = 76)
        assertEquals("Pixel Buds Pro", connected.presentation().title)
        assertEquals("76%", connected.presentation().detail)

        val noReading = CockpitSystemAlert.EarbudsConnected("Pixel Buds Pro", batteryPercent = null)
        assertEquals("Connected", noReading.presentation().detail)

        val low = CockpitSystemAlert.EarbudsLowBattery("Pixel Buds Pro", batteryPercent = 12)
        assertEquals("12% low", low.presentation().detail)
        assertEquals("Earbuds", CockpitSystemAlert.EarbudsConnected("", null).presentation().title)
    }

    @Test
    fun ringerAndAirplaneAlertsNameTheNewState() {
        assertEquals("Silent", CockpitSystemAlert.RingerModeChanged(CockpitRingerMode.SILENT).presentation().title)
        assertEquals("Vibrate", CockpitSystemAlert.RingerModeChanged(CockpitRingerMode.VIBRATE).presentation().title)
        assertEquals("Ringer on", CockpitSystemAlert.RingerModeChanged(CockpitRingerMode.NORMAL).presentation().title)
        assertEquals("On", CockpitSystemAlert.AirplaneModeChanged(true).presentation().detail)
        assertEquals("Off", CockpitSystemAlert.AirplaneModeChanged(false).presentation().detail)
    }

    @Test
    fun driveLiftAlertsNameTheFileOrThePeer() {
        val done = CockpitSystemAlert.DriveLiftTransfer("IMG_0412.jpg", 100, isCompleted = true)
        assertEquals("IMG_0412.jpg", done.presentation().title)
        assertEquals("Done", done.presentation().detail)

        val moving = CockpitSystemAlert.DriveLiftTransfer("IMG_0412.jpg", 40, isCompleted = false)
        assertEquals("40%", moving.presentation().detail)

        val radar = CockpitSystemAlert.DriveLiftRadarConnected("Work laptop")
        assertEquals("Work laptop", radar.presentation().title)
        assertEquals("Connected", radar.presentation().detail)
        assertEquals("Laptop", CockpitSystemAlert.DriveLiftRadarConnected("").presentation().title)
    }

    @Test
    fun everyPreviewChipHasItsOwnTagAndAnAlert() {
        val tags = CockpitPreviewAlert.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        assertEquals(10, CockpitPreviewAlert.entries.size)
        for (preview in CockpitPreviewAlert.entries) {
            val alert = CockpitPreviewAlerts.alert(preview)
            assertTrue(alert.id.isNotBlank())
        }
    }
}
