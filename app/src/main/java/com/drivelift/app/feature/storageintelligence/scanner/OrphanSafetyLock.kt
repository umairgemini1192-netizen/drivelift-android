package com.drivelift.app.feature.storageintelligence.scanner

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.drivelift.app.data.AppDatabase
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_GRACE_PERIOD_MS
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.transfer.LiveProgress
import com.drivelift.app.transfer.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Point-in-time lock snapshot of files, folders, and workers owned by DriveLift's transfer engine.
 */
data class ActiveTransferSnapshot(
    val activeMediaUris: Set<String> = emptySet(),
    val activeDriveFileIds: Set<String> = emptySet(),
    val activeFileNames: Set<String> = emptySet(),
    val activeDestFolderIds: Set<String> = emptySet(),
    val activeTransferWorkRunning: Boolean = false,
)

interface TransferLockProvider {
    suspend fun snapshot(): ActiveTransferSnapshot
}

/**
 * Reads DriveLift's transfer ledger, pinned folders, [LiveProgress], and WorkManager jobs
 * (`drivelift-upload`, `drivelift-verify`) without modifying any transfer class.
 */
class DriveLiftTransferLockProvider(
    private val context: Context,
    private val appDb: AppDatabase,
) : TransferLockProvider {

    override suspend fun snapshot(): ActiveTransferSnapshot = withContext(Dispatchers.IO) {
        val dao = appDb.transfers()
        val heldRows = ACTIVE_STATUSES.flatMap { status -> dao.byStatus(status, 5_000) }
        val activeUris = heldRows.mapTo(HashSet()) { it.mediaUri }
        val activeDriveIds = heldRows.mapNotNullTo(HashSet()) { it.driveFileId }
        val activeNames = heldRows.mapTo(HashSet()) { it.name }
        val activeDestFolders = heldRows.mapNotNullTo(HashSet()) { it.destFolderId }
        val liveBusy = LiveProgress.state.value.phase != LiveProgress.Phase.IDLE
        val workRunning = liveBusy ||
            hasPendingWork(TransferRepository.WORK_UPLOAD) ||
            hasPendingWork(TransferRepository.WORK_VERIFY)

        ActiveTransferSnapshot(
            activeMediaUris = activeUris,
            activeDriveFileIds = activeDriveIds,
            activeFileNames = activeNames,
            activeDestFolderIds = activeDestFolders,
            activeTransferWorkRunning = workRunning,
        )
    }

    private fun hasPendingWork(uniqueWorkName: String): Boolean = runCatching {
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueWorkName).get()
        infos.any { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        }
    }.getOrDefault(false)

    companion object {
        private val ACTIVE_STATUSES = listOf(
            TransferStatus.QUEUED.name,
            TransferStatus.UPLOADING.name,
            TransferStatus.UPLOADED.name,
            TransferStatus.VERIFIED.name,
        )
    }
}

/**
 * Safety guardrail preventing orphaned cache or temp cleanup from touching files locked by active
 * transfers, running WorkManager upload/verify jobs, core app databases, or items modified inside
 * the configurable grace period (default 24h) unless explicitly `FAILED` or `CANCELLED`.
 */
class OrphanSafetyLock(
    private val protectedFileNames: Set<String> = DEFAULT_PROTECTED_FILES,
) {

    fun isSafeToFlag(
        candidate: OrphanArtifactCandidate,
        lock: ActiveTransferSnapshot,
        nowMs: Long,
        gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    ): Boolean {
        if (candidate.category == OrphanCategory.CLOUD_TRASH_SUMMARY) return true

        val lowerName = candidate.name.lowercase()
        if (protectedFileNames.any { lowerName == it || lowerName.startsWith("$it-") }) {
            return false
        }
        if (candidate.localUri != null && candidate.localUri in lock.activeMediaUris) return false
        if (candidate.nativeId != null && candidate.nativeId in lock.activeDriveFileIds) return false
        if (candidate.name in lock.activeFileNames) return false
        if (candidate.category == OrphanCategory.CLOUD_EMPTY_FOLDER &&
            candidate.nativeId != null &&
            candidate.nativeId in lock.activeDestFolderIds
        ) {
            return false
        }

        val isExplicitTerminal = candidate.transferStatus in EXPLICIT_TERMINAL_STATUSES
        if (candidate.category == OrphanCategory.TRANSFER_ORPHAN &&
            !isExplicitTerminal &&
            lock.activeTransferWorkRunning
        ) {
            return false
        }

        if (!isExplicitTerminal) {
            val ageMs = nowMs - candidate.modifiedAt
            if (ageMs < gracePeriodMs) return false
        }
        return true
    }

    companion object {
        val EXPLICIT_TERMINAL_STATUSES = setOf(
            TransferStatus.FAILED.name,
            TransferStatus.CANCELLED.name,
        )

        val DEFAULT_PROTECTED_FILES = setOf(
            "drivelift.db",
            "storage_intelligence.db",
            "drive-folder-counts.txt",
        )
    }
}
