package com.drivelift.app.feature.cleaner.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafeStorageCleanupExecutorTest {
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("cleaner-exec-test").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun dryRunLeavesDiskUntouchedAndReportsExactReclaimedBytes() {
        val junkFile = File(tempRoot, "cache.tmp").apply { writeBytes(ByteArray(256)) }
        val emptyDir = File(tempRoot, "EmptyFolder").apply { mkdirs() }

        val executor = SafeStorageCleanupExecutor()
        val result = executor.execute(
            targetPaths = listOf(junkFile.absolutePath, emptyDir.absolutePath),
            dryRun = true,
        )

        assertTrue(result.dryRun)
        assertEquals(256L, result.reclaimedBytes)
        assertEquals(2, result.deletedPaths.size)
        assertTrue(result.failedPaths.isEmpty())
        assertTrue(junkFile.exists())
        assertTrue(emptyDir.exists())
    }

    @Test
    fun realRunDeletesFilesAndNestedEmptyFoldersBottomUp() {
        val parentDir = File(tempRoot, "ParentEmpty").apply { mkdirs() }
        val childDir = File(parentDir, "ChildEmpty").apply { mkdirs() }
        val junkFile = File(tempRoot, "residual.bak").apply { writeBytes(ByteArray(512)) }

        val executor = SafeStorageCleanupExecutor()
        val result = executor.execute(
            targetPaths = listOf(
                parentDir.absolutePath,
                childDir.absolutePath,
                junkFile.absolutePath,
            ),
            dryRun = false,
        )

        assertFalse(result.dryRun)
        assertEquals(512L, result.reclaimedBytes)
        assertTrue(result.failedPaths.isEmpty())
        assertFalse(junkFile.exists())
        assertFalse(childDir.exists())
        assertFalse(parentDir.exists())
    }

    @Test
    fun allCopiesGuardPreservesRecommendedKeepPath() {
        val keepFile = File(tempRoot, "keep.jpg").apply { writeBytes(ByteArray(128)) }
        val dupFile = File(tempRoot, "dup.jpg").apply { writeBytes(ByteArray(128)) }

        val keepNorm = keepFile.toPath().toAbsolutePath().normalize().toString()
        val dupNorm = dupFile.toPath().toAbsolutePath().normalize().toString()

        val dupGroup = DuplicateGroup(
            sha256 = "deadbeef",
            sizeBytes = 128L,
            files = listOf(
                ScannedFileEntry(keepNorm, "keep.jpg", 128L, 1_000L, "jpg"),
                ScannedFileEntry(dupNorm, "dup.jpg", 128L, 2_000L, "jpg"),
            ),
            recommendedKeepPath = keepNorm,
        )

        val executor = SafeStorageCleanupExecutor()
        val result = executor.execute(
            targetPaths = listOf(keepNorm, dupNorm),
            duplicateGroups = listOf(dupGroup),
            dryRun = false,
        )

        assertTrue(keepFile.exists())
        assertFalse(dupFile.exists())
        assertEquals(128L, result.reclaimedBytes)
        assertEquals(listOf(dupNorm), result.deletedPaths)
        assertEquals(listOf(keepNorm), result.skippedPaths)
    }

    @Test
    fun skipsProtectedRootsAndMissingPathsWithoutThrowing() {
        val protectedDir = File(tempRoot, "Protected").apply { mkdirs() }
        val protectedFile = File(protectedDir, "important.txt").apply { writeBytes(ByteArray(64)) }
        val missingFile = File(tempRoot, "does_not_exist.tmp")

        val executor = SafeStorageCleanupExecutor()
        val result = executor.execute(
            targetPaths = listOf(
                protectedDir.absolutePath,
                protectedFile.absolutePath,
                missingFile.absolutePath,
            ),
            protectedRoots = setOf(protectedDir.absolutePath),
            dryRun = false,
        )

        assertTrue(protectedDir.exists())
        assertTrue(protectedFile.exists())
        assertEquals(0L, result.reclaimedBytes)
        assertTrue(result.deletedPaths.isEmpty())
        assertTrue(result.failedPaths.isEmpty())
        assertEquals(3, result.skippedPaths.size)
    }
}
