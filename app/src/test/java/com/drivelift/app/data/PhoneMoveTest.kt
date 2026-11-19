package com.drivelift.app.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Moving phone files between phone folders: what the plan, the pill and the outcome hold. */
@RunWith(RobolectricTestRunner::class)
class PhoneMoveTest {
    private fun item(id: Long, name: String = "IMG_$id.jpg", path: String = "DCIM/Camera/", size: Long = 100) = MediaItem(
        id = id, uri = Uri.parse("content://media/external/images/media/$id"), name = name, size = size,
        mime = "image/jpeg", dateTakenMs = 0, dateModifiedMs = 0, bucketId = 1, bucketName = "Camera",
        relativePath = path, isVideo = false, durationMs = 0,
    )

    private fun row(id: Long, status: TransferStatus) = TransferEntity(
        id = id, mediaUri = "content://media/external/images/media/$id", name = "x", mime = "image/jpeg",
        size = 100, dateModifiedMs = 0, dateTakenMs = 0, bucketName = "Camera", destFolderId = null,
        destLabel = "Drive", status = status.name, driveFileId = null, attempts = 0, lastError = null,
    )

    private fun plan(items: List<MediaItem>, rows: List<TransferEntity> = emptyList(), names: List<String> = emptyList()) =
        planPhoneMove(items, rows, 2, "Pictures/Holiday/", "Holiday", names)

    @Test
    fun filesATransferNeedsStayInPlace() {
        val p = plan(
            listOf(item(1), item(2), item(3), item(4), item(5), item(6)),
            listOf(
                row(2, TransferStatus.QUEUED), row(3, TransferStatus.UPLOADING), row(4, TransferStatus.UPLOADED),
                row(5, TransferStatus.VERIFIED), row(6, TransferStatus.REMOVED),
            ),
        )
        assertEquals(listOf(1L, 6L), p.eligible.map { it.id })
        assertEquals(listOf(2L, 3L, 4L, 5L), p.heldByTransfer.map { it.id })
    }

    @Test
    fun aTakenNameIsNeverOverwrittenOrLeftToAndroid() {
        val p = plan(
            listOf(item(1, "Beach.jpg"), item(2, "IMG_2.jpg"), item(3, "img_2.JPG"), item(4, "Sun.jpg")),
            names = listOf("beach.JPG"),
        )
        assertEquals(listOf(2L, 4L), p.eligible.map { it.id })
        // Taken in the folder (any letter case), or by an earlier file of the same batch.
        assertEquals(listOf(1L, 3L), p.nameTaken.map { it.id })
    }

    @Test
    fun filesAlreadyThereAreNotMoved() {
        val p = plan(listOf(item(1, path = "Pictures/Holiday/"), item(2)))
        assertEquals(listOf(1L), p.alreadyThere.map { it.id })
        assertEquals(listOf(2L), p.eligible.map { it.id })
        assertEquals(100L, p.totalBytes)
    }

    @Test
    fun thePillLetsGoOfWhatIsDoneAndKeepsFailures() {
        val clip = holdPhoneForMove(listOf(item(1), item(2), item(3), item(4), item(5)), 1, "Camera")!!
        assertFalse(clip.canMoveInto(1))
        assertTrue(clip.canMoveInto(2))
        val p = PhoneMovePlan(
            eligible = listOf(item(1), item(2), item(3)), heldByTransfer = listOf(item(4)), nameTaken = listOf(item(5)),
            alreadyThere = emptyList(), toBucketId = 2, toRelativePath = "Pictures/Holiday/", toName = "Holiday",
        )
        val outcome = PhoneMoveOutcome("Holiday", moved = listOf(item(1)), failed = listOf(item(2) to "busy"), skipped = listOf(item(3)))
        assertEquals(listOf(2L), clip.afterMove(p, outcome)!!.items.map { it.id })
        assertNull(holdPhoneForMove(emptyList(), 1, "Camera"))
    }

    @Test
    fun filesGoneFromThePhoneLeaveThePill() {
        val clip = holdPhoneForMove(listOf(item(1), item(2)), 1, "Camera")!!
        assertEquals(listOf(2L), clip.refreshed(listOf(item(2), item(9)))!!.items.map { it.id })
        assertNull(clip.refreshed(emptyList()))
    }

    @Test
    fun theOutcomeSaysWhatHappened() {
        assertEquals("2 files moved to Holiday", PhoneMoveOutcome("Holiday", listOf(item(1), item(2)), emptyList()).message())
        assertEquals(
            "1 moved to Holiday; 1 could not be moved: busy; 1 left in place because a transfer now depends on it",
            PhoneMoveOutcome("Holiday", listOf(item(1)), listOf(item(2) to "busy"), listOf(item(3))).message(),
        )
        assertEquals("Nothing was moved.", PhoneMoveOutcome("Holiday", emptyList(), emptyList()).message())
    }
}
