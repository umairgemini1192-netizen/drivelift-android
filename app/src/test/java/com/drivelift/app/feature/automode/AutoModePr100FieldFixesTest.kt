package com.drivelift.app.feature.automode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.feature.automode.ui.AutoModeSetupContent
import com.drivelift.app.feature.cleaner.ui.AutoModeProgressPill
import com.drivelift.app.feature.cleaner.ui.CleanerDashboardContent
import com.drivelift.app.feature.cleaner.ui.CleanerDashboardUiState
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.SleepExecutionReport
import com.drivelift.app.feature.hibernation.engine.SleepItemOutcome
import com.drivelift.app.feature.hibernation.engine.SleepItemResult
import com.drivelift.app.feature.hibernation.engine.SleepSessionPhase
import com.drivelift.app.feature.hibernation.ui.SleepModeContent
import com.drivelift.app.feature.hibernation.ui.SleepModeUiState
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Verifies PR #100 Auto Mode field fixes from preview.45 (Pixel 8 Pro / Android 17):
 * - Step 1 covers both not-unlocked (About phone / Build number x7) and already-unlocked
 *   (Open Developer options + "Switch on Use developer options at the top") with live
 *   ContentObserver auto-advance on `DEVELOPMENT_SETTINGS_ENABLED`.
 * - Step 2 checks POST_NOTIFICATIONS & channel importance before posting, offers permission
 *   request / settings deep-link, numbered instructions, live pairing discovery indicator + 60 s
 *   timeout error, and in-app 6-digit code + optional pairing port fallback.
 * - Progress card Cancel button, Sleep/Clean summaries showing "Wireless debugging turned off",
 *   and Ready card "Keep Wireless debugging on after Auto Mode" toggle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AutoModePr100FieldFixesTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w412dp-h1600dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
    }

    @Test
    fun step1ShowsBothAboutPhoneAndDeveloperOptionsActionsAndObserverDrivesAdvance() {
        var aboutClicks = 0
        var devOptionsClicks = 0
        var refreshClicks = 0

        val step1 = AutoModeWizardState(
            step = AutoModeWizardStep.ENABLE_DEVELOPER_OPTIONS,
            isDeveloperOptionsEnabled = false,
            isOnWifi = true,
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            AutoModeSetupContent(
                                state = step1,
                                onBack = {},
                                onRefresh = { refreshClicks++ },
                                onOpenAboutPhone = { aboutClicks++ },
                                onOpenDeveloperOptions = { devOptionsClicks++ },
                                onOpenWifiSettings = {},
                                onPostNotificationAndOpenWirelessDebugging = {},
                                onSubmitPairingCode = {},
                                onRetryBootstrap = {},
                                onTurnOffAutoMode = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()

            compose.onNodeWithText("Tap Build number 7 times").assertExists()
            compose.onNodeWithTag("automode-open-about-phone")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()

            compose.onNodeWithText("Switch on Use developer options at the top").assertExists()
            compose.onNodeWithTag("automode-open-developer-options")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()

            compose.onNodeWithTag("automode-refresh-button").performClick()
            compose.waitForIdle()

            assertEquals(1, aboutClicks)
            assertEquals(1, devOptionsClicks)
            assertEquals(1, refreshClicks)
        }

        // ContentObserver on DEVELOPMENT_SETTINGS_ENABLED advances Step 1 -> Step 2 automatically.
        val machine = AutoModeWizardStateMachine()
        var current = step1
        Settings.Global.putInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        val gateway = AndroidAutoModeSystemGateway(app)
        assertFalse(gateway.isDeveloperOptionsEnabled())

        val observer = DeveloperOptionsObserver(
            context = app,
            onChanged = {
                current = machine.onEnvironmentUpdated(
                    current,
                    AutoModeEnvironmentSnapshot(
                        isDeveloperOptionsEnabled = gateway.isDeveloperOptionsEnabled(),
                        isOnWifi = true,
                        hasStoredKeyPair = false,
                        isPaired = false,
                        hasWriteSecureSettings = false,
                        isBootstrapComplete = false,
                        isAutoModeEnabled = false,
                    ),
                )
            },
        )
        observer.register()
        try {
            Settings.Global.putInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            observer.contentObserver.onChange(
                false,
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            )
            assertEquals(AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING, current.step)
            assertTrue(current.isDeveloperOptionsEnabled)
        } finally {
            observer.unregister()
        }
    }

    @Test
    fun pairingNotifierChecksPermissionAndChannelImportanceBeforePosting() {
        val shadowApp = shadowOf(app)
        val nm = app.getSystemService(NotificationManager::class.java)!!
        shadowOf(nm).setNotificationsEnabled(true)

        // Missing POST_NOTIFICATIONS runtime permission on API 33+ must block posting cleanly.
        shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            NotificationAccessState.NEEDS_RUNTIME_PERMISSION,
            AdbPairingNotifier.checkNotificationAccess(app),
        )
        assertFalse(AdbPairingNotifier.showPairingPrompt(app))

        // Granted permission but app notifications disabled -> BLOCKED_IN_SETTINGS.
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(nm).setNotificationsEnabled(false)
        assertEquals(
            NotificationAccessState.BLOCKED_IN_SETTINGS,
            AdbPairingNotifier.checkNotificationAccess(app),
        )
        assertFalse(AdbPairingNotifier.showPairingPrompt(app))

        // Granted permission + app enabled + channel importance NONE -> BLOCKED_IN_SETTINGS.
        shadowOf(nm).setNotificationsEnabled(true)
        nm.createNotificationChannel(
            NotificationChannel(
                AdbPairingNotifier.CHANNEL_ID,
                "Auto Mode setup",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertEquals(
            NotificationAccessState.BLOCKED_IN_SETTINGS,
            AdbPairingNotifier.checkNotificationAccess(app),
        )
        assertFalse(AdbPairingNotifier.showPairingPrompt(app))

        // Simulate user re-enabling the channel in system settings (Android/Robolectric
        // createNotificationChannel intentionally ignores attempts by an app to raise
        // an existing channel's importance above IMPORTANCE_NONE).
        nm.getNotificationChannel(AdbPairingNotifier.CHANNEL_ID)!!.importance =
            NotificationManager.IMPORTANCE_HIGH
        assertEquals(
            NotificationAccessState.ALLOWED,
            AdbPairingNotifier.checkNotificationAccess(app),
        )
        assertTrue(AdbPairingNotifier.showPairingPrompt(app))
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun step2ShowsPermissionBranchDiscoveryIndicatorAndSubmitsInAppCodeAndPort() {
        var requestedPermission = 0
        var submittedCode = ""
        var submittedPort = ""

        val step2 = AutoModeWizardState(
            step = AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING,
            isDeveloperOptionsEnabled = true,
            isOnWifi = true,
            notificationAccess = NotificationAccessState.NEEDS_RUNTIME_PERMISSION,
            isDiscoveringPairingService = true,
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            AutoModeSetupContent(
                                state = step2,
                                onBack = {},
                                onRefresh = {},
                                onOpenAboutPhone = {},
                                onOpenWifiSettings = {},
                                onRequestNotificationPermission = { requestedPermission++ },
                                onPostNotificationAndOpenWirelessDebugging = {},
                                onSubmitPairingCode = {},
                                onSubmitPairingCodeWithPort = { code, port ->
                                    submittedCode = code
                                    submittedPort = port
                                },
                                onRetryBootstrap = {},
                                onTurnOffAutoMode = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()

            compose.onNodeWithTag("automode-notification-permission-banner").assertExists()
            compose.onNodeWithTag("automode-request-notification-permission")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            assertEquals(1, requestedPermission)

            compose.onNodeWithText("Waiting for pairing service on this phone...").assertExists()

            compose.onNodeWithTag("automode-pairing-code-input")
                .performTextInput("482913")
            compose.onNodeWithTag("automode-pairing-port-input")
                .performTextInput("37111")
            compose.onNodeWithTag("automode-submit-pairing-code")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            compose.waitForIdle()

            assertEquals("482913", submittedCode)
            assertEquals("37111", submittedPort)
        }
    }

    @Test
    fun step2ShowsPlainEnglishDiscoveryTimeoutAndReadyCardShowsKeepWirelessDebuggingToggle() {
        var retriedDiscovery = 0
        var toggledKeepOn: Boolean? = null

        val timeoutStep2 = AutoModeWizardState(
            step = AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING,
            isDeveloperOptionsEnabled = true,
            isOnWifi = true,
            isDiscoveringPairingService = false,
            discoveryErrorMessage = AdbSilentExecutor.PAIRING_DISCOVERY_TIMEOUT_MESSAGE,
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            AutoModeSetupContent(
                                state = timeoutStep2,
                                onBack = {},
                                onRefresh = {},
                                onOpenAboutPhone = {},
                                onOpenWifiSettings = {},
                                onPostNotificationAndOpenWirelessDebugging = {},
                                onRetryDiscovery = { retriedDiscovery++ },
                                onSubmitPairingCode = {},
                                onRetryBootstrap = {},
                                onTurnOffAutoMode = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(AdbSilentExecutor.PAIRING_DISCOVERY_TIMEOUT_MESSAGE).assertExists()
            compose.onNodeWithTag("automode-pairing-discovery-retry")
                .performClick()
            assertEquals(1, retriedDiscovery)
        }

        val readyState = AutoModeWizardState(
            step = AutoModeWizardStep.READY,
            isDeveloperOptionsEnabled = true,
            isOnWifi = true,
            hasStoredKeyPair = true,
            isPaired = true,
            hasWriteSecureSettings = true,
            isBootstrapComplete = true,
            keepWirelessDebuggingOn = false,
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            AutoModeSetupContent(
                                state = readyState,
                                onBack = {},
                                onRefresh = {},
                                onOpenAboutPhone = {},
                                onOpenWifiSettings = {},
                                onPostNotificationAndOpenWirelessDebugging = {},
                                onSubmitPairingCode = {},
                                onRetryBootstrap = {},
                                onToggleKeepWirelessDebuggingOn = { toggledKeepOn = it },
                                onTurnOffAutoMode = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Keep Wireless debugging on after Auto Mode").assertIsDisplayed()
            compose.onNodeWithTag("automode-keep-wireless-debugging-switch")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            assertEquals(true, toggledKeepOn)
        }
    }

    @Test
    fun progressCancelButtonAndRunSummariesShowWirelessDebuggingTurnedOff() {
        var cancelClicks = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            AutoModeProgressPill(
                                message = "Auto Mode: sleeping ChatGPT (4 of 16)",
                                onCancel = { cancelClicks++ },
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("automode-progress-cancel")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            assertEquals(1, cancelClicks)
        }

        val report = SleepExecutionReport(
            sessionId = "sleep-16",
            mode = HibernationMode.AUTO_SILENT,
            finalPhase = SleepSessionPhase.COMPLETED,
            totalCandidates = 16,
            sleptPackages = (1..15).map { "com.example.app$it" },
            skippedPackages = listOf("com.openai.chatgpt"),
            failedPackages = emptyList(),
            cancelledPackages = emptyList(),
            results = (1..15).map {
                SleepItemResult("com.example.app$it", "App $it", SleepItemOutcome.SLEPT, "Hibernated via Auto Mode")
            } + SleepItemResult("com.openai.chatgpt", "ChatGPT", SleepItemOutcome.SKIPPED, "skipped (timeout)"),
            startedAtMs = 1_000L,
            completedAtMs = 20_000L,
            wirelessDebuggingTurnedOff = true,
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            SleepModeContent(
                                state = SleepModeUiState(lastReport = report),
                                onBack = {},
                                onRefresh = {},
                                onGrantUsageAccess = {},
                                onToggleSelectCandidate = {},
                                onProtectCandidate = {},
                                onRemoveProtectedRule = {},
                                onStartSleep = {},
                                onDismissReport = {},
                                onOpenGuidedAppInfo = {},
                                onConfirmGuidedStep = {},
                                onSkipGuidedStep = {},
                                onCancelGuidedSession = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Slept 15 of 16 apps - ChatGPT skipped (did not respond)").assertExists()
            compose.onNodeWithText("Wireless debugging turned off").assertExists()
        }

        val cleanCelebration = CleanerDashboardUiState(
            celebrationReclaimedBytes = 512L * 1024L * 1024L,
            celebrationDeletedCount = 8,
            celebrationWirelessDebuggingTurnedOff = true,
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CleanerDashboardContent(
                                state = cleanCelebration,
                                onBack = {},
                                onRescan = {},
                                onGrantUsageAccess = {},
                                onToggleCategoryExpand = {},
                                onToggleCategorySelection = {},
                                onToggleItemSelection = {},
                                onExecuteClean = {},
                                onDismissCelebration = {},
                                onOpenDeepClean = {},
                                onOpenGuidedAppInfo = {},
                                onConfirmGuidedStep = {},
                                onSkipGuidedStep = {},
                                onCancelGuidedSession = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("cleaner-celebration-wireless-off").assertExists()
            compose.onNodeWithText("Wireless debugging turned off").assertExists()
        }
    }
}
