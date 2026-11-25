package com.drivelift.app.feature.automode

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationCommandBuilder
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.SleepCommandExecutor
import com.drivelift.app.feature.hibernation.engine.SleepCommandTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val pairingDiscoveryTimeoutMs: Long = AdbTransport.PAIRING_DISCOVERY_TIMEOUT_MS,
) : SleepCommandExecutor {

    private val activeAutoSession = AtomicBoolean(false)
    private val sessionReconnectCount = AtomicInteger(0)

    @Volatile
    var lastSessionTurnedOffWirelessDebugging: Boolean = false
        private set

    var keepWirelessDebuggingOn: Boolean
        get() = preferences.keepWirelessDebuggingOn
        set(value) {
            preferences.keepWirelessDebuggingOn = value
        }

    /**
     * Startup crash-recovery check: if a previous Auto Mode run crashed before its finally block
     * could close the debug port, turn Wireless debugging off and clear the persisted session flag.
     */
    fun recoverInterruptedSessionIfNeeded(): Boolean =
        recoverInterruptedSession(preferences, systemGateway)

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

        val readyDetail = if (preferences.keepWirelessDebuggingOn) {
            "Auto Mode is on. Runs silently on Wi-Fi and leaves Wireless debugging on after runs."
        } else {
            "Auto Mode is on. Runs silently on Wi-Fi and turns Wireless debugging back off when done."
        }
        return AutoModeStatusSnapshot(
            availability = AutoModeAvailability.READY,
            effectiveMode = HibernationMode.AUTO_SILENT,
            detail = readyDetail,
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
        notificationAccess = runCatching { systemGateway.notificationAccessState() }
            .getOrDefault(NotificationAccessState.ALLOWED),
        keepWirelessDebuggingOn = preferences.keepWirelessDebuggingOn,
    )

    /**
     * Discovers `_adb-tls-pairing._tcp` on this phone with a 60 s default timeout and returns a
     * plain-English error if nothing is found.
     */
    suspend fun discoverPairingPortWithTimeout(
        timeoutMs: Long = pairingDiscoveryTimeoutMs,
    ): Result<Int> {
        if (!systemGateway.isOnWifi()) {
            return Result.failure(IllegalStateException("Connect to Wi-Fi before pairing Wireless debugging."))
        }
        return transport.discoverPairingPort(timeoutMs).fold(
            onSuccess = { port -> Result.success(port) },
            onFailure = { err -> Result.failure(IOException(PAIRING_DISCOVERY_TIMEOUT_MESSAGE, err)) },
        )
    }

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
        if (explicitPort != null && explicitPort !in 1..65535) {
            return Result.failure(IllegalArgumentException("Enter a valid pairing port between 1 and 65535."))
        }

        // Ensure DriveLift's RSA key pair exists before pairing.
        runCatching { keyStorage.loadOrCreateKeyPair() }
            .onFailure { return Result.failure(it) }

        val port = explicitPort ?: transport.discoverPairingPort(pairingDiscoveryTimeoutMs).getOrElse { err ->
            return Result.failure(IOException(PAIRING_DISCOVERY_TIMEOUT_MESSAGE, err))
        }

        return transport.pair(port, normalized).onSuccess {
            preferences.isPaired = true
            preferences.isEnabled = true
        }
    }

    /**
     * Step 3: After first pairing, connect once to `127.0.0.1`, grant `WRITE_SECURE_SETTINGS`,
     * set `adb_allowed_connection_time = 0` so authorization never silently expires, and
     * automatically turn Wireless debugging OFF once setup completes (unless keep-on is enabled).
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

        var bootstrapSucceeded = false
        return try {
            val grantCmd = buildGrantWriteSecureSettingsCommand(appId)
            executeCheckedShell(grantCmd).getOrThrow()

            val timeoutCmd = buildDisableAdbTimeoutCommand()
            executeCheckedShell(timeoutCmd).getOrThrow()

            preferences.isPaired = true
            preferences.isBootstrapComplete = true
            preferences.isEnabled = true
            preferences.storedHibernationMode = HibernationMode.AUTO_SILENT
            bootstrapSucceeded = true
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            withContext(NonCancellable) {
                runCatching { transport.disconnect() }
                if (bootstrapSucceeded && !preferences.keepWirelessDebuggingOn) {
                    runCatching { systemGateway.setAdbWifiEnabled(false) }
                }
            }
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
     * 2. Persists `isSessionOpen = true` synchronously for crash recovery, then enables
     *    `adb_wifi_enabled = 1` if it was off.
     * 3. Discovers `_adb-tls-connect._tcp` and connects strictly to `127.0.0.1`.
     * 4. Executes [block] against the live session.
     * 5. In `finally` (inside [NonCancellable]), disconnects, ALWAYS turns `adb_wifi_enabled = 0`
     *    unless `preferences.keepWirelessDebuggingOn` is enabled, and clears `isSessionOpen`.
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
        lastSessionTurnedOffWirelessDebugging = false
        preferences.isSessionOpen = true

        if (!previousAdbWifiEnabled) {
            val turnedOn = runCatching { systemGateway.setAdbWifiEnabled(true) }.getOrDefault(false)
            if (!turnedOn) {
                preferences.isSessionOpen = false
                return AutoSessionOutcome.FallbackToGuided(
                    "Could not turn on Wireless debugging automatically - using guided steps this time",
                )
            }
        }

        activeAutoSession.set(true)
        sessionReconnectCount.set(0)
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
            withContext(NonCancellable) {
                activeAutoSession.set(false)
                runCatching { transport.disconnect() }
                if (!preferences.keepWirelessDebuggingOn) {
                    val turnedOff = runCatching { systemGateway.setAdbWifiEnabled(false) }.getOrDefault(false)
                    lastSessionTurnedOffWirelessDebugging = turnedOff ||
                        !runCatching { systemGateway.isAdbWifiEnabled() }.getOrDefault(true)
                } else {
                    lastSessionTurnedOffWirelessDebugging = false
                }
                preferences.isSessionOpen = false
            }
        }
    }

    /**
     * Implements [SleepCommandExecutor] for [com.drivelift.app.feature.hibernation.engine.SleepSessionStateMachine].
     * Validates the package name and protection rules, then executes `am force-stop <pkg>` with a
     * per-command timeout. If a command times out or the ADB session drops inside an active batch,
     * reconnects once so subsequent apps continue cleanly.
     */
    override suspend fun sleepApp(candidate: AppDrainCandidate): Result<List<String>> {
        val command = runCatching {
            buildForceStopCommand(
                packageName = candidate.packageName,
                protectedPackages = protectedPackagesProvider(),
            )
        }.getOrElse { return Result.failure(it) }

        if (activeAutoSession.get()) {
            return executeInsideActiveSession(command).map { listOf(command) }
        }
        if (transport.isConnected()) {
            return executeCheckedShell(command).map { listOf(command) }
        }

        return when (val outcome = runWithAutoSession { executeInsideActiveSession(command).map { listOf(command) } }) {
            is AutoSessionOutcome.Completed -> outcome.value
            is AutoSessionOutcome.FallbackToGuided -> Result.failure(IllegalStateException(outcome.reason))
        }
    }

    /**
     * Trims application caches across the device via `pm trim-caches 999999999999`.
     * Uses the active ADB session if already connected, or opens and closes a scoped session.
     */
    suspend fun trimCachesSilent(): Result<String> {
        val command = CMD_TRIM_CACHES
        if (activeAutoSession.get()) {
            return executeInsideActiveSession(command).map { command }
        }
        if (transport.isConnected()) {
            return executeCheckedShell(command).map { command }
        }

        return when (val outcome = runWithAutoSession { executeInsideActiveSession(command).map { command } }) {
            is AutoSessionOutcome.Completed -> outcome.value
            is AutoSessionOutcome.FallbackToGuided -> Result.failure(IllegalStateException(outcome.reason))
        }
    }

    private suspend fun executeInsideActiveSession(command: String): Result<AdbCommandResult> {
        if (!transport.isConnected()) {
            reconnectOnceIfAllowed()
        }
        val result = executeCheckedShell(command)
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            val shouldReconnect = error is SleepCommandTimeoutException || !transport.isConnected()
            if (shouldReconnect) {
                reconnectOnceIfAllowed()
            }
        }
        return result
    }

    private suspend fun reconnectOnceIfAllowed(): Boolean {
        if (!sessionReconnectCount.compareAndSet(0, 1)) {
            return false
        }
        runCatching { transport.disconnect() }
        return connectWithDiscoveryRetries().isSuccess
    }

    private suspend fun executeCheckedShell(command: String): Result<AdbCommandResult> {
        val raw = try {
            if (commandTimeoutMs > 0L) {
                withTimeout(commandTimeoutMs) {
                    transport.runShell(command)
                }
            } else {
                transport.runShell(command)
            }
        } catch (timeout: TimeoutCancellationException) {
            // Re-throw if an enclosing scope or batch watchdog cancelled us; otherwise convert
            // our per-command timeout into a structured per-app skip error.
            currentCoroutineContext().ensureActive()
            val secs = (commandTimeoutMs / 1_000L).coerceAtLeast(1L)
            return Result.failure(
                SleepCommandTimeoutException(
                    "ADB shell command timed out after ${secs}s: $command",
                    timeout,
                ),
            )
        }
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
        const val PAIRING_DISCOVERY_TIMEOUT_MESSAGE =
            "Could not find the Wireless debugging pairing service within 60 seconds. " +
                "Turn on Wireless debugging, open Pair device with pairing code, or enter the pairing port manually."

        const val DEFAULT_DISCOVERY_RETRY_DELAY_MS = 600L
        const val DEFAULT_CONNECT_DISCOVERY_ATTEMPTS = 3
        const val DEFAULT_COMMAND_TIMEOUT_MS = 15_000L

        /**
         * Lightweight startup recovery invoked in `DriveLiftApp.onCreate()` so a process crash
         * during an active Auto Mode session never leaves Wireless debugging open.
         */
        fun recoverInterruptedSession(
            preferences: AutoModePreferences,
            systemGateway: AutoModeSystemGateway,
        ): Boolean {
            if (!preferences.isSessionOpen) return false
            if (!preferences.keepWirelessDebuggingOn) {
                runCatching { systemGateway.setAdbWifiEnabled(false) }
            }
            preferences.isSessionOpen = false
            return true
        }

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
