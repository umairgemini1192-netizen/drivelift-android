package com.drivelift.app.feature.cockpit.hardware

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AlarmCountdownMode {
    NONE,
    CHRONOMETER_UNDER_24H,
    TEXT_OVER_24H,
}

data class NextAlarmUiModel(
    val hasAlarm: Boolean,
    val formattedTime: String,
    val countdownMode: AlarmCountdownMode,
    val chronometerBaseElapsedMs: Long?,
    val countdownText: String,
    val triggerAtMillis: Long?,
)

/**
 * Formats Android's next scheduled alarm (`AlarmManager.getNextAlarmClock()`) into:
 * - formatted alarm time ("06:30 AM" / "Mon 06:30 AM" / "No alarm set")
 * - `<24h` native `<Chronometer>` countdown base (`elapsedRealtime + remainingMs`)
 * - `>=24h` human-readable duration ("Rings in 1d 6h")
 */
object NextAlarmFormatter {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val MINUTE_MS = 60L * 1000L
    private const val SECOND_MS = 1000L

    fun readFromSystem(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    ): NextAlarmUiModel {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val nextClock = runCatching { alarmManager?.nextAlarmClock }.getOrNull()
        val is24Hour = runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(false)
        return formatNextAlarm(
            triggerAtMillis = nextClock?.triggerTime,
            nowMillis = nowMillis,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            is24Hour = is24Hour,
        )
    }

    fun formatNextAlarm(
        triggerAtMillis: Long?,
        nowMillis: Long,
        elapsedRealtimeMillis: Long,
        is24Hour: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.US,
    ): NextAlarmUiModel {
        if (triggerAtMillis == null || triggerAtMillis <= nowMillis) {
            return NextAlarmUiModel(
                hasAlarm = false,
                formattedTime = "No alarm set",
                countdownMode = AlarmCountdownMode.NONE,
                chronometerBaseElapsedMs = null,
                countdownText = "No alarm set",
                triggerAtMillis = null,
            )
        }

        val nowZdt = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val triggerZdt = Instant.ofEpochMilli(triggerAtMillis).atZone(zoneId)
        val sameDay = nowZdt.toLocalDate() == triggerZdt.toLocalDate()
        val pattern = when {
            sameDay && is24Hour -> "HH:mm"
            sameDay -> "hh:mm a"
            is24Hour -> "EEE HH:mm"
            else -> "EEE hh:mm a"
        }
        val formattedTime = DateTimeFormatter.ofPattern(pattern, locale).format(triggerZdt)
        val remainingMs = triggerAtMillis - nowMillis

        return if (remainingMs < DAY_MS) {
            val totalSeconds = remainingMs / SECOND_MS
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            NextAlarmUiModel(
                hasAlarm = true,
                formattedTime = formattedTime,
                countdownMode = AlarmCountdownMode.CHRONOMETER_UNDER_24H,
                chronometerBaseElapsedMs = elapsedRealtimeMillis + remainingMs,
                countdownText = "Rings in %02d:%02d:%02d".format(locale, hours, minutes, seconds),
                triggerAtMillis = triggerAtMillis,
            )
        } else {
            val days = remainingMs / DAY_MS
            val hours = (remainingMs % DAY_MS) / HOUR_MS
            val mins = (remainingMs % HOUR_MS) / MINUTE_MS
            val text = when {
                hours > 0L -> "Rings in ${days}d ${hours}h"
                mins > 0L -> "Rings in ${days}d ${mins}m"
                else -> "Rings in ${days}d"
            }
            NextAlarmUiModel(
                hasAlarm = true,
                formattedTime = formattedTime,
                countdownMode = AlarmCountdownMode.TEXT_OVER_24H,
                chronometerBaseElapsedMs = null,
                countdownText = text,
                triggerAtMillis = triggerAtMillis,
            )
        }
    }
}
