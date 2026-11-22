package com.drivelift.app.feature.cockpit.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.drivelift.app.feature.cockpit.activities.CallState
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
    /** `Notification.CallStyle` call type: 0 none, 1 incoming, 2 ongoing, 3 screening. */
    val callType: Int = 0,
    val callPersonName: String = "",
    val callIsVideo: Boolean = false,
    val hasAnswerAction: Boolean = false,
    val hasDeclineAction: Boolean = false,
    val hasHangUpAction: Boolean = false,
    val hasStopAction: Boolean = false,
    val isProgressStyle: Boolean = false,
    val isPromotedOngoing: Boolean = false,
    /** Caller picture: the `CallStyle` person icon, else the large icon. */
    val avatar: Bitmap? = null,
    /** Turn maneuver picture: the large icon, else the small icon. */
    val maneuverIcon: Bitmap? = null,
)

/**
 * Pure notification parser turning phone and VoIP calls, SystemUI screen recording, Google Maps
 * and Waze navigation, WhatsApp messages, Clock timers/stopwatches, and voice recording
 * notifications into typed [CockpitLiveActivity] models.
 */
object LiveNotificationParser {
    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val PKG_MAPS = "com.google.android.apps.maps"
    const val PKG_WAZE = "com.waze"
    const val PKG_GOOGLE_CLOCK = "com.google.android.deskclock"
    const val PKG_AOSP_CLOCK = "com.android.deskclock"
    const val PKG_PIXEL_RECORDER = "com.google.android.apps.recorder"
    const val PKG_SOUND_RECORDER = "com.android.soundrecorder"
    const val PKG_SYSTEM_UI = "com.android.systemui"
    const val PKG_TEAMS = "com.microsoft.teams"
    const val PKG_MEET = "com.google.android.apps.tachyon"
    const val PKG_TELEGRAM = "org.telegram.messenger"

    private const val CATEGORY_STOPWATCH = "stopwatch"
    private const val CALL_TYPE_ONGOING = 2
    private const val TEMPLATE_PROGRESS_SUFFIX = "ProgressStyle"
    private const val ICON_SIZE_PX = 128
    private const val START_TIME_SKEW_MS = 5_000L

    private val distanceRegex = Regex("""\b\d+(?:\.\d+)?\s*(?:km|mi|ft|yd|m)\b""", RegexOption.IGNORE_CASE)
    private val etaRegex = Regex("""\b\d+\s*(?:min|hr|h)\b(?:\s*\([^)]+\))?""", RegexOption.IGNORE_CASE)
    private val tripDistanceRegex = Regex(
        """(\d+(?:\.\d+)?)\s*(km|mi|m|ft)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val clockTimeRegex = Regex("""\b\d{1,2}:\d{2}\s*(?:AM|PM)?""", RegexOption.IGNORE_CASE)
    private val etaHintRegex = Regex("""\d+\s*(?:min|hr|h)\b|\beta\b|\barriv""", RegexOption.IGNORE_CASE)
    private val roadRegex = Regex("""\b(?:onto|on|toward|towards|to|via)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val leadingDistanceRegex = Regex(
        """^\d+(?:\.\d+)?\s*(?:km|mi|ft|yd|m)\s*(?:-|on|toward)?\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** `Notification.FLAG_PROMOTED_ONGOING` exists from Android 16; read it by name so older SDKs compile and run. */
    private val flagPromotedOngoing: Int by lazy {
        runCatching { Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null) }
            .getOrDefault(0)
    }

    fun parse(snapshot: NotificationSnapshot): CockpitLiveActivity? {
        if (snapshot.isGroupSummary) return null
        // A call is recognised before the per-app parsers so a WhatsApp or Telegram call is shown
        // as a call, never as a message.
        parseCall(snapshot)?.let { return it }
        return when (snapshot.packageName) {
            PKG_WHATSAPP, PKG_WHATSAPP_BUSINESS -> parseWhatsApp(snapshot)
            PKG_MAPS, PKG_WAZE -> parseNavigation(snapshot)
            PKG_GOOGLE_CLOCK, PKG_AOSP_CLOCK -> parseClockOrTimer(snapshot)
            PKG_PIXEL_RECORDER, PKG_SOUND_RECORDER -> parseRecorder(snapshot)
            PKG_SYSTEM_UI -> parseSystemUiRecording(snapshot)
            else -> if (snapshot.category == Notification.CATEGORY_NAVIGATION && snapshot.isOngoing) {
                parseNavigation(snapshot)
            } else {
                parseGenericChronometer(snapshot)
            }
        }
    }

    /**
     * Builds the pure [NotificationSnapshot]. When [context] is given, the caller picture (call
     * person icon, else large icon) and the maneuver picture (large icon, else small icon) are
     * loaded into bitmaps; without it those stay null.
     */
    fun fromStatusBarNotification(
        sbn: StatusBarNotification,
        updatedAtMs: Long = sbn.postTime,
        context: Context? = null,
    ): NotificationSnapshot {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val isCountDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val actions = notification.actions.orEmpty()
        val hasRemoteInput = actions.any { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
        val actionKinds = actions.mapNotNull { CockpitNotificationActions.classify(it.title) }.toSet()
        val flags = notification.flags
        val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isCall = notification.category == Notification.CATEGORY_CALL
        val callType = if (Build.VERSION.SDK_INT >= 31) extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
        val person = readCallPerson(extras)
        val callIsVideo = Build.VERSION.SDK_INT >= 31 && extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO, false)
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val avatar = if (isCall || callType != 0) {
            loadIconBitmap(person?.icon, context) ?: loadIconBitmap(notification.getLargeIcon(), context)
        } else {
            null
        }
        val maneuverIcon = if (!isCall && callType == 0 && isOngoing) {
            loadIconBitmap(notification.getLargeIcon(), context)
                ?: loadIconBitmap(notification.smallIcon, context)
        } else {
            null
        }

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
            callType = callType,
            callPersonName = person?.name?.toString().orEmpty(),
            callIsVideo = callIsVideo,
            hasAnswerAction = NotificationActionKind.ANSWER in actionKinds ||
                hasIntentExtra(extras, Notification.EXTRA_ANSWER_INTENT),
            hasDeclineAction = NotificationActionKind.DECLINE in actionKinds ||
                hasIntentExtra(extras, Notification.EXTRA_DECLINE_INTENT),
            hasHangUpAction = NotificationActionKind.HANG_UP in actionKinds ||
                hasIntentExtra(extras, Notification.EXTRA_HANG_UP_INTENT),
            hasStopAction = NotificationActionKind.STOP in actionKinds,
            isProgressStyle = template.endsWith(TEMPLATE_PROGRESS_SUFFIX),
            isPromotedOngoing = flagPromotedOngoing != 0 && (flags and flagPromotedOngoing) != 0,
            avatar = avatar,
            maneuverIcon = maneuverIcon,
        )
    }

    private fun readCallPerson(extras: Bundle): Person? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<Person>(Notification.EXTRA_CALL_PERSON)
            }
        }.getOrNull()
    }

    private fun hasIntentExtra(extras: Bundle, key: String): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(key, PendingIntent::class.java) != null
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<PendingIntent>(key) != null
            }
        }.getOrDefault(false)
    }

    private fun loadIconBitmap(icon: Icon?, context: Context?): Bitmap? {
        if (icon == null || context == null) return null
        return runCatching {
            val drawable = icon.loadDrawable(context) ?: return@runCatching null
            val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    /** Wall-clock start of a connected call or recording: the chronometer base when plausible, else first sight. */
    private fun startedAtMs(snapshot: NotificationSnapshot): Long =
        snapshot.whenMs.takeIf { it > 0L && it <= snapshot.updatedAtMs + START_TIME_SKEW_MS }
            ?: snapshot.updatedAtMs

    private fun parseCall(snapshot: NotificationSnapshot): CockpitLiveActivity.CallActivity? {
        val isCallStyle = snapshot.callType != 0
        if (snapshot.category != Notification.CATEGORY_CALL && !isCallStyle) return null
        val isLive = snapshot.isOngoing || isCallStyle || snapshot.usesChronometer ||
            snapshot.hasAnswerAction || snapshot.hasHangUpAction
        if (!isLive) return null
        val state = when {
            snapshot.callType == CALL_TYPE_ONGOING -> CallState.ONGOING
            isCallStyle -> CallState.INCOMING
            snapshot.hasHangUpAction || snapshot.usesChronometer -> CallState.ONGOING
            snapshot.hasAnswerAction -> CallState.INCOMING
            else -> CallState.ONGOING
        }
        val name = snapshot.callPersonName.ifBlank { snapshot.title }.trim().ifEmpty { "Unknown caller" }
        return CockpitLiveActivity.CallActivity(
            id = "call_${snapshot.key}",
            callerName = name,
            sourceLabel = callSourceLabel(snapshot.packageName, snapshot.callIsVideo),
            isVideo = snapshot.callIsVideo,
            state = state,
            startedAtMs = startedAtMs(snapshot),
            packageName = snapshot.packageName,
            notificationKey = snapshot.key,
            avatar = snapshot.avatar,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    /** Muted source line under the caller name: `Phone`, `WhatsApp Audio`, `WhatsApp Video`, and so on. */
    fun callSourceLabel(packageName: String, isVideo: Boolean): String {
        val medium = if (isVideo) "Video" else "Audio"
        return when {
            packageName == PKG_WHATSAPP -> "WhatsApp $medium"
            packageName == PKG_WHATSAPP_BUSINESS -> "WhatsApp Business $medium"
            packageName == PKG_TEAMS -> "Teams $medium"
            packageName == PKG_MEET || packageName.startsWith("com.google.android.apps.meetings") -> "Meet $medium"
            packageName.startsWith(PKG_TELEGRAM) -> "Telegram $medium"
            packageName.contains("dialer") || packageName.contains("incallui") ||
                packageName.contains("telecom") -> "Phone"
            else -> if (isVideo) "Video call" else "Call"
        }
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

    private fun parseNavigation(snapshot: NotificationSnapshot): CockpitLiveActivity.MapsNavActivity? {
        val title = snapshot.title.trim()
        val text = snapshot.text.trim()
        val subText = snapshot.subText.trim()
        val looksLikeNav = snapshot.isOngoing ||
            snapshot.category == Notification.CATEGORY_NAVIGATION ||
            snapshot.isProgressStyle ||
            snapshot.isPromotedOngoing
        if (!looksLikeNav) return null
        if (title.isEmpty() && text.isEmpty()) return null

        val distance = extractDistance(title, text)
        val maneuver = cleanManeuver(title, text)
        val eta = extractEta(text, subText)
        val direction = inferTurnDirection("$title $text")
        val trip = extractRemainingTrip(title, text, subText)

        return CockpitLiveActivity.MapsNavActivity(
            id = CockpitLiveActivity.ID_MAPS,
            maneuverText = maneuver,
            distanceText = distance,
            etaText = eta,
            turnDirection = direction,
            packageName = snapshot.packageName,
            notificationKey = snapshot.key,
            roadText = extractRoad(maneuver),
            destinationText = subText.takeIf { it.isNotEmpty() && !etaHintRegex.containsMatchIn(it) }.orEmpty(),
            maneuverIcon = snapshot.maneuverIcon,
            remainingTripKm = trip?.km,
            tripUsesMiles = trip?.imperial == true,
            updatedAtMs = snapshot.updatedAtMs,
        )
    }

    /** A distance read from notification text, in kilometres. */
    data class TripDistance(val km: Double, val imperial: Boolean)

    /**
     * The distance left on the whole trip. The sub text is searched first, then the text; the
     * title is skipped because it carries the distance to the next turn. A distance equal to the
     * next-turn distance is ignored so it is never mistaken for the trip.
     */
    fun extractRemainingTrip(title: String, text: String, subText: String): TripDistance? {
        val nextTurn = extractDistance(title, text).replace(" ", "").lowercase()
        for (source in listOf(subText, text)) {
            for (match in tripDistanceRegex.findAll(source)) {
                if (match.value.replace(" ", "").lowercase() == nextTurn) continue
                val value = match.groupValues[1].toDoubleOrNull() ?: continue
                return when (match.groupValues[2].lowercase()) {
                    "km" -> TripDistance(value, imperial = false)
                    "m" -> TripDistance(value / 1000.0, imperial = false)
                    "mi" -> TripDistance(value * 1.609344, imperial = true)
                    "ft" -> TripDistance(value * 0.0003048, imperial = true)
                    else -> null
                }
            }
        }
        return null
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

    private fun parseSystemUiRecording(snapshot: NotificationSnapshot): CockpitLiveActivity.ScreenRecordingActivity? {
        val combined = "${snapshot.title} ${snapshot.text} ${snapshot.subText}".lowercase()
        if (!snapshot.isOngoing || (!combined.contains("record") && !combined.contains("screen capture"))) {
            return null
        }
        return CockpitLiveActivity.ScreenRecordingActivity(
            id = "screenrec_${snapshot.key}",
            startedAtMs = startedAtMs(snapshot),
            notificationKey = snapshot.key,
            packageName = snapshot.packageName,
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
        return distanceRegex.find(title)?.value ?: distanceRegex.find(text)?.value ?: ""
    }

    /** The instruction without its leading distance. When the title is only a distance, the text carries the instruction. */
    private fun cleanManeuver(title: String, text: String): String {
        val fromTitle = title.replace(leadingDistanceRegex, "").trim()
        if (fromTitle.isNotEmpty()) return fromTitle
        val fromText = text.replace(leadingDistanceRegex, "").trim()
        if (fromText.isNotEmpty() && !etaHintRegex.containsMatchIn(fromText)) return fromText
        return title.ifEmpty { text }
    }

    /** The road name after "onto", "on" or "toward"; otherwise the whole instruction, for example "North". */
    private fun extractRoad(maneuver: String): String {
        return roadRegex.find(maneuver)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: maneuver.trim()
    }

    /** The trip time left or arrival clock time; empty when the notification carries neither. */
    private fun extractEta(text: String, subText: String): String {
        return etaRegex.find(subText)?.value
            ?: etaRegex.find(text)?.value
            ?: clockTimeRegex.find(subText)?.value
            ?: clockTimeRegex.find(text)?.value
            ?: ""
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
