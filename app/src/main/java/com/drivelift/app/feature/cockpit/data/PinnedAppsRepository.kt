package com.drivelift.app.feature.cockpit.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class PinnedAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
)

/**
 * Queries installed launcher apps via PackageManager (<queries> ACTION_MAIN + CATEGORY_LAUNCHER)
 * so the Cockpit dock and Master Widget can list, resolve icons for, and launch any installed app.
 */
class PinnedAppsRepository(
    private val context: Context,
) {
    @Volatile
    private var testAppsOverride: List<PinnedAppInfo>? = null

    fun listLaunchableApps(excludeSelf: Boolean = true): List<PinnedAppInfo> {
        testAppsOverride?.let { override ->
            return if (excludeSelf) override.filterNot { it.packageName == context.packageName } else override
        }
        val pm = context.packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(queryIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(queryIntent, 0)
            }
        }.getOrDefault(emptyList())

        val seen = LinkedHashMap<String, PinnedAppInfo>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (excludeSelf && pkg == context.packageName) continue
            if (pkg in seen) continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg).ifBlank { pkg }
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            seen[pkg] = PinnedAppInfo(packageName = pkg, label = label, icon = icon)
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun resolvePinnedApps(pinnedPackages: List<String>): List<PinnedAppInfo> {
        if (pinnedPackages.isEmpty()) return emptyList()
        val byPkg = listLaunchableApps(excludeSelf = false).associateBy { it.packageName }
        return pinnedPackages.mapNotNull { pkg ->
            byPkg[pkg] ?: resolveSinglePackage(pkg)
        }
    }

    private fun resolveSinglePackage(packageName: String): PinnedAppInfo? {
        testAppsOverride?.firstOrNull { it.packageName == packageName }?.let { return it }
        val pm = context.packageManager
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return null
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(packageName)
        val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
        return PinnedAppInfo(packageName = packageName, label = label, icon = icon)
    }

    fun buildLaunchIntent(packageName: String): Intent? {
        return context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun launchApp(packageName: String): Boolean {
        val intent = buildLaunchIntent(packageName) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun seedForTest(apps: List<PinnedAppInfo>?) {
        testAppsOverride = apps
    }
}
