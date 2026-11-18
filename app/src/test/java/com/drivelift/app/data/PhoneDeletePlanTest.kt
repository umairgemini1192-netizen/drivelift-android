package com.drivelift.app.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Issue #36: which selected phone files a user-directed delete may move to the trash. */
@RunWith(RobolectricTestRunner::class)
class PhoneDeletePlanTest {
    private fun item(id: Long, size: Long = 100) = MediaItem(
        id = id, uri = Uri.parse("content://media/external/images/media/$id"), name = "IMG_$id.jpg", size = size,
        mime = "image/jpeg", dateTakenMs = 0, dateModifiedMs = 0, bucketId = 1, bucketName = "Camera",
        relativePath = "DCIM/Camera/", isVideo = false, durationMs = 0,
    )

    private fun row(id: Long, status: TransferStatus) = TransferEntity(
        id = id, mediaUri = "content://media/external/images/media/$id", name = "IMG_$id.jpg", mime = "image/jpeg",
        size = 100, dateModifiedMs = 0, dateTakenMs = 0, bucketName = "Camera", destFolderId = null,
        destLabel = "Drive", status = status.name, driveFileId = null, attempts = 0, lastError = null,
    )

    @Test
    fun filesATransferStillNeedsAreLeftOutAndTheRestKeepTheirOrder() {
        val selected = listOf(item(5), item(1), item(2), item(3), item(4), item(6), item(7), item(8))
        val rows = listOf(
            row(1, TransferStatus.QUEUED), row(2, TransferStatus.UPLOADING), row(3, TransferStatus.UPLOADED),
            row(4, TransferStatus.VERIFIED), row(6, TransferStatus.REMOVED), row(7, TransferStatus.FAILED),
            row(8, TransferStatus.CANCELLED),
        )
        val plan = planPhoneDelete(selected, rows)
        assertEquals(listOf(5L, 6L, 7L, 8L), plan.eligible.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), plan.blocked.map { it.id })
    }

    @Test
    fun noTransferNeedsNoDriveAndCountsTheBytes() {
        val plan = planPhoneDelete(listOf(item(1, 300), item(2, 700)), emptyList())
        assertEquals(2, plan.eligible.size)
        assertEquals(0, plan.blocked.size)
        assertEquals(1_000L, plan.totalBytes)
    }
}
