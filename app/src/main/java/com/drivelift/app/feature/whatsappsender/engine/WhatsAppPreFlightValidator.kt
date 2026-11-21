package com.drivelift.app.feature.whatsappsender.engine

import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.MAX_WHATSAPP_BATCH_FILES
import com.drivelift.app.feature.whatsappsender.model.MAX_WHATSAPP_FILE_BYTES
import com.drivelift.app.feature.whatsappsender.model.PreFlightReport

/** Validates local selections against WhatsApp size limits, batch caps, and readability before sharing. */
class WhatsAppPreFlightValidator {

    fun validate(
        items: List<LocalSendItem>,
        isFolderSource: Boolean = false,
    ): PreFlightReport {
        val eligible = ArrayList<LocalSendItem>(items.size)
        val oversized = ArrayList<LocalSendItem>()
        var skippedZeroByte = 0
        var skippedUnreadable = 0

        for (item in items) {
            when {
                !item.isReadable -> skippedUnreadable++
                item.sizeBytes <= 0L -> skippedZeroByte++
                item.sizeBytes > MAX_WHATSAPP_FILE_BYTES -> oversized += item
                else -> eligible += item
            }
        }

        return PreFlightReport(
            eligibleItems = eligible,
            oversizedItems = oversized,
            skippedZeroByteCount = skippedZeroByte,
            skippedUnreadableCount = skippedUnreadable,
            isFolderSource = isFolderSource,
        )
    }

    fun splitIntoBatches(
        items: List<LocalSendItem>,
        batchSize: Int = MAX_WHATSAPP_BATCH_FILES,
    ): List<List<LocalSendItem>> {
        require(batchSize > 0) { "Batch size must be positive" }
        if (items.isEmpty()) return emptyList()
        return items.chunked(batchSize)
    }
}
