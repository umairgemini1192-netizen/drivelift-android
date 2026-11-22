package com.drivelift.app.feature.hibernation.engine

import kotlin.math.roundToInt

/**
 * Configuration for [BackgroundDrainerScorer].
 */
data class DrainerScorerConfig(
    val selfPackageName: String = BackgroundDrainerScorer.DEFAULT_SELF_PACKAGE,
    val minBackgroundTimeMs: Long = 60_000L,
    val extraDialerPackages: Set<String> = emptySet(),
    val extraClockPackages: Set<String> = emptySet(),
)

/**
 * Ranks non-system applications by background usage, standby bucket, and recency.
 *
 * DriveLift itself, dialer/telecom apps, clock/alarm apps, system apps, and any
 * user-whitelisted packages are unconditionally excluded.
 */
class BackgroundDrainerScorer(
    private val config: DrainerScorerConfig = DrainerScorerConfig(),
) {

    /**
     * Returns the [ProtectionReason] if [snapshot] is immune from hibernation, or null if eligible.
     */
    fun protectionReasonFor(
        snapshot: AppUsageSnapshot,
        userRules: List<ProtectedAppRule> = emptyList(),
    ): ProtectionReason? {
        val pkg = snapshot.packageName.trim()
        if (pkg.isEmpty()) return ProtectionReason.SYSTEM_APP
        if (pkg == DEFAULT_SELF_PACKAGE || pkg == config.selfPackageName.trim()) {
            return ProtectionReason.SELF_APP
        }
        if (pkg in DEFAULT_DIALER_PACKAGES || pkg in config.extraDialerPackages) {
            return ProtectionReason.DIALER
        }
        if (pkg in DEFAULT_CLOCK_PACKAGES || pkg in config.extraClockPackages) {
            return ProtectionReason.CLOCK_OR_ALARM
        }
        if (snapshot.isSystemApp) {
            return ProtectionReason.SYSTEM_APP
        }
        val matchedRule = userRules.firstOrNull { it.packageName.trim() == pkg }
        if (matchedRule != null) {
            return matchedRule.reason
        }
        return null
    }

    /**
     * Scores and ranks eligible background drainers in descending priority order.
     */
    fun rankCandidates(
        snapshots: List<AppUsageSnapshot>,
        userRules: List<ProtectedAppRule> = emptyList(),
        nowMs: Long,
    ): List<AppDrainCandidate> {
        return snapshots
            .asSequence()
            .filter { it.packageName.isNotBlank() }
            .filter { protectionReasonFor(it, userRules) == null }
            .filter {
                it.backgroundTimeMs >= config.minBackgroundTimeMs || it.isRunningInBackground
            }
            .map { snapshot -> scoreSnapshot(snapshot, nowMs) }
            .sortedWith(
                compareByDescending<AppDrainCandidate> { it.drainScore }
                    .thenByDescending { it.backgroundTimeMs }
                    .thenByDescending { it.lastTimeUsedMs }
                    .thenBy { it.packageName },
            )
            .toList()
    }

    private fun scoreSnapshot(snapshot: AppUsageSnapshot, nowMs: Long): AppDrainCandidate {
        val bgMinutes = (snapshot.backgroundTimeMs.coerceAtLeast(0L) / 60_000.0)
        // Saturating curve: 30m -> 25 pts, 60m -> 33 pts, 120m -> 40 pts, approaches 50 pts.
        val bgScore = if (bgMinutes <= 0.0) {
            0.0
        } else {
            50.0 * (bgMinutes / (bgMinutes + 30.0))
        }

        val bucketScore = when (snapshot.standbyBucket) {
            StandbyBucket.ACTIVE -> 25.0
            StandbyBucket.WORKING_SET -> 20.0
            StandbyBucket.FREQUENT -> 15.0
            StandbyBucket.UNKNOWN -> 10.0
            StandbyBucket.RARE -> 8.0
            StandbyBucket.RESTRICTED -> 2.0
            StandbyBucket.NEVER -> 0.0
        }

        val recencyScore = computeRecencyScore(snapshot.lastTimeUsedMs, nowMs)
        val activeBonus = if (snapshot.isRunningInBackground) 5.0 else 0.0

        val totalScore = (bgScore + bucketScore + recencyScore + activeBonus)
            .roundToInt()
            .coerceIn(0, 100)

        val reasons = buildList {
            if (snapshot.isRunningInBackground) {
                add("Currently active in background")
            }
            if (snapshot.backgroundTimeMs > 0L) {
                val mins = (snapshot.backgroundTimeMs / 60_000L).coerceAtLeast(1L)
                add("${mins}m background activity")
            }
            when (snapshot.standbyBucket) {
                StandbyBucket.ACTIVE,
                StandbyBucket.WORKING_SET,
                -> add("Unrestricted standby bucket (${snapshot.standbyBucket.name})")
                else -> Unit
            }
        }

        return AppDrainCandidate(
            packageName = snapshot.packageName.trim(),
            appLabel = snapshot.appLabel.ifBlank { snapshot.packageName.trim() },
            drainScore = totalScore,
            backgroundTimeMs = snapshot.backgroundTimeMs.coerceAtLeast(0L),
            lastTimeUsedMs = snapshot.lastTimeUsedMs,
            standbyBucket = snapshot.standbyBucket,
            isRunningInBackground = snapshot.isRunningInBackground,
            reasons = reasons,
        )
    }

    private fun computeRecencyScore(lastTimeUsedMs: Long, nowMs: Long): Double {
        if (lastTimeUsedMs <= 0L) return 0.0
        val ageMs = (nowMs - lastTimeUsedMs).coerceAtLeast(0L)
        return when {
            ageMs <= ONE_HOUR_MS -> 20.0
            ageMs <= SIX_HOURS_MS -> 15.0
            ageMs <= TWENTY_FOUR_HOURS_MS -> 10.0
            ageMs <= SEVENTY_TWO_HOURS_MS -> 5.0
            else -> 2.0
        }
    }

    companion object {
        const val DEFAULT_SELF_PACKAGE = "com.drivelift.app"

        private const val ONE_HOUR_MS = 60L * 60_000L
        private const val SIX_HOURS_MS = 6L * ONE_HOUR_MS
        private const val TWENTY_FOUR_HOURS_MS = 24L * ONE_HOUR_MS
        private const val SEVENTY_TWO_HOURS_MS = 72L * ONE_HOUR_MS

        val DEFAULT_DIALER_PACKAGES: Set<String> = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.incallui",
            "com.android.phone",
            "com.android.server.telecom",
        )

        val DEFAULT_CLOCK_PACKAGES: Set<String> = setOf(
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock",
            "com.coloros.alarmclock",
            "com.miui.clock",
        )

        val ALWAYS_PROTECTED_PACKAGES: Set<String> =
            setOf(DEFAULT_SELF_PACKAGE) + DEFAULT_DIALER_PACKAGES + DEFAULT_CLOCK_PACKAGES
    }
}
