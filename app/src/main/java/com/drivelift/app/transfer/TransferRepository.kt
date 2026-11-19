package com.drivelift.app.transfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.drivelift.app.data.AppDatabase
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.MediaItem
import com.drivelift.app.data.MediaRepository
import com.drivelift.app.data.Prefs
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Where a batch goes. A null folder id means auto-organize by phone folder name. */
data class Destination(val folderId: String?, val label: String) {
    companion object {
        const val AUTO_ROOT = "DriveLift Backup"
        val Auto = Destination(null, "$AUTO_ROOT / phone folder name")
    }
}

class TransferRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val media: MediaRepository,
    private val fence: DriveCopyFence,
    private val phoneFence: PhoneFileFence = PhoneFileFence(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao get() = db.transfers()
    private val work get() = WorkManager.getInstance(context)

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (prefs.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /**
     * Queues files and starts the pipeline. Returns how many were newly added. A file that is
     * being moved to the phone's trash (its request is open) is not queued ([PhoneFileFence]).
     */
    suspend fun enqueue(items: List<MediaItem>, dest: Destination): Int = phoneFence.whileEnqueueing { blocked ->
        // A fresh batch starts with a clean ledger; anything still in flight stays.
        if (dao.count(PENDING) == 0) dao.clearFinished()
        var added = 0
        items.filter { it.uri.toString() !in blocked }.chunked(400).forEach { chunk ->
            dao.purgeFinished(chunk.map { it.uri.toString() })
            added += dao.insertAll(chunk.map { m ->
                TransferEntity(
                    mediaUri = m.uri.toString(), name = m.name, mime = m.mime, size = m.size,
                    dateModifiedMs = m.dateModifiedMs, dateTakenMs = m.dateTakenMs,
                    bucketName = m.bucketName, destFolderId = dest.folderId, destLabel = dest.label,
                )
            }).count { it != -1L }
        }
        prefs.setPaused(false)
        kickUpload(ExistingWorkPolicy.APPEND_OR_REPLACE)
        added
    }

    fun kickUpload(policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        if (prefs.paused.value) return
        work.enqueueUniqueWork(
            WORK_UPLOAD, policy,
            OneTimeWorkRequestBuilder<TransferWorker>().setConstraints(constraints()).build(),
        )
    }

    fun kickVerify(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        if (prefs.paused.value) return
        work.enqueueUniqueWork(
            WORK_VERIFY, policy,
            OneTimeWorkRequestBuilder<VerifyWorker>().setConstraints(constraints()).build(),
        )
    }

    /** Restarts unfinished work after a reboot, crash or app update. */
    fun recover() {
        scope.launch {
            if (dao.count(PENDING) > 0) kickUpload()
            if (dao.count(listOf(TransferStatus.UPLOADED.name)) > 0) kickVerify(ExistingWorkPolicy.KEEP)
        }
    }

    fun pause() {
        prefs.setPaused(true)
        work.cancelUniqueWork(WORK_UPLOAD)
        work.cancelUniqueWork(WORK_VERIFY)
    }

    fun resume() {
        prefs.setPaused(false)
        kickUpload(ExistingWorkPolicy.REPLACE)
        kickVerify(ExistingWorkPolicy.REPLACE)
    }

    /** Re-reads the network rule (Wi-Fi only or any network) by restarting the jobs. */
    fun applyNetworkRule() {
        if (prefs.paused.value) return
        scope.launch {
            if (dao.count(PENDING) > 0) kickUpload(ExistingWorkPolicy.REPLACE)
            if (dao.count(listOf(TransferStatus.UPLOADED.name)) > 0) kickVerify(ExistingWorkPolicy.REPLACE)
        }
    }

    suspend fun cancel(id: Long) {
        dao.cancel(id)
        if (LiveProgress.state.value.itemId == id) LiveProgress.cancelItemId = id
    }

    suspend fun cancelAllPending() {
        dao.cancelAllPending()
        LiveProgress.cancelItemId = LiveProgress.state.value.itemId
    }

    /**
     * Sends failed rows back to the queue. Like [enqueue] this runs under the [PhoneFileFence]
     * lock: a file a phone trash request is sending stays failed until Android has answered.
     */
    suspend fun retryFailed() {
        phoneFence.whileEnqueueing { blocked -> dao.retryFailedExcept(blocked.toList()) }
        kickUpload(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    suspend fun clearFinished() = dao.clearFinished()

    /**
     * Verified files that are still on the phone, ready for the system delete
     * prompt. Rows whose file is already gone are closed out as removed, but only
     * while the app can see every photo and video: otherwise "not visible" is not "gone".
     *
     * The returned files are reserved in the [DriveCopyFence] and the [PhoneFileFence] until
     * [removalFinished]: no Drive trash can take their copies and no duplicate cleanup can keep
     * them while the request is open. A file whose copy is being trashed, a copy a duplicate
     * cleanup keeps, and a file a phone trash request is sending are left out. The rows are read before the fence is asked, so a copy reserved for a
     * trash after that read is seen by the trash as VERIFIED and left alone.
     */
    suspend fun verifiedForRemoval(limit: Int): List<String> {
        media.refresh()
        val present = media.items.value.mapTo(HashSet()) { it.uri.toString() }
        val rows = dao.byStatus(TransferStatus.VERIFIED.name, limit)
        val (onPhone, gone) = splitForRemoval(
            verified = rows.map { it.mediaUri },
            present = present,
            visibility = MediaAccess.visibility(context),
        )
        markRemoved(gone)
        val copyOf = rows.associate { it.mediaUri to it.driveFileId }
        // Copies a duplicate cleanup keeps, and files a phone trash request is sending, stay out.
        return fence.beginRemoval(phoneFence.beginRemoval(onPhone).map { it to copyOf[it] })
    }

    /** The removal request from [verifiedForRemoval] was answered, cancelled or never shown. */
    suspend fun removalFinished() {
        phoneFence.endRemoval()
        fence.endRemoval()
    }

    suspend fun markRemoved(uris: List<String>) {
        uris.chunked(400).forEach { dao.markRemoved(it) }
    }

    companion object {
        const val WORK_UPLOAD = "drivelift-upload"
        const val WORK_VERIFY = "drivelift-verify"
        val PENDING = listOf(TransferStatus.QUEUED.name, TransferStatus.UPLOADING.name)
    }
}
