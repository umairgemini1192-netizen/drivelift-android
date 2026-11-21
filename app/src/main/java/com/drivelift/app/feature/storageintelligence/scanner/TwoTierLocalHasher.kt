package com.drivelift.app.feature.storageintelligence.scanner

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Full-file SHA-256 and MD5 computed in a single streaming pass for local + cloud matching. */
data class DualDigest(val sha256: String, val md5: String)

/** Byte-reading abstraction so two-tier hashing can be tested cleanly without real MediaStore. */
interface LocalByteSource {
    suspend fun readPartialHash(node: UnifiedFileNode, windowBytes: Int = DEFAULT_PARTIAL_WINDOW_BYTES): String?
    suspend fun computeFullDualDigest(node: UnifiedFileNode, onRead: (Int) -> Unit = {}): DualDigest?

    companion object {
        const val DEFAULT_PARTIAL_WINDOW_BYTES = 64 * 1024
    }
}

private const val FULL_HASH_BUFFER_BYTES = 256 * 1024

/**
 * Computes a fast partial fingerprint from the first [windowBytes], last [windowBytes], and
 * 8-byte big-endian [sizeBytes]. When the file is at most `2 * windowBytes`, reads the whole file.
 */
suspend fun sha256PartialFromChannel(
    channel: FileChannel,
    sizeBytes: Long,
    windowBytes: Int = LocalByteSource.DEFAULT_PARTIAL_WINDOW_BYTES,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val actualSize = sizeBytes.coerceAtLeast(0L)
    if (actualSize <= windowBytes.toLong() * 2L) {
        channel.position(0L)
        val buffer = ByteBuffer.allocate(minOf(FULL_HASH_BUFFER_BYTES, maxOf(1024, actualSize.toInt())))
        while (true) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            digest.update(buffer.array(), 0, n)
        }
    } else {
        readChannelSlice(channel, 0L, windowBytes, digest)
        readChannelSlice(channel, actualSize - windowBytes, windowBytes, digest)
    }
    digest.update(longToBigEndianBytes(actualSize))
    return digest.digest().toHex()
}

private suspend fun readChannelSlice(
    channel: FileChannel,
    offset: Long,
    length: Int,
    digest: MessageDigest,
) {
    channel.position(offset)
    val buffer = ByteBuffer.allocate(minOf(length, FULL_HASH_BUFFER_BYTES))
    var remaining = length
    while (remaining > 0) {
        currentCoroutineContext().ensureActive()
        buffer.clear()
        buffer.limit(minOf(buffer.capacity(), remaining))
        val read = channel.read(buffer)
        if (read < 0) break
        digest.update(buffer.array(), 0, read)
        remaining -= read
    }
}

/**
 * Stream fallback for [sha256PartialFromChannel] producing identical output for any sequential
 * [InputStream] when direct seeking is unavailable.
 */
suspend fun sha256PartialFromStream(
    input: InputStream,
    sizeBytes: Long,
    windowBytes: Int = LocalByteSource.DEFAULT_PARTIAL_WINDOW_BYTES,
): String = input.use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val actualSize = sizeBytes.coerceAtLeast(0L)
    if (actualSize <= windowBytes.toLong() * 2L) {
        val buf = ByteArray(minOf(FULL_HASH_BUFFER_BYTES, maxOf(1024, actualSize.toInt())))
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buf)
            if (read < 0) break
            digest.update(buf, 0, read)
        }
    } else {
        val head = readExactUpTo(stream, windowBytes)
        digest.update(head.first, 0, head.second)
        val tailOffset = actualSize - windowBytes.toLong()
        val toSkip = (tailOffset - head.second.toLong()).coerceAtLeast(0L)
        skipFully(stream, toSkip)
        val tail = readExactUpTo(stream, windowBytes)
        digest.update(tail.first, 0, tail.second)
    }
    digest.update(longToBigEndianBytes(actualSize))
    digest.digest().toHex()
}

/**
 * Computes both SHA-256 and MD5 in one sequential pass over [input].
 * Supports cancellation promptly between 256 KB reads.
 */
suspend fun dualDigestOf(input: InputStream, onRead: (Int) -> Unit = {}): DualDigest = input.use { stream ->
    val sha = MessageDigest.getInstance("SHA-256")
    val md5 = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(FULL_HASH_BUFFER_BYTES)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = stream.read(buffer)
        if (read < 0) break
        sha.update(buffer, 0, read)
        md5.update(buffer, 0, read)
        onRead(read)
    }
    DualDigest(
        sha256 = sha.digest().toHex(),
        md5 = md5.digest().toHex(),
    )
}

private suspend fun readExactUpTo(stream: InputStream, maxBytes: Int): Pair<ByteArray, Int> {
    val out = ByteArray(maxBytes)
    var total = 0
    while (total < maxBytes) {
        currentCoroutineContext().ensureActive()
        val read = stream.read(out, total, maxBytes - total)
        if (read < 0) break
        total += read
    }
    return out to total
}

private suspend fun skipFully(stream: InputStream, bytesToSkip: Long) {
    var remaining = bytesToSkip
    val discard = ByteArray(16 * 1024)
    while (remaining > 0) {
        currentCoroutineContext().ensureActive()
        val skipped = stream.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        val read = stream.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
        if (read < 0) break
        remaining -= read
    }
}

private fun longToBigEndianBytes(value: Long): ByteArray =
    ByteBuffer.allocate(8).putLong(value).array()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Reads original unredacted bytes from MediaStore via [MediaAccess] / [openOriginalStream].
 */
class MediaStoreLocalByteSource(
    private val context: Context,
    private val openOriginalStream: (Context, Uri) -> InputStream,
    private val openOriginalFd: ((Context, Uri) -> ParcelFileDescriptor)? = { ctx, uri ->
        MediaAccess.open(ctx, uri)
    },
) : LocalByteSource {

    override suspend fun readPartialHash(node: UnifiedFileNode, windowBytes: Int): String? {
        val rawUri = node.localUri ?: return null
        val uri = Uri.parse(rawUri)
        return withContext(Dispatchers.IO) {
            try {
                val pfd = openOriginalFd?.let { opener ->
                    runCatching { opener(context, uri) }.getOrNull()
                }
                if (pfd != null) {
                    pfd.use { descriptor ->
                        val size = descriptor.statSize.takeIf { it > 0L } ?: node.sizeBytes
                        FileInputStream(descriptor.fileDescriptor).use { fis ->
                            sha256PartialFromChannel(fis.channel, size, windowBytes)
                        }
                    }
                } else {
                    sha256PartialFromStream(openOriginalStream(context, uri), node.sizeBytes, windowBytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun computeFullDualDigest(node: UnifiedFileNode, onRead: (Int) -> Unit): DualDigest? {
        val rawUri = node.localUri ?: return null
        val uri = Uri.parse(rawUri)
        return withContext(Dispatchers.IO) {
            try {
                dualDigestOf(openOriginalStream(context, uri), onRead)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}
