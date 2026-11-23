package com.drivelift.app.feature.cleaner.engine

/**
 * Snapshot of an installed application's storage footprint and last usage timestamp.
 */
data class AppStorageSnapshot(
    val packageName: String,
    val appLabel: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val externalCacheBytes: Long,
    val lastTimeUsedMs: Long,
    val isSystemApp: Boolean,
)

/**
 * Injectable abstraction over Android's StorageStatsManager + UsageStatsManager
 * so the aggregator remains pure JVM and unit-testable without Robolectric.
 */
interface AppStorageStatsSource {
    suspend fun queryInstalledAppStats(): List<AppStorageSnapshot>
}

/**
 * Aggregates app cache and usage stats into cleaner category groups and builds
 * the three-segment [StorageCapacityBreakdown] for the UI.
 */
class AppCacheAndUsageAggregator(
    private val statsSource: AppStorageStatsSource,
    private val config: CleanerScanConfig = CleanerScanConfig(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Queries [statsSource] and emits non-empty groups for `HIDDEN_CACHE`,
     * `VISIBLE_CACHE`, and `RARELY_USED_APPS`.
     */
    suspend fun aggregate(): List<CleanerCategoryGroup> {
        val snapshots = statsSource.queryInstalledAppStats()
        if (snapshots.isEmpty()) return emptyList()

        val currentMs = nowMs()
        val rarelyUsedThresholdMs = config.rarelyUsedAppAgeDays.coerceAtLeast(0).toLong() *
            CleanerPathUtils.MILLIS_PER_DAY

        val hiddenCacheItems = mutableListOf<CleanerItem>()
        val visibleCacheItems = mutableListOf<CleanerItem>()
        val rarelyUsedAppItems = mutableListOf<CleanerItem>()

        for (app in snapshots) {
            if (app.cacheBytes > 0L) {
                hiddenCacheItems.add(
                    appItem(
                        app = app,
                        category = CleanerCategory.HIDDEN_CACHE,
                        path = "app-cache://${app.packageName}/internal",
                        sizeBytes = app.cacheBytes,
                    ),
                )
            }
            if (app.externalCacheBytes > 0L) {
                visibleCacheItems.add(
                    appItem(
                        app = app,
                        category = CleanerCategory.VISIBLE_CACHE,
                        path = "app-cache://${app.packageName}/external",
                        sizeBytes = app.externalCacheBytes,
                    ),
                )
            }
            val ageMs = (currentMs - app.lastTimeUsedMs).coerceAtLeast(0L)
            if (!app.isSystemApp && app.lastTimeUsedMs > 0L && ageMs >= rarelyUsedThresholdMs) {
                rarelyUsedAppItems.add(
                    appItem(
                        app = app,
                        category = CleanerCategory.RARELY_USED_APPS,
                        path = "app-package://${app.packageName}",
                        sizeBytes = (app.appBytes + app.dataBytes).coerceAtLeast(0L),
                    ),
                )
            }
        }

        val groups = buildList {
            if (hiddenCacheItems.isNotEmpty()) {
                add(CleanerPathUtils.buildCategoryGroup(CleanerCategory.HIDDEN_CACHE, hiddenCacheItems))
            }
            if (visibleCacheItems.isNotEmpty()) {
                add(CleanerPathUtils.buildCategoryGroup(CleanerCategory.VISIBLE_CACHE, visibleCacheItems))
            }
            if (rarelyUsedAppItems.isNotEmpty()) {
                add(CleanerPathUtils.buildCategoryGroup(CleanerCategory.RARELY_USED_APPS, rarelyUsedAppItems))
            }
        }
        return CleanerPathUtils.sortGroups(groups)
    }

    /**
     * Computes the three-segment capacity bar breakdown (Safe Unneeded, Hidden Caches,
     * Files to Review) and normalized health severity.
     */
    fun buildCapacityBreakdown(
        totalDeviceBytes: Long,
        freeDeviceBytes: Long,
        groups: List<CleanerCategoryGroup>,
        duplicateGroups: List<DuplicateGroup> = emptyList(),
    ): StorageCapacityBreakdown {
        var safeUnneededBytes = 0L
        var hiddenCacheBytes = 0L
        var filesToReviewBytes = 0L
        var hasDuplicateGroupCategory = false

        for (group in groups) {
            when {
                group.category == CleanerCategory.HIDDEN_CACHE -> {
                    hiddenCacheBytes += group.totalBytes
                }
                group.category.safetyTier == CleanerSafetyTier.SAFE_UNNEEDED -> {
                    safeUnneededBytes += group.totalBytes
                }
                else -> {
                    if (group.category == CleanerCategory.DUPLICATE_FILES) {
                        hasDuplicateGroupCategory = true
                    }
                    filesToReviewBytes += group.totalBytes
                }
            }
        }

        if (!hasDuplicateGroupCategory && duplicateGroups.isNotEmpty()) {
            filesToReviewBytes += duplicateGroups.sumOf { it.reclaimableBytes }
        }

        return StorageCapacityBreakdown(
            totalDeviceBytes = totalDeviceBytes,
            freeDeviceBytes = freeDeviceBytes,
            safeUnneededBytes = safeUnneededBytes,
            hiddenCacheBytes = hiddenCacheBytes,
            filesToReviewBytes = filesToReviewBytes,
        )
    }

    private fun appItem(
        app: AppStorageSnapshot,
        category: CleanerCategory,
        path: String,
        sizeBytes: Long,
    ): CleanerItem = CleanerItem(
        id = "${category.name}:${app.packageName}",
        path = path,
        name = app.appLabel,
        category = category,
        sizeBytes = sizeBytes,
        lastModifiedMs = app.lastTimeUsedMs,
        adviceLabel = CleanerPathUtils.adviceLabelFor(category.safetyTier),
        selectedByDefault = category.defaultSelected,
        packageName = app.packageName,
    )
}
