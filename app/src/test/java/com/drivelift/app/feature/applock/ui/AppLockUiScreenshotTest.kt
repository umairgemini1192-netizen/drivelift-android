package com.drivelift.app.feature.applock.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.applock.AppLockGateMode
import com.drivelift.app.feature.applock.AppLockManagerUiState
import com.drivelift.app.feature.applock.AppLockMonitorMode
import com.drivelift.app.feature.applock.LockableAppEntry
import com.drivelift.app.feature.applock.engine.AppLockConfig
import com.drivelift.app.feature.applock.engine.ReLockGracePeriod
import com.drivelift.app.feature.applock.engine.TamperProtectionStatus
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Phase 5 App Lock Gate and App Lock Manager surfaces across STANDARD, LARGE_FONT (1.5x),
 * and COMPACT (320dp) configurations under `build/outputs/screenshots/v3/` so the 239-image
 * baseline under `build/outputs/screenshots/` remains unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AppLockUiScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val record = System.getProperty("roborazzi.test.record") == "true"

    private enum class Variant(val qualifiers: String, val fontScale: Float) {
        STANDARD("w360dp-h780dp-xhdpi", 1f),
        LARGE_FONT("w360dp-h780dp-xhdpi", 1.5f),
        COMPACT("w320dp-h640dp-xhdpi", 1f),
    }

    private val sampleApps = listOf(
        LockableAppEntry("com.example.banking", "Vault Bank", isLocked = true),
        LockableAppEntry("com.example.gallery", "Private Gallery", isLocked = true),
        LockableAppEntry(AppLockConfig.SETTINGS_PACKAGE_NAME, "Settings", isLocked = false, isSystemSettings = true),
        LockableAppEntry("com.example.notes", "Field Notes", isLocked = false),
    )

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Graph.get(app).clock = { FIXED_TIME_MS }
    }

    @After
    fun tearDown() {
        Graph.get(app).clock = System::currentTimeMillis
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        pump(6)
    }

    private fun pump(times: Int = 8) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(10)
    }

    private fun launchAndCaptureV3(
        name: String,
        withBackdrop: Boolean = true,
        content: @androidx.compose.runtime.Composable () -> Unit,
        verify: (Variant) -> Unit,
    ) {
        if (record) java.io.File("build/outputs/screenshots/v3").mkdirs()
        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            pump(4)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            if (withBackdrop) {
                                GlassBackdrop { content() }
                            } else {
                                content()
                            }
                        }
                    }
                }
                pump(8)
                verify(variant)
                if (record) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/screenshots/v3/$name-${variant.name.lowercase()}.png",
                    )
                }
            }
        }
    }

    @Test
    fun appLockGateUnlockWithPinPadAndBiometric() {
        val state = AppLockGateUiState(
            mode = AppLockGateMode.UNLOCK_APP,
            targetAppLabel = "Vault Bank",
            targetPackageName = "com.example.banking",
            enteredDigitsCount = 2,
            expectedPinLength = 6,
            hasPinConfigured = true,
            biometricAvailable = true,
            allowBiometric = true,
        )
        launchAndCaptureV3(
            name = "applock-gate-unlock",
            withBackdrop = false,
            content = {
                AppLockGateScreen(
                    state = state,
                    onDigitPressed = {},
                    onBackspacePressed = {},
                    onBiometricPressed = {},
                    onCancelPressed = {},
                )
            },
            verify = {
                compose.onNodeWithTag("applock-gate-root").assertIsDisplayed()
                compose.onNodeWithTag("applock-gate-title").assertIsDisplayed()
                compose.onNodeWithTag("applock-pin-dots").assertIsDisplayed()
                compose.onNodeWithTag("applock-key-1").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("applock-key-biometric").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("applock-gate-cancel").assertHeightIsAtLeast(Tokens.TouchTarget)
            },
        )
    }

    @Test
    fun appLockGatePauseAuthAndLockoutState() {
        val state = AppLockGateUiState(
            mode = AppLockGateMode.AUTHORIZE_PAUSE,
            targetAppLabel = "Pause App Lock",
            enteredDigitsCount = 0,
            expectedPinLength = 6,
            hasPinConfigured = true,
            biometricAvailable = true,
            allowBiometric = true,
            errorMessage = "Incorrect PIN. Lockout active.",
            lockoutRemainingMs = 30_000L,
        )
        launchAndCaptureV3(
            name = "applock-gate-lockout",
            withBackdrop = false,
            content = {
                AppLockGateScreen(
                    state = state,
                    onDigitPressed = {},
                    onBackspacePressed = {},
                    onBiometricPressed = {},
                    onCancelPressed = {},
                )
            },
            verify = {
                compose.onNodeWithTag("applock-gate-root").assertIsDisplayed()
                compose.onNodeWithTag("applock-lockout-banner").assertIsDisplayed()
                compose.onNodeWithTag("applock-gate-error").assertIsDisplayed()
                compose.onNodeWithTag("applock-gate-cancel").assertHeightIsAtLeast(Tokens.TouchTarget)
            },
        )
    }

    @Test
    fun appLockManagerPopulatedWithTamperRecommendationAndApps() {
        val state = AppLockManagerUiState(
            config = AppLockConfig(
                enabled = true,
                lockedPackages = setOf("com.example.banking", "com.example.gallery"),
                gracePeriod = ReLockGracePeriod.ONE_MIN,
                allowBiometric = true,
                tamperProtectionEnabled = false,
            ),
            hasPinConfigured = true,
            pinLength = 6,
            isPaused = false,
            monitorMode = AppLockMonitorMode.ACCESSIBILITY_ACTIVE,
            tamperProtection = TamperProtectionStatus(
                isSettingsLocked = false,
                isRecommended = true,
                reason = "Lock Android Settings (com.android.settings) to prevent disabling App Lock",
            ),
            installedApps = sampleApps,
        )
        launchAndCaptureV3(
            name = "applock-manager-populated",
            content = {
                AppLockManagerContent(
                    state = state,
                    onBack = {},
                    onToggleEnabled = {},
                    onOpenPinSetup = {},
                    onResumeFromPause = {},
                    onToggleTamperProtection = {},
                    onSelectGracePeriod = {},
                    onToggleBiometric = {},
                    onSearchQueryChange = {},
                    onToggleAppLocked = {},
                    onRetryLoadApps = {},
                    onOpenAccessibilitySettings = {},
                    onOpenUsageAccessSettings = {},
                )
            },
            verify = { variant ->
                compose.onNodeWithTag("applock-manager-screen").assertIsDisplayed()
                compose.onNodeWithTag("applock-master-toggle").assertHeightIsAtLeast(Tokens.TouchTarget)
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithTag("applock-protect-settings-button").assertHeightIsAtLeast(Tokens.TouchTarget)
                }
            },
        )
    }

    @Test
    fun appLockManagerPausedAndSettingsProtectedWithUsageFallback() {
        val state = AppLockManagerUiState(
            config = AppLockConfig(
                enabled = true,
                lockedPackages = setOf(
                    "com.example.banking",
                    AppLockConfig.SETTINGS_PACKAGE_NAME,
                ),
                gracePeriod = ReLockGracePeriod.FIVE_MIN,
                allowBiometric = true,
                tamperProtectionEnabled = true,
            ),
            hasPinConfigured = true,
            pinLength = 6,
            isPaused = true,
            pauseRemainingMs = 12L * 60_000L,
            monitorMode = AppLockMonitorMode.USAGE_STATS_FALLBACK,
            tamperProtection = TamperProtectionStatus(
                isSettingsLocked = true,
                isRecommended = false,
                reason = "Android Settings is protected against tampering",
            ),
            installedApps = sampleApps,
        )
        launchAndCaptureV3(
            name = "applock-manager-paused",
            content = {
                AppLockManagerContent(
                    state = state,
                    onBack = {},
                    onToggleEnabled = {},
                    onOpenPinSetup = {},
                    onResumeFromPause = {},
                    onToggleTamperProtection = {},
                    onSelectGracePeriod = {},
                    onToggleBiometric = {},
                    onSearchQueryChange = {},
                    onToggleAppLocked = {},
                    onRetryLoadApps = {},
                    onOpenAccessibilitySettings = {},
                    onOpenUsageAccessSettings = {},
                )
            },
            verify = { variant ->
                compose.onNodeWithTag("applock-paused-banner").assertIsDisplayed()
                compose.onNodeWithTag("applock-resume-now").assertHeightIsAtLeast(Tokens.TouchTarget)
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithTag("applock-open-accessibility").assertHeightIsAtLeast(Tokens.TouchTarget)
                }
            },
        )
    }

    @Test
    fun appLockManagerErrorAndPermissionSetupStates() {
        val state = AppLockManagerUiState(
            errorMessage = "Unable to read installed apps.",
            config = AppLockConfig(enabled = false),
            hasPinConfigured = false,
            monitorMode = AppLockMonitorMode.NEEDS_PERMISSION,
            tamperProtection = TamperProtectionStatus(
                isSettingsLocked = false,
                isRecommended = false,
                reason = "App lock is disabled",
            ),
            installedApps = emptyList(),
        )
        launchAndCaptureV3(
            name = "applock-manager-states",
            content = {
                AppLockManagerContent(
                    state = state,
                    onBack = {},
                    onToggleEnabled = {},
                    onOpenPinSetup = {},
                    onResumeFromPause = {},
                    onToggleTamperProtection = {},
                    onSelectGracePeriod = {},
                    onToggleBiometric = {},
                    onSearchQueryChange = {},
                    onToggleAppLocked = {},
                    onRetryLoadApps = {},
                    onOpenAccessibilitySettings = {},
                    onOpenUsageAccessSettings = {},
                )
            },
            verify = { variant ->
                compose.onNodeWithTag("applock-manager-screen").assertIsDisplayed()
                compose.onNodeWithTag("applock-master-toggle").assertHeightIsAtLeast(Tokens.TouchTarget)
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithTag("applock-monitor-card").assertIsDisplayed()
                    compose.onNodeWithTag("applock-open-accessibility").assertHeightIsAtLeast(Tokens.TouchTarget)
                    compose.onNodeWithTag("applock-open-usage").assertHeightIsAtLeast(Tokens.TouchTarget)
                }
            },
        )
    }

    @Test
    fun interactivePinPadAndProtectSettingsWorkCleanly() {
        var entered by mutableStateOf("")
        var protected by mutableStateOf(false)

        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        AppLockGateScreen(
                            state = AppLockGateUiState(
                                enteredDigitsCount = entered.length,
                                expectedPinLength = 4,
                            ),
                            onDigitPressed = { entered += it },
                            onBackspacePressed = { entered = entered.dropLast(1) },
                            onBiometricPressed = {},
                            onCancelPressed = {},
                        )
                    }
                }
            }
            pump(6)
            compose.onNodeWithTag("applock-key-1").performClick()
            compose.onNodeWithTag("applock-key-2").performClick()
            compose.onNodeWithTag("applock-key-backspace").performClick()
            pump(4)
            assertEquals("1", entered)
        }

        var query by mutableStateOf("")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            AppLockManagerContent(
                                state = AppLockManagerUiState(
                                    config = AppLockConfig(enabled = true, tamperProtectionEnabled = protected),
                                    hasPinConfigured = true,
                                    monitorMode = AppLockMonitorMode.ACCESSIBILITY_ACTIVE,
                                    tamperProtection = TamperProtectionStatus(
                                        isSettingsLocked = protected,
                                        isRecommended = !protected,
                                        reason = if (protected) "Protected" else "Recommended",
                                    ),
                                    searchQuery = query,
                                    installedApps = sampleApps,
                                ),
                                onBack = {},
                                onToggleEnabled = {},
                                onOpenPinSetup = {},
                                onResumeFromPause = {},
                                onToggleTamperProtection = { protected = !protected },
                                onSelectGracePeriod = {},
                                onToggleBiometric = {},
                                onSearchQueryChange = { query = it },
                                onToggleAppLocked = {},
                                onRetryLoadApps = {},
                                onOpenAccessibilitySettings = {},
                                onOpenUsageAccessSettings = {},
                            )
                        }
                    }
                }
            }
            pump(6)
            compose.onNodeWithTag("applock-protect-settings-button").performClick()
            pump(4)
            assertTrue(protected)
        }
    }

    private companion object {
        const val FIXED_TIME_MS = 1_763_766_900_000L
    }
}
