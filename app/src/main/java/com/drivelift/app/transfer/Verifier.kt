package com.drivelift.app.transfer

import com.drivelift.app.data.MediaVisibility
import com.drivelift.app.data.SourceMissingException
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.drive.DriveFile

/** Size and MD5 of the original file as it is on the phone right now. */
data class LocalCopy(val size: Long, val md5: String)

/** The two writes verification makes to the transfer ledger. */
interface VerifyLedger {
    suspend fun setStatus(id: Long, status: TransferStatus, error: String?)

    /** Back to QUEUED, forgetting the Drive file and upload session. */
    suspend fun requeue(id: Long, error: String)
}

/** The two Drive calls verification makes. */
interface RemoteCopies {
    /** Null when the file does not exist any more. */
    suspend fun getFile(fileId: String): DriveFile?

    /** Returns normally when the file is in the trash or already gone. Throws otherwise. */
    suspend fun trashFile(fileId: String)
}

/** A Drive copy is good only when both its size and its MD5 equal the phone file's. */
fun copiesMatch(local: LocalCopy, remote: DriveFile): Boolean =
    remote.size == local.size && remote.md5 != null && remote.md5.equals(local.md5, ignoreCase = true)

/**
 * The decision part of verification, free of Android so it can be tested directly.
 *
 * It only ever moves a row forward on proof:
 * - VERIFIED needs a size and checksum match against the phone file read just now.
 * - A phone file that is gone ends as FAILED with the reason. It is never called verified or
 *   removed, because nothing was compared.
 * - A phone file that cannot be read right now (access revoked) throws, so the caller retries
 *   and the row stays UPLOADED.
 * - A bad Drive copy is trashed before the row is requeued. If trashing fails the row keeps its
 *   Drive file id, so the cleanup is attempted again on the next pass or after a restart.
 */
class Verifier(
    private val ledger: VerifyLedger,
    private val remote: RemoteCopies,
    private val readLocal: suspend (TransferEntity) -> LocalCopy,
    /** Keeps VERIFIED off a copy the user is moving, or moved, to Drive's trash (issue #36). */
    private val fence: DriveCopyFence? = null,
) {
    /** Returns true when the file was sent back to the upload queue. */
    suspend fun verify(item: TransferEntity): Boolean {
        val copy = item.driveFileId?.let { remote.getFile(it) }
        if (copy == null || copy.trashed) {
            ledger.requeue(item.id, MISSING_IN_DRIVE)
            return true
        }

        val local = try {
            readLocal(item)
        } catch (e: SourceMissingException) {
            ledger.setStatus(item.id, TransferStatus.FAILED, SOURCE_GONE_UNCHECKED)
            return false
        }

        if (copiesMatch(local, copy)) {
            // The copy read above may be trashed by the user meanwhile. The VERIFIED write and the
            // trash reservation share one lock, so a trashed copy is never marked VERIFIED; the
            // row stays UPLOADED and the next pass reads the copy again.
            val written = if (fence == null) {
                ledger.setStatus(item.id, TransferStatus.VERIFIED, null)
            } else {
                fence.unlessTrashed(copy.id) { ledger.setStatus(item.id, TransferStatus.VERIFIED, null) }
            }
            if (written == null) throw CopyBeingTrashedException()
            return false
        }

        // Not wrapped: a failure here must stop this pass with the Drive file id still on the
        // row, otherwise the bad copy would be orphaned in Drive looking like a good one.
        remote.trashFile(copy.id)
        ledger.requeue(item.id, CHECKSUM_MISMATCH)
        return true
    }

    companion object {
        const val MISSING_IN_DRIVE = "The uploaded copy was missing in Drive, uploading again"
        const val CHECKSUM_MISMATCH = "Checksum mismatch after upload, uploading again"
        const val SOURCE_GONE_UNCHECKED =
            "The phone copy disappeared before the Drive copy could be checked. " +
                "An unchecked copy remains in Drive"
    }
}

/**
 * Splits verified files into those to put in Android's removal prompt and those that can be
 * closed out because they are already gone from the phone.
 *
 * A file missing from [present] only proves absence when the app can see every photo and every
 * video. With access revoked, limited to photos only, videos only or selected items, an unseen
 * file may still exist, so nothing is closed out and every file goes to the prompt, where
 * Android itself decides.
 */
fun splitForRemoval(
    verified: List<String>,
    present: Set<String>,
    visibility: MediaVisibility,
): Pair<List<String>, List<String>> =
    if (visibility == MediaVisibility.FULL) verified.partition { it in present } else verified to emptyList()
