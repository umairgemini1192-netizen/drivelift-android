package com.drivelift.app.feature.storageintelligence.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.drivelift.app.data.AppDatabase
import com.drivelift.app.data.MediaRepository
import com.drivelift.app.data.Prefs
import com.drivelift.app.data.TRANSFER_HOLDS_FILE
import com.drivelift.app.data.transferHolds
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.feature.storageintelligence.db.DuplicateGroupEntity
import com.drivelift.app.feature.storageintelligence.db.DuplicateItemEntity
import com.drivelift.app.feature.storageintelligence.db.StorageIntelligenceDatabase
import com.drivelift.app.feature.storageintelligence.db.StorageScanSessionEntity
import com.drivelift.app.feature.storageintelligence.db.encodeScanErrors
import com.drivelift.app.feature.storageintelligence.db.toCandidate
import com.drivelift.app.feature.storageintelligence.db.toEntity
import com.drivelift.app.feature.storageintelligence.db.toLargeFileEntity
import com.drivelift.app.feature.storageintelligence.db.toModel
import com.drivelift.app.feature.storageintelligence.db.toUnifiedNode
import com.drivelift.app.feature.storageintelligence.model.BatchCleanupPlan
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_GRACE_PERIOD_MS
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_LARGE_THRESHOLD_BYTES
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_LARGE_TOP_N
import com.drivelift.app.feature.storageintelligence.model.DuplicateGroupModel
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.ScanPhase
import com.drivelift.app.feature.storageintelligence.model.ScanProgress
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.SourceCleanupBreakdown
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.scanner.CrossSourceDuplicateEngine
import com.drivelift.app.feature.storageintelligence.scanner.LargeFileRadarEngine
import com.drivelift.app.feature.storageintelligence.scanner.OrphanCacheScanner
import com.drivelift.app.feature.storageintelligence.scanner.OrphanSafetyLock
import com.drivelift.app.feature.storageintelligence.scanner.StorageSourceScanner
import com.drivelift.app.feature.storageintelligence.scanner.TransferLockProvider
import com.drivelift.app.feature.storageintelligence.worker.StorageIntelligenceScanBus
import com.drivelift.app.feature.storageintelligence.worker.StorageIntelligenceWorker
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.TransferRepository
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class OrphanCleanupResult(
    val cleanedCount: Int,
    val freedBytes: Long,
    val failedCount: Int,
    val skippedLockedCount: Int,
) {
    fun message(): String = buildString {
        if (cleanedCount > 0) {
            append(if (cleanedCount == 1) "Cleaned 1 orphaned item" else "Cleaned $cleanedCount orphaned items")
        } else {
            append("Nothing was cleaned")
        }
        if (skippedLockedCount > 0) append("; $skippedLockedCount locked by active transfers")
        if (failedCount > 0) append("; $failedCount could not be removed")
    }
}

data class OffloadResult(
    val queuedCount: Int,
    val skippedCount: Int,
)

/**
 * Coordinates unified Storage Intelligence scans, Room persistence, safety checks,
 * orphan cleanup, and large-file cloud offloads while treating shared DriveLift engines
 * as read-only collaborators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageIntelligenceRepository(
    private val context: Context,
    private val db: StorageIntelligenceDatabase,
    private val localScanner: StorageSourceScanner,
    private val cloudScanners: List<StorageSourceScanner>,
    private val duplicateEngine: CrossSourceDuplicateEngine,
    private val largeFileEngine: LargeFileRadarEngine,
    private val orphanScanner: OrphanCacheScanner,
    private val orphanSafetyLock: OrphanSafetyLock,
    private val lockProvider: TransferLockProvider,
    private val scanBus: StorageIntelligenceScanBus,
    private val appDb: AppDatabase,
    private val transfers: TransferRepository,
    private val media: MediaRepository,
    private val drive: DriveClient,
    private val prefs: Prefs,
    private val clock: () -> Long,
) {
    private val dao = db.dao()

    val scanProgress: StateFlow<ScanProgress> = scanBus.progress

    val latestSession: Flow<StorageScanSessionEntity?> = dao.observeLatestSession()

    val duplicateGroups: Flow<List<DuplicateGroupModel>> = latestSession.flatMapLatest { session ->
        if (session == null) {
            flowOf(emptyList())
        } else {
            combine(
                dao.observeDuplicateGroups(session.sessionId),
                dao.observeDuplicateItems(session.sessionId),
            ) { groups, items ->
                val byGroup = items.groupBy { it.groupKey }
                groups.map { g -> g.toModel(byGroup[g.groupKey].orEmpty()) }
            }
        }
    }

    val largeFiles: Flow<List<UnifiedFileNode>> = latestSession.flatMapLatest { session ->
        if (session == null) flowOf(emptyList())
        else dao.observeLargeFiles(session.sessionId).map { list -> list.map { it.toUnifiedNode() } }
    }

    val orphans: Flow<List<OrphanArtifactCandidate>> = latestSession.flatMapLatest { session ->
        if (session == null) flowOf(emptyList())
        else dao.observeOrphans(session.sessionId).map { list -> list.map { it.toCandidate() } }
    }

    suspend fun availableSources(): List<Pair<String, String>> = buildList {
        add(localScanner.sourceId to localScanner.sourceLabel)
        for (cloud in cloudScanners) {
            if (cloud.isAvailable()) add(cloud.sourceId to cloud.sourceLabel)
        }
    }

    fun enqueueScan(
        scope: ScanScope,
        singleCloudSourceId: String? = null,
        thresholdBytes: Long = DEFAULT_LARGE_THRESHOLD_BYTES,
        topN: Int = DEFAULT_LARGE_TOP_N,
        gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    ) {
        scanBus.start()
        val request = OneTimeWorkRequestBuilder<StorageIntelligenceWorker>()
            .setInputData(
                StorageIntelligenceWorker.inputData(
                    scope = scope,
                    singleCloudSourceId = singleCloudSourceId,
                    thresholdBytes = thresholdBytes,
                    topN = topN,
                    gracePeriodMs = gracePeriodMs,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            StorageIntelligenceWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelScan() {
        scanBus.requestCancel()
        WorkManager.getInstance(context).cancelUniqueWork(StorageIntelligenceWorker.UNIQUE_WORK_NAME)
        scanBus.cancelled()
    }

    fun markScanCancelled() = scanBus.cancelled()

    fun markScanFailed(error: SourceScanError) = scanBus.failed(error)

    suspend fun executeScan(
        scope: ScanScope,
        singleCloudSourceId: String? = null,
        thresholdBytes: Long = DEFAULT_LARGE_THRESHOLD_BYTES,
        topN: Int = DEFAULT_LARGE_TOP_N,
        gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    ): StorageScanSessionEntity {
        val startedAt = clock()
        scanBus.start()

        val scanners = selectScanners(scope, singleCloudSourceId)
        val errors = ArrayList<SourceScanError>()
        val failedSourceIds = HashSet<String>()
        val allFiles = ArrayList<UnifiedFileNode>()
        var cumulativeScanned = 0
        var cumulativeBytes = 0L

        for (scanner in scanners) {
            ensureNotCancelled()
            if (!scanner.isAvailable()) continue
            scanBus.update(
                phase = ScanPhase.SCANNING_SOURCES,
                activeSourceId = scanner.sourceId,
                activeSourceLabel = scanner.sourceLabel,
            )
            try {
                val baseScanned = cumulativeScanned
                val baseBytes = cumulativeBytes
                val nodes = scanner.scanAllFiles { count, bytes ->
                    ensureNotCancelled()
                    scanBus.update(
                        scannedCount = baseScanned + count,
                        bytesAnalyzed = baseBytes + bytes,
                    )
                }
                allFiles += nodes
                cumulativeScanned += nodes.size
                cumulativeBytes += nodes.sumOf { it.sizeBytes }
                scanBus.update(
                    scannedCount = cumulativeScanned,
                    bytesAnalyzed = cumulativeBytes,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedSourceIds += scanner.sourceId
                errors += SourceScanError(
                    sourceId = scanner.sourceId,
                    sourceLabel = scanner.sourceLabel,
                    message = describeError(e),
                    authExpired = e is AuthRequiredException,
                )
                scanBus.update(errors = errors.toList())
            }
        }

        ensureNotCancelled()
        scanBus.update(
            phase = ScanPhase.HASHING_DUPLICATES,
            activeSourceId = LOCAL_SOURCE_ID,
            activeSourceLabel = "Duplicate analyzer",
        )
        var hashedBytes = 0L
        val duplicateOutput = duplicateEngine.detect(
            allNodes = allFiles,
            scope = scope,
            singleCloudSourceId = singleCloudSourceId,
        ) { _, delta ->
            ensureNotCancelled()
            hashedBytes += delta
            scanBus.update(bytesAnalyzed = cumulativeBytes + hashedBytes)
        }

        ensureNotCancelled()
        scanBus.update(
            phase = ScanPhase.INDEXING_LARGE_FILES,
            activeSourceId = null,
            activeSourceLabel = "Large-file radar",
        )
        val healthyScanners = scanners.filter { it.sourceId !in failedSourceIds }
        val largeOutput = largeFileEngine.scan(healthyScanners, thresholdBytes, topN)
        errors += largeOutput.errors

        ensureNotCancelled()
        scanBus.update(
            phase = ScanPhase.SCANNING_ORPHANS,
            activeSourceId = null,
            activeSourceLabel = "Orphaned caches",
        )
        val cloudUsed = healthyScanners.filter { it.sourceType.isCloud }
        val includeLocal = scope == ScanScope.LOCAL_ONLY || scope == ScanScope.CROSS_SOURCE
        val orphanOutput = orphanScanner.scan(
            cloudScanners = cloudUsed,
            includeLocal = includeLocal,
            gracePeriodMs = gracePeriodMs,
            nowMs = clock(),
        )
        errors += orphanOutput.errors

        val uniqueErrors = errors.distinctBy { it.sourceId to it.message }
        ensureNotCancelled()
        scanBus.update(phase = ScanPhase.SAVING, activeSourceLabel = "Saving results", errors = uniqueErrors)

        val duplicateReclaimable = duplicateOutput.groups.sumOf { it.reclaimableBytes }
        val largeTotal = largeOutput.items.sumOf { it.sizeBytes }
        val orphanReclaimable = orphanOutput.artifacts.filter { it.canClean }.sumOf { it.sizeBytes }
        val completedAt = clock()

        val sessionEntity = StorageScanSessionEntity(
            scope = scope.name,
            startedAtMs = startedAt,
            completedAtMs = completedAt,
            status = ScanPhase.COMPLETED.name,
            scannedFilesCount = cumulativeScanned,
            bytesAnalyzed = cumulativeBytes + hashedBytes,
            duplicateReclaimableBytes = duplicateReclaimable,
            largeFilesTotalBytes = largeTotal,
            orphanReclaimableBytes = orphanReclaimable,
            totalReclaimableBytes = duplicateReclaimable + orphanReclaimable,
            partialErrorsJson = encodeScanErrors(uniqueErrors),
            thresholdBytes = thresholdBytes,
        )

        val groupEntities = duplicateOutput.groups.map { g ->
            DuplicateGroupEntity(
                groupKey = g.groupKey,
                sessionId = 0L,
                matchKind = g.matchKind.name,
                hashAlgorithm = g.hashAlgorithm.name,
                hashValue = g.hashValue,
                normalizedName = g.normalizedName,
                sizeBytes = g.sizeBytes,
                itemCount = g.items.size,
                reclaimableBytes = g.reclaimableBytes,
                isCrossSource = g.isCrossSource,
            )
        }
        val itemEntities = duplicateOutput.groups.flatMap { g ->
            g.items.map { node ->
                DuplicateItemEntity(
                    sessionId = 0L,
                    groupKey = g.groupKey,
                    nodeId = node.id,
                    nativeId = node.nativeId,
                    sourceId = node.sourceId,
                    sourceLabel = node.sourceLabel,
                    sourceType = node.sourceType.name,
                    name = node.name,
                    path = node.path,
                    sizeBytes = node.sizeBytes,
                    mimeType = node.mimeType,
                    modifiedAt = node.modifiedAt,
                    hashAlgorithm = node.hashAlgorithm.name,
                    hashValue = node.hashValue,
                    thumbnailRef = node.thumbnailRef,
                    localUri = node.localUri,
                    bucketId = node.bucketId,
                    parentFolderId = node.parentFolderId,
                    isTrashed = node.isTrashed,
                )
            }
        }
        val largeEntities = largeOutput.items.map { it.toLargeFileEntity(0L) }
        val orphanEntities = orphanOutput.artifacts.map { it.toEntity(0L) }

        val savedId = dao.persistScanResult(
            session = sessionEntity,
            groups = groupEntities,
            duplicateItems = itemEntities,
            largeFiles = largeEntities,
            orphans = orphanEntities,
        )
        val savedSession = sessionEntity.copy(sessionId = savedId)
        scanBus.complete(
            sessionId = savedId,
            scannedCount = cumulativeScanned,
            bytesAnalyzed = cumulativeBytes + hashedBytes,
            errors = uniqueErrors,
        )
        return savedSession
    }

    /**
     * Queries only Large-File Radar with updated threshold and persists the refreshed list
     * in the current session without re-running full duplicate hashing.
     */
    suspend fun refreshLargeFiles(
        scope: ScanScope,
        singleCloudSourceId: String? = null,
        thresholdBytes: Long,
        topN: Int = DEFAULT_LARGE_TOP_N,
    ): LargeFileRadarOutputSummary {
        val scanners = selectScanners(scope, singleCloudSourceId)
        val output = largeFileEngine.scan(scanners, thresholdBytes, topN)
        val latest = dao.latestSessionOnce()
        if (latest != null) {
            dao.persistScanResult(
                session = latest.copy(
                    thresholdBytes = thresholdBytes,
                    largeFilesTotalBytes = output.items.sumOf { it.sizeBytes },
                    partialErrorsJson = encodeScanErrors(output.errors),
                    completedAtMs = clock(),
                ),
                groups = dao.duplicateGroupsForSession(latest.sessionId),
                duplicateItems = dao.duplicateItemsForSession(latest.sessionId),
                largeFiles = output.items.map { it.toLargeFileEntity(0L) },
                orphans = dao.orphansForSession(latest.sessionId),
            )
        }
        return LargeFileRadarOutputSummary(output.items, output.errors)
    }

    data class LargeFileRadarOutputSummary(
        val items: List<UnifiedFileNode>,
        val errors: List<SourceScanError>,
    )

    suspend fun activeProtectedNodeIds(): Set<String> {
        val lock = lockProvider.snapshot()
        val out = HashSet<String>()
        val session = dao.latestSessionOnce() ?: return out
        val items = dao.duplicateItemsForSession(session.sessionId)
        for (item in items) {
            val isLocalHeld = item.localUri != null && item.localUri in lock.activeMediaUris
            val isCloudHeld = item.nativeId in lock.activeDriveFileIds || item.name in lock.activeFileNames
            if (isLocalHeld || isCloudHeld) out += item.nodeId
        }
        return out
    }

    suspend fun buildDuplicateCleanupPlan(
        selectedNodeIds: Set<String>,
        groups: List<DuplicateGroupModel>,
    ): BatchCleanupPlan {
        val lock = lockProvider.snapshot()
        val transferDao = appDb.transfers()
        val localMediaIds = ArrayList<Long>()
        val localNodeIds = ArrayList<String>()
        val cloudDriveFiles = ArrayList<DriveFile>()
        val cloudNodeIds = ArrayList<String>()
        val perSourceNodes = LinkedHashMap<String, MutableList<UnifiedFileNode>>()
        var skippedHeld = 0
        var skippedLastCopy = 0

        for (group in groups) {
            val chosen = group.items.filter { it.id in selectedNodeIds }
            if (chosen.isEmpty()) continue
            var retainedCount = group.items.count { it.id !in selectedNodeIds }
            val candidates = ArrayList<UnifiedFileNode>(chosen.size)
            for (node in chosen) {
                if (retainedCount == 0) {
                    retainedCount++
                    skippedLastCopy++
                    continue
                }
                candidates += node
            }
            for (node in candidates) {
                val held = isNodeHeldByTransfer(node, lock, transferDao)
                if (held) {
                    skippedHeld++
                    continue
                }
                if (node.isLocal) {
                    val mediaId = node.nativeId.toLongOrNull()
                    if (mediaId != null) {
                        localMediaIds += mediaId
                        localNodeIds += node.id
                        perSourceNodes.getOrPut(node.sourceId) { ArrayList() } += node
                    }
                } else {
                    cloudDriveFiles += node.toDriveFile()
                    cloudNodeIds += node.id
                    perSourceNodes.getOrPut(node.sourceId) { ArrayList() } += node
                }
            }
        }

        val breakdown = perSourceNodes.entries.map { (sourceId, nodes) ->
            val first = nodes.first()
            SourceCleanupBreakdown(
                sourceId = sourceId,
                sourceLabel = first.sourceLabel,
                sourceType = first.sourceType,
                itemCount = nodes.size,
                reclaimableBytes = nodes.sumOf { it.sizeBytes },
                destinationNote = if (first.isLocal) {
                    "Moved to Android trash (recoverable)"
                } else {
                    "Moved to Google Drive trash (recoverable)"
                },
            )
        }
        return BatchCleanupPlan(
            title = "Move duplicate copies to trash?",
            perSourceBreakdown = breakdown,
            localEligibleMediaIds = localMediaIds,
            localEligibleNodeIds = localNodeIds,
            cloudEligibleFiles = cloudDriveFiles,
            cloudEligibleNodeIds = cloudNodeIds,
            skippedHeldCount = skippedHeld,
            skippedLastCopyCount = skippedLastCopy,
            totalReclaimableBytes = breakdown.sumOf { it.reclaimableBytes },
        )
    }

    suspend fun buildLargeFileCleanupPlan(
        selectedNodes: List<UnifiedFileNode>,
    ): BatchCleanupPlan {
        val lock = lockProvider.snapshot()
        val transferDao = appDb.transfers()
        val localMediaIds = ArrayList<Long>()
        val localNodeIds = ArrayList<String>()
        val cloudDriveFiles = ArrayList<DriveFile>()
        val cloudNodeIds = ArrayList<String>()
        val perSourceNodes = LinkedHashMap<String, MutableList<UnifiedFileNode>>()
        var skippedHeld = 0

        for (node in selectedNodes) {
            if (isNodeHeldByTransfer(node, lock, transferDao)) {
                skippedHeld++
                continue
            }
            if (node.isLocal) {
                val mediaId = node.nativeId.toLongOrNull()
                if (mediaId != null) {
                    localMediaIds += mediaId
                    localNodeIds += node.id
                    perSourceNodes.getOrPut(node.sourceId) { ArrayList() } += node
                }
            } else {
                cloudDriveFiles += node.toDriveFile()
                cloudNodeIds += node.id
                perSourceNodes.getOrPut(node.sourceId) { ArrayList() } += node
            }
        }

        val breakdown = perSourceNodes.entries.map { (sourceId, nodes) ->
            val first = nodes.first()
            SourceCleanupBreakdown(
                sourceId = sourceId,
                sourceLabel = first.sourceLabel,
                sourceType = first.sourceType,
                itemCount = nodes.size,
                reclaimableBytes = nodes.sumOf { it.sizeBytes },
                destinationNote = if (first.isLocal) {
                    "Moved to Android trash (recoverable)"
                } else {
                    "Moved to Google Drive trash (recoverable)"
                },
            )
        }
        return BatchCleanupPlan(
            title = "Move selected large files to trash?",
            perSourceBreakdown = breakdown,
            localEligibleMediaIds = localMediaIds,
            localEligibleNodeIds = localNodeIds,
            cloudEligibleFiles = cloudDriveFiles,
            cloudEligibleNodeIds = cloudNodeIds,
            skippedHeldCount = skippedHeld,
            totalReclaimableBytes = breakdown.sumOf { it.reclaimableBytes },
        )
    }

    suspend fun buildOrphanCleanupPlan(
        selectedOrphans: List<OrphanArtifactCandidate>,
        gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    ): BatchCleanupPlan {
        val lock = lockProvider.snapshot()
        val now = clock()
        val cleanable = ArrayList<OrphanArtifactCandidate>()
        var skippedLocked = 0

        for (candidate in selectedOrphans) {
            if (!candidate.canClean || candidate.category == OrphanCategory.CLOUD_TRASH_SUMMARY) continue
            if (!orphanSafetyLock.isSafeToFlag(candidate, lock, now, gracePeriodMs)) {
                skippedLocked++
                continue
            }
            cleanable += candidate
        }

        val breakdown = cleanable.groupBy { it.sourceId }.entries.map { (sourceId, items) ->
            val first = items.first()
            SourceCleanupBreakdown(
                sourceId = sourceId,
                sourceLabel = first.sourceLabel,
                sourceType = first.sourceType,
                itemCount = items.size,
                reclaimableBytes = items.sumOf { it.sizeBytes },
                destinationNote = if (first.sourceType == StorageSourceType.LOCAL) {
                    "Clears app-private cache files and expired session metadata"
                } else {
                    "Moved to Google Drive trash (recoverable)"
                },
            )
        }
        return BatchCleanupPlan(
            title = "Clean orphaned caches and stubs?",
            perSourceBreakdown = breakdown,
            orphanArtifactIds = cleanable.map { it.artifactId },
            skippedLockedOrphanCount = skippedLocked,
            totalReclaimableBytes = breakdown.sumOf { it.reclaimableBytes },
        )
    }

    suspend fun cleanOrphans(
        plan: BatchCleanupPlan,
        gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    ): OrphanCleanupResult {
        val session = dao.latestSessionOnce() ?: return OrphanCleanupResult(0, 0L, 0, 0)
        val allCandidates = dao.orphansForSession(session.sessionId).map { it.toCandidate() }
            .associateBy { it.artifactId }
        val lock = lockProvider.snapshot()
        val now = clock()
        val pinnedAccount = prefs.accountEmail?.takeIf { it.isNotBlank() }

        var cleanedCount = 0
        var freedBytes = 0L
        var failedCount = 0
        var skippedLocked = plan.skippedLockedOrphanCount
        val cleanedIds = ArrayList<String>()

        for (artifactId in plan.orphanArtifactIds) {
            val candidate = allCandidates[artifactId] ?: continue
            if (!orphanSafetyLock.isSafeToFlag(candidate, lock, now, gracePeriodMs)) {
                skippedLocked++
                continue
            }
            when (candidate.category) {
                OrphanCategory.LOCAL_APP_CACHE,
                OrphanCategory.TRANSFER_ORPHAN,
                -> {
                    if (artifactId.startsWith("transfer-session:")) {
                        val rowId = candidate.nativeId?.toLongOrNull()
                        if (rowId != null) {
                            appDb.transfers().setSession(rowId, null)
                            cleanedIds += artifactId
                            cleanedCount++
                        } else {
                            failedCount++
                        }
                    } else {
                        val file = File(candidate.path)
                        val removed = withContext(Dispatchers.IO) {
                            isInsideAppCache(file) && (!file.exists() || file.delete())
                        }
                        if (removed) {
                            cleanedIds += artifactId
                            cleanedCount++
                            freedBytes += candidate.sizeBytes
                        } else {
                            failedCount++
                        }
                    }
                }
                OrphanCategory.CLOUD_ZERO_BYTE_STUB,
                OrphanCategory.CLOUD_EMPTY_FOLDER,
                -> {
                    val fileId = candidate.nativeId
                    if (pinnedAccount != null && fileId != null) {
                        val ok = runCatching { drive.moveToTrash(fileId, pinnedAccount) }.isSuccess
                        if (ok) {
                            cleanedIds += artifactId
                            cleanedCount++
                            freedBytes += candidate.sizeBytes
                        } else {
                            failedCount++
                        }
                    } else {
                        failedCount++
                    }
                }
                OrphanCategory.CLOUD_TRASH_SUMMARY -> Unit
            }
        }

        if (cleanedIds.isNotEmpty()) {
            dao.pruneCleanedOrphans(session.sessionId, cleanedIds)
        }
        return OrphanCleanupResult(
            cleanedCount = cleanedCount,
            freedBytes = freedBytes,
            failedCount = failedCount,
            skippedLockedCount = skippedLocked,
        )
    }

    suspend fun markNodesCleaned(nodeIds: List<String>) {
        if (nodeIds.isEmpty()) return
        val session = dao.latestSessionOnce() ?: return
        dao.pruneCleanedNodes(session.sessionId, nodeIds)
    }

    /**
     * Offloads local files via DriveLift's existing verified [TransferRepository] upload queue.
     * Nothing is removed from the phone until the existing transfer + MD5/size verify pipeline finishes.
     */
    suspend fun offloadLocalFilesToCloud(
        mediaIds: List<Long>,
        destination: Destination = Destination.Auto,
    ): OffloadResult {
        if (mediaIds.isEmpty()) return OffloadResult(0, 0)
        val wanted = mediaIds.toHashSet()
        val matching = media.items.value.filter { it.id in wanted }
        if (matching.isEmpty()) return OffloadResult(0, mediaIds.size)
        val queued = transfers.enqueue(matching, destination)
        return OffloadResult(queuedCount = queued, skippedCount = matching.size - queued)
    }

    private suspend fun isNodeHeldByTransfer(
        node: UnifiedFileNode,
        lock: com.drivelift.app.feature.storageintelligence.scanner.ActiveTransferSnapshot,
        transferDao: com.drivelift.app.data.TransferDao,
    ): Boolean {
        if (node.isLocal) {
            val uri = node.localUri ?: return true
            if (uri in lock.activeMediaUris) return true
            val rows = transferDao.byMediaUris(listOf(uri))
            return rows.any { it.status in TRANSFER_HOLDS_FILE }
        }
        if (node.nativeId in lock.activeDriveFileIds) return true
        val rows = transferDao.dependingOnDriveFiles(listOf(node.nativeId), listOf(node.name))
        return transferHolds(node.toDriveFile(), rows)
    }

    private fun isInsideAppCache(file: File): Boolean {
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val allowedRoots = listOfNotNull(
            runCatching { context.cacheDir?.canonicalPath }.getOrNull(),
            runCatching { context.externalCacheDir?.canonicalPath }.getOrNull(),
        )
        return allowedRoots.any { root ->
            canonical.startsWith(root + File.separator) || canonical == root
        }
    }

    private fun selectScanners(
        scope: ScanScope,
        singleCloudSourceId: String?,
    ): List<StorageSourceScanner> = when (scope) {
        ScanScope.LOCAL_ONLY -> listOf(localScanner)
        ScanScope.SINGLE_CLOUD -> cloudScanners.filter {
            singleCloudSourceId == null || it.sourceId == singleCloudSourceId
        }
        ScanScope.ALL_CLOUDS -> cloudScanners
        ScanScope.CROSS_SOURCE -> listOf(localScanner) + cloudScanners
    }

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (scanBus.cancelRequested.get()) {
            throw CancellationException("Storage Intelligence scan cancelled by user")
        }
    }

    private fun UnifiedFileNode.toDriveFile(): DriveFile =
        DriveFile(
            id = nativeId,
            name = name,
            mimeType = mimeType,
            size = sizeBytes,
            md5 = hashValue,
            trashed = isTrashed,
            thumbnailLink = thumbnailRef,
        )
}
