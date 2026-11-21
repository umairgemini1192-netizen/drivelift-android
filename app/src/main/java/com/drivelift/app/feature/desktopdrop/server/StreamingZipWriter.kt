package com.drivelift.app.feature.desktopdrop.server

import com.drivelift.app.feature.desktopdrop.data.DropPathSanitizer
import com.drivelift.app.feature.desktopdrop.data.SharedItemSource
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams multiple shared items directly into a STORED (uncompressed) ZIP output stream with
 * zero intermediate temporary archive files on disk.
 */
object StreamingZipWriter {

    private const val IO_BUFFER_BYTES = 64 * 1024

    fun writeStoredZip(
        items: List<SharedDropItem>,
        source: SharedItemSource,
        outputStream: OutputStream,
        onBytesWritten: (Long) -> Unit = {},
    ) {
        val usedEntries = HashSet<String>(items.size * 2)
        val buffer = ByteArray(IO_BUFFER_BYTES)
        ZipOutputStream(BufferedOutputStream(outputStream, IO_BUFFER_BYTES)).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            zip.setLevel(0)

            for (item in items) {
                val rawEntryName = buildEntryName(item)
                val entryName = DropPathSanitizer.uniqueName(rawEntryName) { it in usedEntries }
                usedEntries += entryName

                val (exactSize, crcValue) = measureSizeAndCrc(item, source, buffer)
                val entry = ZipEntry(entryName).apply {
                    method = ZipEntry.STORED
                    size = exactSize
                    compressedSize = exactSize
                    crc = crcValue
                    if (item.addedAtMs > 0L) time = item.addedAtMs
                }

                zip.putNextEntry(entry)
                source.openInputStream(item).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            zip.write(buffer, 0, read)
                            onBytesWritten(read.toLong())
                        }
                    }
                }
                zip.closeEntry()
            }
            zip.flush()
        }
    }

    private fun buildEntryName(item: SharedDropItem): String {
        val cleanName = DropPathSanitizer.sanitizeFilename(item.name)
        val folderSegments = DropPathSanitizer.sanitizeRelativeFolderSegments(item.relativePath, item.name)
        return if (folderSegments.isEmpty()) cleanName else "${folderSegments.joinToString("/")}/$cleanName"
    }

    private fun measureSizeAndCrc(
        item: SharedDropItem,
        source: SharedItemSource,
        buffer: ByteArray,
    ): Pair<Long, Long> {
        val crc = CRC32()
        var total = 0L
        source.openInputStream(item).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    crc.update(buffer, 0, read)
                    total += read
                }
            }
        }
        return total to crc.value
    }
}
