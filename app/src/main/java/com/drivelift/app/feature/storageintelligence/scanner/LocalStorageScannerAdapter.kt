package com.drivelift.app.feature.storageintelligence.scanner

import android.content.Context
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.MediaItem
import com.drivelift.app.data.MediaRepository
import com.drivelift.app.data.largestItems
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Adapter exposing DriveLift's existing read-only [MediaRepository] as a [StorageSourceScanner]
 * without altering MediaStore queries or permissions.
 */
class LocalStorageScannerAdapter(
    private val context: Context,
    private val mediaRepository: MediaRepository,
) : StorageSourceScanner {
    override val sourceId: String = LOCAL_SOURCE_ID
    override val sourceLabel: String = "This phone"
    override val sourceType: StorageSourceType = StorageSourceType.LOCAL
    override val supportsServerSizeFilter: Boolean = false
    override val supportsEmptyTrash: Boolean = false

    override suspend fun isAvailable(): Boolean =
        MediaAccess.hasPermission(context) || mediaRepository.items.value.isNotEmpty()

    override suspend fun scanAllFiles(
        onProgress: suspend (scannedCount: Int, bytesAnalyzed: Long) -> Unit,
    ): List<UnifiedFileNode> {
        if (MediaAccess.hasPermission(context)) {
            mediaRepository.refresh()
        }
        val items = mediaRepository.items.value
        var scanned = 0
        var bytes = 0L
        val out = ArrayList<UnifiedFileNode>(items.size)
        for (item in items) {
            currentCoroutineContext().ensureActive()
            out += item.toUnifiedNode()
            scanned++
            bytes += item.size
            if (scanned % 100 == 0 || scanned == items.size) {
                onProgress(scanned, bytes)
            }
        }
        return out
    }

    override suspend fun queryLargeFiles(
        minSizeBytes: Long,
        limit: Int,
        onProgress: suspend (scannedCount: Int, bytesAnalyzed: Long) -> Unit,
    ): List<UnifiedFileNode> {
        if (MediaAccess.hasPermission(context)) {
            mediaRepository.refresh()
        }
        val filtered = mediaRepository.items.value.filter { it.size >= minSizeBytes }
        val top = largestItems(filtered, limit)
        val totalBytes = top.sumOf { it.size }
        onProgress(top.size, totalBytes)
        return top.map { it.toUnifiedNode() }
    }

    override suspend fun scanOrphans(
        gracePeriodMs: Long,
        nowMs: Long,
    ): List<OrphanArtifactCandidate> = emptyList()

    private fun MediaItem.toUnifiedNode(): UnifiedFileNode {
        val folder = relativePath.trimEnd('/').ifBlank { bucketName }
        val fullPath = if (folder.isNotBlank()) "$folder/$name" else name
        val uriText = uri.toString()
        return UnifiedFileNode(
            id = "$LOCAL_SOURCE_ID:$id",
            nativeId = id.toString(),
            sourceId = LOCAL_SOURCE_ID,
            sourceLabel = sourceLabel,
            sourceType = StorageSourceType.LOCAL,
            name = name,
            path = fullPath,
            sizeBytes = size,
            mimeType = mime,
            modifiedAt = dateModifiedMs.takeIf { it > 0L } ?: dateTakenMs,
            hashAlgorithm = HashAlgorithm.NONE,
            hashValue = null,
            thumbnailRef = uriText,
            isTrashed = false,
            localUri = uriText,
            bucketId = bucketId,
            parentFolderId = null,
            isFolder = false,
            ownedByMe = true,
        )
    }
}
