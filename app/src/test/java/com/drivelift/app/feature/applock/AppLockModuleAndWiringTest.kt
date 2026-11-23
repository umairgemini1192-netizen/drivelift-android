package com.drivelift.app.feature.applock

import android.view.WindowManager
import android.service.quicksettings.Tile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.applock.data.AppLockPreferencesRepository
import com.drivelift.app.feature.applock.engine.AppLockConfig
import com.drivelift.app.feature.applock.engine.PinCredentialHasher
import com.drivelift.app.feature.applock.engine.ReLockGracePeriod
import com.drivelift.app.feature.applock.monitor.UsageForegroundEvent
import com.drivelift.app.feature.applock.monitor.UsageStatsForegroundFallbackMonitor
import com.drivelift.app.feature.applock.tile.AppLockPauseTileService
import com.drivelift.app.feature.applock.ui.AppLockGateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockModuleAndWiringTest {

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private var fakeNowMs: Long = 10_000_000L
    private val originalHomeRouter = AppLockGateActivity.homeRouter

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Graph.get(app).clock = { fakeNowMs }
    }

    @After
    fun tearDown() {
        AppLockGateActivity.homeRouter = originalHomeRouter
        Graph.get(app).clock = System::currentTimeMillis
    }

    @Test
    fun preferencesRepository_persistsConfigAndNeverStoresPlaintextPin() {
        val repo = AppLockPreferencesRepository(app, prefsName = "test_applock_prefs_1")
        val hasher = PinCredentialHasher()
        val pin = "258046"

        repo.setEnabled(true)
        repo.setGracePeriod(ReLockGracePeriod.FIVE_MIN)
        repo.setAllowBiometric(false)
        repo.toggleLockedPackage("com.example.bank")
        repo.setTamperProtectionEnabled(true)
        repo.savePinCredential(hasher.hashPin(pin, iterations = 1_000), pinLength = pin.length)

        val reloaded = AppLockPreferencesRepository(app, prefsName = "test_applock_prefs_1")
        val cfg = reloaded.config.value
        assertTrue(cfg.enabled)
        assertEquals(ReLockGracePeriod.FIVE_MIN, cfg.gracePeriod)
        assertFalse(cfg.allowBiometric)
        assertTrue("com.example.bank" in cfg.lockedPackages)
        assertTrue(cfg.tamperProtectionEnabled)
        assertTrue(AppLockConfig.SETTINGS_PACKAGE_NAME in cfg.lockedPackages)
        assertTrue(reloaded.hasPinConfigured.value)
        assertEquals(6, reloaded.pinLength.value)

        val rawAll = app.getSharedPreferences("test_applock_prefs_1", 0).all.values.joinToString("|")
        assertFalse("Plaintext PIN must never be written to SharedPreferences", rawAll.contains(pin))

        val cred = reloaded.readPinCredential()
        assertNotNull(cred)
        assertTrue(hasher.verifyPin(pin, cred!!))
        assertFalse(hasher.verifyPin("000000", cred))
    }

    @Test
    fun moduleForegroundHook_enforcesLock_ignoresSelfGate_respectsGraceAndScreenOff() {
        val module = AppLockModule(app, Graph.get(app))
        val launched = mutableListOf<AppLockGateRequest>()
        module.gateLauncher = { _, req -> launched.add(req) }
        module.accessibilityConnectedProvider = { true }
        module.savePin("135790", iterations = 1_000)
        module.setEnabled(true)
        module.setGracePeriod(ReLockGracePeriod.ONE_MIN)
        module.toggleAppLocked("com.example.vault")
        module.setTamperProtectionEnabled(true)

        // Protected app triggers gate
        module.onForegroundWindowChanged("com.example.vault", "com.example.vault.MainActivity")
        assertEquals(1, launched.size)
        assertEquals(AppLockGateMode.UNLOCK_APP, launched.last().mode)
        assertEquals("com.example.vault", launched.last().packageName)

        // DriveLift's own AppLockGateActivity must be suppressed (no lock loop)
        fakeNowMs += 1_000L
        module.onForegroundWindowChanged(app.packageName, AppLockGateActivity::class.java.name)
        assertEquals(1, launched.size)

        // Valid PIN unlocks the package for 1 min grace window
        val pinRes = module.verifyPin("135790", AppLockGateMode.UNLOCK_APP, "com.example.vault")
        assertTrue(pinRes is PinVerifyResult.Success)

        // Switch away and back within grace window: allowed without relaunching gate
        fakeNowMs += 1_000L
        module.onForegroundWindowChanged("com.example.other", "OtherActivity")
        fakeNowMs += 1_000L
        module.onForegroundWindowChanged("com.example.vault", "com.example.vault.MainActivity")
        assertEquals(1, launched.size)

        // Tamper protection locks com.android.settings
        fakeNowMs += 1_000L
        module.onForegroundWindowChanged(AppLockConfig.SETTINGS_PACKAGE_NAME, "SettingsActivity")
        assertEquals(2, launched.size)
        assertEquals(AppLockConfig.SETTINGS_PACKAGE_NAME, launched.last().packageName)

        // Screen off revokes all grants immediately
        module.onScreenOff()
        fakeNowMs += 1_000L
        module.onForegroundWindowChanged("com.example.vault", "com.example.vault.MainActivity")
        assertEquals(3, launched.size)
    }

    @Test
    fun authenticatedPause_requiresValidPin_pausesInRamAndResumesCleanly() {
        val module = AppLockModule(app, Graph.get(app))
        val launched = mutableListOf<AppLockGateRequest>()
        module.gateLauncher = { _, req -> launched.add(req) }
        module.savePin("2468", iterations = 1_000)
        module.setEnabled(true)
        module.toggleAppLocked("com.example.photos")

        // Wrong PIN does NOT activate pause
        val wrong = module.verifyPin("9999", AppLockGateMode.AUTHORIZE_PAUSE, "")
        assertTrue(wrong is PinVerifyResult.WrongPin)
        assertFalse(module.engine.isPaused())

        // Correct PIN activates 15-min RAM-only pause
        val ok = module.verifyPin("2468", AppLockGateMode.AUTHORIZE_PAUSE, "")
        assertTrue(ok is PinVerifyResult.Success)
        assertTrue(module.engine.isPaused())

        // Protected app opens without gate while paused
        module.onForegroundWindowChanged("com.example.photos", "PhotosActivity")
        assertTrue(launched.isEmpty())

        // Resume immediately re-engages lock
        module.resumeFromPause()
        assertFalse(module.engine.isPaused())
        fakeNowMs += 1_000L
        module.onForegroundWindowChanged("com.example.photos", "PhotosActivity")
        assertEquals(1, launched.size)
    }

    @Test
    fun usageStatsFallbackMonitor_pollsOnlyWhenAccessibilityDisconnectedAndUsageGranted() {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var accessibilityConnected = false
            var usageGranted = true
            val observed = mutableListOf<String>()

            val monitor = UsageStatsForegroundFallbackMonitor(
                context = app,
                scope = testScope,
                clockMs = { fakeNowMs },
                isAccessibilityConnected = { accessibilityConnected },
                hasUsageAccessProvider = { usageGranted },
                eventReader = { _, now ->
                    UsageForegroundEvent("com.example.notes", "NotesActivity", now)
                },
                onForegroundEvent = { pkg, _ -> observed.add(pkg) },
                pollIntervalMs = 60_000L,
            )

            // Accessibility off + Usage granted -> pollOnce emits event
            monitor.pollOnce()
            assertEquals(listOf("com.example.notes"), observed)

            // Accessibility connected -> fallback suppressed
            accessibilityConnected = true
            monitor.pollOnce()
            assertEquals(1, observed.size)

            // Accessibility off + Usage denied -> fallback suppressed
            accessibilityConnected = false
            usageGranted = false
            monitor.pollOnce()
            assertEquals(1, observed.size)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun gateActivity_setsSecurityFlagsAndRoutesBackToHomeForUnlockMode() {
        val graph = Graph.get(app)
        graph.appLock.savePin("123456", iterations = 1_000)
        graph.appLock.setAllowBiometric(false)

        var homeRoutedCount = 0
        AppLockGateActivity.homeRouter = { homeRoutedCount += 1 }

        val unlockIntent = AppLockGateActivity.createIntent(
            app,
            AppLockGateRequest(
                mode = AppLockGateMode.UNLOCK_APP,
                packageName = "com.example.vault",
                appLabel = "Private Vault",
            ),
        )

        ActivityScenario.launch<AppLockGateActivity>(unlockIntent).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(
                    "AppLockGateActivity must set FLAG_SECURE",
                    (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
                )
                assertTrue(
                    "AppLockGateActivity must filter obscured touches",
                    activity.window.decorView.filterTouchesWhenObscured,
                )
                activity.handleCancelOrBack()
                assertTrue(activity.isFinishing)
            }
        }
        assertEquals(1, homeRoutedCount)

        // Pause-authorization mode should NOT route to Home on Cancel/Back
        val pauseIntent = AppLockGateActivity.createIntent(
            app,
            AppLockGateRequest(
                mode = AppLockGateMode.AUTHORIZE_PAUSE,
                appLabel = "Pause App Lock",
            ),
        )
        ActivityScenario.launch<AppLockGateActivity>(pauseIntent).use { scenario ->
            scenario.onActivity { activity ->
                activity.handleCancelOrBack()
                assertTrue(activity.isFinishing)
            }
        }
        assertEquals(1, homeRoutedCount)
    }

    @Test
    fun quickSettingsPauseTile_computesAccurateStatesForOffActiveAndPaused() {
        val off = AppLockPauseTileService.computePresentation(
            enabled = false,
            isPaused = false,
            pauseRemainingMs = 0L,
        )
        assertEquals(Tile.STATE_UNAVAILABLE, off.state)
        assertEquals("App Lock off", off.subtitle)

        val active = AppLockPauseTileService.computePresentation(
            enabled = true,
            isPaused = false,
            pauseRemainingMs = 0L,
        )
        assertEquals(Tile.STATE_INACTIVE, active.state)
        assertEquals("Pause App Lock", active.label)

        val paused = AppLockPauseTileService.computePresentation(
            enabled = true,
            isPaused = true,
            pauseRemainingMs = 14L * 60_000L + 10_000L,
        )
        assertEquals(Tile.STATE_ACTIVE, paused.state)
        assertEquals("App Lock Paused", paused.label)
        assertEquals("15m left", paused.subtitle)
    }
}
