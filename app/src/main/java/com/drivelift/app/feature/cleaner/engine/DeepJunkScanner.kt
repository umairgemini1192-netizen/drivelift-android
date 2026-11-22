package com.drivelift.app.feature.cleaner.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Result of a filesystem junk scan prior to app-stat and duplicate-group merging.
 */
data class DeepJunkScanResult(
    val scannedFileCount: Int,
    val groups: List<CleanerCategoryGroup>,
    val skippedPaths: List<String>,
    val durationMs: Long,
)

/**
 * Pure-JVM storage junk and bloat scanner with an injectable clock.
 */
class DeepJunkScanner(
    private val config: CleanerScanConfig = CleanerScanConfig(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Scans [root] for junk, residual files, empty folders, and reviewable bloat.
     */
    suspend fun scan(root: File): DeepJunkScanResult = scan(listOf(root))

    /**
     * Scans all [roots] for junk, residual files, empty folders, and reviewable bloat.
     */
    suspend fun scan(roots: List<File>): DeepJunkScanResult {
        val startMs = nowMs()
        val seenFilePaths = HashSet<String>()
        val seenDirPaths = HashSet<String>()
        val itemsByCategory = mutableMapOf<CleanerCategory, MutableList<CleanerItem>>()
        val skippedPaths = mutableListOf<String>()
        var scannedFileCount = 0

        for (root in roots) {
            currentCoroutineContext().ensureActive()
            if (!root.exists() || !root.isDirectory) {
                skippedPaths.add(root.path)
                continue
            }
            if (!config.followSymlinks && CleanerPathUtils.isSymlink(root)) {
                skippedPaths.add(root.path)
                continue
            }
            traverseDirectory(
                dir = root,
                root = root,
                isRoot = true,
                seenFilePaths = seenFilePaths,
                seenDirPaths = seenDirPaths,
                itemsByCategory = itemsByCategory,
                skippedPaths = skippedPaths,
                onFileVisited = { scannedFileCount++ },
            )
        }

        val groups = CleanerPathUtils.sortGroups(
            itemsByCategory.entries
                .filter { it.value.isNotEmpty() }
                .map { (category, items) ->
                    CleanerPathUtils.buildCategoryGroup(category, items)
                },
        )

        val durationMs = (nowMs() - startMs).coerceAtLeast(0L)
        return DeepJunkScanResult(
            scannedFileCount = scannedFileCount,
            groups = groups,
            skippedPaths = skippedPaths,
            durationMs = durationMs,
        )
    }

    /**
     * Bottom-up traversal. Returns `true` if [dir] has no non-empty descendants (i.e. is empty
     * or contains only empty subdirectories).
     */
    private suspend fun traverseDirectory(
        dir: File,
        root: File,
        isRoot: Boolean,
        seenFilePaths: MutableSet<String>,
        seenDirPaths: MutableSet<String>,
        itemsByCategory: MutableMap<CleanerCategory, MutableList<CleanerItem>>,
        skippedPaths: MutableList<String>,
        onFileVisited: () -> Unit,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        }
        if (children == null) {
            skippedPaths.add(dir.path)
            return false
        }

        var hasNonEmptyContent = false
        for (child in children) {
            currentCoroutineContext().ensureActive()
            if (!config.followSymlinks && CleanerPathUtils.isSymlink(child)) {
                skippedPaths.add(child.path)
                hasNonEmptyContent = true
                continue
            }

            if (child.isDirectory) {
                if (CleanerPathUtils.isExcludedDirectory(child, root, config.excludedDirectoryNames)) {
                    skippedPaths.add(child.path)
                    hasNonEmptyContent = true
                    continue
                }
                val childIsEmpty = traverseDirectory(
                    dir = child,
                    root = root,
                    isRoot = false,
                    seenFilePaths = seenFilePaths,
                    seenDirPaths = seenDirPaths,
                    itemsByCategory = itemsByCategory,
                    skippedPaths = skippedPaths,
                    onFileVisited = onFileVisited,
                )
                if (!childIsEmpty) {
                    hasNonEmptyContent = true
                }
            } else if (child.isFile) {
                hasNonEmptyContent = true
                val normalized = CleanerPathUtils.normalizePath(child).toString()
                if (!seenFilePaths.add(normalized)) continue
                onFileVisited()
                classifyFile(child, normalized)?.let { item ->
                    itemsByCategory.getOrPut(item.category) { mutableListOf() }.add(item)
                }
            }
        }

        val dirIsEmpty = !hasNonEmptyContent
        if (dirIsEmpty && !isRoot) {
            val normalizedDir = CleanerPathUtils.normalizePath(dir).toString()
            if (seenDirPaths.add(normalizedDir)) {
                val category = CleanerCategory.EMPTY_FOLDERS
                itemsByCategory.getOrPut(category) { mutableListOf() }.add(
                    CleanerItem(
                        id = "${category.name}:$normalizedDir",
                        path = normalizedDir,
                        name = dir.name,
                        category = category,
                        sizeBytes = 0L,
                        lastModifiedMs = dir.lastModified(),
                        adviceLabel = CleanerPathUtils.adviceLabelFor(category.safetyTier),
                        selectedByDefault = category.defaultSelected,
                    ),
                )
            }
        }
        return dirIsEmpty
    }

    private fun classifyFile(file: File, normalizedPath: String): CleanerItem? {
        val name = file.name
        val lowerName = name.lowercase()
        val ext = file.extension.lowercase()
        val unixPath = CleanerPathUtils.toUnixSeparators(normalizedPath)
        val sizeBytes = file.length()
        val lastModifiedMs = file.lastModified()
        val ageMs = (nowMs() - lastModifiedMs).coerceAtLeast(0L)

        val category = when {
            isInsideRecycleBin(unixPath) -> CleanerCategory.RECYCLE_BIN
            isInsideThumbnails(unixPath) -> CleanerCategory.THUMBNAILS
            isTempOrResidual(lowerName, ext) -> CleanerCategory.TEMP_AND_RESIDUAL
            isStaleApk(ext, ageMs) -> CleanerCategory.STALE_APK
            isWhatsAppBloat(unixPath, lowerName) -> CleanerCategory.WHATSAPP_BLOAT
            ext in ARCHIVE_EXTENSIONS -> CleanerCategory.COMPRESSED_ARCHIVE
            sizeBytes >= config.largeFileThresholdBytes -> CleanerCategory.LARGE_FILE
            isRarelyUsedFile(ageMs) -> CleanerCategory.RARELY_USED_FILES
            else -> null
        } ?: return null

        return CleanerItem(
            id = "${category.name}:$normalizedPath",
            path = normalizedPath,
            name = name,
            category = category,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            adviceLabel = CleanerPathUtils.adviceLabelFor(category.safetyTier),
            selectedByDefault = category.defaultSelected,
        )
    }

    private fun isInsideThumbnails(unixPath: String): Boolean =
        unixPath.split('/').dropLast(1).any { it == ".thumbnails" }

    private fun isInsideRecycleBin(unixPath: String): Boolean {
        val parentSegments = unixPath.split('/').dropLast(1)
        if (parentSegments.any { it in RECYCLE_BIN_DIR_NAMES }) {
            return true
        }
        val lowerPath = unixPath.lowercase()
        return lowerPath.contains("/whatsapp/.trash/") ||
            lowerPath.contains("/android/media/com.whatsapp/whatsapp/.trash/")
    }

    private fun isTempOrResidual(lowerName: String, ext: String): Boolean =
        lowerName in RESIDUAL_EXACT_NAMES ||
            lowerName.endsWith(".enc.tmp") ||
            ext in TEMP_EXTENSIONS

    private fun isStaleApk(ext: String, ageMs: Long): Boolean {
        if (ext != "apk") return false
        val thresholdMs = config.staleApkAgeDays.coerceAtLeast(0).toLong() *
            CleanerPathUtils.MILLIS_PER_DAY
        return ageMs >= thresholdMs
    }

    private fun isWhatsAppBloat(unixPath: String, lowerName: String): Boolean {
        if (lowerName == ".nomedia") return false
        val lowerPath = unixPath.lowercase()
        val isWhatsAppMedia = lowerPath.contains("/android/media/com.whatsapp/whatsapp/media/") ||
            lowerPath.contains("/whatsapp/media/")
        if (!isWhatsAppMedia) return false

        val segments = lowerPath.split('/').dropLast(1)
        return segments.any { it in WHATSAPP_BLOAT_SUBDIRS } || isWhatsAppMedia
    }

    private fun isRarelyUsedFile(ageMs: Long): Boolean {
        val thresholdMs = config.rarelyUsedFileAgeDays.coerceAtLeast(0).toLong() *
            CleanerPathUtils.MILLIS_PER_DAY
        return ageMs >= thresholdMs
    }

    private companion object {
        val TEMP_EXTENSIONS = setOf("tmp", "temp", "bak", "part", "crdownload", "log")
        val RESIDUAL_EXACT_NAMES = setOf("thumbs.db", ".ds_store")
        val RECYCLE_BIN_DIR_NAMES = setOf(".trash-storage", ".Trash", ".recycle")
        val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz")
        val WHATSAPP_BLOAT_SUBDIRS = setOf(
            "sent",
            ".statuses",
            "whatsapp video",
            "whatsapp voice notes",
        )
    }
}
