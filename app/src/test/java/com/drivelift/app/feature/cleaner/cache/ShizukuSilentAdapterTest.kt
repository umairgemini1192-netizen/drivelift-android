package com.drivelift.app.feature.cleaner.cache

import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.StandbyBucket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuSilentAdapterTest {

    private class FakeBridge(
        var installed: Boolean = false,
        var alive: Boolean = false,
        var permission: Boolean = false,
        var throwOnProbe: Boolean = false,
        var commandResult: Result<Unit> = Result.success(Unit),
    ) : ShizukuRuntimeBridge {
        val executedCommands = mutableListOf<List<String>>()

        override fun isShizukuAppInstalled(): Boolean {
            if (throwOnProbe) error("Simulated PackageManager restriction")
            return installed
        }

        override fun isBinderAlive(): Boolean {
            if (throwOnProbe) error("Simulated binder SecurityException")
            return alive
        }

        override fun hasPermission(): Boolean {
            if (throwOnProbe) error("Simulated permission check error")
            return permission
        }

        override suspend fun executeCommand(args: List<String>): Result<Unit> {
            executedCommands.add(args)
            return commandResult
        }
    }

    private fun sampleCandidate(pkg: String = "com.example.social") = AppDrainCandidate(
        packageName = pkg,
        appLabel = "Social App",
        drainScore = 78,
        backgroundTimeMs = 45 * 60_000L,
        lastTimeUsedMs = 1_000_000L,
        standbyBucket = StandbyBucket.ACTIVE,
        isRunningInBackground = true,
        reasons = listOf("Currently active in background"),
    )

    @Test
    fun fallsBackToGuidedPillWhenShizukuNotInstalled() {
        val adapter = ShizukuSilentAdapter(
            bridge = FakeBridge(installed = false),
            sdkIntProvider = { 35 },
        )
        val status = adapter.inspectStatus()
        assertEquals(ShizukuAvailability.NOT_INSTALLED, status.availability)
        assertEquals(HibernationMode.GUIDED_PILL, status.effectiveMode)
    }

    @Test
    fun fallsBackToGuidedPillWhenShizukuNotRunningOrPermissionMissing() {
        val stopped = ShizukuSilentAdapter(
            bridge = FakeBridge(installed = true, alive = false, permission = false),
            sdkIntProvider = { 35 },
        ).inspectStatus()
        assertEquals(ShizukuAvailability.NOT_RUNNING, stopped.availability)
        assertEquals(HibernationMode.GUIDED_PILL, stopped.effectiveMode)

        val noPerm = ShizukuSilentAdapter(
            bridge = FakeBridge(installed = true, alive = true, permission = false),
            sdkIntProvider = { 35 },
        ).inspectStatus()
        assertEquals(ShizukuAvailability.PERMISSION_REQUIRED, noPerm.availability)
        assertEquals(HibernationMode.GUIDED_PILL, noPerm.effectiveMode)
    }

    @Test
    fun fallsBackToGuidedPillOnAndroid17AndWhenProbeThrows() {
        val android17 = ShizukuSilentAdapter(
            bridge = FakeBridge(installed = true, alive = true, permission = true),
            sdkIntProvider = { 37 },
        ).inspectStatus()
        assertEquals(ShizukuAvailability.UNSUPPORTED_ANDROID_VERSION, android17.availability)
        assertEquals(HibernationMode.GUIDED_PILL, android17.effectiveMode)

        val throwing = ShizukuSilentAdapter(
            bridge = FakeBridge(throwOnProbe = true),
            sdkIntProvider = { 35 },
        ).inspectStatus()
        assertEquals(ShizukuAvailability.NOT_INSTALLED, throwing.availability)
        assertEquals(HibernationMode.GUIDED_PILL, throwing.effectiveMode)
    }

    @Test
    fun executesValidatedSleepSequenceWhenShizukuReady() = runBlocking {
        val bridge = FakeBridge(installed = true, alive = true, permission = true)
        val adapter = ShizukuSilentAdapter(
            bridge = bridge,
            sdkIntProvider = { 35 },
            protectedPackagesProvider = { setOf("com.example.whitelisted") },
        )

        val status = adapter.inspectStatus()
        assertEquals(ShizukuAvailability.READY, status.availability)
        assertEquals(HibernationMode.SHIZUKU_SILENT, status.effectiveMode)

        val result = adapter.sleepApp(sampleCandidate("com.example.social"))
        assertTrue(result.isSuccess)
        assertEquals(3, bridge.executedCommands.size)
        assertEquals(
            listOf("appops", "set", "com.example.social", "RUN_ANY_IN_BACKGROUND", "ignore"),
            bridge.executedCommands[0],
        )
        assertEquals(
            listOf("am", "set-inactive", "com.example.social", "true"),
            bridge.executedCommands[1],
        )
        assertEquals(
            listOf("am", "force-stop", "com.example.social"),
            bridge.executedCommands[2],
        )
    }

    @Test
    fun refusesProtectedPackagesAndShellInjection() = runBlocking {
        val bridge = FakeBridge(installed = true, alive = true, permission = true)
        val adapter = ShizukuSilentAdapter(
            bridge = bridge,
            sdkIntProvider = { 35 },
            protectedPackagesProvider = { setOf("com.example.whitelisted") },
        )

        assertTrue(adapter.sleepApp(sampleCandidate("com.drivelift.app")).isFailure)
        assertTrue(adapter.sleepApp(sampleCandidate("com.example.whitelisted")).isFailure)
        assertTrue(adapter.clearAppCacheSilent("com.bad; rm -rf /").isFailure)
        assertTrue(bridge.executedCommands.isEmpty())
    }

    @Test
    fun clearsAppCacheSilentlyWhenReady() = runBlocking {
        val bridge = FakeBridge(installed = true, alive = true, permission = true)
        val adapter = ShizukuSilentAdapter(
            bridge = bridge,
            sdkIntProvider = { 35 },
        )

        val res = adapter.clearAppCacheSilent("com.example.browser")
        assertTrue(res.isSuccess)
        assertEquals(
            listOf("cmd", "package", "clear-cache", "com.example.browser"),
            bridge.executedCommands.single(),
        )
    }
}
