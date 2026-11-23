package com.drivelift.app.feature.cleaner.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Three-stage duplicate hashing engine:
 * 1. Size pre-filter (skips 0-byte and unique-size files).
 * 2. Partial SHA-256 fingerprint over head + tail slices.
 * 3. Full streaming 64 KB SHA-256 for Stage 2 collisions.
 */
class FastFileHashEngine(
    private val partialHashBytes: Int = 4096,
) {
    /**
     * Groups [entries] into byte-identical duplicate buckets keyed by lowercase full SHA-256 hex.
     * Only buckets with at least 2 readable files are returned.
     */
    suspend fun findDuplicates(
        entries: List<ScannedFileEntry>,
    ): Map<String, List<ScannedFileEntry>> {
        val sizeBuckets = entries
            .asSequence()
            .filter { it.sizeBytes > 0L }
            .groupBy { it.sizeBytes }
            .filterValues { it.size >= 2 }

        if (sizeBuckets.isEmpty()) return emptyMap()

        val stage2Buckets = mutableMapOf<String, MutableList<ScannedFileEntry>>()
        for ((sizeBytes, candidates) in sizeBuckets) {
            currentCoroutineContext().ensureActive()
            for (entry in candidates) {
                currentCoroutineContext().ensureActive()
                val partialHash = computePartialFingerprint(File(entry.path)) ?: continue
                val key = "$sizeBytes:$partialHash"
                stage2Buckets.getOrPut(key) { mutableListOf() }.add(entry)
            }
        }

        val fullBuckets = mutableMapOf<String, MutableList<ScannedFileEntry>>()
        for (candidates in stage2Buckets.values) {
            if (candidates.size < 2) continue
            for (entry in candidates) {
                currentCoroutineContext().ensureActive()
                val fullHash = computeFullSha256(File(entry.path)) ?: continue
                fullBuckets.getOrPut(fullHash) { mutableListOf() }.add(entry)
            }
        }

        return fullBuckets.filterValues { it.size >= 2 }
    }

    /**
     * Computes a SHA-256 fingerprint over the first [partialHashBytes] and last [partialHashBytes]
     * of [file] (or the whole file if its length is at most `2 * partialHashBytes`).
     * Returns `null` on unreadable or missing files.
     */
    suspend fun computePartialFingerprint(file: File): String? {
        currentCoroutineContext().ensureActive()
        if (!isReadableRegularFile(file)) return null
        val sliceSize = partialHashBytes.coerceAtLeast(1)

        return try {
            val length = file.length()
            if (length <= 2L * sliceSize) {
                return computeFullSha256(file)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(sliceSize)
                readExactOrAvailable(raf, buffer, sliceSize)?.let { readCount ->
                    digest.update(buffer, 0, readCount)
                }
                currentCoroutineContext().ensureActive()
                raf.seek(length - sliceSize)
                readExactOrAvailable(raf, buffer, sliceSize)?.let { readCount ->
                    digest.update(buffer, 0, readCount)
                }
            }
            digest.digest().toHexString()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Streams [file] in 64 KB chunks to compute its full SHA-256 hex digest.
     * Returns `null` on unreadable or missing files.
     */
    suspend fun computeFullSha256(file: File): String? {
        currentCoroutineContext().ensureActive()
        if (!isReadableRegularFile(file)) return null

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(FULL_HASH_BUFFER_BYTES)
            FileInputStream(file).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val bytesRead = input.read(buffer)
                    if (bytesRead <= 0) break
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().toHexString()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun isReadableRegularFile(file: File): Boolean =
        try {
            file.exists() && file.isFile && file.canRead()
        } catch (_: SecurityException) {
            false
        }

    private fun readExactOrAvailable(
        raf: RandomAccessFile,
        buffer: ByteArray,
        targetBytes: Int,
    ): Int? {
        var totalRead = 0
        while (totalRead < targetBytes) {
            val n = raf.read(buffer, totalRead, targetBytes - totalRead)
            if (n < 0) break
            totalRead += n
        }
        return if (totalRead > 0) totalRead else null
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val FULL_HASH_BUFFER_BYTES = 64 * 1024
    }
}
