package com.drivelift.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/*
 * Storage insights v1 (issue #42). Everything here works on the phone photos and videos that
 * DriveLift's MediaStore listing can see; nothing else on the phone is measured. Kept free of
 * Android UI so every rule can be tested on its own.
 */

/** Photo and video totals, and the same totals per folder. */
data class StorageBreakdown(
    val photoCount: Int,
    val photoBytes: Long,
    val videoCount: Int,
    val videoBytes: Long,
    /** Largest folder first. */
    val folders: List<FolderUsage>,
) {
    val totalCount: Int get() = photoCount + videoCount
    val totalBytes: Long get() = photoBytes + videoBytes
}

data class FolderUsage(
    val bucketId: Long,
    val name: String,
    val path: String,
    val photoCount: Int,
    val videoCount: Int,
    val bytes: Long,
) {
    val count: Int get() = photoCount + videoCount
}

fun storageBreakdown(items: List<MediaItem>): StorageBreakdown {
    val (videos, photos) = items.partition { it.isVideo }
    val folders = items.groupBy { it.bucketId }.map { (bucketId, list) ->
        val first = list.first()
        FolderUsage(
            bucketId = bucketId,
            name = first.bucketName,
            path = first.relativePath.trimEnd('/'),
            photoCount = list.count { !it.isVideo },
            videoCount = list.count { it.isVideo },
            bytes = list.sumOf { it.size },
        )
    }.sortedWith(compareByDescending<FolderUsage> { it.bytes }.thenBy { it.name })
    return StorageBreakdown(
        photoCount = photos.size,
        photoBytes = photos.sumOf { it.size },
        videoCount = videos.size,
        videoBytes = videos.sumOf { it.size },
        folders = folders,
    )
}

/** The [limit] largest files, largest first; equal sizes keep a stable order by name. */
fun largestItems(items: List<MediaItem>, limit: Int): List<MediaItem> =
    items.sortedWith(compareByDescending<MediaItem> { it.size }.thenBy { it.name }.thenBy { it.id }).take(limit)

/**
 * First step of the duplicate check: files that share an exact byte size with at least one other
 * file. Only these are worth reading. Empty files are skipped, since every empty file matches
 * every other and there is nothing to free. File names play no part.
 */
fun sizeCandidates(items: List<MediaItem>): List<List<MediaItem>> =
    items.filter { it.size > 0 }
        .groupBy { it.size }
        .values
        .filter { it.size >= 2 }
        .sortedByDescending { it.first().size }

/** Files whose bytes produced the same SHA-256 checksum and have the same size. */
data class DuplicateGroup(val size: Long, val sha256: String, val items: List<MediaItem>) {
    val key: String get() = "$size:$sha256"

    /** What removing all but one copy would free. */
    val reclaimableBytes: Long get() = size * (items.size - 1)
}

data class DuplicateResult(
    /** Largest reclaimable space first. */
    val groups: List<DuplicateGroup>,
    /** Files that were read and checksummed. */
    val filesRead: Int,
    /** Same-size files whose bytes could not be read; they are in no group. */
    val unreadable: List<MediaItem>,
)

/**
 * Second step: confirms candidates by content. Each same-size group is split by the SHA-256 of
 * the bytes, so files of equal size but different content never end up together. A file that
 * cannot be read ([digest] returns null) is left out of every group: unknown content is never
 * treated as a match.
 */
suspend fun findDuplicates(
    candidates: List<List<MediaItem>>,
    digest: suspend (MediaItem) -> String?,
): DuplicateResult {
    val groups = mutableListOf<DuplicateGroup>()
    val unreadable = mutableListOf<MediaItem>()
    var read = 0
    for (sameSize in candidates) {
        val byDigest = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in sameSize) {
            currentCoroutineContext().ensureActive()
            val sha = digest(item)
            if (sha == null) {
                unreadable += item
                continue
            }
            read++
            byDigest.getOrPut(sha) { mutableListOf() } += item
        }
        for ((sha, list) in byDigest) {
            if (list.size >= 2) groups += DuplicateGroup(sameSize.first().size, sha, list.sortedBy { it.dateTakenMs })
        }
    }
    return DuplicateResult(
        groups = groups.sortedWith(compareByDescending<DuplicateGroup> { it.reclaimableBytes }.thenBy { it.key }),
        filesRead = read,
        unreadable = unreadable,
    )
}

/**
 * Drops files that are no longer in [present] (trashed, moved, or no longer visible) and groups
 * that are left with fewer than two files.
 */
fun pruneGroups(groups: List<DuplicateGroup>, present: Collection<MediaItem>): List<DuplicateGroup> {
    val byId = present.associateBy { it.id }
    return groups.mapNotNull { group ->
        // A file whose size changed since the check is not the file that was read.
        val kept = group.items.filter { byId[it.id]?.size == group.size }
        if (kept.size >= 2) group.copy(items = kept) else null
    }
}

/**
 * The selection rule inside one group: a file can be selected only while at least one other
 * file in the group stays unselected, so every group always keeps a copy.
 */
fun canSelect(group: DuplicateGroup, selected: Set<Long>, id: Long): Boolean =
    id in selected || group.items.count { it.id != id && it.id !in selected } >= 1

/** Why a selected file was left out of a duplicate cleanup. */
enum class LeftOutReason {
    /** A transfer still depends on it (same hold rule as every phone delete). */
    IN_TRANSFER,

    /** Read again just now, its bytes no longer match the group, or it could not be read. */
    CONTENT_CHANGED,

    /** No unselected copy in its group could be confirmed again, so it is the copy that stays. */
    LAST_COPY,

    /** It is no longer in the phone listing. */
    GONE,
}

data class DuplicateCleanupPlan(
    /** The files that go to the phone's trash, group by group. */
    val eligible: List<MediaItem>,
    val leftOut: List<Pair<MediaItem, LeftOutReason>>,
    /** For each group with an eligible file, the copy that was confirmed to stay. */
    val kept: List<MediaItem>,
) {
    val totalBytes: Long get() = eligible.sumOf { it.size }
    fun leftOut(reason: LeftOutReason): List<MediaItem> = leftOut.filter { it.second == reason }.map { it.first }
}

/**
 * Works out a duplicate cleanup for the files the user selected inside [groups]. Nothing is
 * changed. [freshDigest] reads a file's bytes again now (null when it cannot be read): every
 * selected file and the copies that are meant to stay are checked again here, so the decision
 * rests on what is on the phone at this moment, not on the earlier scan.
 *
 * Per group:
 * - a selected file a transfer depends on is left out ([TRANSFER_HOLDS_FILE]);
 * - a selected file whose bytes no longer match the group is left out;
 * - at least one unselected file must still be present with matching bytes; if none is, every
 *   selected file in the group is left out, so the group keeps a copy;
 * - if every file in the group was selected (the screen does not allow it), the first one is
 *   kept back as the copy that stays.
 */
suspend fun planDuplicateCleanup(
    groups: List<DuplicateGroup>,
    selected: Set<Long>,
    present: Collection<MediaItem>,
    transfers: List<TransferEntity>,
    freshDigest: suspend (MediaItem) -> String?,
): DuplicateCleanupPlan {
    val held = transfers.filter { it.status in TRANSFER_HOLDS_FILE }.mapTo(HashSet()) { it.mediaUri }
    val byId = present.associateBy { it.id }
    val eligible = mutableListOf<MediaItem>()
    val leftOut = mutableListOf<Pair<MediaItem, LeftOutReason>>()
    val kept = mutableListOf<MediaItem>()
    for (group in groups) {
        val chosen = group.items.filter { it.id in selected }
        if (chosen.isEmpty()) continue
        var retainCandidates = group.items.filter { it.id !in selected }
        var toCheck = chosen
        if (retainCandidates.isEmpty()) {
            // Never every copy: the first stays.
            leftOut += chosen.first() to LeftOutReason.LAST_COPY
            retainCandidates = listOf(chosen.first())
            toCheck = chosen.drop(1)
        }
        // A copy that stays must be on the phone now, at the same size, with the same bytes.
        val keeper = retainCandidates.firstOrNull { candidate ->
            val now = byId[candidate.id]
            now != null && now.size == group.size && freshDigest(now) == group.sha256
        }
        if (keeper == null) {
            toCheck.forEach { leftOut += it to LeftOutReason.LAST_COPY }
            continue
        }
        val groupEligible = mutableListOf<MediaItem>()
        for (item in toCheck) {
            val now = byId[item.id]
            when {
                now == null -> leftOut += item to LeftOutReason.GONE
                item.uri.toString() in held -> leftOut += item to LeftOutReason.IN_TRANSFER
                now.size != group.size || freshDigest(now) != group.sha256 ->
                    leftOut += item to LeftOutReason.CONTENT_CHANGED
                else -> groupEligible += now
            }
        }
        if (groupEligible.isNotEmpty()) {
            eligible += groupEligible
            kept += keeper
        }
    }
    return DuplicateCleanupPlan(eligible, leftOut, kept)
}

/**
 * SHA-256 of everything [input] yields, as lowercase hex. Checks for cancellation between reads,
 * so a long file stops promptly when the check is cancelled. [onRead] gets each chunk's length.
 */
suspend fun sha256Of(input: InputStream, onRead: (Int) -> Unit = {}): String = input.use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        onRead(read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private const val HASH_BUFFER = 256 * 1024
