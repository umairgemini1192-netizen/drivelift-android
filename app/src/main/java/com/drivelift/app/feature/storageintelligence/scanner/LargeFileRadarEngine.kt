package com.drivelift.app.feature.storageintelligence.scanner

import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.feature.storageintelligence.model.LargeFileCategory
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.model.classifyLargeFileCategory
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException

data class LargeFileRadarOutput(
    val items: List<UnifiedFileNode>,
    val errors: List<SourceScanError>,
)

/**
 * Aggregates and filters large files across local and cloud [StorageSourceScanner]s.
 * Uses provider-side large-file sorting/filtering when available and degrades gracefully
 * per source when one provider fails.
 */
class LargeFileRadarEngine {

    suspend fun scan(
        scanners: List<StorageSourceScanner>,
        minSizeBytes: Long,
        topN: Int,
        onSourceProgress: suspend (scanner: StorageSourceScanner, count: Int, bytes: Long) -> Unit = { _, _, _ -> },
    ): LargeFileRadarOutput {
        val collected = ArrayList<UnifiedFileNode>()
        val errors = ArrayList<SourceScanError>()
        for (scanner in scanners) {
            if (!scanner.isAvailable()) continue
            try {
                val found = scanner.queryLargeFiles(minSizeBytes, topN) { count, bytes ->
                    onSourceProgress(scanner, count, bytes)
                }
                collected += found.filter { !it.isFolder && !it.isTrashed && it.sizeBytes >= minSizeBytes }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                errors += SourceScanError(
                    sourceId = scanner.sourceId,
                    sourceLabel = scanner.sourceLabel,
                    message = describeError(e),
                    authExpired = true,
                )
            } catch (e: Exception) {
                errors += SourceScanError(
                    sourceId = scanner.sourceId,
                    sourceLabel = scanner.sourceLabel,
                    message = describeError(e),
                    authExpired = false,
                )
            }
        }
        val sorted = collected
            .sortedWith(compareByDescending<UnifiedFileNode> { it.sizeBytes }.thenBy { it.name }.thenBy { it.id })
            .take(topN)
        return LargeFileRadarOutput(items = sorted, errors = errors)
    }

    fun filter(
        items: List<UnifiedFileNode>,
        minSizeBytes: Long,
        sourceFilterId: String?,
        categoryFilter: LargeFileCategory,
        topN: Int,
    ): List<UnifiedFileNode> = items.asSequence()
        .filter { !it.isFolder && !it.isTrashed }
        .filter { it.sizeBytes >= minSizeBytes }
        .filter { sourceFilterId == null || it.sourceId == sourceFilterId }
        .filter {
            categoryFilter == LargeFileCategory.ALL ||
                classifyLargeFileCategory(it.name, it.mimeType) == categoryFilter
        }
        .sortedWith(compareByDescending<UnifiedFileNode> { it.sizeBytes }.thenBy { it.name }.thenBy { it.id })
        .take(topN)
        .toList()
}
