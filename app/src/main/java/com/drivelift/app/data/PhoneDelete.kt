package com.drivelift.app.data

/**
 * A user-directed delete of phone files (file manager, issue #36), worked out before anything is
 * asked or changed. Separate from migration cleanup: no Drive copy or VERIFIED status is needed.
 *
 * Files that a transfer still depends on are left out, so no upload, verification or removal step
 * can act on a source that has gone to the trash: QUEUED, UPLOADING and UPLOADED are still being
 * copied or checked, and VERIFIED belongs to the Transfers removal flow.
 */
data class PhoneDeletePlan(
    /** The files that will be moved to the phone's trash, in the order the user selected them. */
    val eligible: List<MediaItem>,
    /** Files left out because a transfer still depends on them. */
    val blocked: List<MediaItem>,
) {
    val totalBytes: Long get() = eligible.sumOf { it.size }
}

/** Transfer states in which the phone file must stay where it is. */
val TRANSFER_HOLDS_FILE: Set<String> = setOf(
    TransferStatus.QUEUED.name,
    TransferStatus.UPLOADING.name,
    TransferStatus.UPLOADED.name,
    TransferStatus.VERIFIED.name,
)

fun planPhoneDelete(selected: List<MediaItem>, transfers: List<TransferEntity>): PhoneDeletePlan {
    val held = transfers.filter { it.status in TRANSFER_HOLDS_FILE }.map { it.mediaUri }.toSet()
    val (blocked, eligible) = selected.partition { it.uri.toString() in held }
    return PhoneDeletePlan(eligible = eligible, blocked = blocked)
}

/** What MediaStore says about one submitted file after Android's trash request. */
enum class TrashState { TRASHED, PRESENT, UNKNOWN }

/**
 * Asks MediaStore whether [uri] is now trashed (createTrashRequest sets IS_TRASHED). A file that
 * cannot be read back (no row, no permission, provider error) is UNKNOWN, never assumed trashed:
 * absence from a listing is not proof of anything.
 */
fun queryTrashState(resolver: android.content.ContentResolver, uri: android.net.Uri): TrashState = try {
    val args = android.os.Bundle().apply {
        putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED, android.provider.MediaStore.MATCH_INCLUDE)
    }
    resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
        when {
            !c.moveToFirst() -> TrashState.UNKNOWN
            c.getInt(0) == 1 -> TrashState.TRASHED
            else -> TrashState.PRESENT
        }
    } ?: TrashState.UNKNOWN
} catch (_: SecurityException) {
    TrashState.UNKNOWN
} catch (_: Exception) {
    TrashState.UNKNOWN
}

/**
 * The result of one trash request, counted only over the files actually sent to Android, plus
 * the ones that had already left the folder before sending and were therefore never sent.
 */
data class TrashOutcome(val trashed: Int, val stillPresent: Int, val unknown: Int, val notSent: Int) {
    fun message(): String {
        if (stillPresent == 0 && unknown == 0 && notSent == 0) {
            return if (trashed == 1) "1 file moved to this phone's trash" else "$trashed files moved to this phone's trash"
        }
        val parts = buildList {
            if (trashed > 0) add("$trashed moved to this phone's trash")
            if (stillPresent > 0) add("$stillPresent still on the phone")
            if (unknown > 0) add("$unknown could not be checked")
            if (notSent > 0) add(if (notSent == 1) "1 was no longer in the folder and was not sent" else "$notSent were no longer in the folder and were not sent")
        }
        return parts.joinToString("; ").replaceFirstChar { it.uppercase() }
    }
}

/** Counts the per-file states of the submitted files. */
fun trashOutcome(states: List<TrashState>, notSent: Int) = TrashOutcome(
    trashed = states.count { it == TrashState.TRASHED },
    stillPresent = states.count { it == TrashState.PRESENT },
    unknown = states.count { it == TrashState.UNKNOWN },
    notSent = notSent,
)
