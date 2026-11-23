package com.drivelift.app.feature.applock.engine

/**
 * Injectable millisecond clock for deterministic policy evaluation and testing.
 */
fun interface AppLockClock {
    fun nowMs(): Long
}

/**
 * Pure deterministic state machine for App Lock decisions, per-app unlock grace windows,
 * immediate screen-off re-lock, RAM-only pause, PIN exponential backoff lockout, and
 * Settings tamper-protection recommendations.
 */
class AppLockPolicyEngine(
    private val clock: AppLockClock = AppLockClock { System.currentTimeMillis() },
) {
    private val unlockedAtMsByPackage = mutableMapOf<String, Long>()
    private var pausedUntilMs: Long? = null
    private var lockoutState = PinLockoutState()

    /**
     * Evaluates whether [packageName] can stay in the foreground or must be locked.
     */
    fun evaluateForegroundPackage(
        packageName: String,
        config: AppLockConfig,
    ): LockEvaluationResult {
        val now = clock.nowMs()
        if (!config.enabled) {
            return LockEvaluationResult(
                decision = LockDecision.ALLOW,
                packageName = packageName,
                reason = "App lock is disabled",
            )
        }

        if (isPausedAt(now)) {
            return LockEvaluationResult(
                decision = LockDecision.ALLOW,
                packageName = packageName,
                reason = "App lock is temporarily paused in RAM",
                isPaused = true,
            )
        }

        val isProtected = packageName in config.lockedPackages ||
            (config.tamperProtectionEnabled && packageName == AppLockConfig.SETTINGS_PACKAGE_NAME)
        if (!isProtected) {
            return LockEvaluationResult(
                decision = LockDecision.ALLOW,
                packageName = packageName,
                reason = "Package is not in the locked set",
            )
        }

        val unlockedAt = unlockedAtMsByPackage[packageName]
        if (unlockedAt != null) {
            val elapsedMs = now - unlockedAt
            if (elapsedMs >= 0L && elapsedMs < config.gracePeriod.durationMs) {
                return LockEvaluationResult(
                    decision = LockDecision.ALLOW,
                    packageName = packageName,
                    reason = "Within re-lock grace window",
                )
            }
            unlockedAtMsByPackage.remove(packageName)
        }

        return LockEvaluationResult(
            decision = LockDecision.REQUIRE_AUTH,
            packageName = packageName,
            reason = if (unlockedAt == null) "Package requires authentication" else "Re-lock grace window expired",
            lockoutRemainingMs = lockoutState.remainingLockoutMs(now),
        )
    }

    /**
     * Convenience wrapper returning only the [LockDecision] for [packageName].
     */
    fun evaluateDecision(
        packageName: String,
        config: AppLockConfig,
    ): LockDecision = evaluateForegroundPackage(packageName, config).decision

    /**
     * Records a successful authentication for [packageName], starting its grace window and
     * clearing any active PIN lockout counters.
     */
    fun recordUnlock(packageName: String) {
        val now = clock.nowMs()
        unlockedAtMsByPackage[packageName] = now
        lockoutState = PinLockoutState()
    }

    /**
     * Immediately revokes all per-app unlock grants when the device screen turns off.
     */
    fun onScreenOff() {
        unlockedAtMsByPackage.clear()
    }

    /**
     * Temporarily pauses App Lock in RAM only. A process restart creates a fresh engine
     * instance, automatically clearing any pause state.
     */
    fun pauseAppLock(config: AppLockConfig = AppLockConfig()): Long {
        val deadline = clock.nowMs() + config.pauseDurationMs.coerceAtLeast(0L)
        pausedUntilMs = deadline
        return deadline
    }

    /**
     * Ends any active RAM-only pause immediately.
     */
    fun resumeAppLock() {
        pausedUntilMs = null
    }

    /**
     * Returns true if App Lock is currently within its RAM-only pause window.
     */
    fun isPaused(): Boolean = isPausedAt(clock.nowMs())

    /**
     * Returns remaining milliseconds in the RAM-only pause window, or 0 if not paused.
     */
    fun pauseRemainingMs(): Long {
        val now = clock.nowMs()
        return if (isPausedAt(now)) {
            (pausedUntilMs!! - now).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    /**
     * Records a failed PIN attempt and applies exponential backoff lockout once
     * [AppLockConfig.maxFailedPinAttempts] is reached.
     */
    fun recordFailedPinAttempt(config: AppLockConfig = AppLockConfig()): PinLockoutState {
        val now = clock.nowMs()
        val nextAttempts = lockoutState.failedAttempts + 1
        val threshold = config.maxFailedPinAttempts.coerceAtLeast(1)
        val lockedOutUntilMs = if (nextAttempts >= threshold) {
            val exponent = (nextAttempts - threshold).coerceAtMost(20)
            val baseMs = config.baseLockoutMs.coerceAtLeast(0L)
            val maxMs = config.maxLockoutMs.coerceAtLeast(baseMs)
            val multiplier = 1L shl exponent
            val backoffMs = if (baseMs > 0L && multiplier > maxMs / baseMs) {
                maxMs
            } else {
                (baseMs * multiplier).coerceAtMost(maxMs)
            }
            now + backoffMs
        } else {
            0L
        }

        lockoutState = PinLockoutState(
            failedAttempts = nextAttempts,
            lockedOutUntilMs = lockedOutUntilMs,
            lastFailedAtMs = now,
        )
        return lockoutState
    }

    /**
     * Clears failed PIN attempt counters and lockout state after a valid PIN entry.
     */
    fun recordPinSuccess() {
        lockoutState = PinLockoutState()
    }

    fun currentLockoutState(): PinLockoutState = lockoutState

    fun isPinLockedOut(): Boolean = lockoutState.isLockedOut(clock.nowMs())

    /**
     * Evaluates whether Android Settings ([AppLockConfig.SETTINGS_PACKAGE_NAME]) is locked
     * and recommends enabling tamper protection when App Lock is active without it.
     */
    fun evaluateTamperProtection(config: AppLockConfig): TamperProtectionStatus {
        val settingsLocked = config.tamperProtectionEnabled ||
            AppLockConfig.SETTINGS_PACKAGE_NAME in config.lockedPackages
        val recommended = config.enabled && !settingsLocked
        val reason = when {
            settingsLocked -> "Android Settings is protected against tampering"
            recommended -> "Lock Android Settings (${AppLockConfig.SETTINGS_PACKAGE_NAME}) to prevent disabling App Lock"
            else -> "App lock is disabled"
        }
        return TamperProtectionStatus(
            isSettingsLocked = settingsLocked,
            isRecommended = recommended,
            targetPackage = AppLockConfig.SETTINGS_PACKAGE_NAME,
            reason = reason,
        )
    }

    private fun isPausedAt(nowMs: Long): Boolean {
        val deadline = pausedUntilMs ?: return false
        if (nowMs >= deadline) {
            pausedUntilMs = null
            return false
        }
        return true
    }
}
