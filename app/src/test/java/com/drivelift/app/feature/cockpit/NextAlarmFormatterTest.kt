package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.NextAlarmFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class NextAlarmFormatterTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun nullOrExpiredTriggerReturnsNoAlarmSet() {
        val now = 1_000_000L
        val none = NextAlarmFormatter.formatNextAlarm(null, now, 5_000L, zoneId = zone)
        assertFalse(none.hasAlarm)
        assertEquals("No alarm set", none.formattedTime)
        assertEquals("No alarm set", none.countdownText)
        assertEquals(AlarmCountdownMode.NONE, none.countdownMode)
        assertNull(none.chronometerBaseElapsedMs)

        val past = NextAlarmFormatter.formatNextAlarm(now - 1_000L, now, 5_000L, zoneId = zone)
        assertFalse(past.hasAlarm)
        assertEquals(AlarmCountdownMode.NONE, past.countdownMode)
    }

    @Test
    fun under24HoursUsesChronometerBaseAndSameDayFormat() {
        val nowZdt = ZonedDateTime.of(2026, 11, 21, 5, 15, 0, 0, zone)
        val alarmZdt = ZonedDateTime.of(2026, 11, 21, 6, 30, 0, 0, zone)
        val nowMs = nowZdt.toInstant().toEpochMilli()
        val triggerMs = alarmZdt.toInstant().toEpochMilli()
        val elapsedMs = 200_000L
        val remainingMs = triggerMs - nowMs

        val model = NextAlarmFormatter.formatNextAlarm(
            triggerAtMillis = triggerMs,
            nowMillis = nowMs,
            elapsedRealtimeMillis = elapsedMs,
            is24Hour = false,
            zoneId = zone,
            locale = Locale.US,
        )
        assertTrue(model.hasAlarm)
        assertEquals("06:30 AM", model.formattedTime)
        assertEquals(AlarmCountdownMode.CHRONOMETER_UNDER_24H, model.countdownMode)
        assertEquals(elapsedMs + remainingMs, model.chronometerBaseElapsedMs)
        assertEquals("Rings in 01:15:00", model.countdownText)
    }

    @Test
    fun over24HoursFormatsWeekdayAndDayHourDuration() {
        val nowZdt = ZonedDateTime.of(2026, 11, 21, 0, 30, 0, 0, zone) // Sat
        val alarmZdt = ZonedDateTime.of(2026, 11, 22, 6, 30, 0, 0, zone) // Sun, +30h = 1d 6h
        val model = NextAlarmFormatter.formatNextAlarm(
            triggerAtMillis = alarmZdt.toInstant().toEpochMilli(),
            nowMillis = nowZdt.toInstant().toEpochMilli(),
            elapsedRealtimeMillis = 100_000L,
            is24Hour = false,
            zoneId = zone,
            locale = Locale.US,
        )
        assertTrue(model.hasAlarm)
        assertEquals("Sun 06:30 AM", model.formattedTime)
        assertEquals(AlarmCountdownMode.TEXT_OVER_24H, model.countdownMode)
        assertNull(model.chronometerBaseElapsedMs)
        assertEquals("Rings in 1d 6h", model.countdownText)
    }

    @Test
    fun advancingClockByOneSecondDecrementsCountdownText() {
        val nowZdt = ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, zone)
        val nowMs = nowZdt.toInstant().toEpochMilli()
        val remainingMs = (6L * 3600L + 40L * 60L + 45L) * 1000L
        val triggerMs = nowMs + remainingMs

        val t0 = NextAlarmFormatter.formatNextAlarm(triggerMs, nowMs, 10_000L, zoneId = zone, locale = Locale.US)
        val t1 = NextAlarmFormatter.formatNextAlarm(triggerMs, nowMs + 1_000L, 11_000L, zoneId = zone, locale = Locale.US)

        assertEquals("Rings in 06:40:45", t0.countdownText)
        assertEquals("Rings in 06:40:44", t1.countdownText)
    }
}
