package com.drivelift.app.feature.cockpit

import android.media.AudioDeviceInfo
import com.drivelift.app.feature.cockpit.activities.CockpitDurationFormat
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.notifications.CallAudioOutput
import com.drivelift.app.feature.cockpit.notifications.CockpitCallActionsController
import com.drivelift.app.feature.cockpit.notifications.CockpitNotificationActions
import com.drivelift.app.feature.cockpit.notifications.NotificationActionKind
import com.drivelift.app.feature.cockpit.ui.CockpitPreviewActivities
import com.drivelift.app.feature.cockpit.ui.CockpitPreviewScenario
import com.drivelift.app.feature.cockpit.ui.callerInitials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitLiveActivityLogicTest {

    @Test
    fun clockFormatsMinutesAndSecondsThenHours() {
        assertEquals("00:00", CockpitDurationFormat.clock(0L))
        assertEquals("00:06", CockpitDurationFormat.clock(6_000L))
        assertEquals("00:06", CockpitDurationFormat.clock(6_999L))
        assertEquals("02:14", CockpitDurationFormat.clock(134_000L))
        assertEquals("59:59", CockpitDurationFormat.clock(3_599_000L))
        assertEquals("1:00:00", CockpitDurationFormat.clock(3_600_000L))
        assertEquals("1:02:03", CockpitDurationFormat.clock(3_723_000L))
        // A start time slightly in the future never shows a negative clock.
        assertEquals("00:00", CockpitDurationFormat.clock(-4_000L))
    }

    @Test
    fun actionTitlesAreClassifiedForCallsAndScreenRecording() {
        assertEquals(NotificationActionKind.ANSWER, CockpitNotificationActions.classify("Answer"))
        assertEquals(NotificationActionKind.ANSWER, CockpitNotificationActions.classify("Accept"))
        assertEquals(NotificationActionKind.DECLINE, CockpitNotificationActions.classify("Decline"))
        assertEquals(NotificationActionKind.DECLINE, CockpitNotificationActions.classify("Reject"))
        assertEquals(NotificationActionKind.HANG_UP, CockpitNotificationActions.classify("Hang up"))
        assertEquals(NotificationActionKind.HANG_UP, CockpitNotificationActions.classify("End call"))
        assertEquals(NotificationActionKind.MUTE, CockpitNotificationActions.classify("Mute"))
        assertEquals(NotificationActionKind.MUTE, CockpitNotificationActions.classify("Unmute"))
        assertEquals(NotificationActionKind.SPEAKER, CockpitNotificationActions.classify("Speaker"))
        assertEquals(NotificationActionKind.STOP, CockpitNotificationActions.classify("Stop"))
        assertEquals(NotificationActionKind.STOP, CockpitNotificationActions.classify("Stop recording"))
        assertNull(CockpitNotificationActions.classify("Share"))
        assertNull(CockpitNotificationActions.classify(""))
        assertNull(CockpitNotificationActions.classify(null))
        assertTrue(CockpitNotificationActions.titleMeansMuted("Unmute"))
        assertFalse(CockpitNotificationActions.titleMeansMuted("Mute"))
    }

    @Test
    fun audioOutputCyclesBluetoothThenSpeakerThenEarpiece() {
        val all = setOf(
            CallAudioOutput.BLUETOOTH,
            CallAudioOutput.SPEAKER,
            CallAudioOutput.EARPIECE,
        )
        assertEquals(CallAudioOutput.SPEAKER, CockpitCallActionsController.nextOutput(CallAudioOutput.BLUETOOTH, all))
        assertEquals(CallAudioOutput.EARPIECE, CockpitCallActionsController.nextOutput(CallAudioOutput.SPEAKER, all))
        assertEquals(CallAudioOutput.BLUETOOTH, CockpitCallActionsController.nextOutput(CallAudioOutput.EARPIECE, all))

        // Without earbuds the cycle is Speaker <-> Earpiece.
        val phoneOnly = setOf(CallAudioOutput.SPEAKER, CallAudioOutput.EARPIECE)
        assertEquals(CallAudioOutput.SPEAKER, CockpitCallActionsController.nextOutput(CallAudioOutput.EARPIECE, phoneOnly))
        assertEquals(CallAudioOutput.EARPIECE, CockpitCallActionsController.nextOutput(CallAudioOutput.SPEAKER, phoneOnly))

        // Wired earbuds sit between Bluetooth and the speaker.
        val wired = setOf(CallAudioOutput.WIRED, CallAudioOutput.SPEAKER, CallAudioOutput.EARPIECE)
        assertEquals(CallAudioOutput.WIRED, CockpitCallActionsController.nextOutput(CallAudioOutput.EARPIECE, wired))

        // One output only: nothing to switch to.
        assertNull(CockpitCallActionsController.nextOutput(CallAudioOutput.EARPIECE, setOf(CallAudioOutput.EARPIECE)))
    }

    @Test
    fun audioDeviceTypesMapToCallOutputs() {
        assertEquals(CallAudioOutput.BLUETOOTH, CockpitCallActionsController.classifyDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(CallAudioOutput.BLUETOOTH, CockpitCallActionsController.classifyDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(CallAudioOutput.WIRED, CockpitCallActionsController.classifyDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(CallAudioOutput.SPEAKER, CockpitCallActionsController.classifyDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(CallAudioOutput.EARPIECE, CockpitCallActionsController.classifyDeviceType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertNull(CockpitCallActionsController.classifyDeviceType(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test
    fun callerInitialsUseUpToTwoWords() {
        assertEquals("AK", callerInitials("Amina Khan"))
        assertEquals("Z", callerInitials("zara"))
        assertEquals("AB", callerInitials("  Ali   Bin Ahmad "))
        assertEquals("?", callerInitials("   "))
        assertEquals("?", callerInitials(""))
    }

    @Test
    fun previewScenariosBuildQueuesInPriorityOrder() {
        val fallback = listOf<CockpitLiveActivity>(
            CockpitLiveActivity.TimerRecordingActivity(
                id = "preview_timer",
                label = "Timer",
                timeText = "04:12",
                isRecording = false,
                packageName = "com.google.android.deskclock",
            ),
        )
        val multi = CockpitPreviewActivities.queue(CockpitPreviewScenario.MULTI_SWAP, 1_000_000L, fallback)
        assertEquals(listOf(0, 1, 2, 4), multi.map { it.priority })

        assertEquals(fallback, CockpitPreviewActivities.queue(CockpitPreviewScenario.DEFAULT, 1_000_000L, fallback))
        assertEquals(1, CockpitPreviewActivities.queue(CockpitPreviewScenario.INCOMING_CALL, 1_000_000L, fallback).size)

        // The ongoing preview call has already run for 2 minutes 14 seconds, the recording for 6 seconds.
        val now = 1_000_000L
        assertEquals(134_000L, now - CockpitPreviewActivities.ongoingCall(now).startedAtMs)
        assertEquals(6_000L, now - CockpitPreviewActivities.screenRecording(now).startedAtMs)
    }
}
