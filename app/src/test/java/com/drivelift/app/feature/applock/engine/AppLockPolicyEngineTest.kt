package com.drivelift.app.feature.applock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyEngineTest {

    private class FakeClock(var nowMs: Long = 1_000_000L) : AppLockClock {
        override fun nowMs(): Long = nowMs
        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    private val protectedPkg = "com.example.vault"
    private val otherProtectedPkg = "com.example.photos"
    private val unprotectedPkg = "com.example.calculator"

    @Test
    fun disabledConfig_allowsAllPackages() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(
            enabled = false,
            lockedPackages = setOf(protectedPkg),
        )

        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(protectedPkg, config))
        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(unprotectedPkg, config))
    }

    @Test
    fun unlockedVsLockedPackage_enforcesAuthOnlyForLockedSet() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(
            enabled = true,
            lockedPackages = setOf(protectedPkg),
        )

        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(protectedPkg, config))
        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(unprotectedPkg, config))
    }

    @Test
    fun gracePeriodImmediate_requiresAuthEvenAfterUnlock() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(
            enabled = true,
            lockedPackages = setOf(protectedPkg),
            gracePeriod = ReLockGracePeriod.IMMEDIATE,
        )

        engine.recordUnlock(protectedPkg)
        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(protectedPkg, config))
    }

    @Test
    fun gracePeriodOneMin_allowsUntilSixtySecondsAndExpiresAfter() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(
            enabled = true,
            lockedPackages = setOf(protectedPkg, otherProtectedPkg),
            gracePeriod = ReLockGracePeriod.ONE_MIN,
        )

        engine.recordUnlock(protectedPkg)
        clock.advanceBy(59_999L)
        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(protectedPkg, config))
        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(otherProtectedPkg, config))

        clock.advanceBy(1L)
        val result = engine.evaluateForegroundPackage(protectedPkg, config)
        assertEquals(LockDecision.REQUIRE_AUTH, result.decision)
        assertEquals("Re-lock grace window expired", result.reason)
    }

    @Test
    fun gracePeriodFiveMin_allowsUntilFiveMinutesAndExpiresAfter() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(
            enabled = true,
            lockedPackages = setOf(protectedPkg),
            gracePeriod = ReLockGracePeriod.FIVE_MIN,
        )

        engine.recordUnlock(protectedPkg)
        clock.advanceBy(4L * 60_000L + 59_999L)
        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(protectedPkg, config))

        clock.advanceBy(1L)
        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(protectedPkg, config))
    }

    @Test
    fun onScreenOff_immediatelyRelocksAllUnlockedApps() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(
            enabled = true,
            lockedPackages = setOf(protectedPkg, otherProtectedPkg),
            gracePeriod = ReLockGracePeriod.FIVE_MIN,
        )

        engine.recordUnlock(protectedPkg)
        engine.recordUnlock(otherProtectedPkg)
        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(protectedPkg, config))
        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(otherProtectedPkg, config))

        engine.onScreenOff()
        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(protectedPkg, config))
        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(otherProtectedPkg, config))
    }

    @Test
    fun ramPause_allowsForFifteenMinutes_expiresAndResetsOnProcessRestart() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(
            enabled = true,
            lockedPackages = setOf(protectedPkg),
        )

        engine.pauseAppLock(config)
        assertTrue(engine.isPaused())
        assertEquals(15L * 60_000L, engine.pauseRemainingMs())

        val pausedResult = engine.evaluateForegroundPackage(protectedPkg, config)
        assertEquals(LockDecision.ALLOW, pausedResult.decision)
        assertTrue(pausedResult.isPaused)

        clock.advanceBy(15L * 60_000L - 1L)
        assertTrue(engine.isPaused())
        assertEquals(LockDecision.ALLOW, engine.evaluateDecision(protectedPkg, config))

        clock.advanceBy(1L)
        assertFalse(engine.isPaused())
        assertEquals(0L, engine.pauseRemainingMs())
        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(protectedPkg, config))

        // Simulate RAM-only pause cleared on process restart (new engine instance).
        engine.pauseAppLock(config)
        assertTrue(engine.isPaused())
        val restartedEngine = AppLockPolicyEngine(clock)
        assertFalse(restartedEngine.isPaused())
        assertEquals(LockDecision.REQUIRE_AUTH, restartedEngine.evaluateDecision(protectedPkg, config))
    }

    @Test
    fun resumeAppLock_clearsPauseImmediately() {
        val clock = FakeClock()
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(enabled = true, lockedPackages = setOf(protectedPkg))

        engine.pauseAppLock(config)
        assertTrue(engine.isPaused())

        engine.resumeAppLock()
        assertFalse(engine.isPaused())
        assertEquals(LockDecision.REQUIRE_AUTH, engine.evaluateDecision(protectedPkg, config))
    }

    @Test
    fun pinLockout_appliesExponentialBackoffAfterFiveFailedAttemptsAndCapsAtMax() {
        val clock = FakeClock(nowMs = 10_000L)
        val engine = AppLockPolicyEngine(clock)
        val config = AppLockConfig(enabled = true, lockedPackages = setOf(protectedPkg))

        repeat(4) { attempt ->
            val state = engine.recordFailedPinAttempt(config)
            assertEquals(attempt + 1, state.failedAttempts)
            assertFalse(state.isLockedOut(clock.nowMs()))
            assertEquals(0L, state.remainingLockoutMs(clock.nowMs()))
        }

        // 5th attempt: 30s
        val fifth = engine.recordFailedPinAttempt(config)
        assertEquals(5, fifth.failedAttempts)
        assertTrue(engine.isPinLockedOut())
        assertEquals(30_000L, fifth.remainingLockoutMs(clock.nowMs()))

        // 6th attempt: 60s
        clock.advanceBy(30_000L)
        assertFalse(engine.isPinLockedOut())
        val sixth = engine.recordFailedPinAttempt(config)
        assertEquals(60_000L, sixth.remainingLockoutMs(clock.nowMs()))

        // 7th attempt: 120s
        clock.advanceBy(60_000L)
        val seventh = engine.recordFailedPinAttempt(config)
        assertEquals(120_000L, seventh.remainingLockoutMs(clock.nowMs()))

        // Advance to 10th attempt: 30s * 2^5 = 960s, capped at 900s (15 min)
        engine.recordFailedPinAttempt(config) // 8th
        engine.recordFailedPinAttempt(config) // 9th
        val tenth = engine.recordFailedPinAttempt(config) // 10th
        assertEquals(AppLockConfig.DEFAULT_MAX_LOCKOUT_MS, tenth.remainingLockoutMs(clock.nowMs()))

        // Success clears lockout
        engine.recordPinSuccess()
        assertFalse(engine.isPinLockedOut())
        assertEquals(0, engine.currentLockoutState().failedAttempts)
    }

    @Test
    fun tamperProtection_recommendsLockingSettingsWhenEnabledAndUnprotected() {
        val engine = AppLockPolicyEngine()

        val disabledStatus = engine.evaluateTamperProtection(AppLockConfig(enabled = false))
        assertFalse(disabledStatus.isSettingsLocked)
        assertFalse(disabledStatus.isRecommended)

        val unprotectedStatus = engine.evaluateTamperProtection(
            AppLockConfig(enabled = true, lockedPackages = setOf(protectedPkg)),
        )
        assertFalse(unprotectedStatus.isSettingsLocked)
        assertTrue(unprotectedStatus.isRecommended)
        assertEquals(AppLockConfig.SETTINGS_PACKAGE_NAME, unprotectedStatus.targetPackage)

        val protectedByFlag = AppLockConfig(
            enabled = true,
            lockedPackages = setOf(protectedPkg),
            tamperProtectionEnabled = true,
        )
        val flagStatus = engine.evaluateTamperProtection(protectedByFlag)
        assertTrue(flagStatus.isSettingsLocked)
        assertFalse(flagStatus.isRecommended)
        assertEquals(
            LockDecision.REQUIRE_AUTH,
            engine.evaluateDecision(AppLockConfig.SETTINGS_PACKAGE_NAME, protectedByFlag),
        )

        val protectedBySet = engine.evaluateTamperProtection(
            AppLockConfig(enabled = true, lockedPackages = setOf(AppLockConfig.SETTINGS_PACKAGE_NAME)),
        )
        assertTrue(protectedBySet.isSettingsLocked)
        assertFalse(protectedBySet.isRecommended)
    }
}
