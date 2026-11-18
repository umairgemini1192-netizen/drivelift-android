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
)

/** Transfer states in which the Drive copy must stay where it is. */
val TRANSFER_HOLDS_DRIVE_COPY: Set<String> = setOf(
    TransferStatus.UPLOADING.name,
    TransferStatus.UPLOADED.name,
    TransferStatus.VERIFIED.name,
)

fun planDriveTrash(selected: List<DriveFile>, transfers: List<TransferEntity>): DriveTrashPlan {
    val byDriveId = transfers.filter { it.driveFileId != null }.groupBy { it.driveFileId!! }
    val folders = selected.filter { it.isFolder }
    val files = selected.filterNot { it.isFolder }
    val (held, eligible) = files.partition { f -> byDriveId[f.id].orEmpty().any { it.status in TRANSFER_HOLDS_DRIVE_COPY } }
    val onlyCopy = eligible.filter { f -> byDriveId[f.id].orEmpty().any { it.status == TransferStatus.REMOVED.name } }
    return DriveTrashPlan(eligible = eligible, folders = folders, heldByTransfer = held, onlyCopy = onlyCopy)
}

/** What happened to each file of a Drive trash request. */
data class DriveTrashOutcome(val trashed: List<DriveFile>, val failed: List<Pair<DriveFile, String>>) {
    fun message(): String = when {
        failed.isEmpty() -> if (trashed.size == 1) "1 file moved to Google Drive's trash" else "${trashed.size} files moved to Google Drive's trash"
        trashed.isEmpty() -> if (failed.size == 1) "Could not move ${failed[0].first.name} to the trash: ${failed[0].second}"
            else "None of the ${failed.size} files could be moved to the trash: ${failed[0].second}"
        else -> "${trashed.size} moved to Google Drive's trash; ${failed.size} could not be moved: ${failed[0].second}"
    }
}
