package com.drivelift.app.feature.desktopdrop.data

import android.content.Context
import android.net.Uri
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Abstraction over local File and Android ContentResolver streams for Desktop Drop downloads. */
interface SharedItemSource {
    fun localFileOrNull(item: SharedDropItem): File? =
        item.localFilePath?.let(::File)?.takeIf { it.isFile && it.canRead() }

    fun openInputStream(item: SharedDropItem): InputStream

    fun openRangedInputStream(item: SharedDropItem, startOffset: Long, length: Long): InputStream
}

class FileSharedItemSource : SharedItemSource {
    override fun openInputStream(item: SharedDropItem): InputStream {
        val file = localFileOrNull(item) ?: throw FileNotFoundException("Missing file for ${item.name}")
        return FileInputStream(file)
    }

    override fun openRangedInputStream(item: SharedDropItem, startOffset: Long, length: Long): InputStream {
        val stream = openInputStream(item)
        if (stream is FileInputStream && startOffset > 0L) {
            stream.channel.position(startOffset)
        } else if (startOffset > 0L) {
            skipFully(stream, startOffset)
        }
        return BoundedInputStream(stream, length)
    }
}

class AndroidSharedItemSource(private val context: Context) : SharedItemSource {
    private val fileSource = FileSharedItemSource()

    override fun openInputStream(item: SharedDropItem): InputStream {
        fileSource.localFileOrNull(item)?.let { return FileInputStream(it) }
        val uriString = item.sourceUri ?: throw FileNotFoundException("No URI for ${item.name}")
        val uri = Uri.parse(uriString)
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
        }
        return context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open ${item.name}")
    }

    override fun openRangedInputStream(item: SharedDropItem, startOffset: Long, length: Long): InputStream {
        fileSource.localFileOrNull(item)?.let {
            return fileSource.openRangedInputStream(item, startOffset, length)
        }
        val uriString = item.sourceUri ?: throw FileNotFoundException("No URI for ${item.name}")
        val uri = Uri.parse(uriString)
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            if (startOffset > 0L) {
                try {
                    stream.channel.position(startOffset)
                } catch (_: Exception) {
                    skipFully(stream, startOffset)
                }
            }
            return BoundedInputStream(stream, length)
        }
        val stream = openInputStream(item)
        if (startOffset > 0L) skipFully(stream, startOffset)
        return BoundedInputStream(stream, length)
    }
}

private fun skipFully(stream: InputStream, bytesToSkip: Long) {
    var remaining = bytesToSkip
    val discard = ByteArray(64 * 1024)
    while (remaining > 0L) {
        val skipped = stream.skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
            continue
        }
        val read = stream.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
        if (read < 0) break
        remaining -= read
    }
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0L) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val capped = minOf(len.toLong(), remaining).toInt()
        val count = delegate.read(b, off, capped)
        if (count > 0) remaining -= count
        return count
    }

    override fun close() = delegate.close()
}
