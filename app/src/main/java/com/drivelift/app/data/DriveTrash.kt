package com.drivelift.app.data

import com.drivelift.app.drive.DriveFile

/**
 * A user-directed "Move to trash" of Drive files (file manager, issue #36), worked out before
 * anything is asked or changed. Separate from the automated cleanup of a copy that failed
 * verification: only the files the user selected, only to Google Drive's trash, never permanently.
 */
data class DriveTrashPlan(
    /** Files that will be moved to Drive's trash, in the order the user selected them. */
    val eligible: List<DriveFile>,
    /** Folders are left out in this version: trashing one would take everything inside it. */
    val folders: List<DriveFile>,
    /** Files a transfer still depends on (being checked, or verified and waiting for removal). */
    val heldByTransfer: List<DriveFile>,
    /**
     * Eligible files whose phone original DriveLift already removed after verifying this copy:
     * the Drive copy is the only one, so the confirmation says so.
     */
    val onlyCopy: List<DriveFile>,
    /**
     * The connected account when the plan was made: the files listed belong to it and the batch
     * runs for it only. Null when no account is connected, and then nothing is moved.
     */
    val account: String? = null,
)

/** Transfer states in which the Drive copy must stay where it is. */
val TRANSFER_HOLDS_DRIVE_COPY: Set<String> = setOf(
    TransferStatus.UPLOADING.name,
    TransferStatus.UPLOADED.name,
    TransferStatus.VERIFIED.name,
)

/**
 * Whether a transfer still depends on [file]: a row with its Drive id in an active or verified
 * state, or an upload still in progress under the same name (Drive can list a finished upload
 * before the worker has recorded its id; same-named files are held too, to stay safe).
 */
fun transferHolds(file: DriveFile, transfers: List<TransferEntity>): Boolean = transfers.any { t ->
    (t.driveFileId == file.id && t.status in TRANSFER_HOLDS_DRIVE_COPY) ||
        (t.name == file.name && t.status in setOf(TransferStatus.QUEUED.name, TransferStatus.UPLOADING.name))
}

fun planDriveTrash(
    selected: List<DriveFile>,
    transfers: List<TransferEntity>,
    account: String? = null,
): DriveTrashPlan {
    val byDriveId = transfers.filter { it.driveFileId != null }.groupBy { it.driveFileId!! }
    val folders = selected.filter { it.isFolder }
    val files = selected.filterNot { it.isFolder }
    val (held, eligible) = files.partition { f -> transferHolds(f, transfers) }
    val onlyCopy = eligible.filter { f -> byDriveId[f.id].orEmpty().any { it.status == TransferStatus.REMOVED.name } }
    return DriveTrashPlan(
        eligible = eligible, folders = folders, heldByTransfer = held, onlyCopy = onlyCopy,
        account = account?.takeIf { it.isNotBlank() },
    )
}

/** What happened to each file of a Drive trash request. */
data class DriveTrashOutcome(
    val trashed: List<DriveFile>,
    val failed: List<Pair<DriveFile, String>>,
    /** Confirmed files left alone because a transfer came to depend on them before their turn. */
    val skipped: List<DriveFile> = emptyList(),
    /** Confirmed files Drive was never asked about, and why (for example the account changed). */
    val notTried: List<DriveFile> = emptyList(),
    val notTriedReason: String? = null,
) {
    fun message(): String {
        val base = when {
            failed.isEmpty() && trashed.isEmpty() -> null
            failed.isEmpty() -> if (trashed.size == 1) "1 file moved to Google Drive's trash" else "${trashed.size} files moved to Google Drive's trash"
            trashed.isEmpty() -> if (failed.size == 1) "Could not move ${failed[0].first.name} to the trash: ${failed[0].second}"
                else "None of the ${failed.size} files could be moved to the trash: ${failed[0].second}"
            else -> "${trashed.size} moved to Google Drive's trash; ${failed.size} could not be moved: ${failed[0].second}"
        }
        val held = when (skipped.size) {
            0 -> null
            1 -> "1 left in Drive because a transfer now depends on it"
            else -> "${skipped.size} left in Drive because a transfer now depends on them"
        }
        val untried = when (notTried.size) {
            0 -> null
            1 -> "1 not tried: ${notTriedReason ?: "stopped"}"
            else -> "${notTried.size} not tried: ${notTriedReason ?: "stopped"}"
        }
        return listOfNotNull(base, held, untried).joinToString("; ").ifEmpty { "Nothing was moved to the trash." }
    }
}
