package com.drivelift.app.data

import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #36: which selected Drive items a user-directed trash may move. */
class DriveTrashPlanTest {
    private fun file(id: String) = DriveFile(id, "$id.jpg", "image/jpeg", 100, "00", false)
    private fun folder(id: String) = DriveFile(id, id, DriveClient.FOLDER_MIME, 0, null, false)
    private fun row(id: Long, driveId: String, status: TransferStatus) = TransferEntity(
        id = id, mediaUri = "content://media/external/images/media/$id", name = "x", mime = "image/jpeg",
        size = 100, dateModifiedMs = 0, dateTakenMs = 0, bucketName = "Camera", destFolderId = null,
        destLabel = "Drive", status = status.name, driveFileId = driveId, attempts = 0, lastError = null,
    )

    @Test
    fun foldersAndFilesATransferNeedsAreLeftOut() {
        val selected = listOf(file("a"), folder("f"), file("b"), file("c"), file("d"), file("e"))
        val rows = listOf(
            row(1, "b", TransferStatus.UPLOADING), row(2, "c", TransferStatus.UPLOADED),
            row(3, "d", TransferStatus.VERIFIED), row(4, "e", TransferStatus.REMOVED),
        )
        val plan = planDriveTrash(selected, rows)
        assertEquals(listOf("a", "e"), plan.eligible.map { it.id })
        assertEquals(listOf("f"), plan.folders.map { it.id })
        assertEquals(listOf("b", "c", "d"), plan.heldByTransfer.map { it.id })
        assertEquals("the phone original of e was removed: the Drive copy is the only one", listOf("e"), plan.onlyCopy.map { it.id })
    }

    @Test
    fun resultWordingReportsEachOutcome() {
        assertEquals("2 files moved to Google Drive's trash", DriveTrashOutcome(listOf(file("a"), file("b")), emptyList()).message())
        assertEquals(
            "1 moved to Google Drive's trash; 1 could not be moved: No permission",
            DriveTrashOutcome(listOf(file("a")), listOf(file("b") to "No permission")).message(),
        )
        assertEquals(
            "Could not move b.jpg to the trash: No permission",
            DriveTrashOutcome(emptyList(), listOf(file("b") to "No permission")).message(),
        )
    }

    @Test
    fun aRunningUploadOfTheSameNameHoldsTheFile() {
        val active = row(9, "zzz", TransferStatus.UPLOADING).copy(driveFileId = null, name = "a.jpg")
        val plan = planDriveTrash(listOf(file("a"), file("b")), listOf(active))
        assertEquals(listOf("b"), plan.eligible.map { it.id })
        assertEquals(listOf("a"), plan.heldByTransfer.map { it.id })
    }
}
