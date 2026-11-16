package com.drivelift.app.transfer

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.drivelift.app.Graph
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.SourceMissingException
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.SessionExpiredException
import com.drivelift.app.drive.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Uploads every queued file to Drive, one at a time, with resumable sessions.
 *
 * Failure handling has two layers:
 *  - Inside the loop, any error is retried with backoff, forever.
 *  - WorkManager's network constraint stops the worker when the connection
 *    drops and starts it again when it returns, even after a reboot.
 * Either way an interrupted file continues from the byte Drive last confirmed.
 */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph = Graph.get(applicationContext)
    private val dao = graph.db.transfers()
    private val drive = graph.drive
    private val autoFolders = HashMap<String, String>()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foreground(applicationContext, "Preparing upload", "", -1)

    override suspend fun doWork(): Result {
        // Promotion can be refused when started from the background; the job
        // still runs, just under the normal background time limit.
        runCatching { setForeground(getForegroundInfo()) }
        val backoff = Backoff()
        try {
            while (true) {
                val item = dao.nextPending() ?: break
                try {
                    upload(item)
                    backoff.reset()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SourceMissingException) {
                    dao.setStatus(item.id, TransferStatus.FAILED.name, e.message)
                } catch (e: Exception) {
                    if (dao.get(item.id)?.status == TransferStatus.CANCELLED.name) {
                        LiveProgress.cancelItemId = -1
                        continue
                    }
                    val message = describeError(e)
                    dao.noteError(item.id, message)
                    if (e is AuthRequiredException) Notifications.authNeeded(applicationContext)
                    val wait = backoff.next()
                    LiveProgress.state.value = LiveProgress.State(
                        phase = LiveProgress.Phase.RETRY_WAIT, itemId = item.id, name = item.name,
                        total = item.size, message = message,
                        retryAtMs = System.currentTimeMillis() + wait,
                    )
                    Notifications.updateProgress(applicationContext, "Retrying shortly", message, -1)
                    delay(wait)
                }
            }
        } finally {
            LiveProgress.state.value = LiveProgress.State()
        }
        graph.transfers.kickVerify()
        return Result.success()
    }

    private suspend fun destinationFor(item: TransferEntity): String {
        item.destFolderId?.let { return it }
        val folderName = item.bucketName.ifBlank { "Other" }
        val id = autoFolders.getOrPut(folderName) {
            val root = autoFolders.getOrPut("") {
                drive.findOrCreateFolder(Destination.AUTO_ROOT, DriveClient.ROOT)
            }
            drive.findOrCreateFolder(folderName, root)
        }
        dao.setDest(item.id, id)
        return id
    }

    private suspend fun upload(item: TransferEntity) {
        val context = applicationContext
        val parent = destinationFor(item)
        MediaAccess.open(context, Uri.parse(item.mediaUri)).use { pfd ->
            val total = pfd.statSize.coerceAtLeast(0)
            if (total != item.size) dao.setSize(item.id, total)

            var offset = 0L
            var session = item.sessionUri
            if (session != null && total > 0) {
                when (val state = drive.querySession(session, total)) {
                    is SessionState.Done -> {
                        dao.markUploaded(item.id, state.file.id)
                        return
                    }
                    is SessionState.Offset -> offset = state.next
                    SessionState.Expired -> session = null
                }
            } else {
                session = null
            }
            if (session == null) {
                session = drive.startUpload(
                    item.name, item.mime, parent, total, item.dateModifiedMs, item.dateTakenMs,
                )
                dao.setSession(item.id, session)
                offset = 0
            }
            dao.markUploading(item.id)

            val done = dao.count(DONE)
            val all = done + dao.count(TransferRepository.PENDING)
            val title = "Uploading ${done + 1} of $all"
            val meter = SpeedMeter()
            var lastUi = 0L
            var lastNotification = 0L
            fun publish(sent: Long, force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastUi < 250) return
                lastUi = now
                LiveProgress.state.value = LiveProgress.State(
                    phase = LiveProgress.Phase.UPLOADING, itemId = item.id, name = item.name,
                    sent = sent, total = total, bytesPerSec = meter.record(now, sent),
                )
                if (now - lastNotification > 1000) {
                    lastNotification = now
                    val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                    Notifications.updateProgress(context, title, item.name, percent)
                }
            }
            publish(offset, force = true)

            val file = try {
                drive.uploadFrom(
                    session, pfd, offset, total, item.mime,
                    onProgress = { publish(it) },
                    cancelled = { LiveProgress.cancelItemId == item.id },
                )
            } catch (e: SessionExpiredException) {
                dao.setSession(item.id, null)
                throw e
            }
            dao.markUploaded(item.id, file.id)
        }
    }

    private companion object {
        val DONE = listOf(
            TransferStatus.UPLOADED.name, TransferStatus.VERIFIED.name, TransferStatus.REMOVED.name,
        )
    }
}
