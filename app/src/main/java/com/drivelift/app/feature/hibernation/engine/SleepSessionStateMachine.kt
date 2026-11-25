package com.drivelift.app.feature.hibernation.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Abstraction for executing hibernation commands for a single candidate during [HibernationMode.AUTO_SILENT].
 */
fun interface SleepCommandExecutor {
    suspend fun sleepApp(candidate: AppDrainCandidate): Result<List<String>>
}

/**
 * Deterministic queue controller handling batch silent sleep, Guided Sleep step-by-step
 * advance/skip/cancel, and safe recovery on interruption.
 */
class SleepSessionStateMachine {

    /**
     * Creates an initialized [SleepSessionState] from [candidates], deduplicating by package name.
     */
    fun startSession(
        sessionId: String,
        mode: HibernationMode,
        candidates: List<AppDrainCandidate>,
        nowMs: Long,
    ): SleepSessionState {
        val deduplicated = candidates.distinctBy { it.packageName }
        val initialPhase = when {
            deduplicated.isEmpty() -> SleepSessionPhase.COMPLETED
            mode == HibernationMode.GUIDED_PILL -> SleepSessionPhase.AWAITING_GUIDED_STEP
            else -> SleepSessionPhase.RUNNING
        }
        return SleepSessionState(
            sessionId = sessionId,
            mode = mode,
            phase = initialPhase,
            queue = deduplicated,
            currentIndex = 0,
            results = emptyList(),
            startedAtMs = nowMs,
            updatedAtMs = nowMs,
        )
    }

    /**
     * Marks the current Guided Sleep step as slept and advances to the next candidate or completion.
     */
    fun confirmGuidedStep(
        state: SleepSessionState,
        nowMs: Long,
        detail: String = "Stopped by user",
    ): SleepSessionState {
        if (state.mode != HibernationMode.GUIDED_PILL ||
            state.phase != SleepSessionPhase.AWAITING_GUIDED_STEP
        ) {
            return state
        }
        return recordOutcomeAndAdvance(
            state = state,
            outcome = SleepItemOutcome.SLEPT,
            detail = detail,
            commandsExecuted = emptyList(),
            nowMs = nowMs,
        )
    }

    /**
     * Skips the current Guided Sleep step and advances to the next candidate or completion.
     */
    fun skipGuidedStep(
        state: SleepSessionState,
        nowMs: Long,
        reason: String = "Skipped by user",
    ): SleepSessionState {
        if (state.mode != HibernationMode.GUIDED_PILL ||
            state.phase != SleepSessionPhase.AWAITING_GUIDED_STEP
        ) {
            return state
        }
        return recordOutcomeAndAdvance(
            state = state,
            outcome = SleepItemOutcome.SKIPPED,
            detail = reason,
            commandsExecuted = emptyList(),
            nowMs = nowMs,
        )
    }

    /**
     * Records a failure for the current active candidate and advances safely to the next item.
     */
    fun failCurrentStep(
        state: SleepSessionState,
        nowMs: Long,
        error: String,
    ): SleepSessionState {
        if (state.phase != SleepSessionPhase.RUNNING &&
            state.phase != SleepSessionPhase.AWAITING_GUIDED_STEP
        ) {
            return state
        }
        return recordOutcomeAndAdvance(
            state = state,
            outcome = SleepItemOutcome.FAILED,
            detail = error,
            commandsExecuted = emptyList(),
            nowMs = nowMs,
        )
    }

    /**
     * Cancels an active or interrupted session, marking all unprocessed candidates as [SleepItemOutcome.CANCELLED].
     */
    fun cancelSession(
        state: SleepSessionState,
        nowMs: Long,
        reason: String = "Cancelled by user",
    ): SleepSessionState {
        if (state.phase == SleepSessionPhase.COMPLETED ||
            state.phase == SleepSessionPhase.CANCELLED
        ) {
            return state
        }
        val remainingResults = if (state.currentIndex < state.queue.size) {
            state.queue.subList(state.currentIndex, state.queue.size).map { candidate ->
                SleepItemResult(
                    packageName = candidate.packageName,
                    appLabel = candidate.appLabel,
                    outcome = SleepItemOutcome.CANCELLED,
                    detail = reason,
                )
            }
        } else {
            emptyList()
        }
        return state.copy(
            phase = SleepSessionPhase.CANCELLED,
            currentIndex = state.queue.size,
            results = state.results + remainingResults,
            updatedAtMs = nowMs,
            interruptionReason = null,
        )
    }

    /**
     * Pauses an active session at the current index without losing completed results so it can resume safely.
     */
    fun interruptSession(
        state: SleepSessionState,
        nowMs: Long,
        reason: String,
    ): SleepSessionState {
        if (state.isTerminal) {
            return state
        }
        return state.copy(
            phase = SleepSessionPhase.INTERRUPTED,
            updatedAtMs = nowMs,
            interruptionReason = reason,
        )
    }

    /**
     * Resumes an [SleepSessionPhase.INTERRUPTED] session from the exact pending [SleepSessionState.currentIndex].
     */
    fun recoverSession(
        state: SleepSessionState,
        nowMs: Long,
    ): SleepSessionState {
        if (state.phase != SleepSessionPhase.INTERRUPTED) {
            return state
        }
        val resumedPhase = when {
            state.currentIndex >= state.queue.size -> SleepSessionPhase.COMPLETED
            state.mode == HibernationMode.GUIDED_PILL -> SleepSessionPhase.AWAITING_GUIDED_STEP
            else -> SleepSessionPhase.RUNNING
        }
        return state.copy(
            phase = resumedPhase,
            updatedAtMs = nowMs,
            interruptionReason = null,
        )
    }

    /**
     * Executes an [HibernationMode.AUTO_SILENT] queue deterministically using [executor].
     *
     * Cooperative interruption is supported via [shouldInterrupt], cooperative user cancellation
     * via [shouldCancel], and whole-batch stalling is bounded by [batchWatchdogMs] (defaulting to
     * 20 s per queued app). Per-app [SleepCommandTimeoutException] results are recorded as
     * [SleepItemOutcome.SKIPPED] with detail `"skipped (timeout)"`, while other per-app failures
     * are recorded as [SleepItemOutcome.FAILED] so remaining candidates still run.
     */
    suspend fun runSilentBatch(
        initialState: SleepSessionState,
        executor: SleepCommandExecutor,
        clockMs: () -> Long,
        shouldInterrupt: () -> Boolean = { false },
        shouldCancel: () -> Boolean = { false },
        batchWatchdogMs: Long = DEFAULT_BATCH_WATCHDOG_PER_APP_MS * initialState.queue.size.coerceAtLeast(1),
        onStateChange: (SleepSessionState) -> Unit = {},
    ): SleepSessionState {
        require(initialState.mode == HibernationMode.AUTO_SILENT) {
            "runSilentBatch requires AUTO_SILENT mode."
        }
        var state = initialState
        onStateChange(state)

        suspend fun executeLoop() {
            while (state.phase == SleepSessionPhase.RUNNING && state.currentIndex < state.queue.size) {
                if (shouldCancel()) {
                    state = cancelSession(state, clockMs(), REASON_CANCELLED_BY_USER)
                    onStateChange(state)
                    return
                }
                if (shouldInterrupt()) {
                    state = interruptSession(state, clockMs(), "Interrupted before next candidate")
                    onStateChange(state)
                    return
                }
                val candidate = state.queue[state.currentIndex]
                val executionResult = try {
                    executor.sleepApp(candidate)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                if (shouldCancel()) {
                    state = if (executionResult.isSuccess) {
                        recordOutcomeAndAdvance(
                            state = state,
                            outcome = SleepItemOutcome.SLEPT,
                            detail = "Hibernated via Auto Mode",
                            commandsExecuted = executionResult.getOrDefault(emptyList()),
                            nowMs = clockMs(),
                        )
                    } else {
                        state
                    }
                    if (!state.isTerminal) {
                        state = cancelSession(state, clockMs(), REASON_CANCELLED_BY_USER)
                    }
                    onStateChange(state)
                    return
                }
                val failure = executionResult.exceptionOrNull()
                state = when {
                    executionResult.isSuccess -> recordOutcomeAndAdvance(
                        state = state,
                        outcome = SleepItemOutcome.SLEPT,
                        detail = "Hibernated via Auto Mode",
                        commandsExecuted = executionResult.getOrDefault(emptyList()),
                        nowMs = clockMs(),
                    )
                    failure is SleepCommandTimeoutException -> recordOutcomeAndAdvance(
                        state = state,
                        outcome = SleepItemOutcome.SKIPPED,
                        detail = REASON_SKIPPED_TIMEOUT,
                        commandsExecuted = emptyList(),
                        nowMs = clockMs(),
                    )
                    else -> {
                        val errorMessage = failure?.message ?: "Execution failed"
                        recordOutcomeAndAdvance(
                            state = state,
                            outcome = SleepItemOutcome.FAILED,
                            detail = errorMessage,
                            commandsExecuted = emptyList(),
                            nowMs = clockMs(),
                        )
                    }
                }
                onStateChange(state)
            }
        }

        val completedWithinWatchdog = if (batchWatchdogMs > 0L) {
            withTimeoutOrNull(batchWatchdogMs) {
                executeLoop()
                true
            } ?: false
        } else {
            executeLoop()
            true
        }

        if (!completedWithinWatchdog && !state.isTerminal) {
            state = skipRemainingOnWatchdog(state, clockMs(), REASON_SKIPPED_TIMEOUT)
            onStateChange(state)
        }
        return state
    }

    private fun skipRemainingOnWatchdog(
        state: SleepSessionState,
        nowMs: Long,
        reason: String,
    ): SleepSessionState {
        if (state.isTerminal) return state
        val remainingResults = if (state.currentIndex < state.queue.size) {
            state.queue.subList(state.currentIndex, state.queue.size).map { candidate ->
                SleepItemResult(
                    packageName = candidate.packageName,
                    appLabel = candidate.appLabel,
                    outcome = SleepItemOutcome.SKIPPED,
                    detail = reason,
                )
            }
        } else {
            emptyList()
        }
        return state.copy(
            phase = SleepSessionPhase.COMPLETED,
            currentIndex = state.queue.size,
            results = state.results + remainingResults,
            updatedAtMs = nowMs,
            interruptionReason = "Batch watchdog cap reached",
        )
    }

    /**
     * Builds a [SleepExecutionReport] from any [SleepSessionState].
     */
    fun buildReport(
        state: SleepSessionState,
        completedAtMs: Long = state.updatedAtMs,
        wirelessDebuggingTurnedOff: Boolean = false,
    ): SleepExecutionReport {
        return SleepExecutionReport(
            sessionId = state.sessionId,
            mode = state.mode,
            finalPhase = state.phase,
            totalCandidates = state.queue.size,
            sleptPackages = state.results
                .filter { it.outcome == SleepItemOutcome.SLEPT }
                .map { it.packageName },
            skippedPackages = state.results
                .filter { it.outcome == SleepItemOutcome.SKIPPED }
                .map { it.packageName },
            failedPackages = state.results
                .filter { it.outcome == SleepItemOutcome.FAILED }
                .map { it.packageName },
            cancelledPackages = state.results
                .filter { it.outcome == SleepItemOutcome.CANCELLED }
                .map { it.packageName },
            results = state.results,
            startedAtMs = state.startedAtMs,
            completedAtMs = completedAtMs,
            interruptionReason = state.interruptionReason,
            wirelessDebuggingTurnedOff = wirelessDebuggingTurnedOff,
        )
    }

    companion object {
        const val DEFAULT_BATCH_WATCHDOG_PER_APP_MS = 20_000L
        const val REASON_SKIPPED_TIMEOUT = "skipped (timeout)"
        const val REASON_CANCELLED_BY_USER = "Cancelled by user"
    }

    private fun recordOutcomeAndAdvance(
        state: SleepSessionState,
        outcome: SleepItemOutcome,
        detail: String,
        commandsExecuted: List<String>,
        nowMs: Long,
    ): SleepSessionState {
        val candidate = state.queue.getOrNull(state.currentIndex) ?: return state
        val itemResult = SleepItemResult(
            packageName = candidate.packageName,
            appLabel = candidate.appLabel,
            outcome = outcome,
            detail = detail,
            commandsExecuted = commandsExecuted,
        )
        val nextIndex = state.currentIndex + 1
        val nextPhase = when {
            nextIndex >= state.queue.size -> SleepSessionPhase.COMPLETED
            state.mode == HibernationMode.GUIDED_PILL -> SleepSessionPhase.AWAITING_GUIDED_STEP
            else -> SleepSessionPhase.RUNNING
        }
        return state.copy(
            phase = nextPhase,
            currentIndex = nextIndex,
            results = state.results + itemResult,
            updatedAtMs = nowMs,
        )
    }
}
