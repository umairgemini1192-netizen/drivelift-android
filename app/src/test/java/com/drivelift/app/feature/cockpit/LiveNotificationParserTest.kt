package com.drivelift.app.feature.cockpit

import android.app.Notification
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.notifications.LiveNotificationParser
import com.drivelift.app.feature.cockpit.notifications.NotificationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationParserTest {

    @Test
    fun parsesWhatsAppMessageWithRemoteInputAndSkipsSummaries() {
        val msg = NotificationSnapshot(
            key = "0|com.whatsapp|101|null|1001",
            packageName = LiveNotificationParser.PKG_WHATSAPP,
            title = "Zara",
            text = "Arriving in 10 minutes",
            hasRemoteInput = true,
            updatedAtMs = 5_000L,
        )
        val parsed = LiveNotificationParser.parse(msg) as CockpitLiveActivity.WhatsAppAlertActivity
        assertEquals("Zara", parsed.senderName)
        assertEquals("Arriving in 10 minutes", parsed.messageSnippet)
        assertTrue(parsed.canInlineReply)
        assertFalse(parsed.isBusiness)

        val summary = msg.copy(isGroupSummary = true)
        assertNull(LiveNotificationParser.parse(summary))

        val silentSync = msg.copy(text = "Checking for new messages")
        assertNull(LiveNotificationParser.parse(silentSync))

        val biz = msg.copy(packageName = LiveNotificationParser.PKG_WHATSAPP_BUSINESS)
        val parsedBiz = LiveNotificationParser.parse(biz) as CockpitLiveActivity.WhatsAppAlertActivity
        assertTrue(parsedBiz.isBusiness)
    }

    @Test
    fun parsesGoogleMapsTurnByTurnNavigation() {
        val nav = NotificationSnapshot(
            key = "0|com.google.android.apps.maps|1|null|1002",
            packageName = LiveNotificationParser.PKG_MAPS,
            title = "350 m - Turn left onto Constitution Ave",
            text = "14 min (6.2 km) - 10:42 AM ETA",
            subText = "14 min",
            category = Notification.CATEGORY_NAVIGATION,
            isOngoing = true,
            updatedAtMs = 6_000L,
        )
        val parsed = LiveNotificationParser.parse(nav) as CockpitLiveActivity.MapsNavActivity
        assertEquals("Turn left onto Constitution Ave", parsed.maneuverText)
        assertEquals("350 m", parsed.distanceText)
        assertEquals("14 min", parsed.etaText)
        assertEquals(MapsTurnDirection.LEFT, parsed.turnDirection)
        assertEquals("Constitution Ave", parsed.roadText)
        assertEquals("", parsed.destinationText)
    }

    @Test
    fun parsesNavigationCardFieldsDistanceRoadAndDestination() {
        val nav = NotificationSnapshot(
            key = "0|com.google.android.apps.maps|2|null|1010",
            packageName = LiveNotificationParser.PKG_MAPS,
            title = "90 ft",
            text = "North",
            subText = "San Francisco",
            category = Notification.CATEGORY_NAVIGATION,
            isOngoing = true,
            updatedAtMs = 6_500L,
        )
        val parsed = LiveNotificationParser.parse(nav) as CockpitLiveActivity.MapsNavActivity
        assertEquals("90 ft", parsed.distanceText)
        assertEquals("North", parsed.maneuverText)
        assertEquals("North", parsed.roadText)
        assertEquals("San Francisco", parsed.destinationText)
        assertEquals("", parsed.etaText)
        assertEquals(MapsTurnDirection.STRAIGHT, parsed.turnDirection)
    }

    @Test
    fun parsesWazeNavigationAndProgressStyleWithoutNavigationCategory() {
        val waze = NotificationSnapshot(
            key = "0|com.waze|3|null|1011",
            packageName = LiveNotificationParser.PKG_WAZE,
            title = "300 m",
            text = "Turn right onto Market St",
            isOngoing = true,
            updatedAtMs = 6_600L,
        )
        val parsedWaze = LiveNotificationParser.parse(waze) as CockpitLiveActivity.MapsNavActivity
        assertEquals("300 m", parsedWaze.distanceText)
        assertEquals(MapsTurnDirection.RIGHT, parsedWaze.turnDirection)
        assertEquals("Market St", parsedWaze.roadText)
        assertEquals(LiveNotificationParser.PKG_WAZE, parsedWaze.packageName)

        // Android 16 progress-style navigation is not always flagged ongoing or categorised.
        val progressStyle = NotificationSnapshot(
            key = "0|com.google.android.apps.maps|4|null|1012",
            packageName = LiveNotificationParser.PKG_MAPS,
            title = "200 m",
            text = "Turn left onto Pine St",
            isProgressStyle = true,
            updatedAtMs = 6_700L,
        )
        val parsedProgress = LiveNotificationParser.parse(progressStyle) as CockpitLiveActivity.MapsNavActivity
        assertEquals("200 m", parsedProgress.distanceText)
        assertEquals(MapsTurnDirection.LEFT, parsedProgress.turnDirection)

        // A Maps notification that is not navigation at all is ignored.
        val other = progressStyle.copy(isProgressStyle = false)
        assertNull(LiveNotificationParser.parse(other))
    }

    @Test
    fun parsesGooglePhoneIncomingAndOngoingCallStyle() {
        val incoming = NotificationSnapshot(
            key = "0|com.google.android.dialer|11|null|2001",
            packageName = "com.google.android.dialer",
            title = "Amina Khan",
            text = "Incoming call",
            category = Notification.CATEGORY_CALL,
            isOngoing = true,
            callType = 1,
            callPersonName = "Amina Khan",
            hasAnswerAction = true,
            hasDeclineAction = true,
            updatedAtMs = 10_000L,
        )
        val ringing = LiveNotificationParser.parse(incoming) as CockpitLiveActivity.CallActivity
        assertEquals(CallState.INCOMING, ringing.state)
        assertEquals("Amina Khan", ringing.callerName)
        assertEquals("Phone", ringing.sourceLabel)
        assertFalse(ringing.isVideo)
        assertEquals(0, ringing.priority)

        val ongoing = incoming.copy(
            text = "Ongoing call",
            callType = 2,
            hasAnswerAction = false,
            hasDeclineAction = false,
            hasHangUpAction = true,
            usesChronometer = true,
            whenMs = 5_000L,
            updatedAtMs = 140_000L,
        )
        val connected = LiveNotificationParser.parse(ongoing) as CockpitLiveActivity.CallActivity
        assertEquals(CallState.ONGOING, connected.state)
        assertEquals(5_000L, connected.startedAtMs)
        assertEquals(ringing.id, connected.id)

        // The call-screening type still rings.
        val screening = incoming.copy(callType = 3)
        assertEquals(CallState.INCOMING, (LiveNotificationParser.parse(screening) as CockpitLiveActivity.CallActivity).state)
    }

    @Test
    fun parsesWhatsAppAudioAndVideoCallsAsCallsNotMessages() {
        val ringing = NotificationSnapshot(
            key = "0|com.whatsapp|12|null|2002",
            packageName = LiveNotificationParser.PKG_WHATSAPP,
            title = "Zara",
            text = "Incoming WhatsApp voice call",
            category = Notification.CATEGORY_CALL,
            isOngoing = true,
            hasAnswerAction = true,
            hasDeclineAction = true,
            updatedAtMs = 20_000L,
        )
        val parsedRinging = LiveNotificationParser.parse(ringing) as CockpitLiveActivity.CallActivity
        assertEquals(CallState.INCOMING, parsedRinging.state)
        assertEquals("Zara", parsedRinging.callerName)
        assertEquals("WhatsApp Audio", parsedRinging.sourceLabel)

        val audioCall = ringing.copy(
            text = "Ongoing voice call",
            hasAnswerAction = false,
            hasDeclineAction = false,
            hasHangUpAction = true,
            usesChronometer = true,
            whenMs = 8_000L,
            updatedAtMs = 150_000L,
        )
        val parsedAudio = LiveNotificationParser.parse(audioCall) as CockpitLiveActivity.CallActivity
        assertEquals(CallState.ONGOING, parsedAudio.state)
        assertEquals("WhatsApp Audio", parsedAudio.sourceLabel)
        assertEquals(8_000L, parsedAudio.startedAtMs)

        val videoCall = audioCall.copy(callIsVideo = true)
        val parsedVideo = LiveNotificationParser.parse(videoCall) as CockpitLiveActivity.CallActivity
        assertTrue(parsedVideo.isVideo)
        assertEquals("WhatsApp Video", parsedVideo.sourceLabel)

        // A plain message notification from the same app is still a message.
        val message = ringing.copy(category = "msg", isOngoing = false, hasAnswerAction = false, hasDeclineAction = false)
        assertTrue(LiveNotificationParser.parse(message) is CockpitLiveActivity.WhatsAppAlertActivity)
    }

    @Test
    fun callSourceLabelsCoverPhoneAndVoipApps() {
        assertEquals("Phone", LiveNotificationParser.callSourceLabel("com.google.android.dialer", false))
        assertEquals("Phone", LiveNotificationParser.callSourceLabel("com.samsung.android.incallui", true))
        assertEquals("WhatsApp Business Audio", LiveNotificationParser.callSourceLabel(LiveNotificationParser.PKG_WHATSAPP_BUSINESS, false))
        assertEquals("Teams Video", LiveNotificationParser.callSourceLabel(LiveNotificationParser.PKG_TEAMS, true))
        assertEquals("Meet Audio", LiveNotificationParser.callSourceLabel(LiveNotificationParser.PKG_MEET, false))
        assertEquals("Telegram Audio", LiveNotificationParser.callSourceLabel(LiveNotificationParser.PKG_TELEGRAM, false))
        assertEquals("Call", LiveNotificationParser.callSourceLabel("com.example.voip", false))
        assertEquals("Video call", LiveNotificationParser.callSourceLabel("com.example.voip", true))
    }

    @Test
    fun ignoresCallCategoryNotificationsThatAreNotLive() {
        val missed = NotificationSnapshot(
            key = "0|com.google.android.dialer|13|null|2003",
            packageName = "com.google.android.dialer",
            title = "Missed call",
            text = "Amina Khan",
            category = Notification.CATEGORY_CALL,
            isOngoing = false,
            updatedAtMs = 30_000L,
        )
        assertNull(LiveNotificationParser.parse(missed))
    }

    @Test
    fun parsesScreenRecordingWithStartTimeAndStopAction() {
        val recording = NotificationSnapshot(
            key = "0|com.android.systemui|14|null|3001",
            packageName = LiveNotificationParser.PKG_SYSTEM_UI,
            title = "Screen Recorder",
            text = "Recording screen",
            isOngoing = true,
            usesChronometer = true,
            whenMs = 40_000L,
            hasStopAction = true,
            updatedAtMs = 46_000L,
        )
        val parsed = LiveNotificationParser.parse(recording) as CockpitLiveActivity.ScreenRecordingActivity
        assertEquals(40_000L, parsed.startedAtMs)
        assertEquals(recording.key, parsed.notificationKey)
        assertEquals(1, parsed.priority)

        // A SystemUI notification that is not a recording, or not ongoing, is ignored.
        assertNull(LiveNotificationParser.parse(recording.copy(title = "Battery saver", text = "On")))
        assertNull(LiveNotificationParser.parse(recording.copy(isOngoing = false)))
    }

    @Test
    fun parsesClockTimerStopwatchAndRecorderNotifications() {
        val timer = NotificationSnapshot(
            key = "0|com.google.android.deskclock|2|null|1003",
            packageName = LiveNotificationParser.PKG_GOOGLE_CLOCK,
            title = "Pasta Timer",
            text = "07:45 remaining",
            isOngoing = true,
            whenMs = 99_000L,
            usesChronometer = true,
            isChronometerCountDown = true,
            updatedAtMs = 7_000L,
        )
        val parsedTimer = LiveNotificationParser.parse(timer) as CockpitLiveActivity.TimerRecordingActivity
        assertEquals("Pasta Timer", parsedTimer.label)
        assertEquals("07:45", parsedTimer.timeText)
        assertFalse(parsedTimer.isRecording)
        assertEquals(99_000L, parsedTimer.chronometerBaseMs)
        assertTrue(parsedTimer.isCountDown)

        val recording = NotificationSnapshot(
            key = "0|com.google.android.apps.recorder|3|null|1004",
            packageName = LiveNotificationParser.PKG_PIXEL_RECORDER,
            title = "Voice Memo",
            text = "01:24",
            isOngoing = true,
            whenMs = 55_000L,
            usesChronometer = true,
            updatedAtMs = 8_000L,
        )
        val parsedRec = LiveNotificationParser.parse(recording) as CockpitLiveActivity.TimerRecordingActivity
        assertEquals("Voice Memo", parsedRec.label)
        assertEquals("01:24", parsedRec.timeText)
        assertTrue(parsedRec.isRecording)
    }
}
