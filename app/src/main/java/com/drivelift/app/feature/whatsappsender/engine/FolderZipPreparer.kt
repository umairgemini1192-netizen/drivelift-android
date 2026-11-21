package com.drivelift.app.feature.whatsappsender.engine

import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.ZipPreparationResult
import com.drivelift.app.feature.whatsappsender.model.ZipProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams folders or oversized media batches into a temporary cache ZIP and cleans stale archives. */
class FolderZipPreparer(
    private val cacheDir: File,
    private val uriResolver: SecureUriResolver,
    private val streamOpener: (LocalSendItem) -> InputStream?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun stagingDir(): File = File(cacheDir, STAGING_DIR_NAME)

    fun sanitizeArchiveName(folderName: String): String {
        val cleaned = folderName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', '-')
            .take(64)
        return cleaned.ifEmpty { "DriveLift_Folder" }
    }

    fun cleanupStaleArchives(
        maxAgeMs: Long = STALE_MAX_AGE_MS,
        nowMs: Long = clock(),
    ): Int {
        val dir = stagingDir()
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (!file.isFile) continue
            val isStaged = file.name.endsWith(".zip", ignoreCase = true) ||
                file.name.endsWith(".tmp", ignoreCase = true)
            if (isStaged && (nowMs - file.lastModified() > maxAgeMs)) {
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    suspend fun prepareZip(
        folderName: String,
        items: List<LocalSendItem>,
        onProgress: (ZipProgress) -> Unit = {},
    ): ZipPreparationResult = withContext(Dispatchers.IO) {
        cleanupStaleArchives()

        val staging = stagingDir()
        if (!staging.exists() && !staging.mkdirs()) {
            return@withContext ZipPreparationResult.Failure("Could not prepare staging directory")
        }

        val archiveName = "${sanitizeArchiveName(folderName)}.zip"
        val targetFile = File(staging, archiveName)
        val tempFile = File(staging, "$archiveName.tmp")
        if (tempFile.exists()) tempFile.delete()

        val totalFiles = items.size
        val totalBytes = items.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        var filesCompleted = 0
        var bytesProcessed = 0L
        val usedEntryNames = HashSet<String>(items.size)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var writtenFiles = 0

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zipOut ->
                for (item in items) {
                    currentCoroutineContext().ensureActive()
                    val entryName = uniqueEntryName(usedEntryNames, item.relativeFolderPath, item.name)
                    onProgress(
                        ZipProgress(
                            filesCompleted = filesCompleted,
                            totalFiles = totalFiles,
                            bytesProcessed = bytesProcessed,
                            totalBytes = totalBytes,
                            currentFileName = item.name,
                        ),
                    )
                    val stream = streamOpener(item)
                    if (stream == null) {
                        filesCompleted++
                        continue
                    }
                    stream.use { input ->
                        val entry = ZipEntry(entryName)
                        if (item.sizeBytes > 0L) {
                            entry.size = item.sizeBytes
                        }
                        zipOut.putNextEntry(entry)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            zipOut.write(buffer, 0, read)
                            bytesProcessed += read
                        }
                        zipOut.closeEntry()
                        writtenFiles++
                    }
                    filesCompleted++
                    onProgress(
                        ZipProgress(
                            filesCompleted = filesCompleted,
                            totalFiles = totalFiles,
                            bytesProcessed = bytesProcessed,
                            totalBytes = totalBytes,
                            currentFileName = item.name,
                        ),
                    )
                }
            }

            if (writtenFiles == 0) {
                tempFile.delete()
                return@withContext ZipPreparationResult.Failure("No readable files were available to zip.")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val archiveUri = uriResolver.resolveFile(targetFile)
            ZipPreparationResult.Success(
                archiveFile = targetFile,
                archiveUri = archiveUri,
                fileCount = writtenFiles,
                archiveSizeBytes = targetFile.length(),
            )
        } catch (ce: CancellationException) {
            tempFile.delete()
            throw ce
        } catch (t: Exception) {
            tempFile.delete()
            ZipPreparationResult.Failure(
                message = t.message ?: "Failed to create archive",
                cause = t,
            )
        }
    }

    private fun uniqueEntryName(
        used: MutableSet<String>,
        relativeFolderPath: String,
        rawFileName: String,
    ): String {
        val safeName = sanitizePathSegment(rawFileName).ifEmpty { "file" }
        val safeDir = relativeFolderPath
            .replace('\\', '/')
            .split('/')
            .map { sanitizePathSegment(it) }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

        val basePath = if (safeDir.isEmpty()) safeName else "$safeDir/$safeName"
        if (used.add(basePath)) return basePath

        val dotIdx = safeName.lastIndexOf('.')
        val stem = if (dotIdx > 0) safeName.substring(0, dotIdx) else safeName
        val ext = if (dotIdx > 0) safeName.substring(dotIdx) else ""
        var counter = 1
        while (true) {
            val candidateName = "$stem ($counter)$ext"
            val candidatePath = if (safeDir.isEmpty()) candidateName else "$safeDir/$candidateName"
            if (used.add(candidatePath)) return candidatePath
            counter++
        }
    }

    private fun sanitizePathSegment(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").trim()

    companion object {
        const val STAGING_DIR_NAME = "whatsapp_send_staging"
        const val STALE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
