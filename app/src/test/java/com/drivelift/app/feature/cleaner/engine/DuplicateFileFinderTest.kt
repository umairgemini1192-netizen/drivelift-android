package com.drivelift.app.feature.cleaner.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DuplicateFileFinderTest {
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("cleaner-dup-test").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun findsExactDuplicatesAcrossNestedFoldersAndSelectsOldestKeepPath() = runBlocking {
        val dirA = File(tempRoot, "DCIM/Camera").apply { mkdirs() }
        val dirB = File(tempRoot, "Downloads/Backup/Nested").apply { mkdirs() }

        val content = ByteArray(16 * 1024) { (it % 251).toByte() }
        val olderFile = File(dirB, "photo_copy.jpg").apply {
            writeBytes(content)
            setLastModified(1_000_000L)
        }
        val newerFile = File(dirA, "photo.jpg").apply {
            writeBytes(content)
            setLastModified(2_000_000L)
        }

        val finder = DuplicateFileFinder()
        val groups = finder.findDuplicates(tempRoot)

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(content.size.toLong(), group.sizeBytes)
        assertEquals(content.size.toLong(), group.reclaimableBytes)
        assertEquals(2, group.files.size)
        assertEquals(olderFile.toPath().toAbsolutePath().normalize().toString(), group.recommendedKeepPath)
        assertTrue(group.files.any { it.path == newerFile.toPath().toAbsolutePath().normalize().toString() })
    }

    @Test
    fun stage2AndStage3DistinguishDifferentHeadTailAndDifferentMiddleBytes() = runBlocking {
        val partialBytes = 4096
        val totalSize = partialBytes * 3 // 12 KB: head (4 KB) + middle (4 KB) + tail (4 KB)

        val head = ByteArray(partialBytes) { 0x11 }
        val tail = ByteArray(partialBytes) { 0x22 }
        val middleA = ByteArray(partialBytes) { 0x33 }
        val middleB = ByteArray(partialBytes) { 0x44 }
        val diffTail = ByteArray(partialBytes) { 0x55 }

        // Pair 1: identical head + middleA + tail (true duplicates)
        File(tempRoot, "dup1.bin").writeBytes(head + middleA + tail)
        File(tempRoot, "dup2.bin").writeBytes(head + middleA + tail)

        // Stage 3 test: same head + tail, different middle -> NOT a duplicate
        File(tempRoot, "same_ends_diff_mid.bin").writeBytes(head + middleB + tail)

        // Stage 2 test: same size, different tail -> eliminated at Stage 2
        File(tempRoot, "diff_tail.bin").writeBytes(head + middleA + diffTail)

        val finder = DuplicateFileFinder(CleanerScanConfig(partialHashBytes = partialBytes))
        val groups = finder.findDuplicates(tempRoot)

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(totalSize.toLong(), group.sizeBytes)
        assertEquals(setOf("dup1.bin", "dup2.bin"), group.files.map { it.name }.toSet())
    }

    @Test
    fun skipsZeroByteFilesAndExcludedDirectories() = runBlocking {
        File(tempRoot, "empty1.txt").writeBytes(ByteArray(0))
        File(tempRoot, "empty2.txt").writeBytes(ByteArray(0))

        val gitDir = File(tempRoot, ".git/objects").apply { mkdirs() }
        val androidDataDir = File(tempRoot, "Android/data/com.example").apply { mkdirs() }
        val payload = "duplicate-payload".toByteArray()

        File(gitDir, "a.bin").writeBytes(payload)
        File(gitDir, "b.bin").writeBytes(payload)
        File(androidDataDir, "c.bin").writeBytes(payload)
        File(androidDataDir, "d.bin").writeBytes(payload)

        val finder = DuplicateFileFinder()
        val groups = finder.findDuplicates(tempRoot)

        assertTrue(groups.isEmpty())
    }

    @Test
    fun tieBreaksRecommendedKeepPathByShortestThenLexicographicalPath() = runBlocking {
        val sub = File(tempRoot, "sub").apply { mkdirs() }
        val payload = "same-bytes".toByteArray()
        val fixedTime = 5_000_000L

        val bFile = File(tempRoot, "b.dat").apply {
            writeBytes(payload)
            setLastModified(fixedTime)
        }
        val aFile = File(tempRoot, "a.dat").apply {
            writeBytes(payload)
            setLastModified(fixedTime)
        }
        File(sub, "0.dat").apply {
            writeBytes(payload)
            setLastModified(fixedTime)
        }

        val finder = DuplicateFileFinder()
        val groups = finder.findDuplicates(tempRoot)

        assertEquals(1, groups.size)
        assertEquals(
            aFile.toPath().toAbsolutePath().normalize().toString(),
            groups.single().recommendedKeepPath,
        )
        assertEquals(payload.size.toLong() * 2L, groups.single().reclaimableBytes)
        assertTrue(bFile.exists())
    }
}
