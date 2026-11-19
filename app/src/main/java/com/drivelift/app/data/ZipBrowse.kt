package com.drivelift.app.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Where the archive bytes come from. The archive is read in two places: its end (the central
 * directory, which is the authority for what the archive contains) and from the start (the entry
 * data). Both need a stream at a known offset, so a plain one-pass stream is not enough.
 */
interface ZipSource {
    /** Total size of the archive, in bytes. */
    val length: Long

    /** A new stream that starts at [offset] and ends at the end of the archive. Caller closes it. */
    fun open(offset: Long): InputStream

    companion object {
        /** A source over bytes already in memory (used by tests and small archives). */
        fun of(bytes: ByteArray): ZipSource = object : ZipSource {
            override val length: Long = bytes.size.toLong()
            override fun open(offset: Long): InputStream =
                java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }
}

/**
 * One entry listed from a ZIP archive. [size] is the number of bytes actually read from the
 * entry while streaming, and it must match the size the central directory records.
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
 * slice 3). Nothing is written to storage.
 *
 * Integrity, in order:
 * 1. The end record (end of central directory) must be complete, and its directory position must
 *    fit exactly before it. A truncated or four-byte end signature is refused.
 * 2. The central directory is parsed in full and is the authority for names. A name with the
 *    UTF-8 flag (bit 11) is decoded strictly as UTF-8; without it, as IBM437 (the DOS default
 *    Windows archivers use). An invalid UTF-8 name is refused. Each name goes through
 *    [ArchiveEntryPath]. Entry count and sizes are checked. Encrypted entries, unsupported
 *    compression, and ZIP64 are refused.
 * 3. The entry data is read from the start. Each entry's data and size must match its directory
 *    record, in the same order, and nothing may follow the last one. Names in the data are not
 *    trusted: the directory names are used.
 * [ArchiveLimits] are checked on the entry count and folder depth from the directory, and on the
 * bytes read while streaming.
 *
 * Any failure stops the whole job. No partial listing is returned as complete.
 */
object ZipBrowse {
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val EOCD_MIN_BYTES = 22
    private const val EOCD_MAX_TAIL = EOCD_MIN_BYTES + 65_535
    private const val CEN_HEADER_BYTES = 46
    private const val MAX_DIRECTORY_BYTES = 16 * 1024 * 1024
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_UTF8 = 0x0800
    private val IBM437: Charset = Charset.forName("IBM437")

    /** One record from the central directory. */
    private class DirEntry(val path: String, val isDirectory: Boolean, val size: Long)

    private class Directory(val entries: List<DirEntry>)

    /** Lists every entry after checking the archive is complete. */
    fun list(source: ZipSource, limits: ArchiveLimits = ArchiveLimits()): ZipListResult {
        val directory = when (val d = readDirectory(source, limits)) {
            is DirectoryResult.Refused -> return ZipListResult.Refused(null, d.reason)
            is DirectoryResult.Ok -> d.directory
        }
        val entries = ArrayList<ZipEntryInfo>()
        val walk = walk(source, limits, directory.entries, target = null, captureCap = 0) { info, _ ->
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
        source: ZipSource,
        entryPath: String,
        maxBytes: Int,
        limits: ArchiveLimits = ArchiveLimits(),
    ): ZipOpenResult {
        val wanted = when (val check = ArchiveEntryPath.check(entryPath)) {
            is ArchiveEntryPath.Result.Refused -> return ZipOpenResult.Refused(check.reason)
            is ArchiveEntryPath.Result.Accepted -> check.components.joinToString("/")
        }
        val directory = when (val d = readDirectory(source, limits)) {
            is DirectoryResult.Refused -> return ZipOpenResult.Refused(d.reason)
            is DirectoryResult.Ok -> d.directory
        }
        var found: ByteArray? = null
        var tooLargeSeen = false
        val walk = walk(source, limits, directory.entries, target = wanted, captureCap = maxBytes) { info, bytes ->
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

    private sealed interface DirectoryResult {
        class Ok(val directory: Directory) : DirectoryResult
        data class Refused(val reason: String) : DirectoryResult
    }

    /** Finds and parses the end record and the central directory. Reads only what it needs. */
    private fun readDirectory(source: ZipSource, limits: ArchiveLimits): DirectoryResult {
        val length = source.length
        if (length < EOCD_MIN_BYTES) {
            return DirectoryResult.Refused("This file is not a complete ZIP archive.")
        }
        val tailLen = minOf(length, EOCD_MAX_TAIL.toLong()).toInt()
        val tail = readAt(source, length - tailLen, tailLen)
            ?: return DirectoryResult.Refused("The archive could not be read to the end.")

        // The end record is the last PK 05 06 whose comment length reaches exactly the end of file.
        var eocd = -1
        for (i in tailLen - EOCD_MIN_BYTES downTo 0) {
            if (isSig(tail, i, 5, 6) && u16(tail, i + 20) == tailLen - i - EOCD_MIN_BYTES) {
                eocd = i
                break
            }
        }
        if (eocd < 0) {
            return DirectoryResult.Refused("The ZIP has no complete end record. It may be truncated.")
        }
        val diskNumber = u16(tail, eocd + 4)
        val cdDisk = u16(tail, eocd + 6)
        val entriesOnDisk = u16(tail, eocd + 8)
        val entryCount = u16(tail, eocd + 10)
        val cenSize = u32(tail, eocd + 12)
        val cenOffset = u32(tail, eocd + 16)

        if (entryCount == 0xFFFF || cenSize == 0xFFFFFFFFL || cenOffset == 0xFFFFFFFFL) {
            return DirectoryResult.Refused("ZIP64 archives are not supported yet.")
        }
        if (diskNumber != 0 || cdDisk != 0 || entriesOnDisk != entryCount) {
            return DirectoryResult.Refused("Multi-disk ZIP archives are not supported.")
        }
        val eocdPosition = length - tailLen + eocd
        if (cenOffset + cenSize != eocdPosition) {
            return DirectoryResult.Refused("The ZIP directory does not sit where its end record says. The archive may be damaged or have extra data in front.")
        }
        limits.violation(entries = entryCount, depth = 0, totalBytes = 0)?.let {
            return DirectoryResult.Refused(it)
        }
        if (cenSize > MAX_DIRECTORY_BYTES) {
            return DirectoryResult.Refused("The ZIP directory is too large to read.")
        }

        val cen = readAt(source, cenOffset, cenSize.toInt())
            ?: return DirectoryResult.Refused("The ZIP directory could not be read to the end.")
        val entries = ArrayList<DirEntry>(entryCount)
        var pos = 0
        for (k in 0 until entryCount) {
            if (pos + CEN_HEADER_BYTES > cen.size || !isSig(cen, pos, 1, 2)) {
                return DirectoryResult.Refused("The ZIP directory is damaged.")
            }
            val flags = u16(cen, pos + 8)
            val method = u16(cen, pos + 10)
            val uncompressed = u32(cen, pos + 24)
            val nameLen = u16(cen, pos + 28)
            val extraLen = u16(cen, pos + 30)
            val commentLen = u16(cen, pos + 32)
            val recordEnd = pos + CEN_HEADER_BYTES + nameLen + extraLen + commentLen
            if (recordEnd > cen.size) return DirectoryResult.Refused("The ZIP directory is damaged.")
            if ((flags and FLAG_ENCRYPTED) != 0) return DirectoryResult.Refused("Encrypted ZIP entries are not supported.")
            if (method != 0 && method != 8) {
                return DirectoryResult.Refused("An entry uses a compression method this app cannot read.")
            }
            val rawName = decodeName(cen, pos + CEN_HEADER_BYTES, nameLen, (flags and FLAG_UTF8) != 0)
                ?: return DirectoryResult.Refused("An entry name is not valid UTF-8.")
            val check = ArchiveEntryPath.check(rawName)
            if (check is ArchiveEntryPath.Result.Refused) {
                return DirectoryResult.Refused("Entry \"$rawName\" refused: ${check.reason}")
            }
            val components = (check as ArchiveEntryPath.Result.Accepted).components
            val isDirectory = rawName.endsWith("/")
            val depth = if (isDirectory) components.size else components.size - 1
            limits.violation(entries = k + 1, depth = depth, totalBytes = 0)?.let {
                return DirectoryResult.Refused(it)
            }
            entries += DirEntry(components.joinToString("/"), isDirectory, if (isDirectory) 0L else uncompressed)
            pos = recordEnd
        }
        if (pos != cen.size) return DirectoryResult.Refused("The ZIP directory is damaged.")
        return DirectoryResult.Ok(Directory(entries))
    }

    /**
     * Decodes one directory name. With the UTF-8 flag it must be valid UTF-8, or null is returned.
     * Without the flag it is IBM437, which maps every byte, so it never fails.
     */
    private fun decodeName(b: ByteArray, at: Int, count: Int, utf8: Boolean): String? {
        if (!utf8) return String(b, at, count, IBM437)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(b, at, count)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private class Walk(val totalBytes: Long, val refusal: ZipListResult.Refused?)

    /**
     * Reads the archive from the start, one entry at a time, and checks each entry against the
     * central directory. The path, folder flag and size come from the directory; the names inside
     * the entry data are not used. [onEntry] receives the info and, for [target], its bytes (null
     * when they exceed [captureCap]); it returns a reason to stop the job, or null to continue.
     */
    private fun walk(
        source: ZipSource,
        limits: ArchiveLimits,
        expected: List<DirEntry>,
        target: String?,
        captureCap: Int,
        onEntry: (ZipEntryInfo, ByteArray?) -> String?,
    ): Walk {
        var count = 0
        var total = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        source.open(0).use { raw ->
            val buffered = BufferedInputStream(raw)
            if (!hasZipStart(buffered)) {
                return Walk(0L, ZipListResult.Refused(null, "This file is not a ZIP archive."))
            }
            try {
                // IBM437 decodes every byte, so ZipInputStream cannot fail on a name. Names read
                // from the data are not used anyway: the central directory is the authority.
                ZipInputStream(buffered, IBM437).use { zip ->
                    while (true) {
                        zip.nextEntry ?: break
                        count += 1
                        if (count > expected.size) {
                            return Walk(total, ZipListResult.Refused(null, "The archive has more entries than its directory lists."))
                        }
                        val want = expected[count - 1]
                        val path = want.path
                        val depth = if (want.isDirectory) pathSegments(path) else pathSegments(path) - 1
                        limits.violation(entries = count, depth = depth, totalBytes = total)?.let {
                            return Walk(total, ZipListResult.Refused(path, it))
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
                                return Walk(total, ZipListResult.Refused(path, it))
                            }
                            if (captured != null && !overCap) {
                                if (captured.size() + n > captureCap) overCap = true
                                else captured.write(buffer, 0, n)
                            }
                        }
                        if (size != want.size) {
                            return Walk(total, ZipListResult.Refused(path, "The archive contents do not match its directory."))
                        }
                        val bytes = when {
                            captured == null -> null
                            overCap -> null
                            else -> captured.toByteArray()
                        }
                        onEntry(ZipEntryInfo(path, want.isDirectory, size), bytes)?.let {
                            return Walk(total, ZipListResult.Refused(path, it))
                        }
                    }
                }
            } catch (e: ZipException) {
                return Walk(total, ZipListResult.Refused(null, "The archive is damaged or not a ZIP file."))
            } catch (e: IllegalArgumentException) {
                // ZipInputStream rejects some name encodings it cannot decode. Refuse, do not throw.
                return Walk(total, ZipListResult.Refused(null, "The archive uses names this app cannot read."))
            } catch (e: IOException) {
                return Walk(total, ZipListResult.Refused(null, "The archive could not be read to the end."))
            }
        }
        if (count != expected.size) {
            return Walk(total, ZipListResult.Refused(null, "The archive is incomplete: it has fewer entries than its directory lists."))
        }
        return Walk(total, null)
    }

    private fun pathSegments(path: String): Int = if (path.isEmpty()) 0 else path.count { it == '/' } + 1

    /** True when the stream starts with a local-file header (PK 3 4) or an empty archive's end record. */
    private fun hasZipStart(stream: BufferedInputStream): Boolean {
        stream.mark(4)
        val head = ByteArray(4)
        val read = readFully(stream, head, 4)
        stream.reset()
        return read == 4 && (isSig(head, 0, 3, 4) || isSig(head, 0, 5, 6))
    }

    /** Reads [count] bytes at [offset], or null if the source ends early. */
    private fun readAt(source: ZipSource, offset: Long, count: Int): ByteArray? = try {
        source.open(offset).use { stream ->
            val bytes = ByteArray(count)
            if (readFully(stream, bytes, count) == count) bytes else null
        }
    } catch (e: IOException) {
        null
    }

    private fun isSig(b: ByteArray, at: Int, third: Int, fourth: Int): Boolean =
        at + 3 < b.size && b[at] == 'P'.code.toByte() && b[at + 1] == 'K'.code.toByte() &&
            b[at + 2] == third.toByte() && b[at + 3] == fourth.toByte()

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    /** Reads up to [count] bytes into [buf]; returns how many arrived. Avoids readNBytes (API 33). */
    private fun readFully(stream: InputStream, buf: ByteArray, count: Int): Int {
        var got = 0
        while (got < count) {
            val n = stream.read(buf, got, count - got)
            if (n < 0) break
            got += n
        }
        return got
    }
}
