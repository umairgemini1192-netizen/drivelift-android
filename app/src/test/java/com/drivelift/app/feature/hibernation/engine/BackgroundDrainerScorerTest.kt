package com.drivelift.app.feature.hibernation.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDrainerScorerTest {

    private val nowMs = 1_700_000_000_000L
    private val minuteMs = 60_000L
    private val hourMs = 60L * minuteMs

    @Test
    fun excludesDriveLiftDialerClockSystemAppsAndUserWhitelist() {
        val scorer = BackgroundDrainerScorer()
        val userRules = listOf(
            ProtectedAppRule(
                packageName = "com.example.messaging",
                reason = ProtectionReason.USER_WHITELIST,
                label = "Keep notifications alive",
            ),
        )

        val snapshots = listOf(
            snapshot("com.drivelift.app", "DriveLift", bgMinutes = 120),
            snapshot("com.google.android.dialer", "Phone", bgMinutes = 90),
            snapshot("com.android.dialer", "Dialer", bgMinutes = 90),
            snapshot("com.google.android.deskclock", "Clock", bgMinutes = 90),
            snapshot("com.sec.android.app.clockpackage", "Samsung Clock", bgMinutes = 90),
            snapshot("com.android.systemui", "System UI", bgMinutes = 150, isSystemApp = true),
            snapshot("com.example.messaging", "Messenger", bgMinutes = 180),
            snapshot("com.example.heavy", "HeavyApp", bgMinutes = 60),
        )

        val ranked = scorer.rankCandidates(snapshots, userRules, nowMs)

        assertEquals(listOf("com.example.heavy"), ranked.map { it.packageName })
        assertEquals(
            ProtectionReason.SELF_APP,
            scorer.protectionReasonFor(snapshots[0], userRules),
        )
        assertEquals(
            ProtectionReason.DIALER,
            scorer.protectionReasonFor(snapshots[1], userRules),
        )
        assertEquals(
            ProtectionReason.CLOCK_OR_ALARM,
            scorer.protectionReasonFor(snapshots[3], userRules),
        )
        assertEquals(
            ProtectionReason.SYSTEM_APP,
            scorer.protectionReasonFor(snapshots[5], userRules),
        )
        assertEquals(
            ProtectionReason.USER_WHITELIST,
            scorer.protectionReasonFor(snapshots[6], userRules),
        )
        assertNull(scorer.protectionReasonFor(snapshots[7], userRules))
    }

    @Test
    fun ranksByBackgroundUsageStandbyBucketAndRecency() {
        val scorer = BackgroundDrainerScorer()

        val heavyActiveRecent = snapshot(
            packageName = "com.example.heavy_active",
            label = "Heavy Active",
            bgMinutes = 120,
            lastUsedAgoMs = 15 * minuteMs,
            bucket = StandbyBucket.ACTIVE,
            isRunning = true,
        )
        val mediumWorkingSet = snapshot(
            packageName = "com.example.medium_working",
            label = "Medium Working",
            bgMinutes = 30,
            lastUsedAgoMs = 2 * hourMs,
            bucket = StandbyBucket.WORKING_SET,
            isRunning = false,
        )
        val lightRestrictedOld = snapshot(
            packageName = "com.example.light_restricted",
            label = "Light Restricted",
            bgMinutes = 5,
            lastUsedAgoMs = 96 * hourMs,
            bucket = StandbyBucket.RESTRICTED,
            isRunning = false,
        )

        val ranked = scorer.rankCandidates(
            snapshots = listOf(lightRestrictedOld, mediumWorkingSet, heavyActiveRecent),
            nowMs = nowMs,
        )

        assertEquals(
            listOf(
                "com.example.heavy_active",
                "com.example.medium_working",
                "com.example.light_restricted",
            ),
            ranked.map { it.packageName },
        )
        assertTrue(ranked[0].drainScore > ranked[1].drainScore)
        assertTrue(ranked[1].drainScore > ranked[2].drainScore)
        assertTrue(ranked[0].reasons.any { it.contains("Currently active in background") })
    }

    @Test
    fun filtersIdleLowUsageAppsUnlessActivelyRunningInBackground() {
        val scorer = BackgroundDrainerScorer(
            DrainerScorerConfig(minBackgroundTimeMs = 5 * minuteMs),
        )

        val idleLow = snapshot(
            packageName = "com.example.idle",
            label = "Idle",
            bgMinutes = 1,
            isRunning = false,
        )
        val activeLow = snapshot(
            packageName = "com.example.active_low",
            label = "Active Low",
            bgMinutes = 0,
            isRunning = true,
        )

        val ranked = scorer.rankCandidates(listOf(idleLow, activeLow), nowMs = nowMs)

        assertFalse(ranked.any { it.packageName == "com.example.idle" })
        assertEquals(listOf("com.example.active_low"), ranked.map { it.packageName })
    }

    private fun snapshot(
        packageName: String,
        label: String,
        bgMinutes: Long,
        lastUsedAgoMs: Long = 30 * minuteMs,
        bucket: StandbyBucket = StandbyBucket.FREQUENT,
        isSystemApp: Boolean = false,
        isRunning: Boolean = false,
    ): AppUsageSnapshot {
        return AppUsageSnapshot(
            packageName = packageName,
            appLabel = label,
            isSystemApp = isSystemApp,
            backgroundTimeMs = bgMinutes * minuteMs,
            lastTimeUsedMs = nowMs - lastUsedAgoMs,
            standbyBucket = bucket,
            isRunningInBackground = isRunning,
        )
    }
}
