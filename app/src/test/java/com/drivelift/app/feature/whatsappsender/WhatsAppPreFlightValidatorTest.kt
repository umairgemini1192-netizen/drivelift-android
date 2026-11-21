package com.drivelift.app.feature.whatsappsender

import com.drivelift.app.feature.whatsappsender.engine.WhatsAppPreFlightValidator
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.MAX_WHATSAPP_BATCH_FILES
import com.drivelift.app.feature.whatsappsender.model.MAX_WHATSAPP_FILE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppPreFlightValidatorTest {

    private val validator = WhatsAppPreFlightValidator()

    private fun item(
        id: String,
        sizeBytes: Long,
        readable: Boolean = true,
        mime: String = "image/jpeg",
    ) = LocalSendItem(
        id = id,
        name = "SYNTH_$id.jpg",
        sizeBytes = sizeBytes,
        mimeType = mime,
        uriString = "content://media/external/images/media/$id",
        isReadable = readable,
    )

    @Test
    fun detectsFilesExceedingTwoGigabyteLimitWhileAllowingExactLimit() {
        val exactLimit = item("exact", MAX_WHATSAPP_FILE_BYTES)
        val overLimit = item("over", MAX_WHATSAPP_FILE_BYTES + 1L, mime = "video/mp4")
        val normal = item("normal", 4_500_000L)

        val report = validator.validate(listOf(exactLimit, overLimit, normal))

        assertEquals(listOf(exactLimit, normal), report.eligibleItems)
        assertEquals(listOf(overLimit), report.oversizedItems)
        assertEquals(2, report.effectiveItems(excludeOversized = true).size)
        assertEquals(3, report.effectiveItems(excludeOversized = false).size)
        assertEquals(exactLimit.sizeBytes + normal.sizeBytes, report.totalBytes(excludeOversized = true))
        assertEquals(
            exactLimit.sizeBytes + normal.sizeBytes + overLimit.sizeBytes,
            report.totalBytes(excludeOversized = false),
        )
    }

    @Test
    fun warnsWhenBatchExceedsThirtyFilesAndSplitsCleanly() {
        val thirtyItems = (1..MAX_WHATSAPP_BATCH_FILES).map { idx ->
            item("m$idx", 1_000_000L)
        }
        val thirtyOneItems = thirtyItems + item("m31", 1_000_000L)

        val okReport = validator.validate(thirtyItems)
        assertFalse(okReport.exceedsBatchLimit(excludeOversized = false))
        assertEquals(1, okReport.batchCount(excludeOversized = false))

        val largeReport = validator.validate(thirtyOneItems)
        assertTrue(largeReport.exceedsBatchLimit(excludeOversized = false))
        assertEquals(2, largeReport.batchCount(excludeOversized = false))
        assertTrue(largeReport.recommendZip(excludeOversized = false))

        val batches = validator.splitIntoBatches(largeReport.effectiveItems(excludeOversized = false))
        assertEquals(2, batches.size)
        assertEquals(30, batches[0].size)
        assertEquals(1, batches[1].size)
    }

    @Test
    fun filtersZeroByteAndUnreadableFilesWithExactCounts() {
        val zeroByte = item("zero", 0L)
        val negativeByte = item("neg", -12L)
        val unreadable = item("locked", 2_048L, readable = false)
        val valid = item("ok", 10_000L)

        val report = validator.validate(
            items = listOf(zeroByte, negativeByte, unreadable, valid),
            isFolderSource = true,
        )

        assertEquals(listOf(valid), report.eligibleItems)
        assertTrue(report.oversizedItems.isEmpty())
        assertEquals(2, report.skippedZeroByteCount)
        assertEquals(1, report.skippedUnreadableCount)
        assertEquals(3, report.skippedTotalCount)
        assertTrue(report.recommendZip(excludeOversized = false))
    }
}
