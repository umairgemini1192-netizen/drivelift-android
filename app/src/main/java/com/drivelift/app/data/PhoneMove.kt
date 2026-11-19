package com.drivelift.app.data

/**
 * A user-directed move of phone files from one phone folder to another (file manager, owner
 * request of 19 November 2026), worked out before anything is asked or changed. Only the folder
 * changes: each file keeps its MediaStore address (URI), name, bytes and dates, so nothing about it
 * is copied or deleted and no Drive copy is involved or implied.
 *
 * Left out and named, never moved:
 * - files a transfer still depends on (QUEUED, UPLOADING, UPLOADED, VERIFIED), as for trash and rename;
 * - files whose name is already taken in the destination folder (Android would otherwise rename them
 *   itself), including a second selected file with the same name as another;
 * - files already in the destination folder.
 */
data class PhoneMovePlan(
    /** Files that will be moved, in the order the user chose them. */
    val eligible: List<MediaItem>,
    val heldByTransfer: List<MediaItem>,
    val nameTaken: List<MediaItem>,
    /** Files that are already in the destination folder. */
    val alreadyThere: List<MediaItem>,
    val toBucketId: Long,
    /** The destination as MediaStore names it ("Pictures/Holiday/"), and its own name. */
    val toRelativePath: String,
    val toName: String,
) {
    val totalBytes: Long get() = eligible.sumOf { it.size }
}

fun planPhoneMove(
    items: List<MediaItem>,
    transfers: List<TransferEntity>,
    toBucketId: Long,
    toRelativePath: String,
    toName: String,
    /** Names already in the destination folder (any type MediaStore shows DriveLift there). */
    destinationNames: Collection<String>,
): PhoneMovePlan {
    val held = transfers.filter { it.status in TRANSFER_HOLDS_FILE }.map { it.mediaUri }.toSet()
    val taken = destinationNames.map { it.lowercase() }.toMutableSet()
    val eligible = ArrayList<MediaItem>()
    val heldByTransfer = ArrayList<MediaItem>()
    val nameTaken = ArrayList<MediaItem>()
    val alreadyThere = ArrayList<MediaItem>()
    for (item in items) {
        when {
            item.relativePath == toRelativePath -> alreadyThere += item
            item.uri.toString() in held -> heldByTransfer += item
            // add() is false when the name is taken there, or by a file already in this batch.
            !taken.add(item.name.lowercase()) -> nameTaken += item
            else -> eligible += item
        }
    }
    return PhoneMovePlan(eligible, heldByTransfer, nameTaken, alreadyThere, toBucketId, toRelativePath, toName)
}

/** What happened to each file of a confirmed phone move. */
data class PhoneMoveOutcome(
    val toName: String,
    val moved: List<MediaItem>,
    val failed: List<Pair<MediaItem, String>>,
    /** Confirmed files left alone because a transfer came to depend on them before their turn. */
    val skipped: List<MediaItem> = emptyList(),
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
        return listOfNotNull(base, held).joinToString("; ").ifEmpty { "Nothing was moved." }
    }
}

/**
 * Phone files waiting in the move pill: chosen in folder [fromBucketId], to be moved with
 * "Move here" in whichever phone folder the user opens next. Holding them changes nothing.
 */
data class PhoneMoveClip(
    val items: List<MediaItem>,
    val fromBucketId: Long,
    val fromName: String,
) {
    val totalBytes: Long get() = items.sumOf { it.size }

    fun canMoveInto(bucketId: Long): Boolean = bucketId != fromBucketId

    /** The pill without files whose ids are in [ids]; null once empty. */
    fun without(ids: Set<Long>): PhoneMoveClip? =
        copy(items = items.filterNot { it.id in ids }).takeIf { it.items.isNotEmpty() }

    /**
     * The pill after a confirmed move of [plan]: moved files leave it, and so do files no move can
     * take (held by a transfer, a name taken, already there). Files that failed stay for a retry.
     */
    fun afterMove(plan: PhoneMovePlan, outcome: PhoneMoveOutcome): PhoneMoveClip? {
        val done = (outcome.moved + outcome.skipped + plan.heldByTransfer + plan.nameTaken + plan.alreadyThere)
            .map { it.id }.toSet()
        return without(done)
    }

    /** The pill with each file as the phone listing shows it now; files no longer listed leave it. */
    fun refreshed(listing: List<MediaItem>): PhoneMoveClip? {
        val byId = listing.associateBy { it.id }
        return copy(items = items.mapNotNull { byId[it.id] }).takeIf { it.items.isNotEmpty() }
    }
}

/** A move pill for [selected] in folder [fromBucketId], or null when nothing is selected. */
fun holdPhoneForMove(selected: List<MediaItem>, fromBucketId: Long, fromName: String): PhoneMoveClip? =
    if (selected.isEmpty()) null else PhoneMoveClip(selected, fromBucketId, fromName)

/** Moves [uri] into the folder [relativePath] ("Pictures/Holiday/"). Throws when Android refuses. */
fun updateRelativePath(resolver: android.content.ContentResolver, uri: android.net.Uri, relativePath: String) {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
    }
    val rows = resolver.update(uri, values, null, null)
    if (rows < 1) throw java.io.IOException("Android did not accept the new folder")
}

/** [uri]'s folder (RELATIVE_PATH) as MediaStore shows it now, or null when it cannot be read. */
fun queryRelativePath(resolver: android.content.ContentResolver, uri: android.net.Uri): String? = try {
    resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}
