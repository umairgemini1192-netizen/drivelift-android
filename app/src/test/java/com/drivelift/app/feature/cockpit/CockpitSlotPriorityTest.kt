package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.activities.CallState
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

    private val callActivity = CockpitLiveActivity.CallActivity(
        id = "call_1",
        callerName = "Amina Khan",
        sourceLabel = "WhatsApp Audio",
        isVideo = false,
        state = CallState.ONGOING,
        startedAtMs = 1_000L,
        packageName = "com.whatsapp",
        notificationKey = "notif_call_1",
        updatedAtMs = 50L,
    )

    private val recordingActivity = CockpitLiveActivity.ScreenRecordingActivity(
        id = "screenrec_1",
        startedAtMs = 2_000L,
        notificationKey = "notif_rec_1",
        updatedAtMs = 60L,
    )

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

    private val mediaActivity = CockpitLiveActivity.MediaActivity(
        id = "media_1",
        title = "Evening Drive",
        artist = "Night Radio",
        packageName = "com.example.music",
        notificationKey = "notif_media_1",
        updatedAtMs = 350L,
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
        assertEquals(0, state.activeIndex)
    }

    @Test
    fun priorityConstantsAreCallThenRecordingThenNavigationThenTransferThenTimerThenMedia() {
        assertEquals(0, callActivity.priority)
        assertEquals(1, recordingActivity.priority)
        assertEquals(2, mapsActivity.priority)
        assertEquals(3, dropActivity.priority)
        assertEquals(4, timerActivity.priority)
        assertEquals(5, mediaActivity.priority)
        assertTrue(whatsappActivity.priority > mediaActivity.priority)
    }

    @Test
    fun queueIsSortedByPriorityWhateverTheArrivalOrder() {
        val coordinator = CockpitStateCoordinator()
        listOf(whatsappActivity, mediaActivity, timerActivity, dropActivity, mapsActivity, recordingActivity, callActivity)
            .forEach(coordinator::upsertActivity)

        val state = coordinator.slotState.value
        assertEquals(
            listOf(
                callActivity.id,
                recordingActivity.id,
                mapsActivity.id,
                dropActivity.id,
                timerActivity.id,
                mediaActivity.id,
                whatsappActivity.id,
            ),
            state.visibleQueue.map { it.id },
        )
        assertEquals(callActivity.id, state.primary.id)
        assertEquals(recordingActivity.id, state.secondary?.id)
        assertEquals(0, state.activeIndex)
    }

    @Test
    fun swipeLeftAndRightWrapAroundTheQueueAndReportTheDirection() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(timerActivity)
        coordinator.upsertActivity(mapsActivity)
        coordinator.upsertActivity(whatsappActivity)
        // Queue by priority: Maps, Timer, WhatsApp.

        coordinator.cycleNext()
        var state = coordinator.slotState.value
        assertEquals(timerActivity.id, state.primary.id)
        assertEquals(whatsappActivity.id, state.secondary?.id)
        assertEquals(1, state.activeIndex)
        assertEquals(1, state.swapDirection)

        coordinator.cycleNext()
        state = coordinator.slotState.value
        assertEquals(whatsappActivity.id, state.primary.id)
        assertEquals(mapsActivity.id, state.secondary?.id)
        assertEquals(2, state.activeIndex)

        // Swipe left on the last activity wraps to the first.
        coordinator.cycleNext()
        state = coordinator.slotState.value
        assertEquals(mapsActivity.id, state.primary.id)
        assertEquals(0, state.activeIndex)

        // Swipe right on the first activity wraps to the last.
        coordinator.cyclePrevious()
        state = coordinator.slotState.value
        assertEquals(whatsappActivity.id, state.primary.id)
        assertEquals(2, state.activeIndex)
        assertEquals(-1, state.swapDirection)

        // Any other change reports no swap direction.
        coordinator.setExpanded(true)
        assertEquals(0, coordinator.slotState.value.swapDirection)
    }

    @Test
    fun swipingWithOneActivityDoesNothing() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(callActivity)
        coordinator.cycleNext()
        coordinator.cyclePrevious()
        val state = coordinator.slotState.value
        assertEquals(callActivity.id, state.primary.id)
        assertNull(state.secondary)
        assertEquals(0, state.swapDirection)
    }

    @Test
    fun tappingTheSatelliteSwapsItIntoThePrimarySlotWithoutExpanding() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(callActivity)
        coordinator.upsertActivity(recordingActivity)

        val satellite = coordinator.slotState.value.secondary!!
        assertEquals(recordingActivity.id, satellite.id)
        coordinator.focusActivity(satellite.id)

        val state = coordinator.slotState.value
        assertEquals(recordingActivity.id, state.primary.id)
        assertEquals(callActivity.id, state.secondary?.id)
        assertFalse(state.isExpanded)
        assertEquals(1, state.swapDirection)
    }

    @Test
    fun higherPriorityArrivalTakesFocusAndTheUsersChoiceIsRestoredWhenItEnds() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(dropActivity)
        coordinator.upsertActivity(timerActivity)
        coordinator.upsertActivity(whatsappActivity)
        coordinator.cycleNext()
        assertEquals(timerActivity.id, coordinator.slotState.value.primary.id)

        // An incoming call outranks everything and takes the primary slot.
        coordinator.upsertActivity(callActivity.copy(state = CallState.ONGOING))
        assertEquals(callActivity.id, coordinator.slotState.value.primary.id)

        // When the call ends, the Timer the user was looking at comes back, not the Transfer.
        coordinator.removeByNotificationKey("notif_call_1")
        val state = coordinator.slotState.value
        assertEquals(timerActivity.id, state.primary.id)
        assertEquals(1, state.activeIndex)
    }

    @Test
    fun withoutAManualChoiceTheQueueFallsBackToPriorityWhenTheNewcomerEnds() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(dropActivity)
        coordinator.upsertActivity(timerActivity)
        coordinator.upsertActivity(recordingActivity)
        assertEquals(recordingActivity.id, coordinator.slotState.value.primary.id)

        coordinator.removeByNotificationKey("notif_rec_1")
        assertEquals(dropActivity.id, coordinator.slotState.value.primary.id)
    }

    @Test
    fun incomingCallExpandsTheIslandAndCollapsesWhenConnectedOrEnded() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(timerActivity)
        val ringing = callActivity.copy(state = CallState.INCOMING)

        coordinator.upsertActivity(ringing)
        assertTrue(coordinator.slotState.value.isExpanded)
        assertEquals(ringing.id, coordinator.slotState.value.primary.id)

        // Answering turns the same call into an ongoing one and the island returns to a pill.
        coordinator.upsertActivity(ringing.copy(state = CallState.ONGOING))
        assertFalse(coordinator.slotState.value.isExpanded)
        assertEquals(callActivity.id, coordinator.slotState.value.primary.id)

        // A ringing call that is declined collapses the island too.
        val second = ringing.copy(id = "call_2", notificationKey = "notif_call_2")
        coordinator.upsertActivity(second)
        assertTrue(coordinator.slotState.value.isExpanded)
        coordinator.removeByNotificationKey("notif_call_2")
        assertFalse(coordinator.slotState.value.isExpanded)
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
