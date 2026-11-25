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
    val notificationAccess: NotificationAccessState = NotificationAccessState.ALLOWED,
    val keepWirelessDebuggingOn: Boolean = false,
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
    val notificationAccess: NotificationAccessState = NotificationAccessState.ALLOWED,
    val keepWirelessDebuggingOn: Boolean = false,
    val isDiscoveringPairingService: Boolean = false,
    val discoveredPairingPort: Int? = null,
    val discoveryErrorMessage: String? = null,
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
        val stillInStep2 = nextStep == AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING
        return current.copy(
            step = nextStep,
            isDeveloperOptionsEnabled = env.isDeveloperOptionsEnabled,
            isOnWifi = env.isOnWifi,
            hasStoredKeyPair = env.hasStoredKeyPair,
            isPaired = env.isPaired,
            hasWriteSecureSettings = env.hasWriteSecureSettings,
            isBootstrapComplete = env.isBootstrapComplete,
            notificationAccess = env.notificationAccess,
            keepWirelessDebuggingOn = env.keepWirelessDebuggingOn,
            isDiscoveringPairingService = if (stillInStep2 && env.isOnWifi) current.isDiscoveringPairingService else false,
            discoveredPairingPort = if (stillInStep2 && env.isOnWifi) current.discoveredPairingPort else null,
            discoveryErrorMessage = if (stillInStep2 && env.isOnWifi) current.discoveryErrorMessage else null,
            errorMessage = clearedError,
            turnOffNotice = clearedNotice,
            pairingNotificationPosted = if (stillInStep2) {
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

    /**
     * Validates an optional Wireless debugging pairing port entered in the in-app fallback field.
     * Blank input returns `Result.success(null)` (meaning auto-discover via NsdManager).
     */
    fun validateOptionalPairingPort(rawInput: String): Result<Int?> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return Result.success(null)
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.length != trimmed.length) {
            return Result.failure(IllegalArgumentException("Pairing port must contain digits only (1 to 65535)."))
        }
        val port = digits.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return Result.failure(IllegalArgumentException("Enter a valid pairing port between 1 and 65535."))
        }
        return Result.success(port)
    }

    fun onPairingNotificationPosted(current: AutoModeWizardState): AutoModeWizardState {
        if (!current.isOnWifi) {
            return current.copy(
                pairingNotificationPosted = false,
                errorMessage = "Connect to Wi-Fi first before pairing Wireless debugging.",
            )
        }
        if (current.notificationAccess != NotificationAccessState.ALLOWED) {
            return onPairingNotificationBlocked(current, current.notificationAccess)
        }
        return current.copy(
            pairingNotificationPosted = true,
            errorMessage = null,
            statusMessage = "Pairing notification posted. Open Wireless debugging and reply with the 6-digit code.",
        )
    }

    fun onPairingNotificationBlocked(
        current: AutoModeWizardState,
        access: NotificationAccessState,
    ): AutoModeWizardState = current.copy(
        pairingNotificationPosted = false,
        notificationAccess = access,
        errorMessage = AdbPairingNotifier.BLOCKED_EXPLANATION,
    )

    fun onPairingDiscoveryStarted(current: AutoModeWizardState): AutoModeWizardState = current.copy(
        isDiscoveringPairingService = true,
        discoveredPairingPort = null,
        discoveryErrorMessage = null,
    )

    fun onPairingDiscoveryFound(current: AutoModeWizardState, port: Int): AutoModeWizardState = current.copy(
        isDiscoveringPairingService = false,
        discoveredPairingPort = port,
        discoveryErrorMessage = null,
    )

    fun onPairingDiscoveryTimedOut(
        current: AutoModeWizardState,
        message: String = AdbSilentExecutor.PAIRING_DISCOVERY_TIMEOUT_MESSAGE,
    ): AutoModeWizardState = current.copy(
        isDiscoveringPairingService = false,
        discoveredPairingPort = null,
        discoveryErrorMessage = message.ifBlank { AdbSilentExecutor.PAIRING_DISCOVERY_TIMEOUT_MESSAGE },
    )

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
