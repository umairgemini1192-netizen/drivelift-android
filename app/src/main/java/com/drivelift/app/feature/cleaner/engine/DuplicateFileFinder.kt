package com.drivelift.app.feature.cleaner.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Walks target directories safely and groups byte-identical files into [DuplicateGroup]s.
 */
class DuplicateFileFinder(
    private val config: CleanerScanConfig = CleanerScanConfig(),
    private val hashEngine: FastFileHashEngine = FastFileHashEngine(config.partialHashBytes),
) {
    /**
     * Scans [root] recursively for duplicate files.
     */
    suspend fun findDuplicates(root: File): List<DuplicateGroup> =
        findDuplicates(listOf(root))

    /**
     * Scans all [roots] recursively for duplicate files, sorted by `reclaimableBytes` descending.
     */
    suspend fun findDuplicates(roots: List<File>): List<DuplicateGroup> {
        val seenPaths = HashSet<String>()
        val collectedEntries = mutableListOf<ScannedFileEntry>()

        for (root in roots) {
            currentCoroutineContext().ensureActive()
            if (!root.exists() || !root.isDirectory) continue
            if (!config.followSymlinks && CleanerPathUtils.isSymlink(root)) continue
            walkDirectory(root, root, seenPaths, collectedEntries)
        }

        val duplicateBuckets = hashEngine.findDuplicates(collectedEntries)
        return duplicateBuckets.entries
            .map { (sha256, files) ->
                val sortedFiles = files.sortedBy { it.path }
                val keepEntry = sortedFiles.minWith(
                    compareBy<ScannedFileEntry>(
                        { it.lastModifiedMs },
                        { it.path.length },
                        { it.path },
                    ),
                )
                DuplicateGroup(
                    sha256 = sha256,
                    sizeBytes = sortedFiles.first().sizeBytes,
                    files = sortedFiles,
                    recommendedKeepPath = keepEntry.path,
                )
            }
            .sortedWith(
                compareByDescending<DuplicateGroup> { it.reclaimableBytes }
                    .thenBy { it.sha256 },
            )
    }

    private suspend fun walkDirectory(
        dir: File,
        root: File,
        seenPaths: MutableSet<String>,
        out: MutableList<ScannedFileEntry>,
    ) {
        currentCoroutineContext().ensureActive()
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return

        for (child in children) {
            currentCoroutineContext().ensureActive()
            if (!config.followSymlinks && CleanerPathUtils.isSymlink(child)) {
                continue
            }
            if (child.isDirectory) {
                if (CleanerPathUtils.isExcludedDirectory(child, root, config.excludedDirectoryNames)) {
                    continue
                }
                walkDirectory(child, root, seenPaths, out)
            } else if (child.isFile) {
                val normalized = CleanerPathUtils.normalizePath(child).toString()
                if (!seenPaths.add(normalized)) continue
                val name = child.name
                out.add(
                    ScannedFileEntry(
                        path = normalized,
                        name = name,
                        sizeBytes = child.length(),
                        lastModifiedMs = child.lastModified(),
                        extension = child.extension.lowercase(),
                    ),
                )
            }
        }
    }
}
