package com.drivelift.app.feature.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeWizardStateMachineTest {

    private val machine = AutoModeWizardStateMachine()

    @Test
    fun startsAtStep1WhenDeveloperOptionsAreOffAndAdvancesOnResume() {
        val envOff = AutoModeEnvironmentSnapshot(
            isDeveloperOptionsEnabled = false,
            isOnWifi = true,
            hasStoredKeyPair = false,
            isPaired = false,
            hasWriteSecureSettings = false,
            isBootstrapComplete = false,
            isAutoModeEnabled = false,
        )
        val step1 = machine.onEnvironmentUpdated(AutoModeWizardState(), envOff)
        assertEquals(AutoModeWizardStep.ENABLE_DEVELOPER_OPTIONS, step1.step)
        assertFalse(step1.canStartPairing)

        val envDevOn = envOff.copy(isDeveloperOptionsEnabled = true)
        val step2 = machine.onEnvironmentUpdated(step1, envDevOn)
        assertEquals(AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING, step2.step)
        assertTrue(step2.canStartPairing)
    }

    @Test
    fun requiresWifiForStep2PairingAndNotification() {
        val envNoWifi = AutoModeEnvironmentSnapshot(
            isDeveloperOptionsEnabled = true,
            isOnWifi = false,
            hasStoredKeyPair = false,
            isPaired = false,
            hasWriteSecureSettings = false,
            isBootstrapComplete = false,
            isAutoModeEnabled = false,
        )
        val step2 = machine.onEnvironmentUpdated(AutoModeWizardState(), envNoWifi)
        assertEquals(AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING, step2.step)
        assertFalse(step2.canStartPairing)

        val blocked = machine.onPairingNotificationPosted(step2)
        assertFalse(blocked.pairingNotificationPosted)
        assertNotNull(blocked.errorMessage)

        val withWifi = machine.onEnvironmentUpdated(blocked, envNoWifi.copy(isOnWifi = true))
        assertTrue(withWifi.canStartPairing)
        val posted = machine.onPairingNotificationPosted(withWifi)
        assertTrue(posted.pairingNotificationPosted)
        assertNull(posted.errorMessage)
    }

    @Test
    fun sanitizesSixDigitPairingCodeStrictly() {
        assertEquals("123456", machine.sanitizePairingCode("123456"))
        assertEquals("009876", machine.sanitizePairingCode(" 009-876 "))
        assertNull(machine.sanitizePairingCode("12345"))
        assertNull(machine.sanitizePairingCode("1234567"))
        assertNull(machine.sanitizePairingCode("abcdef"))
        assertNull(machine.sanitizePairingCode(""))
    }

    @Test
    fun transitionsFromPairingThroughStep3ToGreenReadyState() {
        val env = AutoModeEnvironmentSnapshot(
            isDeveloperOptionsEnabled = true,
            isOnWifi = true,
            hasStoredKeyPair = false,
            isPaired = false,
            hasWriteSecureSettings = false,
            isBootstrapComplete = false,
            isAutoModeEnabled = false,
        )
        var state = machine.onEnvironmentUpdated(AutoModeWizardState(), env)
        state = machine.onPairingStarted(state)
        assertTrue(state.isWorking)

        state = machine.onPairingSucceeded(state)
        assertEquals(AutoModeWizardStep.GRANT_PERMISSIONS_AND_VERIFY, state.step)
        assertTrue(state.isPaired)
        assertTrue(state.isWorking)

        state = machine.onBootstrapSucceeded(state)
        assertEquals(AutoModeWizardStep.READY, state.step)
        assertTrue(state.isReady)
        assertFalse(state.isWorking)
        assertEquals("Auto Mode is on", state.statusMessage)
    }

    @Test
    fun staysReadyAcrossRebootEvenIfDeveloperOptionsFlagToggles() {
        val envAfterReboot = AutoModeEnvironmentSnapshot(
            isDeveloperOptionsEnabled = true,
            isOnWifi = false,
            hasStoredKeyPair = true,
            isPaired = true,
            hasWriteSecureSettings = true,
            isBootstrapComplete = true,
            isAutoModeEnabled = true,
        )
        val state = machine.onEnvironmentUpdated(AutoModeWizardState(), envAfterReboot)
        assertEquals(AutoModeWizardStep.READY, state.step)
        assertTrue(state.isReady)
    }

    @Test
    fun turnOffResetsWizardAndExplainsWriteSecureSettingsLimitation() {
        val ready = AutoModeWizardState(
            step = AutoModeWizardStep.READY,
            isDeveloperOptionsEnabled = true,
            isOnWifi = true,
            hasStoredKeyPair = true,
            isPaired = true,
            hasWriteSecureSettings = true,
            isBootstrapComplete = true,
            statusMessage = "Auto Mode is on",
        )
        val envAfterReset = AutoModeEnvironmentSnapshot(
            isDeveloperOptionsEnabled = true,
            isOnWifi = true,
            hasStoredKeyPair = false,
            isPaired = false,
            hasWriteSecureSettings = true,
            isBootstrapComplete = false,
            isAutoModeEnabled = false,
        )
        val turnedOff = machine.onTurnedOff(ready, envAfterReset)
        assertEquals(AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING, turnedOff.step)
        assertFalse(turnedOff.isPaired)
        assertFalse(turnedOff.hasStoredKeyPair)
        assertFalse(turnedOff.isBootstrapComplete)
        assertEquals(AutoModeWizardStateMachine.TURN_OFF_EXPLANATION, turnedOff.turnOffNotice)
        assertTrue(turnedOff.turnOffNotice!!.contains("WRITE_SECURE_SETTINGS"))
    }
}
