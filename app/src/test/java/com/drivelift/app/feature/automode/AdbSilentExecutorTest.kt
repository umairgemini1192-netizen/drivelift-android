package com.drivelift.app.feature.automode

import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.SleepCommandTimeoutException
import com.drivelift.app.feature.hibernation.engine.SleepItemOutcome
import com.drivelift.app.feature.hibernation.engine.SleepReportFormatter
import com.drivelift.app.feature.hibernation.engine.SleepSessionPhase
import com.drivelift.app.feature.hibernation.engine.SleepSessionStateMachine
import com.drivelift.app.feature.hibernation.engine.StandbyBucket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader

class AdbSilentExecutorTest {

    private class FakeAdbKeyStorage(
        private var identity: AdbIdentity? = null,
    ) : AdbKeyStorage {
        var deleteCount = 0

        override fun hasKeyPair(): Boolean = identity != null

        override fun loadKeyPair(): AdbIdentity? = identity

        override fun loadOrCreateKeyPair(): AdbIdentity {
            val existing = identity
            if (existing != null) return existing
            val gen = SelfSignedX509Generator.generate()
            return AdbIdentity(gen.privateKey, gen.certificate).also { identity = it }
        }

        override fun deleteKeyPair() {
            deleteCount++
            identity = null
        }
    }

    private class FakePreferences(
        override var isEnabled: Boolean = false,
        override var isPaired: Boolean = false,
        override var isBootstrapComplete: Boolean = false,
        override var storedHibernationMode: HibernationMode = HibernationMode.GUIDED_PILL,
        override var keepWirelessDebuggingOn: Boolean = false,
        override var isSessionOpen: Boolean = false,
    ) : AutoModePreferences {
        override fun clearAutoModeState() {
            isEnabled = false
            isPaired = false
            isBootstrapComplete = false
            keepWirelessDebuggingOn = false
            isSessionOpen = false
            storedHibernationMode = HibernationMode.GUIDED_PILL
        }
    }

    private class FakeSystemGateway(
        var devOptionsEnabled: Boolean = true,
        var onWifi: Boolean = true,
        var writeSecureSettings: Boolean = true,
        var adbWifiEnabled: Boolean = false,
        var allowWriteAdbWifi: Boolean = true,
        var adbTimeout: Long? = null,
        var appId: String = "com.drivelift.app",
        var notifAccess: NotificationAccessState = NotificationAccessState.ALLOWED,
    ) : AutoModeSystemGateway {
        val adbWifiWrites = mutableListOf<Boolean>()

        override fun isDeveloperOptionsEnabled(): Boolean = devOptionsEnabled

        override fun isOnWifi(): Boolean = onWifi

        override fun hasWriteSecureSettings(): Boolean = writeSecureSettings

        override fun isAdbWifiEnabled(): Boolean = adbWifiEnabled

        override fun setAdbWifiEnabled(enabled: Boolean): Boolean {
            if (!writeSecureSettings || !allowWriteAdbWifi) return false
            adbWifiEnabled = enabled
            adbWifiWrites.add(enabled)
            return true
        }

        override fun getAdbAllowedConnectionTime(): Long? = adbTimeout

        override fun setAdbAllowedConnectionTime(value: Long): Boolean {
            if (!writeSecureSettings) return false
            adbTimeout = value
            return true
        }

        override fun applicationId(): String = appId

        override fun notificationAccessState(): NotificationAccessState = notifAccess
    }

    private class FakeAdbTransport(
        var pairingPortResult: Result<Int> = Result.success(37111),
        var connectPortResult: Result<Int> = Result.success(41234),
        var pairResult: Result<Unit> = Result.success(Unit),
        var connectResult: Result<Unit> = Result.success(Unit),
        var shellHandler: suspend (String) -> Result<AdbCommandResult> = { Result.success(AdbCommandResult(0, "")) },
    ) : AdbTransport {
        val executedCommands = mutableListOf<String>()
        var connected = false
        var connectCount = 0
        var disconnectCount = 0
        var lastPairedPort: Int? = null
        var lastConnectedPort: Int? = null

        override suspend fun discoverPairingPort(timeoutMs: Long): Result<Int> = pairingPortResult

        override suspend fun discoverConnectPort(timeoutMs: Long): Result<Int> = connectPortResult

        override suspend fun pair(port: Int, pairingCode: String): Result<Unit> {
            lastPairedPort = port
            return pairResult
        }

        override suspend fun connect(port: Int): Result<Unit> {
            connectCount++
            lastConnectedPort = port
            return connectResult.onSuccess { connected = true }
        }

        override suspend fun runShell(command: String): Result<AdbCommandResult> {
            executedCommands.add(command)
            return shellHandler(command)
        }

        override suspend fun disconnect() {
            connected = false
            disconnectCount++
        }

        override fun isConnected(): Boolean = connected
    }

    private fun sampleCandidate(pkg: String = "com.example.social", label: String = "Social App") = AppDrainCandidate(
        packageName = pkg,
        appLabel = label,
        drainScore = 82,
        backgroundTimeMs = 45 * 60_000L,
        lastTimeUsedMs = 1_000_000L,
        standbyBucket = StandbyBucket.ACTIVE,
        isRunningInBackground = true,
        reasons = listOf("Active in background"),
    )

    private fun configuredExecutor(
        transport: FakeAdbTransport = FakeAdbTransport(),
        gateway: FakeSystemGateway = FakeSystemGateway(),
        keyStorage: FakeAdbKeyStorage = FakeAdbKeyStorage().apply { loadOrCreateKeyPair() },
        prefs: FakePreferences = FakePreferences(
            isEnabled = true,
            isPaired = true,
            isBootstrapComplete = true,
            storedHibernationMode = HibernationMode.AUTO_SILENT,
        ),
        protectedPackages: Set<String> = emptySet(),
        commandTimeoutMs: Long = AdbSilentExecutor.DEFAULT_COMMAND_TIMEOUT_MS,
    ): AdbSilentExecutor = AdbSilentExecutor(
        transport = transport,
        systemGateway = gateway,
        keyStorage = keyStorage,
        preferences = prefs,
        protectedPackagesProvider = { protectedPackages },
        discoveryRetryDelayMs = 0L,
        maxConnectDiscoveryAttempts = 2,
        commandTimeoutMs = commandTimeoutMs,
    )

    @Test
    fun parsesShellExitCodesAndRejectsMissingMarker() {
        val wrapped = AdbShellOutputParser.wrapWithExitCode("am force-stop com.example.app")
        assertEquals("am force-stop com.example.app; echo __DRIVELIFT_EXIT__=\$?", wrapped)

        val ok = AdbShellOutputParser.parse("Success\n__DRIVELIFT_EXIT__=0\n").getOrThrow()
        assertTrue(ok.isSuccess)
        assertEquals(0, ok.exitCode)
        assertEquals("Success", ok.stdout)

        val err = AdbShellOutputParser.parse("SecurityException: denied\n__DRIVELIFT_EXIT__=1\n").getOrThrow()
        assertFalse(err.isSuccess)
        assertEquals(1, err.exitCode)
        assertEquals("SecurityException: denied", err.stdout)

        assertTrue(AdbShellOutputParser.parse("truncated output without marker").isFailure)
    }

    @Test
    fun migratesLegacyShizukuSilentModeToAutoSilent() {
        assertEquals(HibernationMode.AUTO_SILENT, HibernationMode.fromStored("SHIZUKU_SILENT"))
        assertEquals(HibernationMode.AUTO_SILENT, HibernationMode.fromStored("AUTO_SILENT"))
        assertEquals(HibernationMode.GUIDED_PILL, HibernationMode.fromStored("GUIDED_PILL"))
        assertEquals(HibernationMode.GUIDED_PILL, HibernationMode.fromStored(null))
        assertEquals(HibernationMode.GUIDED_PILL, HibernationMode.fromStored("UNKNOWN"))
    }

    @Test
    fun oneTimePairingAndBootstrapRunsExactGrantAndTimeoutCommands() = runBlocking {
        val transport = FakeAdbTransport()
        val gateway = FakeSystemGateway(writeSecureSettings = false, onWifi = true)
        val keyStorage = FakeAdbKeyStorage()
        val prefs = FakePreferences()
        val executor = AdbSilentExecutor(
            transport = transport,
            systemGateway = gateway,
            keyStorage = keyStorage,
            preferences = prefs,
            discoveryRetryDelayMs = 0L,
        )

        val pairRes = executor.pairWithCode("482913")
        assertTrue(pairRes.isSuccess)
        assertEquals(37111, transport.lastPairedPort)
        assertTrue(keyStorage.hasKeyPair())
        assertTrue(prefs.isPaired)

        val bootRes = executor.runOneTimeBootstrap()
        assertTrue(bootRes.isSuccess)
        assertEquals(
            listOf(
                "pm grant com.drivelift.app android.permission.WRITE_SECURE_SETTINGS",
                "settings put global adb_allowed_connection_time 0",
            ),
            transport.executedCommands,
        )
        assertTrue(prefs.isBootstrapComplete)
        assertEquals(HibernationMode.AUTO_SILENT, prefs.storedHibernationMode)
        assertFalse(transport.isConnected())
    }

    @Test
    fun enablesAndRestoresAdbWifiEnabledWhenPreviouslyOff() = runBlocking {
        val transport = FakeAdbTransport()
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = false)
        val executor = configuredExecutor(transport = transport, gateway = gateway)

        val outcome = executor.runWithAutoSession { exec ->
            val sleepRes = exec.sleepApp(sampleCandidate("com.example.social"))
            val trimRes = exec.trimCachesSilent()
            assertTrue(sleepRes.isSuccess)
            assertTrue(trimRes.isSuccess)
            "done"
        }

        assertTrue(outcome is AutoSessionOutcome.Completed)
        assertEquals(
            listOf("am force-stop com.example.social", "pm trim-caches 999999999999"),
            transport.executedCommands,
        )
        // Turned Wireless debugging ON for the run, then restored OFF afterwards.
        assertEquals(listOf(true, false), gateway.adbWifiWrites)
        assertFalse(gateway.adbWifiEnabled)
        assertFalse(transport.isConnected())
    }

    @Test
    fun turnsAdbWifiOffAfterRunEvenWhenUserAlreadyHadItEnabledByDefault() = runBlocking {
        val transport = FakeAdbTransport()
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = true)
        val prefs = FakePreferences(
            isEnabled = true,
            isPaired = true,
            isBootstrapComplete = true,
            storedHibernationMode = HibernationMode.AUTO_SILENT,
            keepWirelessDebuggingOn = false,
        )
        val executor = configuredExecutor(transport = transport, gateway = gateway, prefs = prefs)

        val outcome = executor.runWithAutoSession { exec ->
            exec.trimCachesSilent()
        }

        assertTrue(outcome is AutoSessionOutcome.Completed)
        // New owner rule (preview.45): always close the Wireless debugging port after a run
        // unless "Keep Wireless debugging on after Auto Mode" is explicitly enabled.
        assertEquals(listOf(false), gateway.adbWifiWrites)
        assertFalse(gateway.adbWifiEnabled)
        assertTrue(executor.lastSessionTurnedOffWirelessDebugging)
        assertFalse(prefs.isSessionOpen)
    }

    @Test
    fun keepsAdbWifiOnWhenKeepWirelessDebuggingSettingIsEnabled() = runBlocking {
        val transport = FakeAdbTransport()
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = true)
        val prefs = FakePreferences(
            isEnabled = true,
            isPaired = true,
            isBootstrapComplete = true,
            storedHibernationMode = HibernationMode.AUTO_SILENT,
            keepWirelessDebuggingOn = true,
        )
        val executor = configuredExecutor(transport = transport, gateway = gateway, prefs = prefs)

        val outcome = executor.runWithAutoSession { exec ->
            exec.trimCachesSilent()
        }

        assertTrue(outcome is AutoSessionOutcome.Completed)
        assertTrue(gateway.adbWifiWrites.isEmpty())
        assertTrue(gateway.adbWifiEnabled)
        assertFalse(executor.lastSessionTurnedOffWirelessDebugging)
        assertFalse(prefs.isSessionOpen)
    }

    @Test
    fun turnsWirelessDebuggingOffAutomaticallyAfterOneTimeBootstrap() = runBlocking {
        val gateway = FakeSystemGateway(writeSecureSettings = false, onWifi = true, adbWifiEnabled = true)
        val transport = FakeAdbTransport(
            shellHandler = { cmd ->
                if (cmd.startsWith("pm grant ")) {
                    gateway.writeSecureSettings = true
                }
                Result.success(AdbCommandResult(0, ""))
            },
        )
        val keyStorage = FakeAdbKeyStorage().apply { loadOrCreateKeyPair() }
        val prefs = FakePreferences(isPaired = true, isEnabled = true, keepWirelessDebuggingOn = false)
        val executor = AdbSilentExecutor(
            transport = transport,
            systemGateway = gateway,
            keyStorage = keyStorage,
            preferences = prefs,
            discoveryRetryDelayMs = 0L,
        )

        val res = executor.runOneTimeBootstrap()
        assertTrue(res.isSuccess)
        assertFalse(gateway.adbWifiEnabled)
        assertEquals(listOf(false), gateway.adbWifiWrites)
    }

    @Test
    fun perCommandTimeoutSkipsAppReconnectsOnceAndContinuesBatch() = runBlocking {
        val transport = FakeAdbTransport()
        transport.shellHandler = { cmd ->
            if (cmd.contains("com.openai.chatgpt")) {
                delay(500L)
            }
            Result.success(AdbCommandResult(0, ""))
        }
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = true)
        val prefs = FakePreferences(
            isEnabled = true,
            isPaired = true,
            isBootstrapComplete = true,
            storedHibernationMode = HibernationMode.AUTO_SILENT,
        )
        val executor = configuredExecutor(
            transport = transport,
            gateway = gateway,
            prefs = prefs,
            commandTimeoutMs = 60L,
        )
        val machine = SleepSessionStateMachine()
        val queue = listOf(
            sampleCandidate("com.example.one", "App One"),
            sampleCandidate("com.openai.chatgpt", "ChatGPT"),
            sampleCandidate("com.example.three", "App Three"),
        )
        val initial = machine.startSession("sleep-timeout", HibernationMode.AUTO_SILENT, queue, 1000L)

        val outcome = executor.runWithAutoSession { exec ->
            machine.runSilentBatch(
                initialState = initial,
                executor = exec,
                clockMs = { 2000L },
                batchWatchdogMs = 5_000L,
            )
        }

        assertTrue(outcome is AutoSessionOutcome.Completed)
        val finalState = (outcome as AutoSessionOutcome.Completed).value
        assertEquals(SleepSessionPhase.COMPLETED, finalState.phase)
        val report = machine.buildReport(
            state = finalState,
            completedAtMs = 3000L,
            wirelessDebuggingTurnedOff = executor.lastSessionTurnedOffWirelessDebugging,
        )
        assertEquals(listOf("com.example.one", "com.example.three"), report.sleptPackages)
        assertEquals(listOf("com.openai.chatgpt"), report.skippedPackages)
        assertEquals(SleepSessionStateMachine.REASON_SKIPPED_TIMEOUT, report.results[1].detail)
        assertEquals(SleepItemOutcome.SKIPPED, report.results[1].outcome)
        assertEquals(
            "Slept 2 of 3 apps - ChatGPT skipped (did not respond)",
            SleepReportFormatter.formatSummary(report),
        )
        // 1 initial connect + 1 reconnect after timeout.
        assertEquals(2, transport.connectCount)
        // Wireless debugging turned off in finally even after a timeout.
        assertFalse(gateway.adbWifiEnabled)
        assertTrue(report.wirelessDebuggingTurnedOff)
    }

    @Test
    fun reconnectsAtMostOnceWhenSessionDropsMidBatch() = runBlocking {
        val transport = FakeAdbTransport()
        var callIndex = 0
        transport.shellHandler = { _ ->
            callIndex++
            when (callIndex) {
                2 -> {
                    transport.connected = false
                    Result.failure(IOException("Connection reset by peer"))
                }
                4 -> {
                    transport.connected = false
                    Result.failure(IOException("Second drop"))
                }
                else -> Result.success(AdbCommandResult(0, ""))
            }
        }
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = false)
        val executor = configuredExecutor(transport = transport, gateway = gateway)

        val outcome = executor.runWithAutoSession { exec ->
            val r1 = exec.sleepApp(sampleCandidate("com.example.a", "A"))
            val r2 = exec.sleepApp(sampleCandidate("com.example.b", "B"))
            val r3 = exec.sleepApp(sampleCandidate("com.example.c", "C"))
            val r4 = exec.sleepApp(sampleCandidate("com.example.d", "D"))
            listOf(r1.isSuccess, r2.isSuccess, r3.isSuccess, r4.isSuccess)
        }

        assertTrue(outcome is AutoSessionOutcome.Completed)
        assertEquals(listOf(true, false, true, false), (outcome as AutoSessionOutcome.Completed).value)
        // Initial connect (1) + single reconnect after first drop (2); second drop does not loop.
        assertEquals(2, transport.connectCount)
        assertFalse(gateway.adbWifiEnabled)
    }

    @Test
    fun cancelMidBatchRunsFinallyCleanupAndTurnsWirelessDebuggingOff() = runBlocking {
        val enteredSlowCommand = CompletableDeferred<Unit>()
        val transport = FakeAdbTransport(
            shellHandler = { cmd ->
                if (cmd.contains("com.example.slow")) {
                    enteredSlowCommand.complete(Unit)
                    delay(10_000L)
                }
                Result.success(AdbCommandResult(0, ""))
            },
        )
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = true)
        val prefs = FakePreferences(
            isEnabled = true,
            isPaired = true,
            isBootstrapComplete = true,
            storedHibernationMode = HibernationMode.AUTO_SILENT,
        )
        val executor = configuredExecutor(transport = transport, gateway = gateway, prefs = prefs, commandTimeoutMs = 15_000L)

        var caughtCancel = false
        val job = launch {
            try {
                executor.runWithAutoSession { exec ->
                    exec.sleepApp(sampleCandidate("com.example.fast", "Fast"))
                    exec.sleepApp(sampleCandidate("com.example.slow", "Slow"))
                }
            } catch (_: CancellationException) {
                caughtCancel = true
            }
        }

        enteredSlowCommand.await()
        assertTrue(prefs.isSessionOpen)
        job.cancel()
        job.join()

        assertTrue(caughtCancel)
        assertFalse(transport.isConnected())
        assertFalse(gateway.adbWifiEnabled)
        assertFalse(prefs.isSessionOpen)
        assertTrue(executor.lastSessionTurnedOffWirelessDebugging)
    }

    @Test
    fun startupCrashRecoveryTurnsWirelessDebuggingOffAndClearsSessionFlag() {
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = true)
        val prefs = FakePreferences(isSessionOpen = true, keepWirelessDebuggingOn = false)

        assertTrue(AdbSilentExecutor.recoverInterruptedSession(prefs, gateway))
        assertFalse(gateway.adbWifiEnabled)
        assertEquals(listOf(false), gateway.adbWifiWrites)
        assertFalse(prefs.isSessionOpen)

        // Second call is a no-op once cleared.
        assertFalse(AdbSilentExecutor.recoverInterruptedSession(prefs, gateway))

        // Respects keepWirelessDebuggingOn = true while still clearing the stale flag.
        val keepGateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = true)
        val keepPrefs = FakePreferences(isSessionOpen = true, keepWirelessDebuggingOn = true)
        assertTrue(AdbSilentExecutor.recoverInterruptedSession(keepPrefs, keepGateway))
        assertTrue(keepGateway.adbWifiEnabled)
        assertTrue(keepGateway.adbWifiWrites.isEmpty())
        assertFalse(keepPrefs.isSessionOpen)
    }

    @Test
    fun shellV1ParserSupportsAltExitMarkerStopsImmediatelyAndTreatsCleanEmptyEofAsCompletion() {
        val altOk = AdbShellOutputParser.parse("ok\n__DL_EXIT:0\n").getOrThrow()
        assertTrue(altOk.isSuccess)
        assertEquals(0, altOk.exitCode)
        assertEquals("ok", altOk.stdout)

        val altFail = AdbShellOutputParser.parse("denied\n__DL_EXIT:13\n").getOrThrow()
        assertFalse(altFail.isSuccess)
        assertEquals(13, altFail.exitCode)

        // Stream that throws if read past the exit marker line (proving we never wait for a stalled CLSE).
        val markerThenStallReader = BufferedReader(
            object : Reader() {
                private val payload = "done\n__DL_EXIT:0\n".toCharArray()
                private var pos = 0

                override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                    if (pos >= payload.size) {
                        throw AssertionError("Reader must stop immediately once exit marker line arrives")
                    }
                    val ch = payload[pos++]
                    cbuf[off] = ch
                    return 1
                }

                override fun close() = Unit
            },
        )
        val immediate = AdbShellOutputParser.readStreamUntilComplete(markerThenStallReader).getOrThrow()
        assertTrue(immediate.isSuccess)
        assertEquals("done", immediate.stdout)

        // Clean empty stream EOF (e.g. silent `am force-stop`) completes cleanly with exit 0.
        val emptyEof = AdbShellOutputParser.readStreamUntilComplete(BufferedReader(StringReader(""))).getOrThrow()
        assertTrue(emptyEof.isSuccess)
        assertEquals(0, emptyEof.exitCode)
        assertEquals("", emptyEof.stdout)
    }

    @Test
    fun pairingDiscoveryTimeoutReturnsPlainEnglishMessageAndExplicitPortSkipsDiscovery() = runBlocking {
        val transport = FakeAdbTransport(
            pairingPortResult = Result.failure(IOException("Timed out")),
        )
        val gateway = FakeSystemGateway(onWifi = true)
        val executor = configuredExecutor(transport = transport, gateway = gateway)

        val disc = executor.discoverPairingPortWithTimeout(timeoutMs = 50L)
        assertTrue(disc.isFailure)
        assertEquals(AdbSilentExecutor.PAIRING_DISCOVERY_TIMEOUT_MESSAGE, disc.exceptionOrNull()?.message)

        val explicitRes = executor.pairWithCode("654321", explicitPort = 39876)
        assertTrue(explicitRes.isSuccess)
        assertEquals(39876, transport.lastPairedPort)
    }

    @Test
    fun restoresAdbWifiEnabledOffEvenWhenConnectTimesOut() = runBlocking {
        val transport = FakeAdbTransport(
            connectPortResult = Result.failure(IOException("mDNS timeout")),
        )
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = false)
        val executor = configuredExecutor(transport = transport, gateway = gateway)

        val outcome = executor.runWithAutoSession { exec -> exec.trimCachesSilent() }

        assertTrue(outcome is AutoSessionOutcome.FallbackToGuided)
        val reason = (outcome as AutoSessionOutcome.FallbackToGuided).reason
        assertTrue(reason.startsWith(AdbSilentExecutor.FALLBACK_REASON_CONNECT_FAILED))
        assertEquals(listOf(true, false), gateway.adbWifiWrites)
        assertFalse(gateway.adbWifiEnabled)
    }

    @Test
    fun fallsBackToGuidedWithPlainEnglishReasonWhenNotOnWifi() = runBlocking {
        val transport = FakeAdbTransport()
        val gateway = FakeSystemGateway(onWifi = false, writeSecureSettings = true, adbWifiEnabled = false)
        val executor = configuredExecutor(transport = transport, gateway = gateway)

        val status = executor.inspectStatus()
        assertEquals(AutoModeAvailability.NEEDS_WIFI, status.availability)
        assertEquals(HibernationMode.GUIDED_PILL, status.effectiveMode)
        assertEquals(AdbSilentExecutor.FALLBACK_REASON_NO_WIFI, status.detail)

        val outcome = executor.runWithAutoSession { exec -> exec.trimCachesSilent() }
        assertTrue(outcome is AutoSessionOutcome.FallbackToGuided)
        assertEquals(
            "Auto Mode needs Wi-Fi - using guided steps this time",
            (outcome as AutoSessionOutcome.FallbackToGuided).reason,
        )
        // Must never toggle adb_wifi_enabled when not on Wi-Fi.
        assertTrue(gateway.adbWifiWrites.isEmpty())
        assertTrue(transport.executedCommands.isEmpty())
    }

    @Test
    fun failsCommandOnNonZeroExitCodeAndRestoresAdbWifiEnabled() = runBlocking {
        val transport = FakeAdbTransport(
            shellHandler = { Result.success(AdbCommandResult(exitCode = 255, stdout = "Permission denial")) },
        )
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = false)
        val executor = configuredExecutor(transport = transport, gateway = gateway)

        val res = executor.sleepApp(sampleCandidate("com.example.social"))
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()!!.message!!.contains("255"))
        assertEquals(listOf(true, false), gateway.adbWifiWrites)
        assertFalse(gateway.adbWifiEnabled)
    }

    @Test
    fun refusesProtectedPackagesAndShellInjection() = runBlocking {
        val transport = FakeAdbTransport()
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = false)
        val executor = configuredExecutor(
            transport = transport,
            gateway = gateway,
            protectedPackages = setOf("com.example.whitelisted"),
        )

        assertTrue(executor.sleepApp(sampleCandidate("com.drivelift.app")).isFailure)
        assertTrue(executor.sleepApp(sampleCandidate("com.example.whitelisted")).isFailure)
        assertTrue(executor.sleepApp(sampleCandidate("com.bad; rm -rf /")).isFailure)
        assertTrue(transport.executedCommands.isEmpty())
        assertTrue(gateway.adbWifiWrites.isEmpty())
    }

    @Test
    fun turnOffAutoModeDeletesKeyPairAndClearsFlags() = runBlocking {
        val transport = FakeAdbTransport()
        val keyStorage = FakeAdbKeyStorage().apply { loadOrCreateKeyPair() }
        val prefs = FakePreferences(
            isEnabled = true,
            isPaired = true,
            isBootstrapComplete = true,
            storedHibernationMode = HibernationMode.AUTO_SILENT,
        )
        val executor = configuredExecutor(transport = transport, keyStorage = keyStorage, prefs = prefs)

        executor.turnOffAutoMode()
        assertEquals(1, keyStorage.deleteCount)
        assertFalse(keyStorage.hasKeyPair())
        assertFalse(prefs.isEnabled)
        assertFalse(prefs.isPaired)
        assertFalse(prefs.isBootstrapComplete)
        assertEquals(HibernationMode.GUIDED_PILL, prefs.storedHibernationMode)
        assertEquals(AutoModeAvailability.SETUP_REQUIRED, executor.inspectStatus().availability)
    }
}
