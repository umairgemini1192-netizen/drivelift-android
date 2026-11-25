package com.drivelift.app.feature.cleaner.cache

import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.SleepExecutionReport
import com.drivelift.app.feature.hibernation.engine.SleepSessionPhase
import com.drivelift.app.feature.hibernation.engine.SleepSessionState
import com.drivelift.app.feature.hibernation.engine.SleepSessionStateMachine

/**
 * Mode displayed by the floating OLED Dynamic Island Guided Progress Pill.
 */
enum class GuidedPillMode {
    GUIDED_CLEAN,
    GUIDED_SLEEP,
}

/**
 * Single target application inside a Guided Clean queue.
 */
data class GuidedCleanTarget(
    val packageName: String,
    val appLabel: String,
    val cacheBytes: Long,
)

/**
 * Immutable UI snapshot for the Android 16/17 floating Dynamic Island progress pill.
 */
data class GuidedPillState(
    val mode: GuidedPillMode,
    val isActive: Boolean,
    val currentIndex: Int,
    val totalCount: Int,
    val currentAppLabel: String,
    val currentPackageName: String,
    val instruction: String,
    val completedCount: Int,
    val skippedCount: Int,
    val reclaimedBytes: Long = 0L,
    val fallbackReason: String? = null,
) {
    val stepNumber: Int
        get() = if (totalCount == 0) 0 else (currentIndex + 1).coerceAtMost(totalCount)

    val progressFraction: Float
        get() = if (totalCount <= 0) 0f else ((completedCount + skippedCount).toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
}

/**
 * Deterministic controller driving both Guided Clean (cache clear in App Info) and Guided Sleep
 * (Force Stop in App Info, backed by Phase 2's [SleepSessionStateMachine]).
 */
class GuidedSessionController(
    private val sleepStateMachine: SleepSessionStateMachine = SleepSessionStateMachine(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val isAppAlreadyStopped: (String) -> Boolean = { false },
) {
    private var cleanQueue: List<GuidedCleanTarget> = emptyList()
    private var cleanIndex: Int = 0
    private var cleanCompletedCount: Int = 0
    private var cleanSkippedCount: Int = 0
    private var cleanReclaimedBytes: Long = 0L
    private var cleanFallbackReason: String? = null

    private var sleepState: SleepSessionState? = null
    private var sleepFallbackReason: String? = null

    var activePill: GuidedPillState? = null
        private set

    var lastSleepReport: SleepExecutionReport? = null
        private set

    fun startGuidedClean(
        targets: List<GuidedCleanTarget>,
        fallbackReason: String? = null,
    ): GuidedPillState? {
        val deduplicated = targets.distinctBy { it.packageName }
        sleepState = null
        sleepFallbackReason = null
        cleanQueue = deduplicated
        cleanIndex = 0
        cleanCompletedCount = 0
        cleanSkippedCount = 0
        cleanReclaimedBytes = 0L
        cleanFallbackReason = fallbackReason

        activePill = buildCleanPillState()
        return activePill
    }

    fun startGuidedSleep(
        sessionId: String,
        candidates: List<AppDrainCandidate>,
        fallbackReason: String? = null,
    ): GuidedPillState? {
        cleanQueue = emptyList()
        cleanFallbackReason = null
        sleepFallbackReason = fallbackReason
        val initial = sleepStateMachine.startSession(
            sessionId = sessionId,
            mode = HibernationMode.GUIDED_PILL,
            candidates = candidates,
            nowMs = nowMs(),
        )
        val state = skipAlreadyStoppedSleepCandidates(initial)
        sleepState = state
        if (state.isTerminal) {
            lastSleepReport = sleepStateMachine.buildReport(state)
        }
        activePill = buildSleepPillState(state)
        return activePill
    }

    fun confirmCurrentStep(): GuidedPillState? {
        val current = activePill ?: return null
        return when (current.mode) {
            GuidedPillMode.GUIDED_CLEAN -> {
                val target = cleanQueue.getOrNull(cleanIndex) ?: return null
                cleanCompletedCount++
                cleanReclaimedBytes += target.cacheBytes.coerceAtLeast(0L)
                cleanIndex++
                activePill = buildCleanPillState()
                activePill
            }
            GuidedPillMode.GUIDED_SLEEP -> {
                val state = sleepState ?: return null
                val confirmed = sleepStateMachine.confirmGuidedStep(state, nowMs())
                val next = skipAlreadyStoppedSleepCandidates(confirmed)
                sleepState = next
                if (next.isTerminal) {
                    lastSleepReport = sleepStateMachine.buildReport(next)
                }
                activePill = buildSleepPillState(next)
                activePill
            }
        }
    }

    fun skipCurrentStep(): GuidedPillState? {
        val current = activePill ?: return null
        return when (current.mode) {
            GuidedPillMode.GUIDED_CLEAN -> {
                if (cleanIndex >= cleanQueue.size) return null
                cleanSkippedCount++
                cleanIndex++
                activePill = buildCleanPillState()
                activePill
            }
            GuidedPillMode.GUIDED_SLEEP -> {
                val state = sleepState ?: return null
                val skipped = sleepStateMachine.skipGuidedStep(state, nowMs())
                val next = skipAlreadyStoppedSleepCandidates(skipped)
                sleepState = next
                if (next.isTerminal) {
                    lastSleepReport = sleepStateMachine.buildReport(next)
                }
                activePill = buildSleepPillState(next)
                activePill
            }
        }
    }

    private fun skipAlreadyStoppedSleepCandidates(initial: SleepSessionState): SleepSessionState {
        var current = initial
        while (current.phase == SleepSessionPhase.AWAITING_GUIDED_STEP) {
            val candidate = current.currentCandidate ?: break
            val alreadyStopped = runCatching { isAppAlreadyStopped(candidate.packageName) }.getOrDefault(false)
            if (!alreadyStopped) break
            current = sleepStateMachine.skipGuidedStep(
                state = current,
                nowMs = nowMs(),
                reason = "Already stopped",
            )
        }
        return current
    }

    fun cancelActiveSession() {
        val current = activePill ?: return
        when (current.mode) {
            GuidedPillMode.GUIDED_CLEAN -> {
                cleanQueue = emptyList()
                cleanIndex = 0
                activePill = null
            }
            GuidedPillMode.GUIDED_SLEEP -> {
                val state = sleepState
                if (state != null) {
                    val cancelled = sleepStateMachine.cancelSession(state, nowMs())
                    sleepState = cancelled
                    lastSleepReport = sleepStateMachine.buildReport(cancelled)
                }
                activePill = null
            }
        }
    }

    fun currentSleepState(): SleepSessionState? = sleepState

    private fun buildCleanPillState(): GuidedPillState? {
        val target = cleanQueue.getOrNull(cleanIndex) ?: return null
        return GuidedPillState(
            mode = GuidedPillMode.GUIDED_CLEAN,
            isActive = true,
            currentIndex = cleanIndex,
            totalCount = cleanQueue.size,
            currentAppLabel = target.appLabel,
            currentPackageName = target.packageName,
            instruction = "Open App Info, tap Storage, then Clear cache.",
            completedCount = cleanCompletedCount,
            skippedCount = cleanSkippedCount,
            reclaimedBytes = cleanReclaimedBytes,
            fallbackReason = cleanFallbackReason,
        )
    }

    private fun buildSleepPillState(state: SleepSessionState): GuidedPillState? {
        if (state.phase != SleepSessionPhase.AWAITING_GUIDED_STEP) return null
        val candidate = state.currentCandidate ?: return null
        val completed = state.results.count { it.outcome.name == "SLEPT" }
        val skipped = state.results.count { it.outcome.name == "SKIPPED" }
        return GuidedPillState(
            mode = GuidedPillMode.GUIDED_SLEEP,
            isActive = true,
            currentIndex = state.currentIndex,
            totalCount = state.queue.size,
            currentAppLabel = candidate.appLabel,
            currentPackageName = candidate.packageName,
            instruction = "Open App Info, then tap Force stop.",
            completedCount = completed,
            skippedCount = skipped,
            reclaimedBytes = 0L,
            fallbackReason = sleepFallbackReason,
        )
    }
}
