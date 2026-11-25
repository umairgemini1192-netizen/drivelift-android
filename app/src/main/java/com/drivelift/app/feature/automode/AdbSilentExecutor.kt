package com.drivelift.app.feature.automode

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationCommandBuilder
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.SleepCommandExecutor
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Runtime readiness of DriveLift's Built-in Auto Mode.
 */
enum class AutoModeAvailability {
    /** Paired, bootstrapped, and currently on Wi-Fi: ready to run silently. */
    READY,

    /** Paired and bootstrapped, but phone is not on Wi-Fi right now. */
    NEEDS_WIFI,

    /** Paired, but Step 3 one-time bootstrap still needs to finish. */
    BOOTSTRAP_INCOMPLETE,

    /** Not yet set up (or turned off by owner). */
    SETUP_REQUIRED,
}

/**
 * Snapshot of Auto Mode status and the effective execution mode for Cleaner and Sleep Mode.
 */
data class AutoModeStatusSnapshot(
    val availability: AutoModeAvailability,
    val effectiveMode: HibernationMode,
    val detail: String,
    val isConfigured: Boolean = availability == AutoModeAvailability.READY ||
        availability == AutoModeAvailability.NEEDS_WIFI,
)

/**
 * Result of attempting to run a batch of Cleaner or Sleep actions through Built-in Auto Mode.
 */
sealed interface AutoSessionOutcome<out T> {
    data class Completed<T>(val value: T) : AutoSessionOutcome<T>
    data class FallbackToGuided(val reason: String) : AutoSessionOutcome<Nothing>
}

/**
 * Built-in Auto Mode ADB executor implementing [SleepCommandExecutor] without Shizuku or any
 * external helper app.
 *
 * Manages:
 * - One-time pairing & bootstrap (`pm grant <appId> android.permission.WRITE_SECURE_SETTINGS` and
 *   `settings put global adb_allowed_connection_time 0`)
 * - Fully automatic Clean/Sleep runs on Wi-Fi: enables `adb_wifi_enabled = 1` via
 *   `WRITE_SECURE_SETTINGS`, discovers the localhost TLS connect port, connects strictly to
 *   `127.0.0.1` with DriveLift's Keystore-protected key pair, executes validated commands with
 *   exit-code checking, and ALWAYS restores `adb_wifi_enabled` to its prior value when done.
 * - Deterministic fallback to Guided Pill with a plain-English reason whenever not on Wi-Fi or
 *   localhost connect fails.
 */
class AdbSilentExecutor(
    private val transport: AdbTransport,
    private val systemGateway: AutoModeSystemGateway,
    private val keyStorage: AdbKeyStorage,
    private val preferences: AutoModePreferences,
    private val protectedPackagesProvider: () -> Set<String> = { emptySet() },
    private val discoveryRetryDelayMs: Long = DEFAULT_DISCOVERY_RETRY_DELAY_MS,
    private val maxConnectDiscoveryAttempts: Int = DEFAULT_CONNECT_DISCOVERY_ATTEMPTS,
) : SleepCommandExecutor {

    /**
     * Evaluates Built-in Auto Mode readiness without throwing.
     */
    fun inspectStatus(): AutoModeStatusSnapshot {
        val hasKey = runCatching { keyStorage.hasKeyPair() }.getOrDefault(false)
        val hasWriteSecure = runCatching { systemGateway.hasWriteSecureSettings() }.getOrDefault(false)
        val onWifi = runCatching { systemGateway.isOnWifi() }.getOrDefault(false)

        if (!preferences.isEnabled || !preferences.isPaired || !hasKey) {
            return AutoModeStatusSnapshot(
                availability = AutoModeAvailability.SETUP_REQUIRED,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = "Turn on built-in Auto Mode once to clean and sleep apps without prompts.",
                isConfigured = false,
            )
        }

        if (!preferences.isBootstrapComplete || !hasWriteSecure) {
            return AutoModeStatusSnapshot(
                availability = AutoModeAvailability.BOOTSTRAP_INCOMPLETE,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = "Finish one-time Auto Mode setup to run Clean and Sleep automatically.",
                isConfigured = false,
            )
        }

        if (!onWifi) {
            return AutoModeStatusSnapshot(
                availability = AutoModeAvailability.NEEDS_WIFI,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = FALLBACK_REASON_NO_WIFI,
                isConfigured = true,
            )
        }

        return AutoModeStatusSnapshot(
            availability = AutoModeAvailability.READY,
            effectiveMode = HibernationMode.AUTO_SILENT,
            detail = "Auto Mode is on. Runs silently on Wi-Fi and turns Wireless debugging back off when done.",
            isConfigured = true,
        )
    }

    fun environmentSnapshot(): AutoModeEnvironmentSnapshot = AutoModeEnvironmentSnapshot(
        isDeveloperOptionsEnabled = runCatching { systemGateway.isDeveloperOptionsEnabled() }.getOrDefault(false),
        isOnWifi = runCatching { systemGateway.isOnWifi() }.getOrDefault(false),
        hasStoredKeyPair = runCatching { keyStorage.hasKeyPair() }.getOrDefault(false),
        isPaired = preferences.isPaired,
        hasWriteSecureSettings = runCatching { systemGateway.hasWriteSecureSettings() }.getOrDefault(false),
        isBootstrapComplete = preferences.isBootstrapComplete,
        isAutoModeEnabled = preferences.isEnabled,
    )

    /**
     * Step 2: Pair with Android's Wireless debugging service on `127.0.0.1` using a 6-digit code.
     * Never logs or persists [pairingCode].
     */
    suspend fun pairWithCode(pairingCode: String, explicitPort: Int? = null): Result<Unit> {
        if (!systemGateway.isOnWifi()) {
            return Result.failure(IllegalStateException("Connect to Wi-Fi before pairing Wireless debugging."))
        }
        val normalized = pairingCode.filter { it in '0'..'9' }
        if (normalized.length != AutoModeWizardStateMachine.PAIRING_CODE_LENGTH) {
            return Result.failure(IllegalArgumentException("Enter the 6-digit pairing code shown in Wireless debugging."))
        }

        // Ensure DriveLift's RSA key pair exists before pairing.
        runCatching { keyStorage.loadOrCreateKeyPair() }
            .onFailure { return Result.failure(it) }

        val port = explicitPort ?: transport.discoverPairingPort().getOrElse { err ->
            return Result.failure(
                IOException("Could not find the Wireless debugging pairing screen. Keep 'Pair device with pairing code' open and try again.", err),
            )
        }

        return transport.pair(port, normalized).onSuccess {
            preferences.isPaired = true
            preferences.isEnabled = true
        }
    }

    /**
     * Step 3: After first pairing, connect once to `127.0.0.1`, grant `WRITE_SECURE_SETTINGS`,
     * and set `adb_allowed_connection_time = 0` so authorization never silently expires.
     */
    suspend fun runOneTimeBootstrap(): Result<Unit> {
        if (!systemGateway.isOnWifi()) {
            return Result.failure(IllegalStateException("Connect to Wi-Fi to finish Auto Mode setup."))
        }
        val appId = runCatching {
            HibernationCommandBuilder.validatePackageName(systemGateway.applicationId())
        }.getOrElse { return Result.failure(it) }

        val connectResult = connectWithDiscoveryRetries()
        if (connectResult.isFailure) {
            return Result.failure(
                connectResult.exceptionOrNull()
                    ?: IOException("Could not connect to Wireless debugging to finish setup."),
            )
        }

        return try {
            val grantCmd = buildGrantWriteSecureSettingsCommand(appId)
            executeCheckedShell(grantCmd).getOrThrow()

            val timeoutCmd = buildDisableAdbTimeoutCommand()
            executeCheckedShell(timeoutCmd).getOrThrow()

            preferences.isPaired = true
            preferences.isBootstrapComplete = true
            preferences.isEnabled = true
            preferences.storedHibernationMode = HibernationMode.AUTO_SILENT
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { transport.disconnect() }
        }
    }

    /**
     * Turns off Built-in Auto Mode, deletes DriveLift's Keystore-protected RSA key pair, and clears
     * persisted flags. Android does not allow an app to revoke WRITE_SECURE_SETTINGS from itself,
     * so DriveLift stops using it immediately.
     */
    suspend fun turnOffAutoMode() {
        runCatching { transport.disconnect() }
        (transport as? LibAdbTransport)?.resetSession()
        keyStorage.deleteKeyPair()
        preferences.clearAutoModeState()
    }

    /**
     * Runs a whole Clean or Sleep batch inside a single automatic localhost ADB session.
     *
     * Lifecycle:
     * 1. Verifies Auto Mode is configured and device is on Wi-Fi.
     * 2. Reads prior `adb_wifi_enabled`, enables it if it was off.
     * 3. Discovers `_adb-tls-connect._tcp` and connects strictly to `127.0.0.1`.
     * 4. Executes [block] against the live session.
     * 5. In `finally`, disconnects AND restores `adb_wifi_enabled` to its prior value so the
     *    Wireless debugging port is never left open when DriveLift turned it on.
     */
    suspend fun <T> runWithAutoSession(
        block: suspend (AdbSilentExecutor) -> T,
    ): AutoSessionOutcome<T> {
        val status = inspectStatus()
        if (!status.isConfigured) {
            return AutoSessionOutcome.FallbackToGuided(status.detail)
        }
        if (status.availability == AutoModeAvailability.NEEDS_WIFI) {
            return AutoSessionOutcome.FallbackToGuided(FALLBACK_REASON_NO_WIFI)
        }

        val previousAdbWifiEnabled = runCatching { systemGateway.isAdbWifiEnabled() }.getOrDefault(false)
        var toggledOnByDriveLift = false

        if (!previousAdbWifiEnabled) {
            val turnedOn = runCatching { systemGateway.setAdbWifiEnabled(true) }.getOrDefault(false)
            if (!turnedOn) {
                return AutoSessionOutcome.FallbackToGuided(
                    "Could not turn on Wireless debugging automatically - using guided steps this time",
                )
            }
            toggledOnByDriveLift = true
        }

        try {
            val connectResult = connectWithDiscoveryRetries()
            if (connectResult.isFailure) {
                val errMsg = connectResult.exceptionOrNull()?.message
                val reason = if (errMsg.isNullOrBlank()) {
                    FALLBACK_REASON_CONNECT_FAILED
                } else {
                    "$FALLBACK_REASON_CONNECT_FAILED ($errMsg)"
                }
                return AutoSessionOutcome.FallbackToGuided(reason)
            }
            val value = block(this)
            return AutoSessionOutcome.Completed(value)
        } finally {
            runCatching { transport.disconnect() }
            if (toggledOnByDriveLift) {
                runCatching { systemGateway.setAdbWifiEnabled(previousAdbWifiEnabled) }
            }
        }
    }

    /**
     * Implements [SleepCommandExecutor] for [com.drivelift.app.feature.hibernation.engine.SleepSessionStateMachine].
     * Validates the package name and protection rules, then executes `am force-stop <pkg>`.
     *
     * If called outside an active [runWithAutoSession], opens and restores a scoped session
     * automatically.
     */
    override suspend fun sleepApp(candidate: AppDrainCandidate): Result<List<String>> {
        val command = runCatching {
            buildForceStopCommand(
                packageName = candidate.packageName,
                protectedPackages = protectedPackagesProvider(),
            )
        }.getOrElse { return Result.failure(it) }

        if (transport.isConnected()) {
            return executeCheckedShell(command).map { listOf(command) }
        }

        return when (val outcome = runWithAutoSession { executeCheckedShell(command).map { listOf(command) } }) {
            is AutoSessionOutcome.Completed -> outcome.value
            is AutoSessionOutcome.FallbackToGuided -> Result.failure(IllegalStateException(outcome.reason))
        }
    }

    /**
     * Trims application caches across the device via `pm trim-caches 999999999999`.
     * Uses the active ADB session if already connected, or opens and restores a scoped session.
     */
    suspend fun trimCachesSilent(): Result<String> {
        val command = CMD_TRIM_CACHES
        if (transport.isConnected()) {
            return executeCheckedShell(command).map { command }
        }

        return when (val outcome = runWithAutoSession { executeCheckedShell(command).map { command } }) {
            is AutoSessionOutcome.Completed -> outcome.value
            is AutoSessionOutcome.FallbackToGuided -> Result.failure(IllegalStateException(outcome.reason))
        }
    }

    private suspend fun executeCheckedShell(command: String): Result<AdbCommandResult> {
        val raw = transport.runShell(command)
        val result = raw.getOrElse { return Result.failure(it) }
        if (!result.isSuccess) {
            val snippet = result.stdout.lines().firstOrNull { it.isNotBlank() }?.take(120)
            val detail = if (snippet.isNullOrBlank()) {
                "ADB command exited with code ${result.exitCode}: $command"
            } else {
                "ADB command exited with code ${result.exitCode}: $snippet"
            }
            return Result.failure(IOException(detail))
        }
        return Result.success(result)
    }

    private suspend fun connectWithDiscoveryRetries(): Result<Unit> {
        var lastError: Throwable? = null
        val attempts = maxConnectDiscoveryAttempts.coerceAtLeast(1)
        for (attempt in 1..attempts) {
            val portResult = transport.discoverConnectPort()
            val port = portResult.getOrNull()
            if (port != null) {
                val connected = transport.connect(port)
                if (connected.isSuccess) {
                    return Result.success(Unit)
                }
                lastError = connected.exceptionOrNull()
            } else {
                lastError = portResult.exceptionOrNull()
            }
            if (attempt < attempts && discoveryRetryDelayMs > 0L) {
                delay(discoveryRetryDelayMs)
            }
        }
        return Result.failure(lastError ?: IOException("Timed out connecting to localhost ADB."))
    }

    companion object {
        const val CMD_TRIM_CACHES = "pm trim-caches 999999999999"
        const val FALLBACK_REASON_NO_WIFI = "Auto Mode needs Wi-Fi - using guided steps this time"
        const val FALLBACK_REASON_CONNECT_FAILED = "Could not connect to Auto Mode - using guided steps this time"

        const val DEFAULT_DISCOVERY_RETRY_DELAY_MS = 600L
        const val DEFAULT_CONNECT_DISCOVERY_ATTEMPTS = 3

        fun buildForceStopCommand(
            packageName: String,
            protectedPackages: Set<String> = emptySet(),
        ): String {
            val specs = HibernationCommandBuilder.buildSleepSequence(
                packageName = packageName,
                options = com.drivelift.app.feature.hibernation.engine.HibernationCommandOptions(
                    restrictBackgroundAppOp = false,
                    setInactive = false,
                    forceStop = true,
                    suspendPackage = false,
                ),
                protectedPackages = protectedPackages,
            )
            return specs.single().commandLine
        }

        fun buildGrantWriteSecureSettingsCommand(applicationId: String): String {
            val validAppId = HibernationCommandBuilder.validatePackageName(applicationId)
            return "pm grant $validAppId android.permission.WRITE_SECURE_SETTINGS"
        }

        fun buildDisableAdbTimeoutCommand(): String =
            "settings put global ${AndroidAutoModeSystemGateway.SETTING_ADB_ALLOWED_CONNECTION_TIME} 0"

        /**
         * Builds an Android App Info settings intent for Guided Clean and Guided Sleep fallback steps.
         */
        fun createAppInfoIntent(packageName: String): Intent {
            val safePackage = HibernationCommandBuilder.validatePackageName(packageName)
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", safePackage, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
