package com.drivelift.app.feature.storageintelligence.scanner

import android.content.Context
import com.drivelift.app.data.AppDatabase
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class OrphanScanOutput(
    val artifacts: List<OrphanArtifactCandidate>,
    val errors: List<SourceScanError>,
)

/**
 * Scans app-private local caches, abandoned `.part`/`.tmp` transfer files, expired resumable
 * upload session metadata, and cloud orphans, enforcing [OrphanSafetyLock] on every candidate.
 */
class OrphanCacheScanner(
    private val context: Context,
    private val appDb: AppDatabase,
    private val safetyLock: OrphanSafetyLock,
    private val lockProvider: TransferLockProvider,
    private val extraCacheRoots: List<File> = emptyList(),
) {

    suspend fun scan(
        cloudScanners: List<StorageSourceScanner>,
        includeLocal: Boolean,
        gracePeriodMs: Long,
        nowMs: Long,
    ): OrphanScanOutput {
        val lock = lockProvider.snapshot()
        val raw = ArrayList<OrphanArtifactCandidate>()
        val errors = ArrayList<SourceScanError>()

        if (includeLocal) {
            raw += scanLocalAppCachesAndTempFiles()
            raw += scanExpiredTransferSessions()
        }

        for (scanner in cloudScanners) {
            if (!scanner.isAvailable()) continue
            try {
                raw += scanner.scanOrphans(gracePeriodMs, nowMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += SourceScanError(
                    sourceId = scanner.sourceId,
                    sourceLabel = scanner.sourceLabel,
                    message = describeError(e),
                    authExpired = e is com.drivelift.app.drive.AuthRequiredException,
                )
            }
        }

        val safe = raw
            .distinctBy { it.artifactId }
            .filter { safetyLock.isSafeToFlag(it, lock, nowMs, gracePeriodMs) }
            .sortedWith(compareByDescending<OrphanArtifactCandidate> { it.sizeBytes }.thenBy { it.name })

        return OrphanScanOutput(artifacts = safe, errors = errors)
    }

    private suspend fun scanLocalAppCachesAndTempFiles(): List<OrphanArtifactCandidate> =
        withContext(Dispatchers.IO) {
            val candidates = ArrayList<OrphanArtifactCandidate>()
            val cacheRoots = buildList {
                context.cacheDir?.let { add(it) }
                context.externalCacheDir?.let { add(it) }
                addAll(extraCacheRoots)
            }.distinctBy { it.absolutePath }

            for (root in cacheRoots) {
                if (!root.exists()) continue
                collectFiles(root).forEach { file ->
                    val nameLower = file.name.lowercase()
                    val isTempArtifact = nameLower.endsWith(".part") ||
                        nameLower.endsWith(".tmp") ||
                        nameLower.endsWith(".temp")
                    val category = if (isTempArtifact) {
                        OrphanCategory.TRANSFER_ORPHAN
                    } else {
                        OrphanCategory.LOCAL_APP_CACHE
                    }
                    val detail = when {
                        isTempArtifact -> "Abandoned temporary or partial file"
                        file.absolutePath.contains("drive_preview") -> "Cached Drive thumbnail or preview"
                        file.absolutePath.contains("exoplayer") || file.absolutePath.contains("media") ->
                            "Cached media or video preview data"
                        file.absolutePath.contains("image_cache") -> "Cached thumbnail image"
                        else -> "App cache file"
                    }
                    candidates += OrphanArtifactCandidate(
                        artifactId = "local-cache:${file.absolutePath}",
                        sourceId = LOCAL_SOURCE_ID,
                        sourceLabel = "This phone",
                        sourceType = StorageSourceType.LOCAL,
                        category = category,
                        name = file.name,
                        path = file.absolutePath,
                        sizeBytes = file.length().coerceAtLeast(0L),
                        modifiedAt = file.lastModified(),
                        itemCount = 1,
                        nativeId = file.absolutePath,
                        detail = detail,
                        canClean = true,
                    )
                }
            }
            candidates
        }

    private suspend fun scanExpiredTransferSessions(): List<OrphanArtifactCandidate> {
        val dao = appDb.transfers()
        val terminalRows = dao.byStatus(TransferStatus.FAILED.name, 500) +
            dao.byStatus(TransferStatus.CANCELLED.name, 500)
        return terminalRows
            .filter { !it.sessionUri.isNullOrBlank() }
            .map { row ->
                OrphanArtifactCandidate(
                    artifactId = "transfer-session:${row.id}",
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL,
                    category = OrphanCategory.TRANSFER_ORPHAN,
                    name = row.name,
                    path = "Expired upload session (${row.status.lowercase()})",
                    sizeBytes = 0L,
                    modifiedAt = row.createdAt,
                    itemCount = 1,
                    nativeId = row.id.toString(),
                    detail = "Upload session metadata for ${row.status.lowercase()} transfer",
                    localUri = row.mediaUri,
                    transferStatus = row.status,
                    canClean = true,
                )
            }
    }

    private fun collectFiles(dir: File): List<File> {
        val result = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && result.size < MAX_LOCAL_CACHE_FILES) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (child.isFile) {
                    result += child
                }
            }
        }
        return result
    }

    companion object {
        private const val MAX_LOCAL_CACHE_FILES = 5_000
    }
}
