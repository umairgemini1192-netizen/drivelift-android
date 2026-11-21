package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitStateCoordinator
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitSlotPriorityTest {

    private val dropActivity = CockpitLiveActivity.TransferActivity(
        id = CockpitLiveActivity.ID_DESKTOP_DROP,
        title = "Desktop Drop",
        subtitle = "1 active",
        speedText = "58 MB/s",
        progressFraction = 0.5f,
        isDesktopDrop = true,
        canStop = true,
        updatedAtMs = 100L,
    )

    private val timerActivity = CockpitLiveActivity.TimerRecordingActivity(
        id = "timer_1",
        label = "Kitchen Timer",
        timeText = "04:12",
        isRecording = false,
        packageName = "com.google.android.deskclock",
        notificationKey = "notif_timer_1",
        updatedAtMs = 200L,
    )

    private val mapsActivity = CockpitLiveActivity.MapsNavActivity(
        id = CockpitLiveActivity.ID_MAPS,
        maneuverText = "Turn right onto Main St",
        distanceText = "350 m",
        etaText = "12 min",
        turnDirection = MapsTurnDirection.RIGHT,
        notificationKey = "notif_maps",
        updatedAtMs = 300L,
    )

    private val whatsappActivity = CockpitLiveActivity.WhatsAppAlertActivity(
        id = "wa_1",
        senderName = "Amina",
        messageSnippet = "On my way now",
        notificationKey = "notif_wa_1",
        canInlineReply = true,
        updatedAtMs = 400L,
    )

    @Test
    fun emptyCoordinatorDefaultsToIdleWithNoSecondaryBubble() {
        val coordinator = CockpitStateCoordinator()
        val state = coordinator.slotState.value
        assertTrue(state.primary is CockpitLiveActivity.IdleQuickDockActivity)
        assertNull(state.secondary)
        assertTrue(state.visibleQueue.isEmpty())
    }

    @Test
    fun priorityArbitrationOrdersTransferThenTimerThenMapsThenWhatsApp() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(whatsappActivity)
        coordinator.upsertActivity(mapsActivity)
        coordinator.upsertActivity(timerActivity)

        // With 3 active (Timer, Maps, WhatsApp), Primary=Timer and Secondary=Maps.
        var state = coordinator.slotState.value
        assertEquals(timerActivity.id, state.primary.id)
        assertEquals(mapsActivity.id, state.secondary?.id)
        assertEquals(listOf(timerActivity.id, mapsActivity.id, whatsappActivity.id), state.visibleQueue.map { it.id })

        // Adding Transfer/Desktop Drop puts it into Primary and moves Timer to Secondary.
        coordinator.upsertActivity(dropActivity)
        state = coordinator.slotState.value
        assertEquals(dropActivity.id, state.primary.id)
        assertEquals(timerActivity.id, state.secondary?.id)
    }

    @Test
    fun swipeCyclingRotatesPrimaryAndSecondarySlotsCleanly() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(timerActivity)
        coordinator.upsertActivity(mapsActivity)
        coordinator.upsertActivity(whatsappActivity)

        coordinator.cycleNext()
        var state = coordinator.slotState.value
        assertEquals(mapsActivity.id, state.primary.id)
        assertEquals(whatsappActivity.id, state.secondary?.id)

        coordinator.cycleNext()
        state = coordinator.slotState.value
        assertEquals(whatsappActivity.id, state.primary.id)
        assertEquals(timerActivity.id, state.secondary?.id)

        coordinator.cyclePrevious()
        state = coordinator.slotState.value
        assertEquals(mapsActivity.id, state.primary.id)
        assertEquals(whatsappActivity.id, state.secondary?.id)
    }

    @Test
    fun softDismissHidesWithoutDestroyingSourceAndRestoreBringsItBack() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(dropActivity)
        coordinator.upsertActivity(whatsappActivity)

        coordinator.softDismiss(dropActivity.id)
        var state = coordinator.slotState.value
        assertEquals(whatsappActivity.id, state.primary.id)
        assertNull(state.secondary)
        assertEquals(1, state.dismissedCount)

        coordinator.restoreDismissed(dropActivity.id)
        state = coordinator.slotState.value
        assertEquals(dropActivity.id, state.primary.id)
        assertEquals(whatsappActivity.id, state.secondary?.id)
        assertEquals(0, state.dismissedCount)
    }

    @Test
    fun categoryTogglesHideAndRestoreWithoutDroppingUnderlyingState() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(mapsActivity)
        coordinator.upsertActivity(whatsappActivity)

        coordinator.applyPrefs(CockpitPrefsState(mapsEnabled = false))
        var state = coordinator.slotState.value
        assertEquals(whatsappActivity.id, state.primary.id)
        assertNull(state.secondary)

        coordinator.applyPrefs(CockpitPrefsState(mapsEnabled = true))
        state = coordinator.slotState.value
        assertEquals(mapsActivity.id, state.primary.id)
        assertEquals(whatsappActivity.id, state.secondary?.id)

        coordinator.removeByNotificationKey("notif_maps")
        state = coordinator.slotState.value
        assertEquals(whatsappActivity.id, state.primary.id)
        assertNull(state.secondary)
    }

    @Test
    fun disablingDynamicIslandBlocksOverlayMountOnIncomingNotifications() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(whatsappActivity)

        assertFalse(
            CockpitOverlayService.shouldMountFallbackOverlay(
                masterEnabled = false,
                hasOverlayPermission = true,
                accessibilityOverlayActive = false,
            ),
        )
        assertFalse(
            CockpitOverlayService.shouldMountFallbackOverlay(
                masterEnabled = true,
                hasOverlayPermission = true,
                accessibilityOverlayActive = true,
            ),
        )
        assertTrue(
            CockpitOverlayService.shouldMountFallbackOverlay(
                masterEnabled = true,
                hasOverlayPermission = true,
                accessibilityOverlayActive = false,
            ),
        )
    }
}
