package com.drivelift.app.feature.applock.engine

/**
 * Foreground window event observed by a platform monitor (AccessibilityService or UsageStats).
 */
data class ForegroundEvent(
    val packageName: String,
    val className: String? = null,
    val timestampMs: Long,
)

/**
 * Reason a foreground package event was suppressed before reaching [AppLockPolicyEngine].
 */
enum class IgnoreReason {
    BLANK_PACKAGE,
    SYSTEM_UI_OR_OVERLAY,
    LAUNCHER,
    SELF_LOCK_ACTIVITY,
    DUPLICATE_PACKAGE,
    DEBOUNCED,
}

/**
 * Result of filtering a raw foreground event.
 */
sealed class ForegroundFilterResult {
    data class Accepted(
        val packageName: String,
        val timestampMs: Long,
    ) : ForegroundFilterResult()

    data class Ignored(
        val packageName: String,
        val reason: IgnoreReason,
    ) : ForegroundFilterResult()
}

/**
 * Configuration for [ForegroundPackageChangeFilter].
 */
data class MonitorFilterConfig(
    val selfPackageName: String = DEFAULT_SELF_PACKAGE,
    val lockActivityClassName: String = DEFAULT_LOCK_ACTIVITY_CLASS,
    val ignoredPackages: Set<String> = DEFAULT_IGNORED_PACKAGES,
    val launcherPackages: Set<String> = DEFAULT_LAUNCHER_PACKAGES,
    val debounceWindowMs: Long = DEFAULT_DEBOUNCE_WINDOW_MS,
) {
    companion object {
        const val DEFAULT_SELF_PACKAGE: String = "com.drivelift.app"
        const val DEFAULT_LOCK_ACTIVITY_CLASS: String =
            "com.drivelift.app.feature.applock.ui.AppLockActivity"
        const val DEFAULT_DEBOUNCE_WINDOW_MS: Long = 250L

        val DEFAULT_IGNORED_PACKAGES: Set<String> = setOf(
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        val DEFAULT_LAUNCHER_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.oneplus.launcher",
        )
    }
}

/**
 * Contract for observing debounced, loop-safe foreground package transitions.
 */
interface ForegroundPackageMonitorContract {
    fun onForegroundEvent(event: ForegroundEvent): ForegroundFilterResult

    fun reset()
}

/**
 * Debounced foreground package change filter that suppresses SystemUI, launcher surfaces,
 * DriveLift's own lock screen activity, duplicate package emissions, and rapid transient
 * window switches to prevent lock loops.
 */
class ForegroundPackageChangeFilter(
    private val config: MonitorFilterConfig = MonitorFilterConfig(),
) : ForegroundPackageMonitorContract {

    private var lastAcceptedPackage: String? = null
    private var lastAcceptedTimestampMs: Long = Long.MIN_VALUE

    override fun onForegroundEvent(event: ForegroundEvent): ForegroundFilterResult {
        val pkg = event.packageName.trim()
        if (pkg.isEmpty()) {
            return ForegroundFilterResult.Ignored(pkg, IgnoreReason.BLANK_PACKAGE)
        }

        if (pkg in config.ignoredPackages) {
            return ForegroundFilterResult.Ignored(pkg, IgnoreReason.SYSTEM_UI_OR_OVERLAY)
        }

        if (pkg in config.launcherPackages) {
            return ForegroundFilterResult.Ignored(pkg, IgnoreReason.LAUNCHER)
        }

        if (pkg == config.selfPackageName && isLockActivity(event.className)) {
            return ForegroundFilterResult.Ignored(pkg, IgnoreReason.SELF_LOCK_ACTIVITY)
        }

        if (pkg == lastAcceptedPackage) {
            return ForegroundFilterResult.Ignored(pkg, IgnoreReason.DUPLICATE_PACKAGE)
        }

        if (lastAcceptedTimestampMs != Long.MIN_VALUE) {
            val deltaMs = event.timestampMs - lastAcceptedTimestampMs
            if (deltaMs in 0L until config.debounceWindowMs) {
                return ForegroundFilterResult.Ignored(pkg, IgnoreReason.DEBOUNCED)
            }
        }

        lastAcceptedPackage = pkg
        lastAcceptedTimestampMs = event.timestampMs
        return ForegroundFilterResult.Accepted(pkg, event.timestampMs)
    }

    override fun reset() {
        lastAcceptedPackage = null
        lastAcceptedTimestampMs = Long.MIN_VALUE
    }

    private fun isLockActivity(className: String?): Boolean {
        if (className.isNullOrBlank()) {
            return false
        }
        return className == config.lockActivityClassName ||
            className.endsWith(".AppLockActivity") ||
            className.endsWith(".LockScreenActivity")
    }
}
