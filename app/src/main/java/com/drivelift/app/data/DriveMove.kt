package com.drivelift.app.data

import com.drivelift.app.drive.DriveFile

/**
 * A user-directed move of Drive files from one folder to another (file manager, issue #36),
 * worked out before anything is asked or changed. Files keep their Drive id; only their folder
 * changes. Folders are not moved in this version.
 */
data class DriveMovePlan(
    /** Files that will be moved, in the order the user selected them. */
    val eligible: List<DriveFile>,
    /** Folders are left out in this version. */
    val folders: List<DriveFile>,
    /** Files a transfer still depends on; they stay where the transfer put them. */
    val heldByTransfer: List<DriveFile>,
    /** The folder the files are in now (the folder the user selected them in). */
    val fromFolderId: String,
    /** The chosen folder and its path as shown to the user. */
    val toFolderId: String,
    val toLabel: String,
    /**
     * The connected account when the plan was made: the files listed belong to it and the batch
     * runs for it only. Null when no account is connected, and then nothing is moved.
     */
    val account: String? = null,
) {
    /** The chosen folder is the one the files are already in: nothing to do. */
    val sameFolder: Boolean get() = fromFolderId == toFolderId

    /** Last part of [toLabel], the folder's own name. */
    val toName: String get() = toLabel.substringAfterLast(" / ")
}

fun planDriveMove(
    selected: List<DriveFile>,
    transfers: List<TransferEntity>,
    fromFolderId: String,
    toFolderId: String,
    toLabel: String,
    account: String? = null,
): DriveMovePlan {
    val folders = selected.filter { it.isFolder }
    val files = selected.filterNot { it.isFolder }
    val (held, movable) = files.partition { f -> transferHolds(f, transfers) }
    return DriveMovePlan(
        eligible = if (fromFolderId == toFolderId) emptyList() else movable,
        folders = folders,
        heldByTransfer = held,
        fromFolderId = fromFolderId,
        toFolderId = toFolderId,
        toLabel = toLabel,
        account = account?.takeIf { it.isNotBlank() },
    )
}

/** What happened to each file of a Drive move. */
data class DriveMoveOutcome(
    val toName: String,
    val moved: List<DriveFile>,
    val failed: List<Pair<DriveFile, String>>,
    /** Confirmed files left alone because a transfer came to depend on them before their turn. */
    val skipped: List<DriveFile> = emptyList(),
    /** Confirmed files Drive was never asked about, and why (for example the account changed). */
    val notTried: List<DriveFile> = emptyList(),
    val notTriedReason: String? = null,
    /** The folders of the batch, so a screen applies the outcome to the right listing. */
    val fromFolderId: String = "",
    val toFolderId: String = "",
) {
    fun message(): String {
        val base = when {
            failed.isEmpty() && moved.isEmpty() -> null
            failed.isEmpty() -> if (moved.size == 1) "1 file moved to $toName" else "${moved.size} files moved to $toName"
            moved.isEmpty() -> if (failed.size == 1) "Could not move ${failed[0].first.name}: ${failed[0].second}"
                else "None of the ${failed.size} files could be moved: ${failed[0].second}"
            else -> "${moved.size} moved to $toName; ${failed.size} could not be moved: ${failed[0].second}"
        }
        val held = when (skipped.size) {
            0 -> null
            1 -> "1 left in place because a transfer now depends on it"
            else -> "${skipped.size} left in place because a transfer now depends on them"
        }
        val untried = when (notTried.size) {
            0 -> null
            else -> "${notTried.size} not tried: ${notTriedReason ?: "stopped"}"
        }
        return listOfNotNull(base, held, untried).joinToString("; ").ifEmpty { "Nothing was moved." }
    }
}

/**
 * Drive files waiting in the move pill: chosen with Move in folder [fromFolderId], to be moved
 * with "Move here" in whichever folder the user opens next. Holding files changes nothing on
 * Drive; the move itself is planned with [planDriveMove] and confirmed like any other move.
 */
data class DriveMoveClip(
    /** The files, in the order they were selected. Never folders. */
    val files: List<DriveFile>,
    val fromFolderId: String,
    /** The source folder's own name, shown on the pill. */
    val fromName: String,
    /** The account the files were listed for; the pill is dropped when another one connects. */
    val account: String?,
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    /** Whether the pill can move its files into [folderId] (not the folder they are in). */
    fun canMoveInto(folderId: String): Boolean = folderId != fromFolderId

    /** The pill without [ids] (for example files that went to Drive's trash); null once empty. */
    fun without(ids: Set<String>): DriveMoveClip? =
        copy(files = files.filterNot { it.id in ids }).takeIf { it.files.isNotEmpty() }

    /** The pill with [file]'s new name, after a rename. */
    fun renamed(file: DriveFile): DriveMoveClip =
        copy(files = files.map { if (it.id == file.id) file else it })

    /**
     * The pill after a confirmed move of [plan] from its folder: moved files leave it, and so do
     * files no move can take (held by a transfer, or left in place because one came to depend on
     * them). Files that failed or were not tried stay, so "Move here" can try them again. A move
     * from another folder leaves the pill as it is.
     */
    fun afterMove(plan: DriveMovePlan, outcome: DriveMoveOutcome): DriveMoveClip? {
        if (plan.fromFolderId != fromFolderId) return this
        val done = (outcome.moved + outcome.skipped + plan.heldByTransfer + plan.folders).map { it.id }.toSet()
        return without(done)
    }
}

/** A move pill for [selected] in folder [fromFolderId], or null when no file is selected. */
fun holdForMove(selected: List<DriveFile>, fromFolderId: String, fromName: String, account: String?): DriveMoveClip? {
    val files = selected.filterNot { it.isFolder }
    if (files.isEmpty()) return null
    return DriveMoveClip(files, fromFolderId, fromName, account?.takeIf { it.isNotBlank() })
}

/**
 * What to tell the user when Move leaves folders of [selected] out of the pill, or null when the
 * selection has no folders. Folders are never moved, so they are named rather than dropped silently.
 */
fun holdNotice(selected: List<DriveFile>): String? {
    val folders = selected.count { it.isFolder }
    if (folders == 0) return null
    val files = selected.size - folders
    return when {
        files == 0 -> "Folders can't be moved yet. Select files to move."
        folders == 1 -> "1 folder stays where it is; folders can't be moved yet."
        else -> "$folders folders stay where they are; folders can't be moved yet."
    }
}
