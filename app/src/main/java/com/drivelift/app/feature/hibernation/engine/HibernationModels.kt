package com.drivelift.app.feature.hibernation.engine

/**
 * Execution mode for a sleep/hibernation session.
 */
enum class HibernationMode {
    /** Executes stop/restrict sequences silently via Shizuku binder/shell access. */
    SHIZUKU_SILENT,

    /** Guides the user step-by-step through Android App Info / force-stop actions. */
    GUIDED_PILL,
}

/**
 * Android App Standby bucket classification used to weight background drain risk.
 */
enum class StandbyBucket {
    ACTIVE,
    WORKING_SET,
    FREQUENT,
    RARE,
    RESTRICTED,
    NEVER,
    UNKNOWN,
}

/**
 * Reason an application is immune from hibernation scoring and sleep actions.
 */
enum class ProtectionReason {
    SELF_APP,
    DIALER,
    CLOCK_OR_ALARM,
    SYSTEM_APP,
    USER_WHITELIST,
}

/**
 * Rule protecting a specific package from being hibernated.
 */
data class ProtectedAppRule(
    val packageName: String,
    val reason: ProtectionReason,
    val label: String = "",
    val immutable: Boolean = reason != ProtectionReason.USER_WHITELIST,
)

/**
 * Raw usage snapshot for an installed application evaluated by [BackgroundDrainerScorer].
 */
data class AppUsageSnapshot(
    val packageName: String,
    val appLabel: String,
    val isSystemApp: Boolean,
    val backgroundTimeMs: Long,
    val foregroundTimeMs: Long = 0L,
    val lastTimeUsedMs: Long,
    val standbyBucket: StandbyBucket = StandbyBucket.UNKNOWN,
    val wakeupsCount: Int = 0,
    val isRunningInBackground: Boolean = false,
)

/**
 * Ranked candidate eligible for hibernation.
 */
data class AppDrainCandidate(
    val packageName: String,
    val appLabel: String,
    val drainScore: Int,
    val backgroundTimeMs: Long,
    val lastTimeUsedMs: Long,
    val standbyBucket: StandbyBucket,
    val isRunningInBackground: Boolean,
    val reasons: List<String>,
)

/**
 * Outcome for a single application inside a sleep session.
 */
enum class SleepItemOutcome {
    SLEPT,
    SKIPPED,
    FAILED,
    CANCELLED,
}

/**
 * Per-app execution record within a [SleepSessionState].
 */
data class SleepItemResult(
    val packageName: String,
    val appLabel: String,
    val outcome: SleepItemOutcome,
    val detail: String = "",
    val commandsExecuted: List<String> = emptyList(),
)

/**
 * Lifecycle phase of a sleep session.
 */
enum class SleepSessionPhase {
    IDLE,
    RUNNING,
    AWAITING_GUIDED_STEP,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
}

/**
 * Immutable state snapshot for a deterministic sleep session.
 */
data class SleepSessionState(
    val sessionId: String,
    val mode: HibernationMode,
    val phase: SleepSessionPhase,
    val queue: List<AppDrainCandidate>,
    val currentIndex: Int,
    val results: List<SleepItemResult>,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val interruptionReason: String? = null,
) {
    val currentCandidate: AppDrainCandidate?
        get() = if (phase == SleepSessionPhase.RUNNING || phase == SleepSessionPhase.AWAITING_GUIDED_STEP) {
            queue.getOrNull(currentIndex)
        } else {
            null
        }

    val remainingCount: Int
        get() = (queue.size - results.size).coerceAtLeast(0)

    val isTerminal: Boolean
        get() = phase == SleepSessionPhase.COMPLETED ||
            phase == SleepSessionPhase.CANCELLED ||
            phase == SleepSessionPhase.INTERRUPTED
}

/**
 * Final summary produced when a sleep session finishes, cancels, or is interrupted.
 */
data class SleepExecutionReport(
    val sessionId: String,
    val mode: HibernationMode,
    val finalPhase: SleepSessionPhase,
    val totalCandidates: Int,
    val sleptPackages: List<String>,
    val skippedPackages: List<String>,
    val failedPackages: List<String>,
    val cancelledPackages: List<String>,
    val results: List<SleepItemResult>,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val interruptionReason: String? = null,
) {
    val durationMs: Long
        get() = (completedAtMs - startedAtMs).coerceAtLeast(0L)
}
