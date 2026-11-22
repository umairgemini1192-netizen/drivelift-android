package com.drivelift.app.feature.cleaner.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeepJunkScannerTest {
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("cleaner-junk-test").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun classifiesAllJunkAndReviewCategoriesWithoutDoubleCounting() = runBlocking {
        val nowMs = 1_700_000_000_000L
        val dayMs = 86_400_000L

        // 1. Thumbnails
        val thumbDir = File(tempRoot, "DCIM/.thumbnails").apply { mkdirs() }
        File(thumbDir, "thumb_01.jpg").writeBytes(ByteArray(120))

        // 2. Temp & residual
        File(tempRoot, "download.tmp").writeBytes(ByteArray(80))
        File(tempRoot, "backup.enc.tmp").writeBytes(ByteArray(90))
        File(tempRoot, "Thumbs.db").writeBytes(ByteArray(40))

        // 3. Recycle bins (.trash-storage and WhatsApp/.trash)
        val trashDir = File(tempRoot, ".trash-storage").apply { mkdirs() }
        File(trashDir, "deleted_old.zip").writeBytes(ByteArray(200))
        val waTrash = File(tempRoot, "Android/media/com.whatsapp/WhatsApp/.trash").apply { mkdirs() }
        File(waTrash, "trashed_vid.mp4").writeBytes(ByteArray(150))

        // 4. Nested empty folders (leaf + parent containing only empty subdirs)
        val emptyParent = File(tempRoot, "EmptyParent").apply { mkdirs() }
        File(emptyParent, "EmptyChild").mkdirs()

        // 5. Stale vs fresh APKs
        File(tempRoot, "stale_installer.apk").apply {
            writeBytes(ByteArray(300))
            setLastModified(nowMs - 10 * dayMs)
        }
        File(tempRoot, "fresh_installer.apk").apply {
            writeBytes(ByteArray(300))
            setLastModified(nowMs - 2 * dayMs)
        }

        // 6. Compressed archive (REVIEW_CAREFULLY, unchecked)
        File(tempRoot, "archive.zip").apply {
            writeBytes(ByteArray(500))
            setLastModified(nowMs - 100 * dayMs)
        }

        // 7. Large file (using 400-byte threshold for fast test)
        File(tempRoot, "movie_clip.mp4").apply {
            writeBytes(ByteArray(600))
            setLastModified(nowMs - 1 * dayMs)
        }

        // 8. WhatsApp bloat (excluding .nomedia)
        val waMedia = File(tempRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent").apply {
            mkdirs()
        }
        File(waMedia, "VID-001.mp4").writeBytes(ByteArray(250))
        File(waMedia, ".nomedia").writeBytes(ByteArray(0))

        // 9. Rarely used regular file
        File(tempRoot, "old_tax_return.pdf").apply {
            writeBytes(ByteArray(110))
            setLastModified(nowMs - 95 * dayMs)
        }

        // Recent normal file -> should NOT be flagged
        File(tempRoot, "recent_note.txt").apply {
            writeBytes(ByteArray(50))
            setLastModified(nowMs - 5 * dayMs)
        }

        val config = CleanerScanConfig(
            staleApkAgeDays = 7,
            largeFileThresholdBytes = 400L,
            rarelyUsedFileAgeDays = 90,
        )
        val scanner = DeepJunkScanner(config = config, nowMs = { nowMs })
        val report = scanner.scan(tempRoot)

        val byCategory = report.groups.associateBy { it.category }

        assertEquals(120L, byCategory[CleanerCategory.THUMBNAILS]?.totalBytes)
        assertEquals(210L, byCategory[CleanerCategory.TEMP_AND_RESIDUAL]?.totalBytes)
        assertEquals(350L, byCategory[CleanerCategory.RECYCLE_BIN]?.totalBytes)
        assertEquals(300L, byCategory[CleanerCategory.STALE_APK]?.totalBytes)
        assertEquals(500L, byCategory[CleanerCategory.COMPRESSED_ARCHIVE]?.totalBytes)
        assertEquals(600L, byCategory[CleanerCategory.LARGE_FILE]?.totalBytes)
        assertEquals(250L, byCategory[CleanerCategory.WHATSAPP_BLOAT]?.totalBytes)
        assertEquals(110L, byCategory[CleanerCategory.RARELY_USED_FILES]?.totalBytes)

        // Empty folders: both EmptyParent and EmptyChild, never tempRoot
        val emptyItems = byCategory[CleanerCategory.EMPTY_FOLDERS]?.items.orEmpty()
        assertEquals(setOf("EmptyParent", "EmptyChild"), emptyItems.map { it.name }.toSet())
        assertTrue(emptyItems.all { it.selectedByDefault && it.adviceLabel == "Advice: clean" })

        // Verify review-carefully defaults
        val archiveItem = byCategory[CleanerCategory.COMPRESSED_ARCHIVE]!!.items.single()
        assertFalse(archiveItem.selectedByDefault)
        assertEquals("Clean up carefully", archiveItem.adviceLabel)

        // Verify .nomedia and fresh_installer.apk and recent_note.txt are excluded
        val allPaths = report.groups.flatMap { it.items }.map { it.name }
        assertFalse(".nomedia" in allPaths)
        assertFalse("fresh_installer.apk" in allPaths)
        assertFalse("recent_note.txt" in allPaths)

        // Verify no double counting: archive.zip (old + large + zip) only counted once in COMPRESSED_ARCHIVE
        val totalAcrossGroups = report.groups.sumOf { it.totalBytes }
        assertEquals(120L + 210L + 350L + 300L + 500L + 600L + 250L + 110L, totalAcrossGroups)

        // Empty folders group is sorted last
        assertEquals(CleanerCategory.EMPTY_FOLDERS, report.groups.last().category)
    }
}
