package com.drivelift.app

import com.drivelift.app.drive.nextOffsetFromRange
import com.drivelift.app.transfer.Backoff
import com.drivelift.app.transfer.SpeedMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLogicTest {
    @Test
    fun backoffDoublesThenCapsAtOneMinute() {
        val backoff = Backoff(random = { 1.0 })
        val waits = List(8) { backoff.next() }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun backoffJitterNeverGoesBelowHalf() {
        val backoff = Backoff(random = { 0.0 })
        assertEquals(1_000L, backoff.next())
        assertEquals(2_000L, backoff.next())
    }

    @Test
    fun backoffResetStartsOver() {
        val backoff = Backoff(random = { 1.0 })
        repeat(5) { backoff.next() }
        backoff.reset()
        assertEquals(2_000L, backoff.next())
    }

    @Test
    fun backoffNeverOverflowsAfterManyAttempts() {
        val backoff = Backoff(random = { 1.0 })
        repeat(200) { assertTrue(backoff.next() in 1..60_000) }
    }

    @Test
    fun resumeOffsetComesFromRangeHeader() {
        assertEquals(1_048_576L, nextOffsetFromRange("bytes=0-1048575"))
        assertEquals(1L, nextOffsetFromRange("bytes=0-0"))
    }

    @Test
    fun missingRangeHeaderMeansStartFromZero() {
        assertEquals(0L, nextOffsetFromRange(null))
        assertEquals(0L, nextOffsetFromRange("garbage"))
    }

    @Test
    fun speedMeterAveragesOverWindow() {
        val meter = SpeedMeter(windowMs = 5_000)
        assertEquals(0L, meter.record(0, 0))
        assertEquals(1_000_000L, meter.record(1_000, 1_000_000))
        assertEquals(1_000_000L, meter.record(2_000, 2_000_000))
    }
}
