package com.drivelift.app.feature.cockpit.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

data class PinnedAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val iconBitmap: Bitmap? = null,
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
    private val iconBitmapCache = ConcurrentHashMap<String, Bitmap>()

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
            val bitmap = cachedBitmap(pkg, icon)
            seen[pkg] = PinnedAppInfo(packageName = pkg, label = label, icon = icon, iconBitmap = bitmap)
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun resolvePinnedApps(pinnedPackages: List<String>): List<PinnedAppInfo> {
        if (pinnedPackages.isEmpty()) return emptyList()
        testAppsOverride?.let { override ->
            val byPkg = override.associateBy { it.packageName }
            return pinnedPackages.mapNotNull { pkg -> byPkg[pkg] ?: resolveSinglePackage(pkg) }
        }
        // Resolve only the requested pinned packages directly instead of scanning and rendering
        // bitmaps for every installed app on the device during widget broadcasts.
        return pinnedPackages.mapNotNull { pkg -> resolveSinglePackage(pkg) }
    }

    private fun resolveSinglePackage(packageName: String): PinnedAppInfo? {
        testAppsOverride?.firstOrNull { it.packageName == packageName }?.let { return it }
        val pm = context.packageManager
        val launchIntent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()
        val resolvedActivity = launchIntent?.let { intent ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.resolveActivity(intent, 0)
                }
            }.getOrNull()
        }
        if (resolvedActivity != null) {
            val label = runCatching { resolvedActivity.loadLabel(pm).toString() }
                .getOrDefault(packageName).ifBlank { packageName }
            val icon = runCatching { resolvedActivity.loadIcon(pm) }.getOrNull()
            val bitmap = cachedBitmap(packageName, icon)
            return PinnedAppInfo(packageName = packageName, label = label, icon = icon, iconBitmap = bitmap)
        }
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return null
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName).ifBlank { packageName }
        val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
        val bitmap = cachedBitmap(packageName, icon)
        return PinnedAppInfo(packageName = packageName, label = label, icon = icon, iconBitmap = bitmap)
    }

    private fun cachedBitmap(packageName: String, drawable: Drawable?): Bitmap? {
        iconBitmapCache[packageName]?.takeIf { !it.isRecycled }?.let { return it }
        val bmp = drawableToBitmap(drawable) ?: return null
        iconBitmapCache[packageName] = bmp
        return bmp
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val src = drawable.bitmap
            val softwareSrc = if (Build.VERSION.SDK_INT >= 26 && src.config == Bitmap.Config.HARDWARE) {
                runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            } else {
                src
            }
            if (softwareSrc != null) {
                return runCatching {
                    Bitmap.createScaledBitmap(softwareSrc, ICON_BITMAP_PX, ICON_BITMAP_PX, true)
                }.getOrNull()
            }
        }
        return runCatching {
            val bitmap = Bitmap.createBitmap(ICON_BITMAP_PX, ICON_BITMAP_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
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

    companion object {
        private const val ICON_BITMAP_PX = 64
    }
}
