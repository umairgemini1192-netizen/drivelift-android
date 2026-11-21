package com.drivelift.app.feature.storageintelligence.scanner

import com.drivelift.app.data.accountHash
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Multi-cloud compatible [StorageSourceScanner] backed by [DriveMetadataApi].
 * Performs zero-download metadata-only scanning using Drive's native `sha256Checksum` / `md5Checksum`.
 */
class GoogleDriveStorageScannerAdapter(
    private val api: DriveMetadataApi,
    private val maxScanItems: Int = DEFAULT_MAX_SCAN_ITEMS,
) : StorageSourceScanner {

    override val sourceId: String
        get() {
            val email = api.pinnedAccountEmail() ?: return "gdrive:disconnected"
            return "gdrive:${accountHash(email)}"
        }

    override val sourceLabel: String = "Google Drive"
    override val sourceType: StorageSourceType = StorageSourceType.GOOGLE_DRIVE
    override val supportsServerSizeFilter: Boolean = true

    /**
     * Rulebook invariant (`docs/AI_CONTEXT.md`): permanent deletion and emptying Drive's trash
     * are never requested automatically or programmatically.
     */
    override val supportsEmptyTrash: Boolean = false

    override suspend fun isAvailable(): Boolean = api.isConnected()

    override suspend fun scanAllFiles(
        onProgress: suspend (scannedCount: Int, bytesAnalyzed: Long) -> Unit,
    ): List<UnifiedFileNode> {
        val entries = collectAllEntries(onProgress)
        val foldersById = entries.filter { it.isFolder }.associateBy { it.id }
        val rootId = runCatching { api.myDriveRootId() }.getOrNull()
        val pathCache = HashMap<String, String>()
        return entries.filter { !it.isFolder && !it.trashed }.map { entry ->
            entry.toUnifiedNode(sourceId, buildPath(entry, foldersById, rootId, pathCache))
        }
    }

    override suspend fun queryLargeFiles(
        minSizeBytes: Long,
        limit: Int,
        onProgress: suspend (scannedCount: Int, bytesAnalyzed: Long) -> Unit,
    ): List<UnifiedFileNode> {
        val entries = api.queryLargestFiles(minSizeBytes, limit)
        var bytes = 0L
        val out = entries.mapIndexed { idx, entry ->
            currentCoroutineContext().ensureActive()
            bytes += entry.size
            if ((idx + 1) % 25 == 0 || idx + 1 == entries.size) {
                onProgress(idx + 1, bytes)
            }
            entry.toUnifiedNode(sourceId, entry.name)
        }
        return out
    }

    override suspend fun scanOrphans(
        gracePeriodMs: Long,
        nowMs: Long,
    ): List<OrphanArtifactCandidate> {
        val entries = collectAllEntries()
        val rootId = runCatching { api.myDriveRootId() }.getOrNull()
        val foldersById = entries.filter { it.isFolder }.associateBy { it.id }
        val pathCache = HashMap<String, String>()
        val candidates = ArrayList<OrphanArtifactCandidate>()

        val nonEmptyFolderIds = HashSet<String>()
        for (entry in entries) {
            if (!entry.isFolder && !entry.trashed) {
                entry.parents.forEach { nonEmptyFolderIds += it }
            }
        }

        // Mark folders that contain non-empty child folders as non-empty bottom-up.
        val parentLookup = foldersById.mapValues { it.value.parents }
        var changed = true
        while (changed) {
            changed = false
            for (folderId in nonEmptyFolderIds.toList()) {
                for (parent in parentLookup[folderId].orEmpty()) {
                    if (nonEmptyFolderIds.add(parent)) changed = true
                }
            }
        }

        for (entry in entries) {
            if (entry.trashed || !entry.ownedByMe) continue
            val path = buildPath(entry, foldersById, rootId, pathCache)
            when {
                !entry.isFolder && !entry.isGoogleWorkspaceDoc && entry.size == 0L -> {
                    candidates += OrphanArtifactCandidate(
                        artifactId = "cloud-stub:$sourceId:${entry.id}",
                        sourceId = sourceId,
                        sourceLabel = sourceLabel,
                        sourceType = StorageSourceType.GOOGLE_DRIVE,
                        category = OrphanCategory.CLOUD_ZERO_BYTE_STUB,
                        name = entry.name,
                        path = path,
                        sizeBytes = 0L,
                        modifiedAt = entry.modifiedAtMs,
                        itemCount = 1,
                        nativeId = entry.id,
                        detail = "0-byte file in Google Drive",
                        canClean = true,
                    )
                }
                entry.isFolder && entry.id != rootId && entry.id !in nonEmptyFolderIds -> {
                    candidates += OrphanArtifactCandidate(
                        artifactId = "cloud-folder:$sourceId:${entry.id}",
                        sourceId = sourceId,
                        sourceLabel = sourceLabel,
                        sourceType = StorageSourceType.GOOGLE_DRIVE,
                        category = OrphanCategory.CLOUD_EMPTY_FOLDER,
                        name = entry.name,
                        path = path,
                        sizeBytes = 0L,
                        modifiedAt = entry.modifiedAtMs,
                        itemCount = 1,
                        nativeId = entry.id,
                        detail = "Empty Google Drive folder",
                        canClean = true,
                    )
                }
            }
        }

        val trashSummary = runCatching { api.summarizeTrash() }.getOrNull()
        if (trashSummary != null && trashSummary.itemCount > 0) {
            val truncatedNote = if (trashSummary.truncated) " (partial sample)" else ""
            candidates += OrphanArtifactCandidate(
                artifactId = "cloud-trash:$sourceId",
                sourceId = sourceId,
                sourceLabel = sourceLabel,
                sourceType = StorageSourceType.GOOGLE_DRIVE,
                category = OrphanCategory.CLOUD_TRASH_SUMMARY,
                name = "Google Drive trash",
                path = "Trash",
                sizeBytes = trashSummary.totalBytes,
                modifiedAt = nowMs,
                itemCount = trashSummary.itemCount,
                nativeId = null,
                detail = "${trashSummary.itemCount} trashed items$truncatedNote. Manage in Google Drive.",
                canClean = supportsEmptyTrash,
            )
        }
        return candidates
    }

    private suspend fun collectAllEntries(
        onProgress: suspend (scannedCount: Int, bytesAnalyzed: Long) -> Unit = { _, _ -> },
    ): List<DriveMetadataEntry> {
        val out = ArrayList<DriveMetadataEntry>()
        val seenTokens = HashSet<String>()
        var token: String? = null
        var bytes = 0L
        do {
            currentCoroutineContext().ensureActive()
            val page = api.listActiveFilesPage(token)
            for (entry in page.entries) {
                out += entry
                bytes += entry.size
                if (out.size >= maxScanItems) break
            }
            onProgress(out.size, bytes)
            val next = page.nextPageToken
            if (next != null && !seenTokens.add(next)) {
                throw IOException("Drive repeated a page while scanning metadata")
            }
            token = next
        } while (token != null && out.size < maxScanItems)
        return out
    }

    private fun buildPath(
        entry: DriveMetadataEntry,
        foldersById: Map<String, DriveMetadataEntry>,
        rootId: String?,
        cache: HashMap<String, String>,
    ): String {
        val parentId = entry.parents.firstOrNull() ?: return entry.name
        val parentPath = folderPath(parentId, foldersById, rootId, cache, HashSet())
        return if (parentPath.isBlank()) entry.name else "$parentPath/${entry.name}"
    }

    private fun folderPath(
        folderId: String,
        foldersById: Map<String, DriveMetadataEntry>,
        rootId: String?,
        cache: HashMap<String, String>,
        visiting: MutableSet<String>,
    ): String {
        if (folderId == rootId) return "My Drive"
        cache[folderId]?.let { return it }
        if (!visiting.add(folderId)) return ""
        val folder = foldersById[folderId] ?: return ""
        val parentId = folder.parents.firstOrNull()
        val prefix = if (parentId != null) folderPath(parentId, foldersById, rootId, cache, visiting) else ""
        val resolved = if (prefix.isBlank()) folder.name else "$prefix/${folder.name}"
        cache[folderId] = resolved
        return resolved
    }

    private fun DriveMetadataEntry.toUnifiedNode(sourceId: String, path: String): UnifiedFileNode {
        val (primaryAlg, primaryVal, secondaryAlg, secondaryVal) = when {
            !sha256Checksum.isNullOrBlank() && !md5Checksum.isNullOrBlank() ->
                Quad(HashAlgorithm.SHA256, sha256Checksum.lowercase(), HashAlgorithm.MD5, md5Checksum.lowercase())
            !sha256Checksum.isNullOrBlank() ->
                Quad(HashAlgorithm.SHA256, sha256Checksum.lowercase(), HashAlgorithm.NONE, null)
            !md5Checksum.isNullOrBlank() ->
                Quad(HashAlgorithm.MD5, md5Checksum.lowercase(), HashAlgorithm.NONE, null)
            else ->
                Quad(HashAlgorithm.NONE, null, HashAlgorithm.NONE, null)
        }
        return UnifiedFileNode(
            id = "$sourceId:$id",
            nativeId = id,
            sourceId = sourceId,
            sourceLabel = sourceLabel,
            sourceType = StorageSourceType.GOOGLE_DRIVE,
            name = name,
            path = path,
            sizeBytes = size,
            mimeType = mimeType,
            modifiedAt = modifiedAtMs,
            hashAlgorithm = primaryAlg,
            hashValue = primaryVal,
            secondaryHashAlgorithm = secondaryAlg,
            secondaryHashValue = secondaryVal,
            thumbnailRef = thumbnailLink,
            isTrashed = trashed,
            localUri = null,
            bucketId = null,
            parentFolderId = parents.firstOrNull(),
            isFolder = isFolder,
            ownedByMe = ownedByMe,
        )
    }

    private data class Quad(
        val primaryAlg: HashAlgorithm,
        val primaryVal: String?,
        val secondaryAlg: HashAlgorithm,
        val secondaryVal: String?,
    )

    companion object {
        const val DEFAULT_MAX_SCAN_ITEMS = 50_000
    }
}
