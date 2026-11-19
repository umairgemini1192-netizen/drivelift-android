package com.drivelift.app.data

import android.net.Uri
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Issue #42: checking a new name and planning the rename of one phone or Drive file. */
@RunWith(RobolectricTestRunner::class)
class RenamePlanTest {
    private fun item(id: Long, name: String = "IMG_$id.jpg") = MediaItem(
        id = id, uri = Uri.parse("content://media/external/images/media/$id"), name = name, size = 100,
        mime = "image/jpeg", dateTakenMs = 0, dateModifiedMs = 0, bucketId = 1, bucketName = "Camera",
        relativePath = "DCIM/Camera/", isVideo = false, durationMs = 0,
    )

    private fun row(id: Long, status: TransferStatus, name: String = "IMG_$id.jpg", driveId: String? = null) = TransferEntity(
        id = id, mediaUri = "content://media/external/images/media/$id", name = name, mime = "image/jpeg",
        size = 100, dateModifiedMs = 0, dateTakenMs = 0, bucketName = "Camera", destFolderId = null,
        destLabel = "Drive", status = status.name, driveFileId = driveId, attempts = 0, lastError = null,
    )

    private fun phone(typed: String, siblings: List<String> = emptyList(), original: String = "IMG_0001.jpg") =
        checkNewName(original, typed, siblings, RenameWhere.PHONE).error

    private fun drive(typed: String, siblings: List<String> = emptyList(), original: String = "Scan_0042.jpg") =
        checkNewName(original, typed, siblings, RenameWhere.DRIVE).error

    @Test
    fun aGoodNameIsTrimmedAndAccepted() {
        val check = checkNewName("IMG_0001.jpg", "  Beach day.jpg ", listOf("IMG_0002.jpg"), RenameWhere.PHONE)
        assertEquals("Beach day.jpg", check.name)
        assertNull(check.error)
        assertNull(drive("Receipt March.pdf"))
    }

    @Test
    fun emptyUnchangedReservedAndSlashAreRefused() {
        assertEquals("Enter a name.", phone("   "))
        assertEquals("Enter a different name.", phone("IMG_0001.jpg"))
        assertEquals("Enter a different name.", drive(" Scan_0042.jpg "))
        assertTrue(drive(".")!!.contains("reserved"))
        assertTrue(drive("..")!!.contains("reserved"))
        assertEquals("A name can't contain /.", phone("a/b.jpg"))
        assertEquals("A name can't contain /.", drive("a/b.jpg"))
        assertTrue(drive("tab\there.jpg")!!.contains("control characters"))
    }

    @Test
    fun aNameLongerThan255BytesIsRefused() {
        val ok = "a".repeat(251) + ".jpg"
        assertNull(phone(ok))
        assertTrue(phone("a".repeat(252) + ".jpg")!!.contains("too long"))
        // Bytes, not characters: 128 two-byte letters are 256 bytes.
        assertTrue(drive("é".repeat(128))!!.contains("too long"))
    }

    @Test
    fun phoneNamesKeepTheirEndingAndStorageSafeCharacters() {
        assertEquals("Keep the ending .jpg: Android ties it to the file's type.", phone("Beach day.png"))
        assertEquals("Keep the ending .jpg: Android ties it to the file's type.", phone("Beach day"))
        // The same ending in other letter case is the same ending.
        assertNull(phone("Beach day.JPG"))
        assertTrue(phone("What?.jpg")!!.contains("can't contain any of these"))
        assertTrue(phone("a:b.jpg")!!.contains("can't contain any of these"))
        assertTrue(phone(".hidden.jpg")!!.contains("hide the file"))
        // Drive has no such limits: the ending may change on purpose, with a note.
        assertNull(drive("Scan_0042.png"))
        assertNull(drive("What?.jpg"))
    }

    @Test
    fun aNameAlreadyInTheFolderIsNeverUsed() {
        assertTrue(phone("IMG_0002.jpg", listOf("IMG_0002.jpg"))!!.contains("already in this folder"))
        // Phone storage ignores letter case, so a name differing only in case is taken too.
        assertTrue(phone("img_0002.jpg", listOf("IMG_0002.jpg"))!!.contains("already in this folder"))
        assertTrue(drive("Packing list.pdf", listOf("Packing list.pdf"))!!.contains("already in this folder"))
        // Drive tells case apart.
        assertNull(drive("packing list.pdf", listOf("Packing list.pdf")))
    }

    @Test
    fun endingHelpers() {
        assertEquals(".jpg", extensionOf("IMG_0001.jpg"))
        assertEquals("", extensionOf("README"))
        assertEquals("", extensionOf(".nomedia"))
        assertEquals(".gz", extensionOf("a.tar.gz"))
        assertEquals(8, baseNameLength("IMG_0001.jpg"))
        assertNull(endingChangeNote("a.jpg", "b.JPG"))
        assertEquals("The ending changes from .jpg to .png. The file's content and type stay the same.", endingChangeNote("a.jpg", "a.png"))
        assertTrue(endingChangeNote("a.jpg", "a")!!.contains("no longer ends in .jpg"))
    }

    @Test
    fun aPhoneFileATransferDependsOnIsRefused() {
        for (status in listOf(TransferStatus.QUEUED, TransferStatus.UPLOADING, TransferStatus.UPLOADED, TransferStatus.VERIFIED)) {
            assertTrue(status.name, planPhoneRename(item(1), listOf(row(1, status))).heldByTransfer)
        }
        for (status in listOf(TransferStatus.FAILED, TransferStatus.CANCELLED, TransferStatus.REMOVED)) {
            assertFalse(status.name, planPhoneRename(item(1), listOf(row(1, status))).heldByTransfer)
        }
        // Another file's transfer does not hold this one.
        assertFalse(planPhoneRename(item(1), listOf(row(2, TransferStatus.QUEUED))).heldByTransfer)
        val plan = planPhoneRename(item(1, "IMG_0001.jpg"), emptyList(), listOf("IMG_0002.jpg"))
        assertTrue(checkPhoneRename(plan, "IMG_0002.jpg").error!!.contains("already"))
    }

    @Test
    fun aDriveCopyATransferDependsOnIsRefusedAndUploadingNamesAreNotUsed() {
        val file = DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 100, "00", false)
        val held = planDriveRename(file, "root", listOf(row(1, TransferStatus.VERIFIED, driveId = "d2")), account = "me@example.com")
        assertTrue(held.heldByTransfer)
        assertFalse(held.allowed)
        val removed = planDriveRename(file, "root", listOf(row(1, TransferStatus.REMOVED, driveId = "d2")), account = "me@example.com")
        assertTrue(removed.allowed)
        assertEquals("me@example.com", removed.account)
        assertNull(planDriveRename(file, "root", emptyList(), account = " ").account)

        // A file still uploading under the name asked for: that name is refused.
        val uploading = planDriveRename(
            file, "root", listOf(row(3, TransferStatus.UPLOADING, name = "IMG_0003.jpg")), siblings = listOf("Notes.txt"),
        )
        assertTrue(uploading.allowed)
        assertTrue(checkDriveRename(uploading, "IMG_0003.jpg").error!!.contains("uploading"))
        assertTrue(checkDriveRename(uploading, "Notes.txt").error!!.contains("already"))
        assertNull(checkDriveRename(uploading, "Receipt.jpg").error)
        assertTrue(nameOfActiveUpload("IMG_0003.jpg", listOf(row(3, TransferStatus.QUEUED, name = "IMG_0003.jpg"))))
        assertFalse(nameOfActiveUpload("IMG_0003.jpg", listOf(row(3, TransferStatus.VERIFIED, name = "IMG_0003.jpg"))))

        val folder = DriveFile("f1", "Archive", DriveClient.FOLDER_MIME, 0, null, false)
        assertFalse(planDriveRename(folder, "root", emptyList()).allowed)
    }

    @Test
    fun messagesSayWhatHappened() {
        assertEquals("Renamed to Beach.jpg", phoneRenameMessage("IMG_1.jpg", "Beach.jpg", "Beach.jpg"))
        assertEquals("Android did not rename IMG_1.jpg. Nothing was changed.", phoneRenameMessage("IMG_1.jpg", "Beach.jpg", "IMG_1.jpg"))
        assertEquals("Android saved the name as Beach (1).jpg instead of Beach.jpg.", phoneRenameMessage("IMG_1.jpg", "Beach.jpg", "Beach (1).jpg"))
        assertTrue(phoneRenameMessage("IMG_1.jpg", "Beach.jpg", null).contains("could not be checked"))
        val file = DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 100, "00", false)
        assertEquals("Renamed to Receipt.jpg", DriveRenameOutcome(file, "Receipt.jpg", renamed = file.copy(name = "Receipt.jpg")).message())
        assertTrue(DriveRenameOutcome(file, "Receipt.jpg", nameTaken = true).message().contains("Nothing was renamed"))
        assertEquals("Could not rename Scan_0042.jpg: offline", DriveRenameOutcome(file, "Receipt.jpg", failure = "offline").message())
    }
}
