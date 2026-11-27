package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitStateCoordinator
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlert
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A system alert is layered on top of the live activity queue: it shows over an active call or
 * navigation without ever replacing it, hides while the island is expanded or a call rings, and the
 * island returns to the activity (or the idle island) the moment the alert ends.
 */
class CockpitStateCoordinatorSystemAlertTest {

    private val lowBattery = CockpitSystemAlert.BatteryLow(percent = 15, isCritical = false)
    private val charging = CockpitSystemAlert.BatteryCharging(
        percent = 82,
        isFastCharging = true,
        estimatedFullMinutes = 40L,
    )

    private fun call(state: CallState) = CockpitLiveActivity.CallActivity(
        id = "call_1",
        callerName = "Amina Khan",
        sourceLabel = "WhatsApp Audio",
        isVideo = false,
        state = state,
        startedAtMs = 1_000L,
        packageName = "com.whatsapp",
        notificationKey = "notif_call_1",
        updatedAtMs = 50L,
    )

    private val navigation = CockpitLiveActivity.MapsNavActivity(
        maneuverText = "North",
        distanceText = "90 ft",
        etaText = "12 min",
        turnDirection = MapsTurnDirection.STRAIGHT,
        notificationKey = "notif_nav_1",
        updatedAtMs = 70L,
    )

    @Test
    fun noAlertByDefault() {
        val coordinator = CockpitStateCoordinator()

        assertNull(coordinator.slotState.value.transientAlert)
        assertNull(coordinator.activeSystemAlert.value)
    }

    @Test
    fun alertShowsOverTheIdleIslandAndClearsBackToIt() {
        val coordinator = CockpitStateCoordinator()

        coordinator.setSystemAlert(lowBattery)
        assertSame(lowBattery, coordinator.slotState.value.transientAlert)
        assertSame(lowBattery, coordinator.activeSystemAlert.value)
        assertTrue(coordinator.slotState.value.primary is CockpitLiveActivity.IdleQuickDockActivity)

        coordinator.setSystemAlert(null)
        assertNull(coordinator.slotState.value.transientAlert)
        assertNull(coordinator.activeSystemAlert.value)
        assertTrue(coordinator.slotState.value.primary is CockpitLiveActivity.IdleQuickDockActivity)
    }

    @Test
    fun alertOverNavigationLeavesTheNavigationActivityUntouched() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(navigation)
        val before = coordinator.slotState.value

        coordinator.setSystemAlert(charging)
        val during = coordinator.slotState.value
        assertSame(charging, during.transientAlert)
        assertEquals(before.primary, during.primary)
        assertEquals(before.visibleQueue, during.visibleQueue)
        assertEquals(before.activeIndex, during.activeIndex)

        coordinator.setSystemAlert(null)
        assertEquals(before, coordinator.slotState.value)
    }

    @Test
    fun alertOverAnOngoingCallKeepsTheCallAsPrimary() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(call(CallState.ONGOING))

        coordinator.setSystemAlert(lowBattery)

        val slot = coordinator.slotState.value
        assertSame(lowBattery, slot.transientAlert)
        assertEquals("call_1", slot.primary.id)
        assertFalse(slot.isExpanded)
    }

    @Test
    fun aNewerAlertReplacesTheShownOne() {
        val coordinator = CockpitStateCoordinator()

        coordinator.setSystemAlert(charging)
        coordinator.setSystemAlert(lowBattery)

        assertSame(lowBattery, coordinator.activeSystemAlert.value)
    }

    @Test
    fun alertIsHiddenWhileTheIslandIsExpandedAndComesBackWhenItCollapses() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(navigation)
        coordinator.setSystemAlert(charging)

        coordinator.setExpanded(true)
        assertNull(coordinator.slotState.value.transientAlert)
        assertNull(coordinator.activeSystemAlert.value)

        coordinator.setExpanded(false)
        assertSame(charging, coordinator.slotState.value.transientAlert)
    }

    @Test
    fun alertIsHiddenWhileACallRingsAndReturnsOnceItIsAnswered() {
        val coordinator = CockpitStateCoordinator()
        coordinator.setSystemAlert(charging)
        assertSame(charging, coordinator.activeSystemAlert.value)

        // A ringing call takes the whole island and expands it, so the alert steps aside.
        coordinator.upsertActivity(call(CallState.INCOMING))
        assertNull(coordinator.activeSystemAlert.value)
        assertNull(coordinator.slotState.value.transientAlert)

        // Answering collapses the island again; the alert, still alive, shows over the call.
        coordinator.upsertActivity(call(CallState.ONGOING).copy(updatedAtMs = 80L))
        assertFalse(coordinator.slotState.value.isExpanded)
        assertSame(charging, coordinator.activeSystemAlert.value)
        assertEquals("call_1", coordinator.slotState.value.primary.id)
    }

    @Test
    fun alertIsHiddenWhenTheUserTurnsSystemAlertsOff() {
        val coordinator = CockpitStateCoordinator()
        coordinator.setSystemAlert(lowBattery)

        coordinator.applyPrefs(CockpitPrefsState(systemAlertsEnabled = false))
        assertNull(coordinator.slotState.value.transientAlert)
        assertNull(coordinator.activeSystemAlert.value)

        coordinator.applyPrefs(CockpitPrefsState(systemAlertsEnabled = true))
        assertSame(lowBattery, coordinator.slotState.value.transientAlert)
    }

    @Test
    fun alertsAreOnAndLedFlashIsOffUntilTheUserOptsIn() {
        val defaults = CockpitPrefsState()

        assertTrue(defaults.systemAlertsEnabled)
        assertFalse(defaults.ledFlashEnabled)
        assertTrue(defaults.ledFlashSuppressWhenScreenOn)
    }

    @Test
    fun clearingEverythingForATestAlsoClearsTheAlert() {
        val coordinator = CockpitStateCoordinator()
        coordinator.setSystemAlert(lowBattery)

        coordinator.clearAllForTest()

        assertNull(coordinator.activeSystemAlert.value)
        assertNull(coordinator.slotState.value.transientAlert)
    }
}
