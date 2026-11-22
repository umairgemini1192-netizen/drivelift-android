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
}
