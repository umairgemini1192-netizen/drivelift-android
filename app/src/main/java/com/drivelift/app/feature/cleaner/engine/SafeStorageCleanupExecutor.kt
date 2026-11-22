package com.drivelift.app.feature.cleaner.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Safe, dry-run-first storage cleanup executor with protected-root and all-copies guards.
 */
class SafeStorageCleanupExecutor {
    /**
     * Executes or dry-runs deletion for [targetPaths].
     *
     * Safety invariants:
     * 1. Never deletes any path inside, equal to, or an ancestor of [protectedRoots].
     * 2. All-Copies Guard: if [targetPaths] attempts to delete every copy in a [DuplicateGroup],
     *    that group's `recommendedKeepPath` is preserved and recorded in `skippedPaths`.
     * 3. When [dryRun] is `true`, calculates exact `reclaimedBytes` without modifying disk.
     * 4. When [dryRun] is `false`, deletes files first and directories deepest-path-first.
     */
    fun execute(
        targetPaths: List<String>,
        duplicateGroups: List<DuplicateGroup> = emptyList(),
        protectedRoots: Set<String> = emptySet(),
        dryRun: Boolean = true,
    ): CleanupExecutionResult {
        val normalizedProtected = protectedRoots
            .map { File(it).toPath().toAbsolutePath().normalize() }
            .toSet()

        val uniqueNormalizedTargets = linkedMapOf<Path, String>()
        for (rawPath in targetPaths) {
            if (rawPath.isBlank()) continue
            val norm = File(rawPath).toPath().toAbsolutePath().normalize()
            uniqueNormalizedTargets.putIfAbsent(norm, rawPath)
        }

        val guardedKeepPaths = collectAllCopiesKeepPaths(
            targetSet = uniqueNormalizedTargets.keys,
            duplicateGroups = duplicateGroups,
        )

        val eligibleFiles = mutableListOf<Path>()
        val eligibleDirs = mutableListOf<Path>()
        val skippedPaths = mutableListOf<String>()

        for ((normPath, _) in uniqueNormalizedTargets) {
            if (normPath in guardedKeepPaths || isProtected(normPath, normalizedProtected)) {
                skippedPaths.add(normPath.toString())
                continue
            }
            if (!Files.exists(normPath, LinkOption.NOFOLLOW_LINKS)) {
                skippedPaths.add(normPath.toString())
                continue
            }
            if (Files.isDirectory(normPath, LinkOption.NOFOLLOW_LINKS)) {
                eligibleDirs.add(normPath)
            } else {
                eligibleFiles.add(normPath)
            }
        }

        if (dryRun) {
            val dryDeleted = mutableListOf<String>()
            var reclaimed = 0L
            for (filePath in eligibleFiles) {
                val size = safeFileSize(filePath)
                reclaimed += size
                dryDeleted.add(filePath.toString())
            }
            for (dirPath in eligibleDirs) {
                dryDeleted.add(dirPath.toString())
            }
            return CleanupExecutionResult(
                dryRun = true,
                deletedPaths = dryDeleted,
                skippedPaths = skippedPaths,
                failedPaths = emptyMap(),
                reclaimedBytes = reclaimed,
            )
        }

        val deletedPaths = mutableListOf<String>()
        val failedPaths = linkedMapOf<String, String>()
        var reclaimedBytes = 0L

        for (filePath in eligibleFiles) {
            val sizeBeforeDelete = safeFileSize(filePath)
            try {
                Files.delete(filePath)
                deletedPaths.add(filePath.toString())
                reclaimedBytes += sizeBeforeDelete
            } catch (e: Exception) {
                failedPaths[filePath.toString()] = e.message ?: e::class.java.simpleName
            }
        }

        // Delete directories deepest-path-first so nested empty folders succeed bottom-up.
        val sortedDirs = eligibleDirs.sortedWith(
            compareByDescending<Path> { it.nameCount }
                .thenByDescending { it.toString().length },
        )
        for (dirPath in sortedDirs) {
            try {
                Files.delete(dirPath)
                deletedPaths.add(dirPath.toString())
            } catch (e: Exception) {
                failedPaths[dirPath.toString()] = e.message ?: e::class.java.simpleName
            }
        }

        return CleanupExecutionResult(
            dryRun = false,
            deletedPaths = deletedPaths,
            skippedPaths = skippedPaths,
            failedPaths = failedPaths,
            reclaimedBytes = reclaimedBytes,
        )
    }

    private fun collectAllCopiesKeepPaths(
        targetSet: Set<Path>,
        duplicateGroups: List<DuplicateGroup>,
    ): Set<Path> {
        val guarded = HashSet<Path>()
        for (group in duplicateGroups) {
            if (group.files.isEmpty()) continue
            val groupPaths = group.files.map { File(it.path).toPath().toAbsolutePath().normalize() }
            if (groupPaths.all { it in targetSet }) {
                val keepNorm = File(group.recommendedKeepPath).toPath().toAbsolutePath().normalize()
                guarded.add(keepNorm)
            }
        }
        return guarded
    }

    private fun isProtected(candidate: Path, protectedRoots: Set<Path>): Boolean {
        if (protectedRoots.isEmpty()) return false
        return protectedRoots.any { root ->
            candidate == root || candidate.startsWith(root) || root.startsWith(candidate)
        }
    }

    private fun safeFileSize(path: Path): Long =
        try {
            Files.size(path)
        } catch (_: Exception) {
            0L
        }
}
