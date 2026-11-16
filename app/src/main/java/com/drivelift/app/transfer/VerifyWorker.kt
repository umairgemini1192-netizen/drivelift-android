package com.drivelift.app.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.drivelift.app.Graph
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.SourceMissingException
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.drive.AuthRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Runs after the uploads. For every uploaded file it re-reads the copy on the
 * phone, computes its MD5, and compares checksum and size with what Drive
 * reports for the stored file. Only a match marks the file VERIFIED, and only
 * VERIFIED files can ever be offered for removal from the phone. A mismatch
 * trashes the bad Drive copy and sends the file back through the upload queue.
 */
class VerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph = Graph.get(applicationContext)
    private val dao = graph.db.transfers()
    private val drive = graph.drive

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foreground(applicationContext, "Verifying uploads", "", -1)

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val backoff = Backoff()
        var requeued = false
        try {
            while (true) {
                val item = dao.byStatus(TransferStatus.UPLOADED.name, 1).firstOrNull() ?: break
                try {
                    if (verify(item)) requeued = true
                    backoff.reset()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = describeError(e)
                    dao.noteError(item.id, message)
                    if (e is AuthRequiredException) Notifications.authNeeded(applicationContext)
                    val wait = backoff.next()
                    LiveProgress.state.value = LiveProgress.State(
                        phase = LiveProgress.Phase.RETRY_WAIT, itemId = item.id, name = item.name,
                        message = message, retryAtMs = System.currentTimeMillis() + wait,
                    )
                    delay(wait)
                }
            }
        } finally {
            LiveProgress.state.value = LiveProgress.State()
        }
        if (requeued) {
            graph.transfers.kickUpload(ExistingWorkPolicy.APPEND_OR_REPLACE)
        } else {
            val verified = dao.count(listOf(TransferStatus.VERIFIED.name))
            if (verified > 0) Notifications.verified(applicationContext, verified)
        }
        return Result.success()
    }

    /** Returns true when the file had to be sent back to the upload queue. */
    private suspend fun verify(item: TransferEntity): Boolean {
        val remote = item.driveFileId?.let { drive.getFile(it) }
        if (remote == null || remote.trashed) {
            dao.requeue(item.id, "The uploaded copy was missing in Drive, uploading again")
            return true
        }

        val local = try {
            MediaAccess.open(applicationContext, Uri.parse(item.mediaUri)).use { pfd ->
                pfd.statSize to md5(pfd, item)
            }
        } catch (e: SourceMissingException) {
            null
        }

        if (local == null) {
            // The phone copy vanished before it could be compared. Nothing is left
            // to remove; accept the Drive copy only if at least its size matches.
            if (remote.size == item.size) {
                dao.setStatus(item.id, TransferStatus.REMOVED.name, null)
            } else {
                dao.setStatus(
                    item.id, TransferStatus.FAILED.name,
                    "The phone copy disappeared before the Drive copy could be verified",
                )
            }
            return false
        }

        val (localSize, localMd5) = local
        val matches = remote.size == localSize && remote.md5 != null &&
            remote.md5.equals(localMd5, ignoreCase = true)
        if (matches) {
            dao.setStatus(item.id, TransferStatus.VERIFIED.name, null)
            return false
        }
        runCatching { drive.trashFile(remote.id) }
        dao.requeue(item.id, "Checksum mismatch after upload, uploading again")
        return true
    }

    private suspend fun md5(pfd: ParcelFileDescriptor, item: TransferEntity): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("MD5")
            val channel = FileInputStream(pfd.fileDescriptor).channel
            channel.position(0)
            val total = pfd.statSize
            val buffer = ByteBuffer.allocate(512 * 1024)
            var read = 0L
            var lastUi = 0L
            while (true) {
                coroutineContext.ensureActive()
                buffer.clear()
                val n = channel.read(buffer)
                if (n < 0) break
                digest.update(buffer.array(), 0, n)
                read += n
                val now = SystemClock.elapsedRealtime()
                if (now - lastUi > 250) {
                    lastUi = now
                    LiveProgress.state.value = LiveProgress.State(
                        phase = LiveProgress.Phase.VERIFYING, itemId = item.id, name = item.name,
                        sent = read, total = total,
                    )
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
}
