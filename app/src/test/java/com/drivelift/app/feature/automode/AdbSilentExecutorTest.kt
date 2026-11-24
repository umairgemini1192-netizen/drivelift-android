package com.drivelift.app.feature.automode

import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.StandbyBucket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
    ) : AutoModePreferences {
        override fun clearAutoModeState() {
            isEnabled = false
            isPaired = false
            isBootstrapComplete = false
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
    }

    private class FakeAdbTransport(
        var pairingPortResult: Result<Int> = Result.success(37111),
        var connectPortResult: Result<Int> = Result.success(41234),
        var pairResult: Result<Unit> = Result.success(Unit),
        var connectResult: Result<Unit> = Result.success(Unit),
        var shellHandler: (String) -> Result<AdbCommandResult> = { Result.success(AdbCommandResult(0, "")) },
    ) : AdbTransport {
        val executedCommands = mutableListOf<String>()
        var connected = false
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

    private fun sampleCandidate(pkg: String = "com.example.social") = AppDrainCandidate(
        packageName = pkg,
        appLabel = "Social App",
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
    ): AdbSilentExecutor = AdbSilentExecutor(
        transport = transport,
        systemGateway = gateway,
        keyStorage = keyStorage,
        preferences = prefs,
        protectedPackagesProvider = { protectedPackages },
        discoveryRetryDelayMs = 0L,
        maxConnectDiscoveryAttempts = 2,
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
    fun leavesAdbWifiEnabledOnWhenUserAlreadyHadItEnabled() = runBlocking {
        val transport = FakeAdbTransport()
        val gateway = FakeSystemGateway(onWifi = true, writeSecureSettings = true, adbWifiEnabled = true)
        val executor = configuredExecutor(transport = transport, gateway = gateway)

        val outcome = executor.runWithAutoSession { exec ->
            exec.trimCachesSilent()
        }

        assertTrue(outcome is AutoSessionOutcome.Completed)
        assertTrue(gateway.adbWifiWrites.isEmpty())
        assertTrue(gateway.adbWifiEnabled)
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
