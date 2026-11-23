package com.drivelift.app.feature.cleaner.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal object CleanerPathUtils {
    const val ADVICE_SAFE_CLEAN = "Advice: clean"
    const val ADVICE_REVIEW_CAREFULLY = "Clean up carefully"
    const val MILLIS_PER_DAY = 86_400_000L

    fun adviceLabelFor(tier: CleanerSafetyTier): String = when (tier) {
        CleanerSafetyTier.SAFE_UNNEEDED -> ADVICE_SAFE_CLEAN
        CleanerSafetyTier.REVIEW_CAREFULLY -> ADVICE_REVIEW_CAREFULLY
    }

    fun normalizePath(file: File): Path = file.toPath().toAbsolutePath().normalize()

    fun normalizePathString(path: String): String =
        File(path).toPath().toAbsolutePath().normalize().toString()

    fun toUnixSeparators(path: String): String = path.replace('\\', '/')

    fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    /**
     * Returns true if [dir] matches any entry in [excludedDirectoryNames] either by
     * single folder name (e.g. `.git`) or by relative path suffix (e.g. `Android/data`).
     */
    fun isExcludedDirectory(
        dir: File,
        root: File,
        excludedDirectoryNames: Set<String>,
    ): Boolean {
        if (excludedDirectoryNames.isEmpty()) return false
        val dirName = dir.name
        val rootNorm = normalizePath(root)
        val dirNorm = normalizePath(dir)
        val relativeUnix = if (dirNorm.startsWith(rootNorm) && dirNorm != rootNorm) {
            toUnixSeparators(rootNorm.relativize(dirNorm).toString())
        } else {
            toUnixSeparators(dirName)
        }
        val fullUnix = toUnixSeparators(dirNorm.toString())

        return excludedDirectoryNames.any { rawPattern ->
            val pattern = toUnixSeparators(rawPattern.trim().trim('/'))
            if (pattern.isEmpty()) return@any false
            if ('/' !in pattern) {
                dirName.equals(pattern, ignoreCase = false)
            } else {
                relativeUnix == pattern ||
                    relativeUnix.endsWith("/$pattern") ||
                    fullUnix.endsWith("/$pattern")
            }
        }
    }

    fun buildCategoryGroup(
        category: CleanerCategory,
        items: List<CleanerItem>,
    ): CleanerCategoryGroup {
        val sorted = items.sortedWith(
            compareByDescending<CleanerItem> { it.sizeBytes }
                .thenBy { it.path },
        )
        val totalBytes = sorted.sumOf { it.sizeBytes }
        val selectedItems = sorted.filter { it.selectedByDefault }
        return CleanerCategoryGroup(
            category = category,
            items = sorted,
            totalBytes = totalBytes,
            selectedBytes = selectedItems.sumOf { it.sizeBytes },
            selectedCount = selectedItems.size,
        )
    }

    fun sortGroups(groups: List<CleanerCategoryGroup>): List<CleanerCategoryGroup> =
        groups.sortedWith(
            compareBy<CleanerCategoryGroup> { it.category == CleanerCategory.EMPTY_FOLDERS }
                .thenByDescending { it.totalBytes }
                .thenBy { it.category.ordinal },
        )
}
