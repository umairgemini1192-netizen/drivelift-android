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
