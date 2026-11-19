package com.drivelift.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Scoped-storage/SAF design slice 3 (issue #42): ZIP browse, read only, with synthetic archives. */
class ZipBrowseTest {

    /** Builds an archive in memory. Names are written exactly as given, including hostile ones. */
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun listOf(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits()): ZipListResult =
        ZipBrowse.list(ZipSource.of(bytes), limits)

    /** Position of the first occurrence of [sig] (4 bytes) at or after [from]. */
    private fun find(bytes: ByteArray, sig: ByteArray, from: Int = 0): Int {
        for (i in from..bytes.size - 4) {
            if (bytes[i] == sig[0] && bytes[i + 1] == sig[1] && bytes[i + 2] == sig[2] && bytes[i + 3] == sig[3]) return i
        }
        return -1
    }

    private val centralSig = byteArrayOf(0x50, 0x4b, 1, 2)

    @Test
    fun listsFilesAndFoldersInArchiveOrderWithMeasuredSizes() {
        val bytes = zip(
            "docs/" to ByteArray(0),
            "docs/readme.txt" to "hello".toByteArray(),
            "photo.final.jpg" to ByteArray(10),
        )
        val result = listOf(bytes) as ZipListResult.Listed
        assertEquals(
            listOf(
                ZipEntryInfo("docs", isDirectory = true, size = 0),
                ZipEntryInfo("docs/readme.txt", isDirectory = false, size = 5),
                ZipEntryInfo("photo.final.jpg", isDirectory = false, size = 10),
            ),
            result.entries,
        )
        assertEquals(15L, result.totalBytes)
    }

    @Test
    fun validEmptyArchiveIsListedAsEmpty() {
        val result = listOf(zip()) as ZipListResult.Listed
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun fourByteEndSignatureAloneIsRefused() {
        val result = listOf(byteArrayOf(0x50, 0x4b, 5, 6))
        assertTrue(result is ZipListResult.Refused)
    }

    @Test
    fun truncatedArchiveIsRefusedEvenWhenItsFirstEntryIsComplete() {
        val full = zip("a.txt" to ByteArray(40) { 1 }, "b.txt" to ByteArray(40) { 2 })
        // Cut off the central directory and end record: the local entries are still intact.
        val cut = full.copyOf(find(full, centralSig))
        val result = listOf(cut)
        assertTrue(result is ZipListResult.Refused)
    }

    @Test
    fun archiveWithItsEndRecordTruncatedIsRefused() {
        val full = zip("a.txt" to ByteArray(4))
        val result = listOf(full.copyOf(full.size - 10))
        assertTrue(result is ZipListResult.Refused)
    }

    @Test
    fun refusesTraversalAndAbsoluteNames() {
        for (hostile in listOf("../evil.txt", "a/../../evil.txt", "/etc/passwd", "C:/x.txt", "a\\..\\b.txt")) {
            val bytes = zip("ok.txt" to ByteArray(1), hostile to ByteArray(1))
            val result = listOf(bytes)
            assertTrue("$hostile must be refused", result is ZipListResult.Refused)
        }
    }

    @Test
    fun refusesNamesStorageCannotKeep() {
        val result = listOf(zip("report./x.txt" to ByteArray(1)))
        assertTrue(result is ZipListResult.Refused)
    }

    @Test
    fun entryCountLimitStopsTheJob() {
        val bytes = zip("a" to ByteArray(1), "b" to ByteArray(1), "c" to ByteArray(1))
        val result = listOf(bytes, ArchiveLimits(maxEntries = 2))
        assertTrue(result is ZipListResult.Refused)
        assertTrue((result as ZipListResult.Refused).reason.contains("entries"))
    }

    @Test
    fun folderDepthLimitCountsFoldersAboveTheFile() {
        // a/b/c.txt sits two folders deep, so depth 2 is allowed and depth 1 is not.
        val bytes = zip("a/b/c.txt" to ByteArray(1))
        assertTrue(listOf(bytes, ArchiveLimits(maxDepth = 2)) is ZipListResult.Listed)
        val refused = listOf(bytes, ArchiveLimits(maxDepth = 1))
        assertTrue(refused is ZipListResult.Refused)
        assertTrue((refused as ZipListResult.Refused).reason.contains("deeper"))
    }

    @Test
    fun expandedByteLimitIsCountedWhileReadingNotFromHeaders() {
        val bytes = zip("big.bin" to ByteArray(200))
        val result = listOf(bytes, ArchiveLimits(maxTotalBytes = 100))
        assertTrue(result is ZipListResult.Refused)
        assertTrue((result as ZipListResult.Refused).reason.contains("expands"))
    }

    @Test
    fun nonZipBytesAreRefusedWithoutThrowing() {
        val result = listOf("this is not a zip archive at all".toByteArray())
        assertTrue(result is ZipListResult.Refused)
    }

    @Test
    fun centralDirectorySizeMismatchWithDataIsRefused() {
        val bytes = zip("a.txt" to ByteArray(5))
        // The directory says the file is 5 bytes; change it to 6 so it no longer matches the data.
        val cen = find(bytes, centralSig)
        bytes[cen + 24] = 6
        val result = listOf(bytes)
        assertTrue(result is ZipListResult.Refused)
        assertTrue((result as ZipListResult.Refused).reason.contains("do not match"))
    }

    @Test
    fun encryptedEntryIsRefused() {
        val bytes = zip("a.txt" to ByteArray(5))
        val cen = find(bytes, centralSig)
        bytes[cen + 8] = 1
        val result = listOf(bytes)
        assertTrue((result as ZipListResult.Refused).reason.contains("Encrypted"))
    }

    /** An archive whose names are IBM437 (no UTF-8 flag), as Windows archivers write non-ASCII names. */
    private fun ibm437Zip(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, java.nio.charset.Charset.forName("IBM437")).use { zos ->
            zos.putNextEntry(ZipEntry(name))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun ibm437NameListsAsItsDecodedText() {
        val bytes = ibm437Zip("café.txt")
        val result = listOf(bytes) as ZipListResult.Listed
        assertEquals("café.txt", result.entries.single().path)
    }

    @Test
    fun ibm437NameCanBeOpenedByItsDecodedText() {
        val bytes = ibm437Zip("café.txt")
        val opened = ZipBrowse.open(ZipSource.of(bytes), "café.txt", maxBytes = 100)
        assertArrayEquals("x".toByteArray(), (opened as ZipOpenResult.Opened).bytes)
    }

    @Test
    fun nameFlaggedAsUtf8ButNotValidUtf8IsRefusedNotThrown() {
        val bytes = ibm437Zip("café.txt")
        // Set the UTF-8 flag (bit 11) on the central directory record: 0x82 is not valid UTF-8.
        val cen = find(bytes, centralSig)
        bytes[cen + 9] = (bytes[cen + 9].toInt() or 0x08).toByte()
        val result = listOf(bytes)
        assertTrue(result is ZipListResult.Refused)
        assertTrue((result as ZipListResult.Refused).reason.contains("UTF-8"))
    }

    @Test
    fun utf8NameWithAccentListsCorrectly() {
        val bytes = zip("café.txt" to "x".toByteArray())
        val result = listOf(bytes) as ZipListResult.Listed
        assertEquals("café.txt", result.entries.single().path)
    }

    @Test
    fun streamIsClosedWhenTheStartIsNotAZipHeader() {
        // A valid directory whose first local header signature is corrupted: the walk refuses at
        // the start, and the stream it opened for that must still be closed.
        val bytes = zip("a.txt" to ByteArray(3))
        bytes[2] = 7
        var opened = 0
        var closed = 0
        val source = object : ZipSource {
            override val length: Long = bytes.size.toLong()
            override fun open(offset: Long): java.io.InputStream {
                opened++
                val inner = java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
                return object : java.io.FilterInputStream(inner) {
                    override fun close() {
                        closed++
                        super.close()
                    }
                }
            }
        }
        val result = ZipBrowse.list(source)
        assertTrue(result is ZipListResult.Refused)
        assertEquals("This file is not a ZIP archive.", (result as ZipListResult.Refused).reason)
        assertEquals(opened, closed)
    }

    @Test
    fun openReturnsTheBytesOfAFileEntry() {
        val bytes = zip("docs/readme.txt" to "hello".toByteArray(), "other.bin" to ByteArray(3))
        val opened = ZipBrowse.open(ZipSource.of(bytes), "docs/readme.txt", maxBytes = 100)
        assertArrayEquals("hello".toByteArray(), (opened as ZipOpenResult.Opened).bytes)
    }

    @Test
    fun openRefusesAnEntryLargerThanTheCapInsteadOfTruncating() {
        val bytes = zip("big.bin" to ByteArray(50))
        val result = ZipBrowse.open(ZipSource.of(bytes), "big.bin", maxBytes = 10)
        assertTrue(result is ZipOpenResult.Refused)
        assertTrue((result as ZipOpenResult.Refused).reason.contains("larger than 10 bytes"))
    }

    @Test
    fun openRefusesAHostileRequestedNameBeforeReading() {
        val bytes = zip("ok.txt" to ByteArray(1))
        val result = ZipBrowse.open(ZipSource.of(bytes), "../ok.txt", maxBytes = 100)
        assertTrue(result is ZipOpenResult.Refused)
    }

    @Test
    fun openReportsAMissingEntry() {
        val bytes = zip("ok.txt" to ByteArray(1))
        val result = ZipBrowse.open(ZipSource.of(bytes), "missing.txt", maxBytes = 100)
        assertTrue((result as ZipOpenResult.Refused).reason.contains("missing.txt"))
    }

    @Test
    fun openDoesNotTreatAFolderAsAFile() {
        val bytes = zip("docs/" to ByteArray(0), "docs/a.txt" to ByteArray(1))
        val result = ZipBrowse.open(ZipSource.of(bytes), "docs", maxBytes = 100)
        assertTrue(result is ZipOpenResult.Refused)
    }
}
