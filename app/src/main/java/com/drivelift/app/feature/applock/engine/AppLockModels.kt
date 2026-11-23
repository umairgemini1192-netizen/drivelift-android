package com.drivelift.app.feature.applock.engine

/**
 * Duration an unlocked app remains accessible before requiring re-authentication.
 */
enum class ReLockGracePeriod(val durationMs: Long) {
    /** Re-locks immediately when the user leaves the app or the screen turns off. */
    IMMEDIATE(0L),

    /** Keeps the app unlocked for 1 minute while the screen remains on. */
    ONE_MIN(60_000L),

    /** Keeps the app unlocked for 5 minutes while the screen remains on. */
    FIVE_MIN(5L * 60_000L),
}

/**
 * Policy decision returned when a protected foreground package is evaluated.
 */
enum class LockDecision {
    /** Package may remain in the foreground without an authentication prompt. */
    ALLOW,

    /** Package must be intercepted by the DriveLift lock screen. */
    REQUIRE_AUTH,
}

/**
 * Immutable configuration governing the App Lock policy engine.
 */
data class AppLockConfig(
    val enabled: Boolean = false,
    val lockedPackages: Set<String> = emptySet(),
    val gracePeriod: ReLockGracePeriod = ReLockGracePeriod.IMMEDIATE,
    val allowBiometric: Boolean = true,
    val tamperProtectionEnabled: Boolean = false,
    val pauseDurationMs: Long = DEFAULT_PAUSE_DURATION_MS,
    val maxFailedPinAttempts: Int = DEFAULT_MAX_FAILED_PIN_ATTEMPTS,
    val baseLockoutMs: Long = DEFAULT_BASE_LOCKOUT_MS,
    val maxLockoutMs: Long = DEFAULT_MAX_LOCKOUT_MS,
) {
    companion object {
        const val DEFAULT_PAUSE_DURATION_MS: Long = 15L * 60_000L
        const val DEFAULT_MAX_FAILED_PIN_ATTEMPTS: Int = 5
        const val DEFAULT_BASE_LOCKOUT_MS: Long = 30_000L
        const val DEFAULT_MAX_LOCKOUT_MS: Long = 15L * 60_000L
        const val SETTINGS_PACKAGE_NAME: String = "com.android.settings"
    }
}

/**
 * Tracks failed PIN attempts and exponential backoff lockout deadlines.
 */
data class PinLockoutState(
    val failedAttempts: Int = 0,
    val lockedOutUntilMs: Long = 0L,
    val lastFailedAtMs: Long = 0L,
) {
    fun isLockedOut(nowMs: Long): Boolean = lockedOutUntilMs > nowMs

    fun remainingLockoutMs(nowMs: Long): Long = (lockedOutUntilMs - nowMs).coerceAtLeast(0L)
}

/**
 * Describes whether Android Settings is protected against uninstall or force-stop tampering.
 */
data class TamperProtectionStatus(
    val isSettingsLocked: Boolean,
    val isRecommended: Boolean,
    val targetPackage: String = AppLockConfig.SETTINGS_PACKAGE_NAME,
    val reason: String,
)

/**
 * Detailed outcome produced by [AppLockPolicyEngine.evaluateForegroundPackage].
 */
data class LockEvaluationResult(
    val decision: LockDecision,
    val packageName: String,
    val reason: String,
    val isPaused: Boolean = false,
    val lockoutRemainingMs: Long = 0L,
)
