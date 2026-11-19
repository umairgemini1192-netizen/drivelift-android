package com.drivelift.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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
        ZipBrowse.list(ByteArrayInputStream(bytes), limits)

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
    fun emptyArchiveListsNothing() {
        val result = listOf(zip()) as ZipListResult.Listed
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun refusesTraversalAndAbsoluteNamesAndStopsTheWholeListing() {
        for (hostile in listOf("../evil.txt", "a/../../evil.txt", "/etc/passwd", "C:/x.txt", "a\\..\\b.txt")) {
            val bytes = zip("ok.txt" to ByteArray(1), hostile to ByteArray(1))
            val result = listOf(bytes)
            assertTrue("$hostile must be refused", result is ZipListResult.Refused)
            assertEquals(hostile, (result as ZipListResult.Refused).entryName)
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
    fun damagedBytesAreRefusedWithoutThrowing() {
        val result = listOf("this is not a zip archive at all".toByteArray())
        assertTrue(result is ZipListResult.Refused)
        assertEquals("This file is not a ZIP archive.", (result as ZipListResult.Refused).reason)
    }

    @Test
    fun openReturnsTheBytesOfAFileEntry() {
        val bytes = zip("docs/readme.txt" to "hello".toByteArray(), "other.bin" to ByteArray(3))
        val opened = ZipBrowse.open(ByteArrayInputStream(bytes), "docs/readme.txt", maxBytes = 100)
        assertArrayEquals("hello".toByteArray(), (opened as ZipOpenResult.Opened).bytes)
    }

    @Test
    fun openRefusesAnEntryLargerThanTheCapInsteadOfTruncating() {
        val bytes = zip("big.bin" to ByteArray(50))
        val result = ZipBrowse.open(ByteArrayInputStream(bytes), "big.bin", maxBytes = 10)
        assertTrue(result is ZipOpenResult.Refused)
        assertTrue((result as ZipOpenResult.Refused).reason.contains("larger than 10 bytes"))
    }

    @Test
    fun openRefusesAHostileRequestedNameBeforeReading() {
        val bytes = zip("ok.txt" to ByteArray(1))
        val result = ZipBrowse.open(ByteArrayInputStream(bytes), "../ok.txt", maxBytes = 100)
        assertTrue(result is ZipOpenResult.Refused)
    }

    @Test
    fun openReportsAMissingEntry() {
        val bytes = zip("ok.txt" to ByteArray(1))
        val result = ZipBrowse.open(ByteArrayInputStream(bytes), "missing.txt", maxBytes = 100)
        assertTrue((result as ZipOpenResult.Refused).reason.contains("missing.txt"))
    }

    @Test
    fun openDoesNotTreatAFolderAsAFile() {
        val bytes = zip("docs/" to ByteArray(0), "docs/a.txt" to ByteArray(1))
        val result = ZipBrowse.open(ByteArrayInputStream(bytes), "docs", maxBytes = 100)
        assertTrue(result is ZipOpenResult.Refused)
    }
}
