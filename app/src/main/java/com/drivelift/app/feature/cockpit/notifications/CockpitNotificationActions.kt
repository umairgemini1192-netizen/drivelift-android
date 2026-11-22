package com.drivelift.app.feature.cockpit.notifications

import java.util.Locale

/** What a notification action button does, judged from its visible title. */
enum class NotificationActionKind {
    ANSWER,
    DECLINE,
    HANG_UP,
    MUTE,
    SPEAKER,
    STOP,
}

/**
 * Pure helpers shared by the notification parser and the call actions controller. Notification
 * actions have no machine readable role outside `Notification.CallStyle`, so the apps that do not
 * use it (WhatsApp, Telegram, the SystemUI screen recorder) are matched by their button titles.
 */
object CockpitNotificationActions {

    fun classify(title: CharSequence?): NotificationActionKind? {
        val t = title?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (t.isEmpty()) return null
        return when {
            t.contains("hang up") || t.contains("hangup") || t.contains("end call") || t == "end" ||
                t.contains("leave") -> NotificationActionKind.HANG_UP
            t.contains("decline") || t.contains("reject") || t == "dismiss" -> NotificationActionKind.DECLINE
            t.contains("answer") || t.contains("accept") || t.contains("pick up") -> NotificationActionKind.ANSWER
            t.contains("mute") -> NotificationActionKind.MUTE
            t.contains("speaker") || t.contains("audio output") || t.contains("bluetooth") ||
                t.contains("earpiece") -> NotificationActionKind.SPEAKER
            t.contains("stop") -> NotificationActionKind.STOP
            else -> null
        }
    }

    /** True when the title offers to undo a mute, which means the microphone is currently muted. */
    fun titleMeansMuted(title: CharSequence?): Boolean =
        title?.toString()?.trim()?.lowercase(Locale.ROOT)?.contains("unmute") == true
}
