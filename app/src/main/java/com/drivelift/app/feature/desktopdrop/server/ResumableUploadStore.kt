package com.drivelift.app.feature.desktopdrop.server

import com.drivelift.app.feature.desktopdrop.data.CommittedDropFile
import com.drivelift.app.feature.desktopdrop.data.DropDestinationWriter
import com.drivelift.app.feature.desktopdrop.data.DropPathSanitizer
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk-backed resumable upload manager keeping active transfers in .drivelift.part files with
 * constant sub-megabyte RAM overhead regardless of total file size.
 */
class ResumableUploadStore(
    private val partsDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        if (!partsDir.exists()) partsDir.mkdirs()
    }

    private data class UploadKey(
        val filename: String,
        val relativePath: String,
        val totalSize: Long,
        val expectedSha256: String?,
    )

    private class ActiveUpload(
        val uploadId: String,
        val key: UploadKey,
        val rawFilename: String,
        val relativePath: String,
        val partFile: File,
        val mimeType: String?,
        val mutex: Mutex = Mutex(),
        @Volatile var receivedBytes: Long = 0L,
        @Volatile var createdAtMs: Long = 0L,
    )

    private val uploads = ConcurrentHashMap<String, ActiveUpload>()

    data class InitResult(
        val uploadId: String,
        val receivedBytes: Long,
        val totalSize: Long,
        val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
        val resumed: Boolean,
    )

    sealed interface ChunkResult {
        data class Appended(val receivedBytes: Long, val deltaBytes: Long, val totalSize: Long) : ChunkResult
        data class Conflict(val expectedOffset: Long) : ChunkResult
        data object NotFound : ChunkResult
    }

    fun initUpload(
        filename: String,
        relativePath: String?,
        totalSize: Long,
        expectedSha256: String? = null,
        mimeType: String? = null,
    ): InitResult {
        if (totalSize < 0L) throw IllegalArgumentException("totalSize must be non-negative")
        val cleanName = DropPathSanitizer.sanitizeFilename(filename)
        val cleanSubdir = DropPathSanitizer.sanitizeRelativeFolderSegments(relativePath, filename)
            .joinToString("/")
        val cleanSha = expectedSha256?.trim()?.lowercase()?.ifBlank { null }
        val key = UploadKey(cleanName, cleanSubdir, totalSize, cleanSha)

        val existing = uploads.values.firstOrNull { it.key == key && it.partFile.exists() }
        if (existing != null) {
            val onDisk = existing.partFile.length().coerceAtMost(totalSize)
            existing.receivedBytes = onDisk
            return InitResult(
                uploadId = existing.uploadId,
                receivedBytes = onDisk,
                totalSize = totalSize,
                resumed = onDisk > 0L,
            )
        }

        val uploadId = UUID.randomUUID().toString()
        if (!partsDir.exists()) partsDir.mkdirs()
        val partFile = File(partsDir, "$uploadId$PART_SUFFIX")
        partFile.createNewFile()
        val session = ActiveUpload(
            uploadId = uploadId,
            key = key,
            rawFilename = cleanName,
            relativePath = cleanSubdir,
            partFile = partFile,
            mimeType = mimeType,
            receivedBytes = 0L,
            createdAtMs = clock(),
        )
        uploads[uploadId] = session
        return InitResult(
            uploadId = uploadId,
            receivedBytes = 0L,
            totalSize = totalSize,
            resumed = false,
        )
    }

    suspend fun appendChunk(
        uploadId: String,
        offset: Long,
        channel: ByteReadChannel,
        onBytesDelta: (receivedBytes: Long, delta: Long) -> Unit = { _, _ -> },
    ): ChunkResult {
        val session = uploads[uploadId] ?: return ChunkResult.NotFound
        return session.mutex.withLock {
            if (!session.partFile.exists()) return@withLock ChunkResult.NotFound
            val currentLength = session.partFile.length()
            if (offset < 0L || offset > currentLength) {
                return@withLock ChunkResult.Conflict(expectedOffset = currentLength)
            }
            val buffer = ByteArray(IO_BUFFER_BYTES)
            var writtenThisChunk = 0L
            var cursor = offset
            RandomAccessFile(session.partFile, "rw").use { raf ->
                raf.seek(offset)
                while (!channel.isClosedForRead) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    val remainingAllowed = session.key.totalSize - cursor
                    if (remainingAllowed <= 0L) {
                        throw IllegalArgumentException("Upload exceeded declared totalSize=${session.key.totalSize}")
                    }
                    val toWrite = minOf(count.toLong(), remainingAllowed).toInt()
                    raf.write(buffer, 0, toWrite)
                    cursor += toWrite
                    writtenThisChunk += toWrite
                    session.receivedBytes = maxOf(raf.length(), cursor)
                    onBytesDelta(session.receivedBytes, toWrite.toLong())
                    if (toWrite < count) {
                        throw IllegalArgumentException("Upload exceeded declared totalSize=${session.key.totalSize}")
                    }
                }
                session.receivedBytes = raf.length()
            }
            ChunkResult.Appended(
                receivedBytes = session.receivedBytes,
                deltaBytes = writtenThisChunk,
                totalSize = session.key.totalSize,
            )
        }
    }

    suspend fun completeUpload(
        uploadId: String,
        destinationWriter: DropDestinationWriter,
    ): CommittedDropFile {
        val session = uploads[uploadId] ?: throw NoSuchElementException("Unknown upload $uploadId")
        return session.mutex.withLock {
            if (!session.partFile.isFile) {
                uploads.remove(uploadId)
                throw IOException("Missing temporary part file for ${session.rawFilename}")
            }
            val actualSize = session.partFile.length()
            if (actualSize != session.key.totalSize) {
                throw IllegalStateException("Size mismatch for ${session.rawFilename}: expected ${session.key.totalSize}, got $actualSize")
            }
            val expectedSha = session.key.expectedSha256
            if (!expectedSha.isNullOrBlank()) {
                val actualSha = computeFileSha256(session.partFile)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw IllegalStateException("Checksum mismatch for ${session.rawFilename}")
                }
            }
            val committed = destinationWriter.commit(
                partFile = session.partFile,
                filename = session.rawFilename,
                relativePath = session.relativePath.ifBlank { null },
                mimeType = session.mimeType,
            )
            uploads.remove(uploadId)
            committed
        }
    }

    suspend fun abortUpload(uploadId: String): Boolean {
        val session = uploads.remove(uploadId) ?: return false
        session.mutex.withLock {
            return session.partFile.delete()
        }
    }

    fun cleanupAllParts() {
        uploads.clear()
        partsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(PART_SUFFIX)) file.delete()
        }
    }

    fun getMetadata(uploadId: String): UploadMeta? {
        val session = uploads[uploadId] ?: return null
        return UploadMeta(session.rawFilename, session.relativePath, session.key.totalSize, session.receivedBytes)
    }

    data class UploadMeta(
        val filename: String,
        val relativePath: String,
        val totalSize: Long,
        val receivedBytes: Long,
    )

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(IO_BUFFER_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE_BYTES = 8L * 1024L * 1024L
        const val PART_SUFFIX = ".drivelift.part"
        private const val IO_BUFFER_BYTES = 64 * 1024
    }
}
