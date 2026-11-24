package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.widget.BatteryCurrentTone
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.feature.cockpit.widget.WidgetStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules the Home Screen widget and its in-app preview share. */
class CockpitWidgetModelTest {

    // ---- Drop <-> Stop and the status pill ----

    @Test
    fun whenDropGoesLiveTheTileBecomesStopAndThePillSaysDropLive() {
        val idleTile = CockpitWidgetModel.dropTile(dropLive = false, available = true)
        val liveTile = CockpitWidgetModel.dropTile(dropLive = true, available = true)
        assertEquals("Drop", idleTile.label)
        assertFalse(idleTile.active)
        assertEquals("Stop", liveTile.label)
        assertTrue(liveTile.active)

        assertEquals("READY", CockpitWidgetModel.statusPill(dropLive = false, torchOn = false).text)
        val live = CockpitWidgetModel.statusPill(dropLive = true, torchOn = false)
        assertEquals("DROP LIVE", live.text)
        assertEquals(WidgetStatusTone.DROP_LIVE, live.tone)
    }

    @Test
    fun stopStaysTappableEvenWhenWifiDropsSoTheServerCanAlwaysBeStopped() {
        val tile = CockpitWidgetModel.dropTile(dropLive = true, available = false)
        assertEquals("Stop", tile.label)
        assertTrue(tile.enabled)
        val offline = CockpitWidgetModel.dropTile(dropLive = false, available = false)
        assertEquals("No Wi-Fi", offline.label)
        assertFalse(offline.enabled)
    }

    @Test
    fun theTorchPillAppearsOnlyWhenDropIsNotLive() {
        assertEquals("TORCH ON", CockpitWidgetModel.statusPill(dropLive = false, torchOn = true).text)
        assertEquals(WidgetStatusTone.TORCH_ON, CockpitWidgetModel.statusPill(dropLive = false, torchOn = true).tone)
        assertEquals("DROP LIVE", CockpitWidgetModel.statusPill(dropLive = true, torchOn = true).text)
    }

    // ---- battery current ----

    @Test
    fun dischargingShowsANegativeFigureAndChargingAPositiveOneWithSeparators() {
        val draining = CockpitWidgetModel.formatBatteryCurrent(microAmps = -510_000L, isCharging = false)
        assertEquals("-510 mA", draining.text)
        assertEquals(-510, draining.milliAmps)
        assertEquals(BatteryCurrentTone.DISCHARGING, draining.tone)

        val charging = CockpitWidgetModel.formatBatteryCurrent(microAmps = 2_450_000L, isCharging = true)
        assertEquals("+2,450 mA", charging.text)
        assertEquals(2450, charging.milliAmps)
        assertEquals(BatteryCurrentTone.CHARGING, charging.tone)
    }

    @Test
    fun theSignFollowsTheChargingStateWhateverSignThePhoneReports() {
        // Some phones report a positive number while draining and a negative one while charging.
        assertEquals("-480 mA", CockpitWidgetModel.formatBatteryCurrent(480_000L, isCharging = false).text)
        assertEquals("+1,200 mA", CockpitWidgetModel.formatBatteryCurrent(-1_200_000L, isCharging = true).text)
    }

    @Test
    fun anUnsupportedOrZeroReadingNeverShowsAFakeFigure() {
        val unsupported = CockpitWidgetModel.formatBatteryCurrent(Long.MIN_VALUE, isCharging = false)
        assertEquals("-- mA", unsupported.text)
        assertNull(unsupported.milliAmps)
        assertEquals(BatteryCurrentTone.UNKNOWN, unsupported.tone)
        assertEquals("0 mA", CockpitWidgetModel.formatBatteryCurrent(0L, isCharging = true).text)
    }

    @Test
    fun smallCurrentWobbleDoesNotRedrawTheWidgetButARealChangeDoes() {
        assertFalse(CockpitWidgetModel.currentChangedEnough(-510, -515))
        assertTrue(CockpitWidgetModel.currentChangedEnough(-510, -520))
        assertTrue(CockpitWidgetModel.currentChangedEnough(-510, -620))
        assertTrue("sign flip is a real change", CockpitWidgetModel.currentChangedEnough(-5, 5))
        assertTrue(CockpitWidgetModel.currentChangedEnough(null, -300))
        assertFalse(CockpitWidgetModel.currentChangedEnough(null, null))
    }

    @Test
    fun chargeCounterConvertsMicroAmpHoursToMilliAmpHoursAndRedrawsOnOneMahChange() {
        val reading = CockpitWidgetModel.formatBatteryCharge(4_210_000L)
        assertEquals(4210, reading.milliAmpHours)
        assertEquals("4,210 mAh", reading.text)

        val unsupported = CockpitWidgetModel.formatBatteryCharge(Long.MIN_VALUE)
        assertNull(unsupported.milliAmpHours)
        assertEquals("-- mAh", unsupported.text)

        val zero = CockpitWidgetModel.formatBatteryCharge(0L)
        assertNull(zero.milliAmpHours)
        assertEquals("-- mAh", zero.text)

        assertFalse(CockpitWidgetModel.chargeChangedEnough(4210, 4210))
        assertTrue(CockpitWidgetModel.chargeChangedEnough(4210, 4211))
        assertTrue(CockpitWidgetModel.chargeChangedEnough(null, 4210))
        assertFalse(CockpitWidgetModel.chargeChangedEnough(null, null))
    }

    // ---- pinned apps pages ----

    @Test
    fun pinnedAppsFormPagesOfFour() {
        assertEquals(1, CockpitWidgetModel.pageCount(0))
        assertEquals(1, CockpitWidgetModel.pageCount(4))
        assertEquals(2, CockpitWidgetModel.pageCount(5))
        assertEquals(3, CockpitWidgetModel.pageCount(12))
    }

    @Test
    fun pagesWrapAroundInBothDirections() {
        assertEquals(0, CockpitWidgetModel.wrapPage(3, 3))
        assertEquals(2, CockpitWidgetModel.wrapPage(-1, 3))
        assertEquals(1, CockpitWidgetModel.wrapPage(1, 3))
        assertEquals(0, CockpitWidgetModel.wrapPage(7, 1))
    }

    @Test
    fun eachPageHoldsItsOwnFourAppsAndTheLastPageHoldsTheRest() {
        val apps = (1..10).map { "app$it" }
        assertEquals(listOf("app1", "app2", "app3", "app4"), CockpitWidgetModel.pageSlice(apps, 0))
        assertEquals(listOf("app5", "app6", "app7", "app8"), CockpitWidgetModel.pageSlice(apps, 1))
        assertEquals(listOf("app9", "app10"), CockpitWidgetModel.pageSlice(apps, 2))
        // Past the end wraps to the first page; before the start wraps to the last.
        assertEquals(listOf("app1", "app2", "app3", "app4"), CockpitWidgetModel.pageSlice(apps, 3))
        assertEquals(listOf("app9", "app10"), CockpitWidgetModel.pageSlice(apps, -1))
        assertTrue(CockpitWidgetModel.pageSlice(emptyList<String>(), 2).isEmpty())
    }

    @Test
    fun twelveAppsStepThroughThreePagesAndComeBackToTheStart() {
        val count = CockpitWidgetModel.pageCount(12)
        var page = 0
        val seen = mutableListOf<Int>()
        repeat(4) {
            seen += page
            page = CockpitWidgetModel.wrapPage(page + 1, count)
        }
        assertEquals(listOf(0, 1, 2, 0), seen)
    }
}
