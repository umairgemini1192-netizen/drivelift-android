package com.drivelift.app.feature.cockpit.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String = "",
    val category: String? = null,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val whenMs: Long = 0L,
    val usesChronometer: Boolean = false,
    val isChronometerCountDown: Boolean = false,
    val hasRemoteInput: Boolean = false,
    val updatedAtMs: Long = 0L,
)

/**
 * Pure notification parser turning WhatsApp, Google Maps, Clock timers/stopwatches, and
 * voice/screen recording notifications into typed [CockpitLiveActivity] models.
 */
object LiveNotificationParser {
    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val PKG_MAPS = "com.google.android.apps.maps"
    const val PKG_GOOGLE_CLOCK = "com.google.android.deskclock"
    const val PKG_AOSP_CLOCK = "com.android.deskclock"
    const val PKG_PIXEL_RECORDER = "com.google.android.apps.recorder"
    const val PKG_SOUND_RECORDER = "com.android.soundrecorder"
    const val PKG_SYSTEM_UI = "com.android.systemui"

    private const val CATEGORY_STOPWATCH = "stopwatch"

    fun parse(snapshot: NotificationSnapshot): CockpitLiveActivity? {
        if (snapshot.isGroupSummary) return null
        return when (snapshot.packageName) {
            PKG_WHATSAPP, PKG_WHATSAPP_BUSINESS -> parseWhatsApp(snapshot)
            PKG_MAPS -> parseGoogleMaps(snapshot)
            PKG_GOOGLE_CLOCK, PKG_AOSP_CLOCK -> parseClockOrTimer(snapshot)
            PKG_PIXEL_RECORDER, PKG_SOUND_RECORDER -> parseRecorder(snapshot)
            PKG_SYSTEM_UI -> parseSystemUiRecording(snapshot)
            else -> parseGenericChronometer(snapshot)
        }
    }

    fun fromStatusBarNotification(
        sbn: StatusBarNotification,
        updatedAtMs: Long = sbn.postTime,
    ): NotificationSnapshot {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val isCountDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val hasRemoteInput = notification.actions.orEmpty().any { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
        val flags = notification.flags
        val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0

        return NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            subText = subText,
            category = notification.category,
            isOngoing = isOngoing,
            isGroupSummary = isGroupSummary,
            whenMs = notification.`when`,
            usesChronometer = usesChronometer,
            isChronometerCountDown = isCountDown,
            hasRemoteInput = hasRemoteInput,
            updatedAtMs = updatedAtMs,
        )
    }

    private fun parseWhatsApp(snapshot: NotificationSnapshot): CockpitLiveActivity.WhatsAppAlertActivity? {
        val sender = snapshot.title.trim()
        val body = snapshot.text.trim()
        if (sender.isEmpty() || body.isEmpty()) return null
        val lowerBody = body.lowercase()
        if (lowerBody.contains("checking for new messages") ||
            lowerBody.contains("whatsapp web is currently active") ||
            lowerBody.contains("backing up messages")
        ) {
            return null
        }
        return CockpitLiveActivity.WhatsAppAlertActivity(
            id = "wa_${snapshot.key}",
            senderName = sender,
            messageSnippet = body,
            notificationKey = snapshot.key,
            canInlineReply = snapshot.hasRemoteInput,
            isBusiness = snapshot.packageName == PKG_WHATSAPP_BUSINESS,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    private fun parseGoogleMaps(snapshot: NotificationSnapshot): CockpitLiveActivity.MapsNavActivity? {
        val title = snapshot.title.trim()
        val text = snapshot.text.trim()
        val subText = snapshot.subText.trim()
        val looksLikeNav = snapshot.isOngoing ||
            snapshot.category == Notification.CATEGORY_NAVIGATION
        if (!looksLikeNav) return null
        if (title.isEmpty() && text.isEmpty()) return null

        val distance = extractDistance(title, text)
        val maneuver = cleanManeuver(title, text)
        val eta = extractEta(text, subText)
        val direction = inferTurnDirection("$title $text")

        return CockpitLiveActivity.MapsNavActivity(
            id = CockpitLiveActivity.ID_MAPS,
            maneuverText = maneuver,
            distanceText = distance,
            etaText = eta,
            turnDirection = direction,
            packageName = snapshot.packageName,
            notificationKey = snapshot.key,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    private fun parseClockOrTimer(snapshot: NotificationSnapshot): CockpitLiveActivity.TimerRecordingActivity? {
        if (!snapshot.isOngoing &&
            snapshot.category != CATEGORY_STOPWATCH &&
            snapshot.category != Notification.CATEGORY_ALARM
        ) {
            return null
        }
        val title = snapshot.title.trim().ifEmpty { "Timer" }
        val text = snapshot.text.trim()
        val combined = "$title $text".lowercase()
        val isStopwatch = snapshot.category == CATEGORY_STOPWATCH || combined.contains("stopwatch")
        val label = when {
            isStopwatch -> "Stopwatch"
            title.lowercase().contains("timer") -> title
            snapshot.subText.isNotBlank() -> snapshot.subText.trim()
            else -> title
        }
        val timeText = extractTimeToken(text).ifEmpty { extractTimeToken(title) }.ifEmpty { text.ifEmpty { "Active" } }
        return CockpitLiveActivity.TimerRecordingActivity(
            id = "timer_${snapshot.key}",
            label = label,
            timeText = timeText,
            isRecording = false,
            packageName = snapshot.packageName,
            chronometerBaseMs = snapshot.whenMs.takeIf { snapshot.usesChronometer && it > 0L },
            isCountDown = snapshot.isChronometerCountDown || !isStopwatch,
            notificationKey = snapshot.key,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    private fun parseRecorder(snapshot: NotificationSnapshot): CockpitLiveActivity.TimerRecordingActivity? {
        if (!snapshot.isOngoing) return null
        val label = snapshot.title.trim().ifEmpty { "Voice recording" }
        val timeText = extractTimeToken(snapshot.text).ifEmpty { snapshot.text.trim().ifEmpty { "Recording" } }
        return CockpitLiveActivity.TimerRecordingActivity(
            id = "rec_${snapshot.key}",
            label = label,
            timeText = timeText,
            isRecording = true,
            packageName = snapshot.packageName,
            chronometerBaseMs = snapshot.whenMs.takeIf { snapshot.usesChronometer && it > 0L },
            isCountDown = false,
            notificationKey = snapshot.key,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    private fun parseSystemUiRecording(snapshot: NotificationSnapshot): CockpitLiveActivity.TimerRecordingActivity? {
        val combined = "${snapshot.title} ${snapshot.text}".lowercase()
        if (!snapshot.isOngoing || (!combined.contains("recording") && !combined.contains("screen record"))) {
            return null
        }
        return CockpitLiveActivity.TimerRecordingActivity(
            id = "sysrec_${snapshot.key}",
            label = "Screen recording",
            timeText = extractTimeToken(snapshot.text).ifEmpty { "Active" },
            isRecording = true,
            packageName = snapshot.packageName,
            chronometerBaseMs = snapshot.whenMs.takeIf { snapshot.usesChronometer && it > 0L },
            isCountDown = false,
            notificationKey = snapshot.key,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    private fun parseGenericChronometer(snapshot: NotificationSnapshot): CockpitLiveActivity.TimerRecordingActivity? {
        if (!snapshot.isOngoing || !snapshot.usesChronometer) return null
        val combined = "${snapshot.title} ${snapshot.text}".lowercase()
        val isRecording = combined.contains("recording") || combined.contains("voice memo")
        val isTimer = snapshot.category == CATEGORY_STOPWATCH ||
            combined.contains("timer") ||
            combined.contains("stopwatch")
        if (!isRecording && !isTimer) return null
        return CockpitLiveActivity.TimerRecordingActivity(
            id = "chrono_${snapshot.key}",
            label = snapshot.title.trim().ifEmpty { if (isRecording) "Recording" else "Timer" },
            timeText = extractTimeToken(snapshot.text).ifEmpty { snapshot.text.trim().ifEmpty { "Active" } },
            isRecording = isRecording,
            packageName = snapshot.packageName,
            chronometerBaseMs = snapshot.whenMs.takeIf { it > 0L },
            isCountDown = snapshot.isChronometerCountDown,
            notificationKey = snapshot.key,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    private fun extractDistance(title: String, text: String): String {
        val regex = Regex("""\b\d+(?:\.\d+)?\s*(?:km|mi|ft|yd|m)\b""", RegexOption.IGNORE_CASE)
        return regex.find(title)?.value ?: regex.find(text)?.value ?: ""
    }

    private fun cleanManeuver(title: String, text: String): String {
        val raw = title.ifEmpty { text }
        val withoutLeadingDist = raw.replace(Regex("""^\d+(?:\.\d+)?\s*(?:km|mi|ft|yd|m)\s*(?:-|on|toward)?\s*""", RegexOption.IGNORE_CASE), "")
        return withoutLeadingDist.trim().ifEmpty { raw }
    }

    private fun extractEta(text: String, subText: String): String {
        val etaRegex = Regex("""\b\d+\s*(?:min|hr|h)\b(?:\s*\([^)]+\))?""", RegexOption.IGNORE_CASE)
        return etaRegex.find(subText)?.value
            ?: etaRegex.find(text)?.value
            ?: subText.ifEmpty { text }
    }

    private fun inferTurnDirection(text: String): MapsTurnDirection {
        val lower = text.lowercase()
        return when {
            lower.contains("u-turn") || lower.contains("u turn") -> MapsTurnDirection.UTURN
            lower.contains("arrive") || lower.contains("destination") -> MapsTurnDirection.ARRIVE
            lower.contains("left") -> MapsTurnDirection.LEFT
            lower.contains("right") -> MapsTurnDirection.RIGHT
            else -> MapsTurnDirection.STRAIGHT
        }
    }

    private fun extractTimeToken(text: String): String {
        val regex = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
        return regex.find(text)?.value.orEmpty()
    }
}
