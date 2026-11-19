package com.drivelift.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/*
 * Storage insights: Google Drive folder details (issue #42). Works out file counts and sizes per
 * folder in the user's My Drive from one flat, paged listing. Kept free of Android and network
 * code so every counting rule can be tested on its own.
 *
 * These totals are file sizes in folders. They are not the account quota, which Drive, Gmail and
 * Google Photos share and which Drive reports separately.
 */

/** One item from the flat listing, with only the fields the totals need. */
data class DriveEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Bytes Drive reports, or null when it reports none (Google Docs, Sheets, Slides and similar). */
    val size: Long?,
    val parents: List<String>,
    val ownedByMe: Boolean = true,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE
    val isShortcut: Boolean get() = mimeType == SHORTCUT_MIME_TYPE
}

/** One page of the flat listing and the token for the next page, or null at the end. */
data class DriveEntryPage(val entries: List<DriveEntry>, val nextPageToken: String?)

const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
const val SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut"

/** Totals for one folder. [fileCount], [bytes] and the other counts include every subfolder. */
data class DriveFolderTotal(
    val id: String,
    val name: String,
    /** Files (not folders) inside, at any depth, each counted once. Shortcuts are included here. */
    val fileCount: Int,
    /** Sum of reported sizes of those files. */
    val bytes: Long,
    /** Files inside with no reported size: counted in [fileCount], not in [bytes]. */
    val unsizedCount: Int,
    /** Shortcuts inside: counted as items, never followed, so their targets are not counted again. */
    val shortcutCount: Int,
    /** Part of [bytes] in files other people own; those bytes do not use this account's quota. */
    val othersBytes: Long,
    /** Folders inside, at any depth. */
    val folderCount: Int,
    /** Files directly in this folder, not in a subfolder. */
    val directFileCount: Int,
    val directBytes: Long,
    /** Ids of the folders directly inside, largest first. */
    val children: List<String>,
)

/** The counted tree of My Drive. */
data class DriveFolderTree(
    val rootId: String,
    val folders: Map<String, DriveFolderTotal>,
    /** Items in the listing that are not inside My Drive (shared with the user, not added). */
    val outsideCount: Int,
) {
    val root: DriveFolderTotal get() = folders.getValue(rootId)

    /** Folders directly inside [folderId], largest first. Empty for an unknown id. */
    fun childrenOf(folderId: String): List<DriveFolderTotal> =
        folders[folderId]?.children?.mapNotNull { folders[it] } ?: emptyList()

    /** The folders from the root down to [folderId], root first; just the root when it is unknown. */
    fun pathTo(folderId: String): List<DriveFolderTotal> {
        val parentOf = HashMap<String, String>()
        folders.values.forEach { f -> f.children.forEach { child -> parentOf.putIfAbsent(child, f.id) } }
        val out = ArrayList<DriveFolderTotal>()
        val seen = HashSet<String>()
        var id: String? = folderId.takeIf { it in folders }
        while (id != null && seen.add(id)) {
            out += folders.getValue(id)
            id = if (id == rootId) null else parentOf[id]
        }
        if (out.lastOrNull()?.id != rootId) return listOf(root)
        return out.asReversed()
    }
}

/**
 * Counts [entries] into folder totals under [rootId], the user's My Drive root.
 *
 * - Only items reachable from [rootId] through their parents count; the rest are [DriveFolderTree.outsideCount].
 * - A file with several parents is counted once in each folder above it and once in the root,
 *   never twice in the same folder, however many of its paths lead there.
 * - A shortcut is a file of its own and is never followed.
 * - A file with no reported size is counted but adds no bytes.
 * - A loop in the parents (not expected from Drive) cannot make a folder its own ancestor twice
 *   or hang the count.
 */
fun driveFolderTotals(rootId: String, rootName: String, entries: Collection<DriveEntry>): DriveFolderTree {
    val byId = HashMap<String, DriveEntry>(entries.size * 2)
    for (e in entries) byId.putIfAbsent(e.id, e)
    val folderIds = byId.values.filter { it.isFolder && it.id != rootId }.mapTo(HashSet()) { it.id }

    // Folders inside My Drive: walked down from the root, each visited once, so a loop ends.
    val childFolders = HashMap<String, MutableList<String>>()
    for (id in folderIds) byId.getValue(id).parents.forEach { p -> childFolders.getOrPut(p) { ArrayList() } += id }
    val reachable = HashSet<String>().apply { add(rootId) }
    val queue = ArrayDeque<String>().apply { add(rootId) }
    while (queue.isNotEmpty()) {
        childFolders[queue.removeFirst()]?.forEach { if (reachable.add(it)) queue.add(it) }
    }

    // Every My Drive folder above a folder, itself included: walked up through its parents, each
    // folder once, so several paths to the same folder count it once and a loop ends. Memoised.
    val ancestorsMemo = HashMap<String, Set<String>>()
    fun ancestorsOfFolder(id: String): Set<String>? {
        if (id !in reachable) return null
        ancestorsMemo[id]?.let { return it }
        val seen = HashSet<String>().apply { add(id) }
        val up = ArrayDeque<String>().apply { add(id) }
        while (up.isNotEmpty()) {
            val current = up.removeFirst()
            if (current == rootId) continue
            byId[current]?.parents?.forEach { p -> if (p in reachable && seen.add(p)) up.add(p) }
        }
        ancestorsMemo[id] = seen
        return seen
    }

    fun ancestorsOf(entry: DriveEntry): Set<String>? {
        var result: MutableSet<String>? = null
        for (parent in entry.parents) {
            val up = ancestorsOfFolder(parent) ?: continue
            if (result == null) result = HashSet()
            result += up
        }
        return result
    }

    class Acc(val name: String) {
        var files = 0
        var bytes = 0L
        var unsized = 0
        var shortcuts = 0
        var others = 0L
        var folders = 0
        var directFiles = 0
        var directBytes = 0L
        val children = LinkedHashSet<String>()
    }

    val acc = HashMap<String, Acc>()
    acc[rootId] = Acc(rootName)
    for (id in folderIds) if (id in reachable) acc[id] = Acc(byId.getValue(id).name)

    var outside = 0
    for (entry in byId.values) {
        if (entry.id == rootId) continue
        if (entry.isFolder) {
            val above = ancestorsOfFolder(entry.id)
            if (above == null) { outside++; continue }
            above.forEach { if (it != entry.id) acc.getValue(it).folders++ }
            entry.parents.forEach { p -> if (p != entry.id) acc[p]?.children?.add(entry.id) }
            continue
        }
        val above = ancestorsOf(entry)
        if (above == null) { outside++; continue }
        val size = entry.size
        for (folderId in above) {
            val a = acc.getValue(folderId)
            a.files++
            if (entry.isShortcut) a.shortcuts++
            when {
                entry.isShortcut -> Unit
                size == null -> a.unsized++
                else -> {
                    a.bytes += size
                    if (!entry.ownedByMe) a.others += size
                }
            }
        }
        // Direct counts: once per direct parent that is inside My Drive.
        entry.parents.distinct().forEach { p ->
            val a = acc[p] ?: return@forEach
            a.directFiles++
            if (!entry.isShortcut && size != null) a.directBytes += size
        }
    }

    val bytesOf = acc.mapValues { it.value.bytes }
    val totals = acc.mapValues { (id, a) ->
        DriveFolderTotal(
            id = id,
            name = a.name,
            fileCount = a.files,
            bytes = a.bytes,
            unsizedCount = a.unsized,
            shortcutCount = a.shortcuts,
            othersBytes = a.others,
            folderCount = a.folders,
            directFileCount = a.directFiles,
            directBytes = a.directBytes,
            children = a.children.sortedWith(
                compareByDescending<String> { bytesOf[it] ?: 0L }.thenBy { acc[it]?.name?.lowercase() }.thenBy { it },
            ),
        )
    }
    return DriveFolderTree(rootId, totals, outside)
}

/** Why a Drive listing ended before its last page. */
sealed interface ScanStop {
    /** The user pressed Stop. */
    data object ByUser : ScanStop

    /** More items than [DRIVE_SCAN_MAX_ITEMS]; counting stopped there to bound memory and time. */
    data object TooMany : ScanStop

    /** The connected account changed while counting. */
    data object AccountChanged : ScanStop

    /** A network or Drive error; [message] says what went wrong. */
    data class Error(val message: String, val needsSignIn: Boolean) : ScanStop
}

/** What a listing pass produced: everything read so far, and why it stopped early, if it did. */
data class DriveScan(val entries: List<DriveEntry>, val pages: Int, val stop: ScanStop?) {
    val complete: Boolean get() = stop == null
}

const val DRIVE_SCAN_MAX_ITEMS = 500_000

/**
 * Reads the flat listing page by page until Drive gives no next page token. [onPage] gets the
 * running item and page counts. Stops early, keeping what was read, when:
 * - [accountStillPinned] turns false (checked before every page);
 * - the item count passes [maxItems];
 * - Drive repeats a page token, which would otherwise loop forever;
 * - [fetch] throws: [describe] turns the error into a reason ([ScanStop.Error]).
 * Cancellation is passed on, never turned into an incomplete result.
 */
suspend fun scanDriveEntries(
    fetch: suspend (pageToken: String?) -> DriveEntryPage,
    accountStillPinned: () -> Boolean,
    describe: (Exception) -> ScanStop.Error,
    maxItems: Int = DRIVE_SCAN_MAX_ITEMS,
    stopRequested: () -> Boolean = { false },
    onPage: (items: Int, pages: Int) -> Unit = { _, _ -> },
): DriveScan {
    val entries = ArrayList<DriveEntry>()
    val seenTokens = HashSet<String>()
    var token: String? = null
    var pages = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!accountStillPinned()) return DriveScan(entries, pages, ScanStop.AccountChanged)
        if (stopRequested()) return DriveScan(entries, pages, ScanStop.ByUser)
        val page = try {
            fetch(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!accountStillPinned()) return DriveScan(entries, pages, ScanStop.AccountChanged)
            if (stopRequested()) return DriveScan(entries, pages, ScanStop.ByUser)
            return DriveScan(entries, pages, describe(e))
        }
        if (!accountStillPinned()) return DriveScan(entries, pages, ScanStop.AccountChanged)
        entries += page.entries
        pages++
        onPage(entries.size, pages)
        if (entries.size >= maxItems && page.nextPageToken != null) return DriveScan(entries, pages, ScanStop.TooMany)
        val next = page.nextPageToken ?: return DriveScan(entries, pages, null)
        if (!seenTokens.add(next)) {
            return DriveScan(entries, pages, ScanStop.Error("Drive repeated a page while listing files", needsSignIn = false))
        }
        token = next
    }
}
