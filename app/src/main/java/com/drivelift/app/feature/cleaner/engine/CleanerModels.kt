package com.drivelift.app.feature.cleaner.engine

/**
 * Safety classification driving UI rail colour and default selection state.
 */
enum class CleanerSafetyTier {
    /** Pre-checked by default ("Advice: clean"). */
    SAFE_UNNEEDED,

    /** Unchecked by default ("Clean up carefully"). */
    REVIEW_CAREFULLY,
}

/**
 * Categories of reclaimable storage items surfaced by the Tier 1 engine.
 */
enum class CleanerCategory(
    val safetyTier: CleanerSafetyTier,
    val defaultSelected: Boolean,
) {
    HIDDEN_CACHE(CleanerSafetyTier.SAFE_UNNEEDED, true),
    VISIBLE_CACHE(CleanerSafetyTier.SAFE_UNNEEDED, true),
    THUMBNAILS(CleanerSafetyTier.SAFE_UNNEEDED, true),
    TEMP_AND_RESIDUAL(CleanerSafetyTier.SAFE_UNNEEDED, true),
    RECYCLE_BIN(CleanerSafetyTier.SAFE_UNNEEDED, true),
    EMPTY_FOLDERS(CleanerSafetyTier.SAFE_UNNEEDED, true),
    STALE_APK(CleanerSafetyTier.SAFE_UNNEEDED, true),
    COMPRESSED_ARCHIVE(CleanerSafetyTier.REVIEW_CAREFULLY, false),
    LARGE_FILE(CleanerSafetyTier.REVIEW_CAREFULLY, false),
    WHATSAPP_BLOAT(CleanerSafetyTier.REVIEW_CAREFULLY, false),
    DUPLICATE_FILES(CleanerSafetyTier.REVIEW_CAREFULLY, false),
    RARELY_USED_FILES(CleanerSafetyTier.REVIEW_CAREFULLY, false),
    RARELY_USED_APPS(CleanerSafetyTier.REVIEW_CAREFULLY, false),
}

/**
 * Lightweight metadata snapshot for a regular file discovered during traversal.
 */
data class ScannedFileEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val extension: String,
)

/**
 * Single actionable storage item presented inside a category accordion.
 */
data class CleanerItem(
    val id: String,
    val path: String,
    val name: String,
    val category: CleanerCategory,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val adviceLabel: String,
    val selectedByDefault: Boolean,
    val packageName: String? = null,
)

/**
 * Aggregated group of [CleanerItem]s sharing a single [CleanerCategory].
 */
data class CleanerCategoryGroup(
    val category: CleanerCategory,
    val items: List<CleanerItem>,
    val totalBytes: Long,
    val selectedBytes: Long,
    val selectedCount: Int,
)

/**
 * Group of byte-identical files sharing the same full SHA-256 digest.
 */
data class DuplicateGroup(
    val sha256: String,
    val sizeBytes: Long,
    val files: List<ScannedFileEntry>,
    val recommendedKeepPath: String,
) {
    val reclaimableBytes: Long
        get() = sizeBytes * (files.size - 1).coerceAtLeast(0)
}

/**
 * Three-segment storage breakdown and normalized severity score (`0f..1f`)
 * for the Phase 3 capacity bar and AGSL hero shader.
 */
data class StorageCapacityBreakdown(
    val totalDeviceBytes: Long,
    val freeDeviceBytes: Long,
    val safeUnneededBytes: Long,
    val hiddenCacheBytes: Long,
    val filesToReviewBytes: Long,
) {
    val totalReclaimableBytes: Long
        get() = safeUnneededBytes + hiddenCacheBytes + filesToReviewBytes

    val healthSeverity: Float
        get() {
            if (totalDeviceBytes <= 0L) {
                return if (totalReclaimableBytes > 0L) 1f else 0f
            }
            return (totalReclaimableBytes.toDouble() / totalDeviceBytes.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }
}

/**
 * Tunable thresholds and traversal rules for a storage scan.
 */
data class CleanerScanConfig(
    val staleApkAgeDays: Int = 7,
    val largeFileThresholdBytes: Long = 100L * 1024L * 1024L,
    val rarelyUsedFileAgeDays: Int = 90,
    val rarelyUsedAppAgeDays: Int = 30,
    val partialHashBytes: Int = 4096,
    val excludedDirectoryNames: Set<String> = setOf(".git", "Android/data", "Android/obb"),
    val followSymlinks: Boolean = false,
)

/**
 * Full output of a Tier 1 storage and junk scan.
 */
data class CleanerScanReport(
    val scannedFileCount: Int,
    val groups: List<CleanerCategoryGroup>,
    val duplicateGroups: List<DuplicateGroup>,
    val capacityBreakdown: StorageCapacityBreakdown,
    val skippedPaths: List<String>,
    val durationMs: Long,
)

/**
 * Result of executing (or dry-running) a storage cleanup batch.
 */
data class CleanupExecutionResult(
    val dryRun: Boolean,
    val deletedPaths: List<String>,
    val skippedPaths: List<String>,
    val failedPaths: Map<String, String>,
    val reclaimedBytes: Long,
)
