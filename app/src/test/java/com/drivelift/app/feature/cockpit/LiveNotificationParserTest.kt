package com.drivelift.app.feature.cockpit

import android.app.Notification
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
