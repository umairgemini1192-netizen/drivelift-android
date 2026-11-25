package com.drivelift.app.feature.hibernation.engine

import java.io.IOException

/**
 * Thrown when a single silent sleep command exceeds its per-command timeout budget.
 * Handled per app by [SleepSessionStateMachine.runSilentBatch] so one stalled app is marked
 * skipped (timeout) without blocking the rest of the batch.
 */
class SleepCommandTimeoutException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Execution mode for a sleep/hibernation session.
 */
enum class HibernationMode {
    /** Executes stop/cache sequences silently via DriveLift's built-in localhost Wireless Debugging ADB client. */
    AUTO_SILENT,

    /** Guides the user step-by-step through Android App Info / force-stop actions. */
    GUIDED_PILL,
    ;

    companion object {
        /**
         * Parses a persisted [HibernationMode] name, migrating any legacy `"SHIZUKU_SILENT"` value
         * transparently to [AUTO_SILENT].
         */
        fun fromStored(raw: String?): HibernationMode = when (raw?.trim()) {
            "AUTO_SILENT", "SHIZUKU_SILENT" -> AUTO_SILENT
            "GUIDED_PILL" -> GUIDED_PILL
            else -> GUIDED_PILL
        }
    }
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
    val wirelessDebuggingTurnedOff: Boolean = false,
) {
    val durationMs: Long
        get() = (completedAtMs - startedAtMs).coerceAtLeast(0L)
}

/**
 * Formats plain-English summary copy for completed, partially skipped, or cancelled sleep batches.
 * Uses only plain ASCII hyphens (zero emojis, zero em-dashes).
 */
object SleepReportFormatter {
    fun formatSummary(report: SleepExecutionReport): String {
        val slept = report.sleptPackages.size
        val total = report.totalCandidates
        val appWord = if (total == 1) "app" else "apps"
        val base = "Slept $slept of $total $appWord"

        val problemItems = report.results.filter {
            it.outcome == SleepItemOutcome.SKIPPED || it.outcome == SleepItemOutcome.FAILED
        }
        val problemSuffix = when {
            problemItems.isEmpty() -> null
            problemItems.size == 1 -> {
                val item = problemItems.single()
                val name = item.appLabel.ifBlank { item.packageName }
                val reason = if (item.detail.contains("timeout", ignoreCase = true) ||
                    item.detail.contains("timed out", ignoreCase = true) ||
                    item.detail.contains("did not respond", ignoreCase = true)
                ) {
                    "did not respond"
                } else {
                    item.detail.ifBlank { "could not stop" }
                }
                "$name skipped ($reason)"
            }
            else -> {
                val shown = problemItems.take(3).joinToString(", ") { it.appLabel.ifBlank { it.packageName } }
                val extra = if (problemItems.size > 3) " +${problemItems.size - 3} more" else ""
                val allTimedOut = problemItems.all {
                    it.detail.contains("timeout", ignoreCase = true) ||
                        it.detail.contains("timed out", ignoreCase = true) ||
                        it.detail.contains("did not respond", ignoreCase = true)
                }
                if (allTimedOut) {
                    "$shown$extra skipped (did not respond)"
                } else {
                    "$shown$extra skipped"
                }
            }
        }

        val cancelSuffix = if (report.finalPhase == SleepSessionPhase.CANCELLED) {
            val remaining = report.cancelledPackages.size
            if (remaining > 0) "cancelled ($remaining remaining)" else "cancelled"
        } else {
            null
        }

        val parts = listOfNotNull(base, problemSuffix, cancelSuffix)
        return parts.joinToString(" - ")
    }
}
