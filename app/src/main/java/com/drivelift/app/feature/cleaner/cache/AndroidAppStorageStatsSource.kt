package com.drivelift.app.feature.cleaner.cache

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import com.drivelift.app.feature.cleaner.engine.AppStorageSnapshot
import com.drivelift.app.feature.cleaner.engine.AppStorageStatsSource
import com.drivelift.app.feature.hibernation.engine.AppUsageSnapshot
import com.drivelift.app.feature.hibernation.engine.StandbyBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device total and free storage capacity in bytes.
 */
data class DeviceStorageSummary(
    val totalBytes: Long,
    val freeBytes: Long,
)

/**
 * Android implementation of [AppStorageStatsSource] backed by [StorageStatsManager] and
 * [UsageStatsManager]. Also exposes [AppUsageSnapshot] records for the Phase 2 hibernation
 * drainer scorer.
 *
 * Gracefully degrades to an empty list when Usage Access is not granted or when Android
 * restricts per-package queries.
 */
class AndroidAppStorageStatsSource(
    private val context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val usageWindowDays: Int = DEFAULT_USAGE_WINDOW_DAYS,
) : AppStorageStatsSource {

    /**
     * Returns true when `PACKAGE_USAGE_STATS` is granted to DriveLift.
     */
    fun hasUsageStatsAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Builds an Android Settings intent targeting the Usage Access grant screen.
     */
    fun createUsageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Reads total and available bytes on the internal data partition.
     */
    fun queryDeviceStorageSummary(): DeviceStorageSummary = runCatching {
        val statFs = StatFs(Environment.getDataDirectory().path)
        DeviceStorageSummary(
            totalBytes = statFs.totalBytes.coerceAtLeast(0L),
            freeBytes = statFs.availableBytes.coerceAtLeast(0L),
        )
    }.getOrDefault(DeviceStorageSummary(totalBytes = 0L, freeBytes = 0L))

    override suspend fun queryInstalledAppStats(): List<AppStorageSnapshot> = withContext(Dispatchers.IO) {
        if (!hasUsageStatsAccess()) return@withContext emptyList()

        val pm = context.packageManager
        val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            ?: return@withContext emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

        val lastUsedByPackage = queryLastUsedMap(usageStatsManager)
        val installedApps = queryInstalledApplications(pm)
        val userHandle = Process.myUserHandle()

        buildList {
            for (appInfo in installedApps) {
                val pkg = appInfo.packageName ?: continue
                val stats = runCatching {
                    storageStatsManager.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT,
                        pkg,
                        userHandle,
                    )
                }.getOrNull() ?: continue

                val externalCache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { stats.externalCacheBytes }.getOrDefault(0L)
                } else {
                    0L
                }
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(pkg)
                    .ifBlank { pkg }
                val isSystem = isSystemApplication(appInfo)

                add(
                    AppStorageSnapshot(
                        packageName = pkg,
                        appLabel = label,
                        appBytes = stats.appBytes.coerceAtLeast(0L),
                        dataBytes = stats.dataBytes.coerceAtLeast(0L),
                        cacheBytes = stats.cacheBytes.coerceAtLeast(0L),
                        externalCacheBytes = externalCache.coerceAtLeast(0L),
                        lastTimeUsedMs = lastUsedByPackage[pkg] ?: 0L,
                        isSystemApp = isSystem,
                    ),
                )
            }
        }
    }

    /**
     * Queries installed application background usage and standby buckets for [BackgroundDrainerScorer].
     */
    suspend fun queryAppUsageSnapshots(): List<AppUsageSnapshot> = withContext(Dispatchers.IO) {
        if (!hasUsageStatsAccess()) return@withContext emptyList()

        val pm = context.packageManager
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext emptyList()

        val endMs = nowMs()
        val startMs = (endMs - usageWindowDays.coerceAtLeast(1).toLong() * MILLIS_PER_DAY).coerceAtLeast(0L)
        val statsList = runCatching {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startMs, endMs)
        }.getOrNull().orEmpty()

        val aggregatedByPackage = linkedMapOf<String, UsageAggregate>()
        for (entry in statsList) {
            val pkg = entry.packageName ?: continue
            val existing = aggregatedByPackage[pkg]
            val fgMs = entry.totalTimeInForeground.coerceAtLeast(0L)
            val bgMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { entry.totalTimeForegroundServiceUsed }.getOrDefault(0L).coerceAtLeast(0L)
            } else {
                0L
            }
            val lastUsed = entry.lastTimeUsed.coerceAtLeast(0L)
            if (existing == null) {
                aggregatedByPackage[pkg] = UsageAggregate(
                    foregroundMs = fgMs,
                    backgroundMs = bgMs,
                    lastTimeUsedMs = lastUsed,
                )
            } else {
                aggregatedByPackage[pkg] = UsageAggregate(
                    foregroundMs = existing.foregroundMs + fgMs,
                    backgroundMs = existing.backgroundMs + bgMs,
                    lastTimeUsedMs = maxOf(existing.lastTimeUsedMs, lastUsed),
                )
            }
        }

        val installedApps = queryInstalledApplications(pm)
        buildList {
            for (appInfo in installedApps) {
                val pkg = appInfo.packageName ?: continue
                val agg = aggregatedByPackage[pkg] ?: continue
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(pkg)
                    .ifBlank { pkg }
                val bucket = if (pkg == context.packageName) {
                    mapStandbyBucket(runCatching { usageStatsManager.appStandbyBucket }.getOrDefault(-1))
                } else {
                    StandbyBucket.UNKNOWN
                }
                val recentlyActive = agg.lastTimeUsedMs > 0L && (endMs - agg.lastTimeUsedMs) <= RECENT_ACTIVE_MS
                val effectiveBgMs = if (agg.backgroundMs > 0L) {
                    agg.backgroundMs
                } else if (recentlyActive && agg.foregroundMs > 0L) {
                    // Conservative estimate when OEM omits foreground service duration.
                    (agg.foregroundMs / 4L).coerceAtLeast(0L)
                } else {
                    0L
                }

                add(
                    AppUsageSnapshot(
                        packageName = pkg,
                        appLabel = label,
                        isSystemApp = isSystemApplication(appInfo),
                        backgroundTimeMs = effectiveBgMs,
                        foregroundTimeMs = agg.foregroundMs,
                        lastTimeUsedMs = agg.lastTimeUsedMs,
                        standbyBucket = bucket,
                        isRunningInBackground = recentlyActive && effectiveBgMs > 0L,
                    ),
                )
            }
        }
    }

    private fun queryLastUsedMap(usageStatsManager: UsageStatsManager?): Map<String, Long> {
        if (usageStatsManager == null) return emptyMap()
        val endMs = nowMs()
        val startMs = (endMs - usageWindowDays.coerceAtLeast(1).toLong() * MILLIS_PER_DAY).coerceAtLeast(0L)
        val raw = runCatching {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startMs, endMs)
        }.getOrNull().orEmpty()

        val result = HashMap<String, Long>(raw.size)
        for (entry in raw) {
            val pkg = entry.packageName ?: continue
            val prev = result[pkg] ?: 0L
            if (entry.lastTimeUsed > prev) {
                result[pkg] = entry.lastTimeUsed
            }
        }
        return result
    }

    private fun queryInstalledApplications(pm: PackageManager): List<ApplicationInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
    }.getOrDefault(emptyList())

    private fun isSystemApplication(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    private data class UsageAggregate(
        val foregroundMs: Long,
        val backgroundMs: Long,
        val lastTimeUsedMs: Long,
    )

    companion object {
        const val DEFAULT_USAGE_WINDOW_DAYS = 90
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val RECENT_ACTIVE_MS = 30L * 60_000L

        /**
         * Maps Android [UsageStatsManager] standby bucket integers to [StandbyBucket].
         */
        fun mapStandbyBucket(rawBucket: Int): StandbyBucket = when (rawBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.ACTIVE
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WORKING_SET
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.FREQUENT
            UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.RARE
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> StandbyBucket.RESTRICTED
            50 -> StandbyBucket.NEVER
            else -> StandbyBucket.UNKNOWN
        }
    }
}
