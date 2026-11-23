package com.drivelift.app.feature.cleaner.cache

import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.SleepSessionPhase
import com.drivelift.app.feature.hibernation.engine.StandbyBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GuidedSessionControllerTest {

    @Test
    fun guidedCleanStepsThroughConfirmAndSkipCleanly() {
        val controller = GuidedSessionController(nowMs = { 10_000L })
        val targets = listOf(
            GuidedCleanTarget("com.example.one", "App One", 12_000_000L),
            GuidedCleanTarget("com.example.two", "App Two", 8_000_000L),
            GuidedCleanTarget("com.example.three", "App Three", 5_000_000L),
        )

        val step1 = controller.startGuidedClean(targets)
        assertNotNull(step1)
        assertEquals(GuidedPillMode.GUIDED_CLEAN, step1!!.mode)
        assertEquals(1, step1.stepNumber)
        assertEquals(3, step1.totalCount)
        assertEquals("App One", step1.currentAppLabel)

        val step2 = controller.confirmCurrentStep()
        assertNotNull(step2)
        assertEquals(2, step2!!.stepNumber)
        assertEquals(1, step2.completedCount)
        assertEquals(12_000_000L, step2.reclaimedBytes)

        val step3 = controller.skipCurrentStep()
        assertNotNull(step3)
        assertEquals(3, step3!!.stepNumber)
        assertEquals(1, step3.skippedCount)

        val done = controller.confirmCurrentStep()
        assertNull(done)
        assertNull(controller.activePill)
    }

    @Test
    fun guidedSleepProducesReportOnCompletionAndCancel() {
        var now = 1_000L
        val controller = GuidedSessionController(nowMs = { now.also { now += 1_000L } })
        val candidates = listOf(
            AppDrainCandidate(
                packageName = "com.example.alpha",
                appLabel = "Alpha",
                drainScore = 82,
                backgroundTimeMs = 60 * 60_000L,
                lastTimeUsedMs = 900L,
                standbyBucket = StandbyBucket.ACTIVE,
                isRunningInBackground = true,
                reasons = listOf("Currently active in background"),
            ),
            AppDrainCandidate(
                packageName = "com.example.beta",
                appLabel = "Beta",
                drainScore = 54,
                backgroundTimeMs = 20 * 60_000L,
                lastTimeUsedMs = 800L,
                standbyBucket = StandbyBucket.WORKING_SET,
                isRunningInBackground = false,
                reasons = listOf("20m background activity"),
            ),
        )

        val step1 = controller.startGuidedSleep("sess-1", candidates)
        assertNotNull(step1)
        assertEquals(GuidedPillMode.GUIDED_SLEEP, step1!!.mode)
        assertEquals("Alpha", step1.currentAppLabel)

        val step2 = controller.confirmCurrentStep()
        assertNotNull(step2)
        assertEquals("Beta", step2!!.currentAppLabel)

        controller.cancelActiveSession()
        assertNull(controller.activePill)
        val report = controller.lastSleepReport
        assertNotNull(report)
        assertEquals(SleepSessionPhase.CANCELLED, report!!.finalPhase)
        assertEquals(listOf("com.example.alpha"), report.sleptPackages)
        assertEquals(listOf("com.example.beta"), report.cancelledPackages)
    }

    @Test
    fun guidedSleepAutoSkipsAlreadyStoppedAppsOnStartAndAdvance() {
        var now = 5_000L
        val stoppedPackages = setOf("com.example.stopped1", "com.example.stopped2")
        val controller = GuidedSessionController(
            nowMs = { now.also { now += 1_000L } },
            isAppAlreadyStopped = { it in stoppedPackages },
        )

        fun candidate(pkg: String, label: String) = AppDrainCandidate(
            packageName = pkg,
            appLabel = label,
            drainScore = 70,
            backgroundTimeMs = 30 * 60_000L,
            lastTimeUsedMs = 1_000L,
            standbyBucket = StandbyBucket.ACTIVE,
            isRunningInBackground = pkg !in stoppedPackages,
            reasons = listOf("Background activity"),
        )

        val first = controller.startGuidedSleep(
            sessionId = "sess-auto-skip",
            candidates = listOf(
                candidate("com.example.stopped1", "Stopped One"),
                candidate("com.example.active1", "Active One"),
                candidate("com.example.stopped2", "Stopped Two"),
                candidate("com.example.active2", "Active Two"),
            ),
        )
        assertNotNull(first)
        assertEquals("com.example.active1", first!!.currentPackageName)
        assertEquals(1, first.skippedCount)

        val second = controller.confirmCurrentStep()
        assertNotNull(second)
        assertEquals("com.example.active2", second!!.currentPackageName)
        assertEquals(1, second.completedCount)
        assertEquals(2, second.skippedCount)

        val finished = controller.confirmCurrentStep()
        assertNull(finished)
        val report = controller.lastSleepReport
        assertNotNull(report)
        assertEquals(SleepSessionPhase.COMPLETED, report!!.finalPhase)
        assertEquals(listOf("com.example.active1", "com.example.active2"), report.sleptPackages)
        assertEquals(listOf("com.example.stopped1", "com.example.stopped2"), report.skippedPackages)
    }
}
