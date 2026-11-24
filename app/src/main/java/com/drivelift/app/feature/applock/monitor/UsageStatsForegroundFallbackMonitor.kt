package com.drivelift.app.feature.applock.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Snapshot of the most recent foreground window transition observed via UsageStatsManager.
 */
data class UsageForegroundEvent(
    val packageName: String,
    val className: String?,
    val timestampMs: Long,
)

/**
 * Abstraction for reading recent foreground transitions so fallback polling is unit-testable.
 */
fun interface UsageForegroundReader {
    fun latestForegroundEvent(sinceMs: Long, nowMs: Long): UsageForegroundEvent?
}

/**
 * Fallback foreground package monitor backed by [UsageStatsManager.queryEvents].
 *
 * Runs only when App Lock is enabled, Accessibility Service is not connected, and the user
 * has granted Usage Access (`OPSTR_GET_USAGE_STATS`). Automatically stops as soon as
 * Accessibility connects or App Lock is disabled.
 */
class UsageStatsForegroundFallbackMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val isAccessibilityConnected: () -> Boolean,
    private val hasUsageAccessProvider: () -> Boolean = { hasUsageStatsPermission(context) },
    private val eventReader: UsageForegroundReader = AndroidUsageForegroundReader(context),
    private val onForegroundEvent: (packageName: String, className: String?) -> Unit,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private var pollJob: Job? = null
    private var lastQueryMs: Long = 0L
    @Volatile
    private var screenPaused: Boolean = false

    val isRunning: Boolean
        get() = pollJob?.isActive == true

    fun hasUsageAccess(): Boolean = hasUsageAccessProvider()

    /** Immediately pauses fallback UsageStats polling while the screen is off. */
    fun onScreenOff() {
        screenPaused = true
        stop()
    }

    /** Cleanly resumes fallback UsageStats polling on screen wake if still needed. */
    fun onScreenOn(appLockEnabled: Boolean) {
        screenPaused = false
        sync(appLockEnabled)
    }

    /**
     * Starts or stops fallback polling based on current App Lock enabled state, Accessibility
     * connectivity, Usage Access grant, and interactive screen state.
     */
    fun sync(appLockEnabled: Boolean) {
        val shouldPoll = appLockEnabled && !screenPaused && !isAccessibilityConnected() && hasUsageAccessProvider()
        if (!shouldPoll) {
            stop()
            return
        }
        if (isRunning) return

        lastQueryMs = (clockMs() - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
        pollJob = scope.launch {
            while (isActive) {
                if (screenPaused || isAccessibilityConnected() || !hasUsageAccessProvider()) {
                    break
                }
                pollOnce()
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Performs a single UsageEvents query window and forwards the newest foreground transition.
     */
    fun pollOnce(): UsageForegroundEvent? {
        if (!hasUsageAccessProvider() || isAccessibilityConnected()) return null
        val now = clockMs()
        val since = if (lastQueryMs in 1L..now) lastQueryMs else (now - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
        val latest = runCatching { eventReader.latestForegroundEvent(since, now) }.getOrNull()
        lastQueryMs = now
        if (latest != null && latest.packageName.isNotBlank()) {
            onForegroundEvent(latest.packageName, latest.className)
        }
        return latest
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 500L
        const val INITIAL_LOOKBACK_MS = 5_000L

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

private class AndroidUsageForegroundReader(
    private val context: Context,
) : UsageForegroundReader {
    override fun latestForegroundEvent(sinceMs: Long, nowMs: Long): UsageForegroundEvent? {
        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val events = usageStats.queryEvents(sinceMs, nowMs) ?: return null
        val scratch = UsageEvents.Event()
        var newest: UsageForegroundEvent? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(scratch)
            val isForeground = scratch.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                @Suppress("DEPRECATION")
                (scratch.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
            if (isForeground && !scratch.packageName.isNullOrBlank()) {
                if (newest == null || scratch.timeStamp >= newest.timestampMs) {
                    newest = UsageForegroundEvent(
                        packageName = scratch.packageName,
                        className = scratch.className,
                        timestampMs = scratch.timeStamp,
                    )
                }
            }
        }
        return newest
    }
}
