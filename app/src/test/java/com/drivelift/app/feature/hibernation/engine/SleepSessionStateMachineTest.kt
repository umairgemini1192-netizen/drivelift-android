package com.drivelift.app.feature.hibernation.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepSessionStateMachineTest {

    private val machine = SleepSessionStateMachine()

    private fun candidate(pkg: String, label: String, score: Int = 80): AppDrainCandidate {
        return AppDrainCandidate(
            packageName = pkg,
            appLabel = label,
            drainScore = score,
            backgroundTimeMs = 1_800_000L,
            lastTimeUsedMs = 1_699_999_000_000L,
            standbyBucket = StandbyBucket.ACTIVE,
            isRunningInBackground = true,
            reasons = listOf("30m background activity"),
        )
    }

    @Test
    fun guidedPillAdvancesSkipsAndCompletesDeterministically() {
        val queue = listOf(
            candidate("com.example.one", "One", 90),
            candidate("com.example.two", "Two", 75),
            candidate("com.example.three", "Three", 60),
        )

        var state = machine.startSession(
            sessionId = "s-guided-1",
            mode = HibernationMode.GUIDED_PILL,
            candidates = queue,
            nowMs = 1000L,
        )
        assertEquals(SleepSessionPhase.AWAITING_GUIDED_STEP, state.phase)
        assertEquals("com.example.one", state.currentCandidate?.packageName)
        assertEquals(3, state.remainingCount)

        state = machine.confirmGuidedStep(state, nowMs = 2000L)
        assertEquals(SleepSessionPhase.AWAITING_GUIDED_STEP, state.phase)
        assertEquals("com.example.two", state.currentCandidate?.packageName)
        assertEquals(2, state.remainingCount)

        state = machine.skipGuidedStep(state, nowMs = 3000L, reason = "User kept open")
        assertEquals(SleepSessionPhase.AWAITING_GUIDED_STEP, state.phase)
        assertEquals("com.example.three", state.currentCandidate?.packageName)

        state = machine.confirmGuidedStep(state, nowMs = 4000L)
        assertEquals(SleepSessionPhase.COMPLETED, state.phase)
        assertNull(state.currentCandidate)
        assertEquals(0, state.remainingCount)

        val report = machine.buildReport(state)
        assertEquals(listOf("com.example.one", "com.example.three"), report.sleptPackages)
        assertEquals(listOf("com.example.two"), report.skippedPackages)
        assertEquals(emptyList<String>(), report.failedPackages)
        assertEquals(3000L, report.durationMs)
    }

    @Test
    fun guidedPillCancelMarksRemainingAsCancelled() {
        val queue = listOf(
            candidate("com.example.one", "One"),
            candidate("com.example.two", "Two"),
            candidate("com.example.three", "Three"),
        )
        var state = machine.startSession("s-cancel", HibernationMode.GUIDED_PILL, queue, 1000L)
        state = machine.confirmGuidedStep(state, 2000L)
        state = machine.cancelSession(state, 3000L, reason = "User dismissed pill")

        assertEquals(SleepSessionPhase.CANCELLED, state.phase)
        val report = machine.buildReport(state)
        assertEquals(listOf("com.example.one"), report.sleptPackages)
        assertEquals(listOf("com.example.two", "com.example.three"), report.cancelledPackages)
    }

    @Test
    fun silentBatchIsolatesPerAppFailuresAndCompletesQueue() = runBlocking {
        val queue = listOf(
            candidate("com.example.one", "One"),
            candidate("com.example.two", "Two"),
            candidate("com.example.three", "Three"),
        )
        val initial = machine.startSession("s-silent", HibernationMode.SHIZUKU_SILENT, queue, 1000L)
        var clock = 1000L

        val finalState = machine.runSilentBatch(
            initialState = initial,
            executor = SleepCommandExecutor { item ->
                if (item.packageName == "com.example.two") {
                    Result.failure(IllegalStateException("Shizuku binder timeout"))
                } else {
                    Result.success(listOf("am force-stop ${item.packageName}"))
                }
            },
            clockMs = {
                clock += 100L
                clock
            },
        )

        assertEquals(SleepSessionPhase.COMPLETED, finalState.phase)
        val report = machine.buildReport(finalState)
        assertEquals(listOf("com.example.one", "com.example.three"), report.sleptPackages)
        assertEquals(listOf("com.example.two"), report.failedPackages)
    }

    @Test
    fun interruptionAndRecoveryResumesWithoutDuplicatingCompletedItems() = runBlocking {
        val queue = listOf(
            candidate("com.example.one", "One"),
            candidate("com.example.two", "Two"),
            candidate("com.example.three", "Three"),
        )
        val initial = machine.startSession("s-recover", HibernationMode.SHIZUKU_SILENT, queue, 1000L)
        var processedInFirstRun = 0
        val executedPackages = mutableListOf<String>()

        val interrupted = machine.runSilentBatch(
            initialState = initial,
            executor = SleepCommandExecutor { item ->
                processedInFirstRun += 1
                executedPackages += item.packageName
                Result.success(listOf("am force-stop ${item.packageName}"))
            },
            clockMs = { 2000L },
            shouldInterrupt = { processedInFirstRun >= 1 },
        )

        assertEquals(SleepSessionPhase.INTERRUPTED, interrupted.phase)
        assertEquals(1, interrupted.currentIndex)
        assertEquals(1, interrupted.results.size)

        val recovered = machine.recoverSession(interrupted, nowMs = 3000L)
        assertEquals(SleepSessionPhase.RUNNING, recovered.phase)
        assertNull(recovered.interruptionReason)

        val completed = machine.runSilentBatch(
            initialState = recovered,
            executor = SleepCommandExecutor { item ->
                executedPackages += item.packageName
                Result.success(listOf("am force-stop ${item.packageName}"))
            },
            clockMs = { 4000L },
        )

        assertEquals(SleepSessionPhase.COMPLETED, completed.phase)
        assertEquals(
            listOf("com.example.one", "com.example.two", "com.example.three"),
            executedPackages,
        )
        assertEquals(3, completed.results.size)
    }

    @Test
    fun emptyQueueStartsInCompletedPhase() {
        val state = machine.startSession(
            sessionId = "s-empty",
            mode = HibernationMode.SHIZUKU_SILENT,
            candidates = emptyList(),
            nowMs = 5000L,
        )
        assertEquals(SleepSessionPhase.COMPLETED, state.phase)
        assertEquals(0, state.remainingCount)
    }
}
