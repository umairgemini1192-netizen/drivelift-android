package com.drivelift.app.feature.cleaner.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCacheAndUsageAggregatorTest {

    @Test
    fun aggregatesCachesAndRarelyUsedAppsExcludingSystemApps() = runBlocking {
        val nowMs = 1_700_000_000_000L
        val dayMs = 86_400_000L

        val fakeSource = object : AppStorageStatsSource {
            override suspend fun queryInstalledAppStats(): List<AppStorageSnapshot> = listOf(
                AppStorageSnapshot(
                    packageName = "com.example.social",
                    appLabel = "SocialApp",
                    appBytes = 100L,
                    dataBytes = 200L,
                    cacheBytes = 500L,
                    externalCacheBytes = 150L,
                    lastTimeUsedMs = nowMs - 45 * dayMs,
                    isSystemApp = false,
                ),
                AppStorageSnapshot(
                    packageName = "com.android.systemui",
                    appLabel = "System UI",
                    appBytes = 400L,
                    dataBytes = 100L,
                    cacheBytes = 50L,
                    externalCacheBytes = 0L,
                    lastTimeUsedMs = nowMs - 90 * dayMs,
                    isSystemApp = true,
                ),
                AppStorageSnapshot(
                    packageName = "com.example.recent",
                    appLabel = "RecentApp",
                    appBytes = 250L,
                    dataBytes = 50L,
                    cacheBytes = 0L,
                    externalCacheBytes = 200L,
                    lastTimeUsedMs = nowMs - 5 * dayMs,
                    isSystemApp = false,
                ),
            )
        }

        val aggregator = AppCacheAndUsageAggregator(
            statsSource = fakeSource,
            config = CleanerScanConfig(rarelyUsedAppAgeDays = 30),
            nowMs = { nowMs },
        )

        val groups = aggregator.aggregate()
        val byCategory = groups.associateBy { it.category }

        val hidden = byCategory[CleanerCategory.HIDDEN_CACHE]!!
        assertEquals(550L, hidden.totalBytes)
        assertEquals(2, hidden.selectedCount)
        assertEquals("com.example.social", hidden.items.first().packageName)

        val visible = byCategory[CleanerCategory.VISIBLE_CACHE]!!
        assertEquals(350L, visible.totalBytes)
        assertEquals("com.example.recent", visible.items.first().packageName)

        val rarelyUsed = byCategory[CleanerCategory.RARELY_USED_APPS]!!
        assertEquals(1, rarelyUsed.items.size)
        assertEquals("com.example.social", rarelyUsed.items.single().packageName)
        assertEquals(300L, rarelyUsed.totalBytes)
        assertFalse(rarelyUsed.items.single().selectedByDefault)
        assertTrue(rarelyUsed.items.none { it.packageName == "com.android.systemui" })
    }

    @Test
    fun buildsThreeSegmentCapacityBreakdownAndClampsHealthSeverity() {
        val aggregator = AppCacheAndUsageAggregator(
            statsSource = object : AppStorageStatsSource {
                override suspend fun queryInstalledAppStats() = emptyList<AppStorageSnapshot>()
            },
        )

        val groups = listOf(
            CleanerCategoryGroup(
                category = CleanerCategory.HIDDEN_CACHE,
                items = emptyList(),
                totalBytes = 200L,
                selectedBytes = 200L,
                selectedCount = 1,
            ),
            CleanerCategoryGroup(
                category = CleanerCategory.TEMP_AND_RESIDUAL,
                items = emptyList(),
                totalBytes = 100L,
                selectedBytes = 100L,
                selectedCount = 1,
            ),
            CleanerCategoryGroup(
                category = CleanerCategory.LARGE_FILE,
                items = emptyList(),
                totalBytes = 300L,
                selectedBytes = 0L,
                selectedCount = 0,
            ),
        )
        val duplicateGroups = listOf(
            DuplicateGroup(
                sha256 = "abc",
                sizeBytes = 150L,
                files = listOf(
                    ScannedFileEntry("/a", "a", 150L, 1L, "bin"),
                    ScannedFileEntry("/b", "b", 150L, 2L, "bin"),
                ),
                recommendedKeepPath = "/a",
            ),
        )

        val normal = aggregator.buildCapacityBreakdown(
            totalDeviceBytes = 1000L,
            freeDeviceBytes = 250L,
            groups = groups,
            duplicateGroups = duplicateGroups,
        )
        assertEquals(100L, normal.safeUnneededBytes)
        assertEquals(200L, normal.hiddenCacheBytes)
        assertEquals(450L, normal.filesToReviewBytes)
        assertEquals(750L, normal.totalReclaimableBytes)
        assertEquals(0.75f, normal.healthSeverity, 0.0001f)

        val clean = aggregator.buildCapacityBreakdown(
            totalDeviceBytes = 1000L,
            freeDeviceBytes = 900L,
            groups = emptyList(),
            duplicateGroups = emptyList(),
        )
        assertEquals(0f, clean.healthSeverity, 0.0001f)

        val clamped = aggregator.buildCapacityBreakdown(
            totalDeviceBytes = 500L,
            freeDeviceBytes = 0L,
            groups = groups,
            duplicateGroups = duplicateGroups,
        )
        assertEquals(1f, clamped.healthSeverity, 0.0001f)
    }
}
