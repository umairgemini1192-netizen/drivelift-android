package com.drivelift.app.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * One entry listed from a ZIP archive. [size] is the number of bytes actually read from the
 * entry while streaming, never the size the archive header claims.
 */
data class ZipEntryInfo(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

/** Result of listing an archive: every entry in archive order, or the reason the job stopped. */
sealed interface ZipListResult {
    data class Listed(val entries: List<ZipEntryInfo>, val totalBytes: Long) : ZipListResult
    data class Refused(val entryName: String?, val reason: String) : ZipListResult
}

/** Result of opening one entry: its bytes, or the reason it could not be opened. */
sealed interface ZipOpenResult {
    class Opened(val bytes: ByteArray) : ZipOpenResult
    data class Refused(val reason: String) : ZipOpenResult
}

/**
 * ZIP browse, read only (scoped-storage/SAF design, `docs/design/scoped-storage-saf.md`, section 8,
 * slice 3). Streams an archive from an [InputStream] the caller already holds a grant for. Nothing
 * is written to storage. Every entry name goes through [ArchiveEntryPath] before it is reported,
 * and every limit in [ArchiveLimits] is checked while the entry data is read, not from headers.
 *
 * A refused entry name, a breached limit or a damaged archive stops the whole job. No partial
 * listing is returned as if it were complete.
 *
 * Not covered by this slice: encrypted entries, ZIP64 (read by the JDK, not tested here), and any
 * link handling (nothing is created, so links are not followed).
 */
object ZipBrowse {
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** Lists every entry. Enforces [limits] on entry count, folder depth and bytes read. */
    fun list(input: InputStream, limits: ArchiveLimits = ArchiveLimits()): ZipListResult {
        val entries = ArrayList<ZipEntryInfo>()
        val walk = walk(input, limits, target = null, captureCap = 0) { info, _ ->
            entries += info
            null
        }
        walk.refusal?.let { return it }
        return ZipListResult.Listed(entries.toList(), walk.totalBytes)
    }

    /**
     * Opens the file entry named [entryPath] (the name exactly as the archive stores it; it is
     * checked again here) and returns its bytes. An entry larger than [maxBytes] is refused, not
     * truncated, so the caller never shows a partial file as complete.
     */
    fun open(
        input: InputStream,
        entryPath: String,
        maxBytes: Int,
        limits: ArchiveLimits = ArchiveLimits(),
    ): ZipOpenResult {
        val wanted = when (val check = ArchiveEntryPath.check(entryPath)) {
            is ArchiveEntryPath.Result.Refused -> return ZipOpenResult.Refused(check.reason)
            is ArchiveEntryPath.Result.Accepted -> check.components.joinToString("/")
        }
        var found: ByteArray? = null
        var tooLargeSeen = false
        val walk = walk(input, limits, target = wanted, captureCap = maxBytes) { info, bytes ->
            if (info.path == wanted && !info.isDirectory) {
                if (bytes == null) tooLargeSeen = true else found = bytes
            }
            null
        }
        walk.refusal?.let { return ZipOpenResult.Refused(it.reason) }
        found?.let { return ZipOpenResult.Opened(it) }
        if (tooLargeSeen) {
            return ZipOpenResult.Refused("The file is larger than $maxBytes bytes and cannot be opened.")
        }
        return ZipOpenResult.Refused("No file named \"$entryPath\" is in this archive.")
    }

    private class Walk(val totalBytes: Long, val refusal: ZipListResult.Refused?)

    /**
     * Reads the archive once, entry by entry. For each accepted entry, calls [onEntry] with its
     * info and, when the entry is the [target], its bytes: null if they exceed [captureCap].
     * [onEntry] returns a reason to stop the whole job, or null to continue.
     */
    private fun walk(
        input: InputStream,
        limits: ArchiveLimits,
        target: String?,
        captureCap: Int,
        onEntry: (ZipEntryInfo, ByteArray?) -> String?,
    ): Walk {
        var count = 0
        var total = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        val buffered = BufferedInputStream(input)
        if (!hasZipSignature(buffered)) {
            // ZipInputStream treats any non-ZIP bytes as an empty archive, so check the start first.
            return Walk(0L, ZipListResult.Refused(null, "This file is not a ZIP archive."))
        }
        try {
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val check = ArchiveEntryPath.check(entry.name)
                    if (check is ArchiveEntryPath.Result.Refused) {
                        return Walk(total, ZipListResult.Refused(entry.name, check.reason))
                    }
                    val components = (check as ArchiveEntryPath.Result.Accepted).components
                    val path = components.joinToString("/")
                    val isDirectory = entry.isDirectory
                    // A file's depth is the number of folders above it; a folder counts itself.
                    val depth = if (isDirectory) components.size else components.size - 1
                    count += 1
                    limits.violation(entries = count, depth = depth, totalBytes = total)?.let {
                        return Walk(total, ZipListResult.Refused(entry.name, it))
                    }

                    val keep = target != null && path == target
                    val captured = if (keep) ByteArrayOutputStream() else null
                    var overCap = false
                    var size = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n
                        total += n
                        limits.violation(entries = count, depth = depth, totalBytes = total)?.let {
                            return Walk(total, ZipListResult.Refused(entry.name, it))
                        }
                        if (captured != null && !overCap) {
                            if (captured.size() + n > captureCap) overCap = true
                            else captured.write(buffer, 0, n)
                        }
                    }
                    val bytes = when {
                        captured == null -> null
                        overCap -> null
                        else -> captured.toByteArray()
                    }
                    onEntry(ZipEntryInfo(path, isDirectory, size), bytes)?.let {
                        return Walk(total, ZipListResult.Refused(entry.name, it))
                    }
                }
            }
        } catch (e: ZipException) {
            return Walk(total, ZipListResult.Refused(null, "The archive is damaged or not a ZIP file."))
        } catch (e: IOException) {
            return Walk(total, ZipListResult.Refused(null, "The archive could not be read to the end."))
        }
        return Walk(total, null)
    }

    /**
     * True when the stream starts with a local-file header (PK 3 4) or, for an empty archive, an
     * end-of-central-directory record (PK 5 6). Peeks with mark and reset, consuming nothing.
     */
    private fun hasZipSignature(stream: BufferedInputStream): Boolean {
        stream.mark(4)
        val head = ByteArray(4)
        val read = stream.readNBytes(head, 0, 4)
        stream.reset()
        if (read < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return false
        return (head[2] == 3.toByte() && head[3] == 4.toByte()) || (head[2] == 5.toByte() && head[3] == 6.toByte())
    }
}
