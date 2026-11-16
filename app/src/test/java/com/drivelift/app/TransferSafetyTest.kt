package com.drivelift.app

import com.drivelift.app.data.SourceInaccessibleException
import com.drivelift.app.data.SourceMissingException
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.data.classifyOpenFailure
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.collectAllPages
import com.drivelift.app.transfer.LocalCopy
import com.drivelift.app.transfer.RemoteCopies
import com.drivelift.app.transfer.Verifier
import com.drivelift.app.transfer.VerifyLedger
import com.drivelift.app.transfer.splitForRemoval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Regression tests for the rules that protect the user's files: nothing is called verified or
 * removed without proof, a bad Drive copy is never orphaned, and listings are never cut short.
 * All data is synthetic.
 */
class TransferSafetyTest {

    private val goodMd5 = "0123456789abcdef0123456789abcdef"
    private val otherMd5 = "ffffffffffffffffffffffffffffffff"

    /** The ledger as one row, behaving like the real UPDATE statements. */
    private class Ledger(var row: TransferEntity) : VerifyLedger {
        val writes = ArrayList<String>()

        override suspend fun setStatus(id: Long, status: TransferStatus, error: String?) {
            writes += status.name
            row = row.copy(status = status.name, lastError = error)
        }

        override suspend fun requeue(id: Long, error: String) {
            writes += "REQUEUE"
            row = row.copy(
                status = TransferStatus.QUEUED.name, sessionUri = null, driveFileId = null,
                attempts = row.attempts + 1, lastError = error,
            )
        }
    }

    private class Drive(var file: DriveFile?) : RemoteCopies {
        var trashFailure: Exception? = null
        val trashed = ArrayList<String>()
        var trashAttempts = 0

        override suspend fun getFile(fileId: String): DriveFile? = file

        override suspend fun trashFile(fileId: String) {
            trashAttempts++
            trashFailure?.let { throw it }
            trashed += fileId
        }
    }

    private fun uploadedRow() = TransferEntity(
        id = 7, mediaUri = "content://media/external/images/media/7", name = "synthetic.jpg",
        mime = "image/jpeg", size = 1000, dateModifiedMs = 0, dateTakenMs = 0, bucketName = "Camera",
        destFolderId = "folder", destLabel = "Test", status = TransferStatus.UPLOADED.name,
        driveFileId = "drive-7",
    )

    private fun driveFile(size: Long = 1000, md5: String? = goodMd5, trashed: Boolean = false) =
        DriveFile("drive-7", "synthetic.jpg", "image/jpeg", size, md5, trashed)

    private fun verifier(ledger: Ledger, drive: Drive, local: suspend (TransferEntity) -> LocalCopy) =
        Verifier(ledger, drive, local)

    // --- Finding 1: no success state without a comparison -------------------------------------

    @Test
    fun matchingSizeAndChecksumIsVerified() = runBlocking {
        val ledger = Ledger(uploadedRow())
        val drive = Drive(driveFile())
        val requeued = verifier(ledger, drive) { LocalCopy(1000, goodMd5.uppercase()) }.verify(ledger.row)
        assertFalse(requeued)
        assertEquals(TransferStatus.VERIFIED.name, ledger.row.status)
        assertTrue(drive.trashed.isEmpty())
    }

    @Test
    fun missingPhoneFileIsNeverCalledRemovedOrVerified() = runBlocking {
        val ledger = Ledger(uploadedRow())
        val drive = Drive(driveFile()) // same size as the row: the old code accepted this
        val requeued = verifier(ledger, drive) { throw SourceMissingException("gone") }.verify(ledger.row)
        assertFalse(requeued)
        assertEquals(TransferStatus.FAILED.name, ledger.row.status)
        assertEquals(Verifier.SOURCE_GONE_UNCHECKED, ledger.row.lastError)
        assertEquals(listOf("FAILED"), ledger.writes)
        assertTrue("an unchecked copy must not be trashed either", drive.trashed.isEmpty())
    }

    @Test
    fun revokedAccessLeavesTheRowUploadedAndIsRetried() {
        val ledger = Ledger(uploadedRow())
        val drive = Drive(driveFile())
        assertThrows(SourceInaccessibleException::class.java) {
            runBlocking {
                verifier(ledger, drive) { throw SourceInaccessibleException("access off") }.verify(ledger.row)
            }
        }
        assertEquals(TransferStatus.UPLOADED.name, ledger.row.status)
        assertEquals("drive-7", ledger.row.driveFileId)
        assertTrue(ledger.writes.isEmpty())
    }

    @Test
    fun sameSizeDifferentContentIsNotVerified() = runBlocking {
        val ledger = Ledger(uploadedRow())
        val drive = Drive(driveFile(size = 1000, md5 = otherMd5))
        val requeued = verifier(ledger, drive) { LocalCopy(1000, goodMd5) }.verify(ledger.row)
        assertTrue(requeued)
        assertEquals(listOf("drive-7"), drive.trashed)
        assertEquals(TransferStatus.QUEUED.name, ledger.row.status)
    }

    @Test
    fun driveCopyWithoutChecksumIsNotVerified() = runBlocking {
        val ledger = Ledger(uploadedRow())
        val drive = Drive(driveFile(md5 = null))
        assertTrue(verifier(ledger, drive) { LocalCopy(1000, goodMd5) }.verify(ledger.row))
        assertEquals(TransferStatus.QUEUED.name, ledger.row.status)
    }

    @Test
    fun missingOrTrashedDriveCopyIsUploadedAgain() = runBlocking {
        for (file in listOf(null, driveFile(trashed = true))) {
            val ledger = Ledger(uploadedRow())
            val drive = Drive(file)
            assertTrue(verifier(ledger, drive) { error("must not read the phone file") }.verify(ledger.row))
            assertEquals(TransferStatus.QUEUED.name, ledger.row.status)
            assertEquals(0, drive.trashAttempts)
        }
    }

    @Test
    fun openFailuresAreClassifiedHonestly() {
        assertTrue(classifyOpenFailure(FileNotFoundException(), fullAccess = true) is SourceMissingException)
        assertTrue(classifyOpenFailure(FileNotFoundException(), fullAccess = false) is SourceInaccessibleException)
        assertTrue(classifyOpenFailure(SecurityException(), fullAccess = true) is SourceInaccessibleException)
        assertTrue(classifyOpenFailure(UnsupportedOperationException(), fullAccess = true) is SourceInaccessibleException)
    }

    // --- Finding 2: a bad Drive copy is never orphaned -----------------------------------------

    @Test
    fun failedCleanupKeepsTheDriveReferenceAndIsRetried() {
        val ledger = Ledger(uploadedRow())
        val drive = Drive(driveFile(md5 = otherMd5))
        val verifier = verifier(ledger, drive) { LocalCopy(1000, goodMd5) }

        for (failure in listOf(IOException("network down"), AuthRequiredException())) {
            drive.trashFailure = failure
            assertThrows(failure.javaClass) { runBlocking { verifier.verify(ledger.row) } }
            assertEquals(TransferStatus.UPLOADED.name, ledger.row.status)
            assertEquals("the reference needed to retry cleanup", "drive-7", ledger.row.driveFileId)
            assertTrue(ledger.writes.isEmpty())
        }

        // A later pass, or a new process after a restart, starts from the same stored row.
        drive.trashFailure = null
        val restarted = verifier(ledger, drive) { LocalCopy(1000, goodMd5) }
        assertTrue(runBlocking { restarted.verify(ledger.row) })
        assertEquals(listOf("drive-7"), drive.trashed)
        assertEquals(3, drive.trashAttempts)
        assertEquals(TransferStatus.QUEUED.name, ledger.row.status)
        assertNull(ledger.row.driveFileId)
    }

    @Test
    fun cancellationDuringCleanupIsNotSwallowed() {
        val ledger = Ledger(uploadedRow())
        val drive = Drive(driveFile(md5 = otherMd5)).apply { trashFailure = CancellationException("stopped") }
        assertThrows(CancellationException::class.java) {
            runBlocking { verifier(ledger, drive) { LocalCopy(1000, goodMd5) }.verify(ledger.row) }
        }
        assertEquals(TransferStatus.UPLOADED.name, ledger.row.status)
        assertEquals("drive-7", ledger.row.driveFileId)
    }

    // --- Removal close-out: "not visible" is not "gone" ----------------------------------------

    @Test
    fun unseenFilesAreClosedOutOnlyWithFullAccess() {
        val verified = listOf("a", "b", "c")
        val (onPhone, gone) = splitForRemoval(verified, present = setOf("a"), fullAccess = true)
        assertEquals(listOf("a"), onPhone)
        assertEquals(listOf("b", "c"), gone)

        val (askAndroid, closed) = splitForRemoval(verified, present = emptySet(), fullAccess = false)
        assertEquals(verified, askAndroid)
        assertTrue(closed.isEmpty())
    }

    // --- Finding 4: listings are never cut short -----------------------------------------------

    @Test
    fun listingLongerThanTwoThousandEntriesIsComplete() = runBlocking {
        val total = 4321
        val pageSize = 1000
        val requested = ArrayList<String?>()
        val all = collectAllPages { token ->
            requested += token
            val start = token?.toInt() ?: 0
            val end = minOf(start + pageSize, total)
            (start until end).toList() to end.takeIf { it < total }?.toString()
        }
        assertEquals((0 until total).toList(), all)
        assertEquals(listOf(null, "1000", "2000", "3000", "4000"), requested)
    }

    @Test
    fun emptyListingAndRepeatedPageAreHandled() {
        assertTrue(runBlocking { collectAllPages<Int> { emptyList<Int>() to null } }.isEmpty())
        assertThrows(IOException::class.java) {
            runBlocking { collectAllPages { listOf(1) to "same" } }
        }
    }
}
