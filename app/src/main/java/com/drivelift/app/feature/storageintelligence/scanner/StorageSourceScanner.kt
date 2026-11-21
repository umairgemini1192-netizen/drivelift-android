package com.drivelift.app.feature.storageintelligence.scanner

import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode

/**
 * Abstraction for local and cloud storage backends scanned by Storage Intelligence.
 * Existing repositories and cloud clients are wrapped by adapters implementing this interface.
 */
interface StorageSourceScanner {
    val sourceId: String
    val sourceLabel: String
    val sourceType: StorageSourceType
    val supportsServerSizeFilter: Boolean
    val supportsEmptyTrash: Boolean

    suspend fun isAvailable(): Boolean

    suspend fun scanAllFiles(
        onProgress: suspend (scannedCount: Int, bytesAnalyzed: Long) -> Unit = { _, _ -> },
    ): List<UnifiedFileNode>

    suspend fun queryLargeFiles(
        minSizeBytes: Long,
        limit: Int,
        onProgress: suspend (scannedCount: Int, bytesAnalyzed: Long) -> Unit = { _, _ -> },
    ): List<UnifiedFileNode>

    suspend fun scanOrphans(
        gracePeriodMs: Long,
        nowMs: Long,
    ): List<OrphanArtifactCandidate>

    /** Empties cloud trash only when supported by the provider; returns false otherwise. */
    suspend fun emptyTrash(): Boolean = false
}
