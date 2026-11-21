package com.drivelift.app.feature.intelligence.rules

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.drivelift.app.Graph
import com.drivelift.app.data.MediaItem
import com.drivelift.app.feature.intelligence.data.SmartRuleEntity
import com.drivelift.app.feature.intelligence.data.isComplete
import com.drivelift.app.feature.storageintelligence.scanner.DriveLiftTransferLockProvider
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.TransferRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Rule file access. `mediastore:<RELATIVE_PATH>` sources list phone photos and videos in that
 * folder and its sub-folders (read-only: Android has to confirm removing phone media, so a rule
 * never deletes them). `content://` tree sources and targets are folders the user picked with
 * Android's folder picker; DriveLift holds persistent access to them and nothing else.
 */
class AndroidRuleFileSystem(private val context: Context) : RuleFileSystem {

    override suspend fun list(sourceUri: String, needVideoHeight: Boolean): List<RuleFile> = withContext(Dispatchers.IO) {
        if (sourceUri.startsWith(SmartRuleExecutor.MEDIASTORE_PREFIX)) {
            listMediaStore(sourceUri.removePrefix(SmartRuleExecutor.MEDIASTORE_PREFIX))
        } else {
            listTree(Uri.parse(sourceUri), needVideoHeight)
        }
    }

    private fun listMediaStore(relativePath: String): List<RuleFile> {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.SIZE, FileColumns.MIME_TYPE,
            FileColumns.DATE_TAKEN, FileColumns.DATE_MODIFIED, FileColumns.BUCKET_ID,
            FileColumns.BUCKET_DISPLAY_NAME, FileColumns.RELATIVE_PATH, FileColumns.MEDIA_TYPE,
            FileColumns.DURATION, FileColumns.VOLUME_NAME, FileColumns.WIDTH, FileColumns.HEIGHT,
        )
        val selection = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})" +
            " AND ${FileColumns.RELATIVE_PATH} LIKE ?"
        val out = ArrayList<RuleFile>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection,
            arrayOf("$relativePath%"), "${FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(FileColumns._ID)
            val iName = c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(FileColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
            val iTaken = c.getColumnIndexOrThrow(FileColumns.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
            val iBucket = c.getColumnIndexOrThrow(FileColumns.BUCKET_ID)
            val iBucketName = c.getColumnIndexOrThrow(FileColumns.BUCKET_DISPLAY_NAME)
            val iPath = c.getColumnIndexOrThrow(FileColumns.RELATIVE_PATH)
            val iType = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
            val iDuration = c.getColumnIndexOrThrow(FileColumns.DURATION)
            val iVolume = c.getColumnIndex(FileColumns.VOLUME_NAME)
            val iWidth = c.getColumnIndexOrThrow(FileColumns.WIDTH)
            val iHeight = c.getColumnIndexOrThrow(FileColumns.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == FileColumns.MEDIA_TYPE_VIDEO
                val modifiedMs = c.getLong(iMod) * 1000
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = ContentUris.withAppendedId(base, id)
                val name = c.getString(iName) ?: "file_$id"
                val mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*"
                val size = c.getLong(iSize)
                val w = c.getInt(iWidth)
                val h = c.getInt(iHeight)
                val item = MediaItem(
                    id = id, uri = uri, name = name, size = size, mime = mime,
                    dateTakenMs = c.getLong(iTaken).takeIf { it > 0 } ?: modifiedMs,
                    dateModifiedMs = modifiedMs, bucketId = c.getLong(iBucket),
                    bucketName = c.getString(iBucketName).orEmpty(), relativePath = c.getString(iPath).orEmpty(),
                    isVideo = isVideo, durationMs = if (isVideo) c.getLong(iDuration) else 0L,
                    volume = (if (iVolume >= 0) c.getString(iVolume) else null) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                )
                out += RuleFile(
                    uri = uri.toString(), name = name, mimeType = mime, sizeBytes = size,
                    lastModifiedMs = modifiedMs,
                    // The short side, so a portrait 4K video still counts as 2160 lines.
                    videoHeightPx = if (isVideo && w > 0 && h > 0) min(w, h) else null,
                    mediaItem = item,
                )
            }
        }
        return out
    }

    private fun listTree(treeUri: Uri, needVideoHeight: Boolean): List<RuleFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return root.listFiles().filter { it.isFile }.mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            val mime = doc.type ?: "application/octet-stream"
            RuleFile(
                uri = doc.uri.toString(), name = name, mimeType = mime, sizeBytes = doc.length(),
                lastModifiedMs = doc.lastModified(),
                videoHeightPx = if (needVideoHeight && mime.startsWith("video/")) videoHeight(doc.uri) else null,
            )
        }
    }

    private fun videoHeight(uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w != null && h != null) min(w, h) else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    override suspend fun namesIn(targetUri: String): Set<String> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(targetUri)) ?: throw IOException("Folder unavailable")
        if (!dir.canWrite()) throw IOException("No write access to the destination folder")
        dir.listFiles().mapNotNull { it.name }.toSet()
    }

    override suspend fun copy(file: RuleFile, targetUri: String, name: String): Long = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(targetUri)) ?: throw IOException("Folder unavailable")
        val created = dir.createFile(file.mimeType, name) ?: throw IOException("Could not create $name")
        try {
            val resolver = context.contentResolver
            val written = resolver.openInputStream(Uri.parse(file.uri)).use { input ->
                input ?: throw IOException("Could not read ${file.name}")
                resolver.openOutputStream(created.uri, "w").use { output ->
                    output ?: throw IOException("Could not write $name")
                    input.copyTo(output)
                }
            }
            written
        } catch (e: Exception) {
            runCatching { created.delete() }
            throw e
        }
    }

    override suspend fun delete(file: RuleFile): Boolean = withContext(Dispatchers.IO) {
        if (file.mediaItem != null) return@withContext false
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(file.uri)) }.getOrDefault(false)
    }
}

/** Queues phone media through DriveLift's existing transfer pipeline; it does nothing else. */
class TransferQueueUploadAdapter(private val transfers: TransferRepository) : CloudUploadAdapter {
    override suspend fun enqueue(items: List<MediaItem>, destination: Destination): Int = transfers.enqueue(items, destination)
}

/** Reads the transfer ledger through Storage Intelligence's read-only lock provider. */
class LedgerTransferLockLookup(private val provider: DriveLiftTransferLockProvider) : TransferLockLookup {
    override suspend fun lockedUris(): Set<String> = provider.snapshot().activeMediaUris
}

/** Runs one enabled rule on its schedule, under that rule's Wi-Fi and charging constraints. */
class SmartRuleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val module = Graph.get(applicationContext).intelligence
        val id = inputData.getLong(KEY_RULE_ID, -1L)
        val rule = module.db.dao().rule(id) ?: return Result.success()
        if (!rule.enabled || !rule.isComplete) return Result.success()
        return try {
            module.ruleExecutor.run(rule, dryRun = false)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
    }
}

/** One periodic job per enabled rule (`drivelift-intel-rule-<id>`). */
class SmartRuleScheduler(private val context: Context) {
    fun sync(rule: SmartRuleEntity) {
        val work = WorkManager.getInstance(context)
        val name = workName(rule.id)
        if (!rule.enabled || !rule.isComplete) {
            work.cancelUniqueWork(name)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (rule.requireWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresCharging(rule.requireCharging)
            .build()
        work.enqueueUniquePeriodicWork(
            name,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SmartRuleWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(workDataOf(SmartRuleWorker.KEY_RULE_ID to rule.id))
                .build(),
        )
    }

    fun cancel(ruleId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(ruleId))
    }

    private fun workName(id: Long) = "drivelift-intel-rule-$id"
}
