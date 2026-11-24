package com.drivelift.app.feature.automode

/**
 * Deterministic one-time setup steps for DriveLift's Built-in Auto Mode.
 */
enum class AutoModeWizardStep {
    /** Step 1: Developer options are off; guide owner to About phone to tap Build number 7 times. */
    ENABLE_DEVELOPER_OPTIONS,

    /** Step 2: Require Wi-Fi and pair with Wireless debugging using a 6-digit pairing code. */
    PAIR_WIRELESS_DEBUGGING,

    /** Step 3: Connect once to grant WRITE_SECURE_SETTINGS and disable ADB authorization timeout. */
    GRANT_PERMISSIONS_AND_VERIFY,

    /** Setup complete: green "Auto Mode is on" state. */
    READY,
}

/**
 * Snapshot of live Android environment inputs consumed by [AutoModeWizardStateMachine].
 */
data class AutoModeEnvironmentSnapshot(
    val isDeveloperOptionsEnabled: Boolean,
    val isOnWifi: Boolean,
    val hasStoredKeyPair: Boolean,
    val isPaired: Boolean,
    val hasWriteSecureSettings: Boolean,
    val isBootstrapComplete: Boolean,
    val isAutoModeEnabled: Boolean,
)

/**
 * Immutable UI state for the Built-in Auto Mode setup wizard.
 */
data class AutoModeWizardState(
    val step: AutoModeWizardStep = AutoModeWizardStep.ENABLE_DEVELOPER_OPTIONS,
    val isDeveloperOptionsEnabled: Boolean = false,
    val isOnWifi: Boolean = false,
    val hasStoredKeyPair: Boolean = false,
    val isPaired: Boolean = false,
    val hasWriteSecureSettings: Boolean = false,
    val isBootstrapComplete: Boolean = false,
    val isWorking: Boolean = false,
    val pairingNotificationPosted: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val turnOffNotice: String? = null,
) {
    val isReady: Boolean
        get() = step == AutoModeWizardStep.READY

    val canStartPairing: Boolean
        get() = step == AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING && isOnWifi && !isWorking
}

/**
 * Pure JVM state machine governing the Built-in Auto Mode one-time setup wizard.
 */
class AutoModeWizardStateMachine {

    /**
     * Computes the exact step from persisted and live environment flags.
     */
    fun evaluateStep(env: AutoModeEnvironmentSnapshot): AutoModeWizardStep {
        val fullyReady = env.isAutoModeEnabled &&
            env.hasStoredKeyPair &&
            env.isPaired &&
            env.hasWriteSecureSettings &&
            env.isBootstrapComplete
        if (fullyReady) return AutoModeWizardStep.READY

        if (!env.isDeveloperOptionsEnabled) {
            return AutoModeWizardStep.ENABLE_DEVELOPER_OPTIONS
        }
        if (!env.isPaired || !env.hasStoredKeyPair) {
            return AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING
        }
        if (!env.hasWriteSecureSettings || !env.isBootstrapComplete) {
            return AutoModeWizardStep.GRANT_PERMISSIONS_AND_VERIFY
        }
        return AutoModeWizardStep.READY
    }

    /**
     * Reconciles the wizard state whenever Android Settings, Wi-Fi, or resume events change.
     */
    fun onEnvironmentUpdated(
        current: AutoModeWizardState,
        env: AutoModeEnvironmentSnapshot,
    ): AutoModeWizardState {
        val nextStep = evaluateStep(env)
        val clearedError = if (nextStep != current.step) null else current.errorMessage
        val clearedNotice = if (nextStep == AutoModeWizardStep.READY) null else current.turnOffNotice
        return current.copy(
            step = nextStep,
            isDeveloperOptionsEnabled = env.isDeveloperOptionsEnabled,
            isOnWifi = env.isOnWifi,
            hasStoredKeyPair = env.hasStoredKeyPair,
            isPaired = env.isPaired,
            hasWriteSecureSettings = env.hasWriteSecureSettings,
            isBootstrapComplete = env.isBootstrapComplete,
            errorMessage = clearedError,
            turnOffNotice = clearedNotice,
            pairingNotificationPosted = if (nextStep == AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING) {
                current.pairingNotificationPosted
            } else {
                false
            },
            statusMessage = when {
                current.isWorking -> current.statusMessage
                nextStep == AutoModeWizardStep.READY -> "Auto Mode is on"
                else -> current.statusMessage
            },
        )
    }

    /**
     * Validates a 6-digit Wireless Debugging pairing code without logging or retaining it.
     * Returns the normalized 6-digit string or null if invalid.
     */
    fun sanitizePairingCode(rawInput: String): String? {
        val digits = rawInput.filter { it in '0'..'9' }
        return if (digits.length == PAIRING_CODE_LENGTH) digits else null
    }

    fun onPairingNotificationPosted(current: AutoModeWizardState): AutoModeWizardState {
        if (!current.isOnWifi) {
            return current.copy(
                pairingNotificationPosted = false,
                errorMessage = "Connect to Wi-Fi first before pairing Wireless debugging.",
            )
        }
        return current.copy(
            pairingNotificationPosted = true,
            errorMessage = null,
            statusMessage = "Pairing notification posted. Open Wireless debugging and reply with the 6-digit code.",
        )
    }

    fun onPairingStarted(current: AutoModeWizardState): AutoModeWizardState = current.copy(
        isWorking = true,
        errorMessage = null,
        turnOffNotice = null,
        statusMessage = "Pairing with local Wireless debugging...",
    )

    fun onPairingSucceeded(current: AutoModeWizardState): AutoModeWizardState = current.copy(
        step = AutoModeWizardStep.GRANT_PERMISSIONS_AND_VERIFY,
        hasStoredKeyPair = true,
        isPaired = true,
        isWorking = true,
        pairingNotificationPosted = false,
        errorMessage = null,
        statusMessage = "Paired. Finishing one-time permission setup...",
    )

    fun onPairingFailed(current: AutoModeWizardState, reason: String): AutoModeWizardState = current.copy(
        isWorking = false,
        errorMessage = reason.ifBlank { "Pairing failed. Check the 6-digit code and try again." },
        statusMessage = null,
    )

    fun onBootstrapStarted(current: AutoModeWizardState): AutoModeWizardState = current.copy(
        step = AutoModeWizardStep.GRANT_PERMISSIONS_AND_VERIFY,
        isWorking = true,
        errorMessage = null,
        statusMessage = "Connecting once to grant Auto Mode permissions...",
    )

    fun onBootstrapSucceeded(current: AutoModeWizardState): AutoModeWizardState = current.copy(
        step = AutoModeWizardStep.READY,
        hasStoredKeyPair = true,
        isPaired = true,
        hasWriteSecureSettings = true,
        isBootstrapComplete = true,
        isWorking = false,
        pairingNotificationPosted = false,
        errorMessage = null,
        turnOffNotice = null,
        statusMessage = "Auto Mode is on",
    )

    fun onBootstrapFailed(current: AutoModeWizardState, reason: String): AutoModeWizardState = current.copy(
        step = AutoModeWizardStep.GRANT_PERMISSIONS_AND_VERIFY,
        isWorking = false,
        errorMessage = reason.ifBlank { "Could not finish one-time setup. Make sure Wireless debugging is on and retry." },
        statusMessage = null,
    )

    fun onTurnedOff(
        current: AutoModeWizardState,
        envAfterReset: AutoModeEnvironmentSnapshot,
    ): AutoModeWizardState {
        val nextStep = evaluateStep(envAfterReset)
        return current.copy(
            step = nextStep,
            isDeveloperOptionsEnabled = envAfterReset.isDeveloperOptionsEnabled,
            isOnWifi = envAfterReset.isOnWifi,
            hasStoredKeyPair = false,
            isPaired = false,
            hasWriteSecureSettings = envAfterReset.hasWriteSecureSettings,
            isBootstrapComplete = false,
            isWorking = false,
            pairingNotificationPosted = false,
            statusMessage = null,
            errorMessage = null,
            turnOffNotice = TURN_OFF_EXPLANATION,
        )
    }

    companion object {
        const val PAIRING_CODE_LENGTH = 6
        const val TURN_OFF_EXPLANATION =
            "Auto Mode is off and DriveLift deleted its pairing key pair. Android does not allow an app " +
                "to revoke WRITE_SECURE_SETTINGS from itself, so DriveLift simply stops using it."
    }
}
