package com.drivelift.app.data

import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #36: which selected Drive items a user-directed move may take to another folder. */
class DriveMovePlanTest {
    private fun file(id: String) = DriveFile(id, "$id.jpg", "image/jpeg", 100, "00", false)
    private fun folder(id: String) = DriveFile(id, id, DriveClient.FOLDER_MIME, 0, null, false)
    private fun row(id: Long, driveId: String, status: TransferStatus) = TransferEntity(
        id = id, mediaUri = "content://media/external/images/media/$id", name = "x", mime = "image/jpeg",
        size = 100, dateModifiedMs = 0, dateTakenMs = 0, bucketName = "Camera", destFolderId = null,
        destLabel = "Drive", status = status.name, driveFileId = driveId, attempts = 0, lastError = null,
    )

    @Test
    fun foldersAndFilesATransferNeedsStayInPlace() {
        val selected = listOf(file("a"), folder("f"), file("b"), file("c"), file("d"), file("e"))
        val rows = listOf(
            row(1, "b", TransferStatus.UPLOADING), row(2, "c", TransferStatus.UPLOADED),
            row(3, "d", TransferStatus.VERIFIED), row(4, "e", TransferStatus.REMOVED),
        )
        val plan = planDriveMove(selected, rows, "root", "f9", "My Drive / Archive", "me@example.com")
        assertEquals(listOf("a", "e"), plan.eligible.map { it.id })
        assertEquals(listOf("f"), plan.folders.map { it.id })
        assertEquals(listOf("b", "c", "d"), plan.heldByTransfer.map { it.id })
        assertEquals("Archive", plan.toName)
        assertEquals("me@example.com", plan.account)
    }

    @Test
    fun theFolderTheFilesAreInMovesNothing() {
        val plan = planDriveMove(listOf(file("a")), emptyList(), "f9", "f9", "My Drive / Archive", "me@example.com")
        assertTrue(plan.sameFolder)
        assertTrue(plan.eligible.isEmpty())
    }

    @Test
    fun noConnectedAccountIsRecordedAsNone() {
        assertNull(planDriveMove(listOf(file("a")), emptyList(), "root", "f9", "Archive", " ").account)
    }

    @Test
    fun resultWordingReportsEachOutcome() {
        val a = file("a"); val b = file("b"); val c = file("c")
        assertEquals("1 file moved to Archive", DriveMoveOutcome("Archive", listOf(a), emptyList()).message())
        assertEquals("2 files moved to Archive", DriveMoveOutcome("Archive", listOf(a, b), emptyList()).message())
        assertEquals(
            "1 moved to Archive; 1 could not be moved: It is no longer in this folder",
            DriveMoveOutcome("Archive", listOf(a), listOf(b to "It is no longer in this folder")).message(),
        )
        assertEquals(
            "1 file moved to Archive; 1 left in place because a transfer now depends on it; 1 not tried: the account changed",
            DriveMoveOutcome("Archive", listOf(a), emptyList(), listOf(b), listOf(c), "the account changed").message(),
        )
        assertEquals("Nothing was moved.", DriveMoveOutcome("Archive", emptyList(), emptyList()).message())
    }
}
