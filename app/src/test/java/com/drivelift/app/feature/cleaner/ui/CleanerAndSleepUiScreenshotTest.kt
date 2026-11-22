package com.drivelift.app.feature.cleaner.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.cleaner.cache.GuidedPillMode
import com.drivelift.app.feature.cleaner.cache.GuidedPillState
import com.drivelift.app.feature.cleaner.cache.ShizukuAvailability
import com.drivelift.app.feature.cleaner.cache.ShizukuStatusSnapshot
import com.drivelift.app.feature.cleaner.engine.CleanerCategory
import com.drivelift.app.feature.cleaner.engine.CleanerCategoryGroup
import com.drivelift.app.feature.cleaner.engine.CleanerItem
import com.drivelift.app.feature.cleaner.engine.StorageCapacityBreakdown
import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.ProtectedAppRule
import com.drivelift.app.feature.hibernation.engine.ProtectionReason
import com.drivelift.app.feature.hibernation.engine.SleepExecutionReport
import com.drivelift.app.feature.hibernation.engine.SleepSessionPhase
import com.drivelift.app.feature.hibernation.engine.StandbyBucket
import com.drivelift.app.feature.hibernation.ui.SleepModeContent
import com.drivelift.app.feature.hibernation.ui.SleepModeUiState
import com.drivelift.app.feature.hibernation.ui.defaultProtectedRules
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
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
 * Renders the Phase 3 Cleaner Dashboard, Guided Progress Dynamic Island pill, and Sleep Mode
 * surfaces across STANDARD, LARGE_FONT (1.5x), and COMPACT (320dp) configurations under
 * `build/outputs/screenshots/v3/` so the 239-image baseline under `build/outputs/screenshots/`
 * remains unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CleanerAndSleepUiScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val record = System.getProperty("roborazzi.test.record") == "true"

    private enum class Variant(val qualifiers: String, val fontScale: Float) {
        STANDARD("w360dp-h780dp-xhdpi", 1f),
        LARGE_FONT("w360dp-h780dp-xhdpi", 1.5f),
        COMPACT("w320dp-h640dp-xhdpi", 1f),
    }

    private val sampleSafeGroup = CleanerCategoryGroup(
        category = CleanerCategory.HIDDEN_CACHE,
        items = listOf(
            CleanerItem(
                id = "HIDDEN_CACHE:com.example.browser",
                path = "app-cache://com.example.browser/internal",
                name = "Web Browser",
                category = CleanerCategory.HIDDEN_CACHE,
                sizeBytes = 640L * 1024L * 1024L,
                lastModifiedMs = FIXED_TIME_MS - 3_600_000L,
                adviceLabel = "Advice: clean",
                selectedByDefault = true,
                packageName = "com.example.browser",
            ),
            CleanerItem(
                id = "HIDDEN_CACHE:com.example.maps",
                path = "app-cache://com.example.maps/internal",
                name = "City Maps",
                category = CleanerCategory.HIDDEN_CACHE,
                sizeBytes = 290L * 1024L * 1024L,
                lastModifiedMs = FIXED_TIME_MS - 7_200_000L,
                adviceLabel = "Advice: clean",
                selectedByDefault = true,
                packageName = "com.example.maps",
            ),
        ),
        totalBytes = 930L * 1024L * 1024L,
        selectedBytes = 930L * 1024L * 1024L,
        selectedCount = 2,
    )

    private val sampleReviewGroup = CleanerCategoryGroup(
        category = CleanerCategory.WHATSAPP_BLOAT,
        items = listOf(
            CleanerItem(
                id = "WHATSAPP_BLOAT:/storage/WhatsApp/Media/WhatsApp Video/Sent/VID_01.mp4",
                path = "/storage/WhatsApp/Media/WhatsApp Video/Sent/VID_01.mp4",
                name = "VID_01.mp4",
                category = CleanerCategory.WHATSAPP_BLOAT,
                sizeBytes = 420L * 1024L * 1024L,
                lastModifiedMs = FIXED_TIME_MS - 86_400_000L,
                adviceLabel = "Clean up carefully",
                selectedByDefault = false,
            ),
        ),
        totalBytes = 420L * 1024L * 1024L,
        selectedBytes = 0L,
        selectedCount = 0,
    )

    private val sampleBreakdown = StorageCapacityBreakdown(
        totalDeviceBytes = 128L * 1024L * 1024L * 1024L,
        freeDeviceBytes = 24L * 1024L * 1024L * 1024L,
        safeUnneededBytes = 510L * 1024L * 1024L,
        hiddenCacheBytes = 930L * 1024L * 1024L,
        filesToReviewBytes = 1_420L * 1024L * 1024L,
    )

    private val sampleDeepCards = listOf(
        DeepCleanCardSummary(DeepCleanDestination.WHATSAPP, "WhatsApp", "14 items", 420L * 1024L * 1024L, 14),
        DeepCleanCardSummary(DeepCleanDestination.LARGE_FILES, "Large Files", "4 files", 880L * 1024L * 1024L, 4),
        DeepCleanCardSummary(DeepCleanDestination.DUPLICATES, "Duplicates", "6 extra copies", 310L * 1024L * 1024L, 6),
        DeepCleanCardSummary(DeepCleanDestination.SLEEP_MODE, "Sleep Mode", "3 active drainers", 0L, 3),
        DeepCleanCardSummary(DeepCleanDestination.RARELY_USED, "Rarely Used", "5 items", 190L * 1024L * 1024L, 5),
    )

    private val sampleDrainers = listOf(
        AppDrainCandidate(
            packageName = "com.example.social",
            appLabel = "Social Feed",
            drainScore = 84,
            backgroundTimeMs = 72L * 60_000L,
            lastTimeUsedMs = FIXED_TIME_MS - 15 * 60_000L,
            standbyBucket = StandbyBucket.ACTIVE,
            isRunningInBackground = true,
            reasons = listOf("Currently active in background", "72m background activity"),
        ),
        AppDrainCandidate(
            packageName = "com.example.shopping",
            appLabel = "Quick Shop",
            drainScore = 58,
            backgroundTimeMs = 28L * 60_000L,
            lastTimeUsedMs = FIXED_TIME_MS - 3 * 3_600_000L,
            standbyBucket = StandbyBucket.WORKING_SET,
            isRunningInBackground = false,
            reasons = listOf("28m background activity", "Unrestricted standby bucket (WORKING_SET)"),
        ),
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
                            GlassBackdrop {
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
    fun cleanerDashboardPopulatedWithAccordionsAndHero() {
        val state = CleanerDashboardUiState(
            groups = listOf(sampleSafeGroup, sampleReviewGroup),
            selectedItemIds = setOf(
                "HIDDEN_CACHE:com.example.browser",
                "HIDDEN_CACHE:com.example.maps",
            ),
            expandedCategories = setOf(CleanerCategory.HIDDEN_CACHE),
            breakdown = sampleBreakdown,
            deepCleanCards = sampleDeepCards,
            shizukuStatus = ShizukuStatusSnapshot(
                availability = ShizukuAvailability.NOT_INSTALLED,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = "Shizuku not installed. Guided Pill fallback is ready.",
            ),
        )
        launchAndCaptureV3(
            name = "cleaner-dashboard-populated",
            content = {
                CleanerDashboardContent(
                    state = state,
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
            },
            verify = { variant ->
                compose.onNodeWithTag("cleaner-mood-hero").assertIsDisplayed()
                compose.onNodeWithTag("cleaner-odometer-counter").assertIsDisplayed()
                compose.onNodeWithTag("cleaner-segmented-capacity-bar").assertIsDisplayed()
                compose.onNodeWithTag("cleaner-execute-button").assertHeightIsAtLeast(Tokens.TouchTarget)
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithText("Advice: clean").assertExists()
                }
            },
        )
    }

    @Test
    fun cleanerDashboardCelebrationAndDeepCleanCards() {
        val cleanBreakdown = StorageCapacityBreakdown(
            totalDeviceBytes = 128L * 1024L * 1024L * 1024L,
            freeDeviceBytes = 25L * 1024L * 1024L * 1024L,
            safeUnneededBytes = 0L,
            hiddenCacheBytes = 0L,
            filesToReviewBytes = 420L * 1024L * 1024L,
        )
        val state = CleanerDashboardUiState(
            breakdown = cleanBreakdown,
            deepCleanCards = sampleDeepCards,
            celebrationReclaimedBytes = 930L * 1024L * 1024L,
            celebrationDeletedCount = 24,
            shizukuStatus = ShizukuStatusSnapshot(
                availability = ShizukuAvailability.READY,
                effectiveMode = HibernationMode.SHIZUKU_SILENT,
                detail = "Shizuku silent mode is active.",
            ),
        )
        launchAndCaptureV3(
            name = "cleaner-dashboard-celebration",
            content = {
                CleanerDashboardContent(
                    state = state,
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
            },
            verify = { variant ->
                compose.onNodeWithTag("cleaner-mood-hero").assertIsDisplayed()
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithTag("cleaner-celebration-panel").assertIsDisplayed()
                    compose.onNodeWithText("Cleanup complete").assertIsDisplayed()
                    compose.onNodeWithTag("cleaner-celebration-done").assertHeightIsAtLeast(Tokens.TouchTarget)
                }
            },
        )
    }

    @Test
    fun cleanerDashboardPermissionErrorAndEmptyState() {
        val state = CleanerDashboardUiState(
            needsUsageAccess = true,
            errorMessage = "Grant Usage Access to measure hidden app caches.",
            groups = emptyList(),
            breakdown = StorageCapacityBreakdown(
                totalDeviceBytes = 128L * 1024L * 1024L * 1024L,
                freeDeviceBytes = 64L * 1024L * 1024L * 1024L,
                safeUnneededBytes = 0L,
                hiddenCacheBytes = 0L,
                filesToReviewBytes = 0L,
            ),
        )
        launchAndCaptureV3(
            name = "cleaner-dashboard-states",
            content = {
                CleanerDashboardContent(
                    state = state,
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
            },
            verify = {
                compose.onNodeWithTag("cleaner-error-banner").assertIsDisplayed()
                compose.onNodeWithTag("cleaner-grant-usage-button").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("cleaner-retry-button").assertHeightIsAtLeast(Tokens.TouchTarget)
            },
        )
    }

    @Test
    fun guidedDynamicIslandPillOverlay() {
        val pillState = GuidedPillState(
            mode = GuidedPillMode.GUIDED_CLEAN,
            isActive = true,
            currentIndex = 1,
            totalCount = 4,
            currentAppLabel = "Web Browser",
            currentPackageName = "com.example.browser",
            instruction = "Open App Info, tap Storage, then Clear cache.",
            completedCount = 1,
            skippedCount = 0,
            reclaimedBytes = 290L * 1024L * 1024L,
        )
        launchAndCaptureV3(
            name = "cleaner-guided-pill",
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Tokens.Edge),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Section),
                ) {
                    GuidedDynamicIslandPill(
                        state = pillState,
                        onOpenAppInfo = {},
                        onConfirmStep = {},
                        onSkipStep = {},
                        onCancel = {},
                    )
                }
            },
            verify = {
                compose.onNodeWithTag("guided-dynamic-island-pill").assertIsDisplayed()
                compose.onNodeWithText("2 of 4").assertIsDisplayed()
                compose.onNodeWithTag("guided-pill-open-settings").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("guided-pill-confirm").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("guided-pill-skip").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("guided-pill-cancel").assertHeightIsAtLeast(Tokens.TouchTarget)
            },
        )
    }

    @Test
    fun sleepModePopulatedWithDrainersAndProtectedWhitelist() {
        val state = SleepModeUiState(
            candidates = sampleDrainers,
            selectedPackages = setOf("com.example.social", "com.example.shopping"),
            protectedRules = defaultProtectedRules() + ProtectedAppRule(
                packageName = "com.example.messenger",
                reason = ProtectionReason.USER_WHITELIST,
                label = "Messenger",
                immutable = false,
            ),
            shizukuStatus = ShizukuStatusSnapshot(
                availability = ShizukuAvailability.READY,
                effectiveMode = HibernationMode.SHIZUKU_SILENT,
                detail = "Shizuku silent mode is active.",
            ),
        )
        launchAndCaptureV3(
            name = "sleep-mode-populated",
            content = {
                SleepModeContent(
                    state = state,
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
            },
            verify = { variant ->
                compose.onNodeWithTag("sleep-mode-screen").assertIsDisplayed()
                compose.onNodeWithTag("sleep-mode-status-card").assertIsDisplayed()
                compose.onNodeWithTag("sleep-one-tap-button").assertHeightIsAtLeast(Tokens.TouchTarget)
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithText("Social Feed").assertExists()
                }
            },
        )
    }

    @Test
    fun sleepModeGuidedPillAndSessionReport() {
        val state = SleepModeUiState(
            candidates = sampleDrainers,
            selectedPackages = setOf("com.example.social"),
            protectedRules = defaultProtectedRules(),
            guidedPill = GuidedPillState(
                mode = GuidedPillMode.GUIDED_SLEEP,
                isActive = true,
                currentIndex = 0,
                totalCount = 2,
                currentAppLabel = "Social Feed",
                currentPackageName = "com.example.social",
                instruction = "Open App Info, then tap Force stop.",
                completedCount = 0,
                skippedCount = 0,
            ),
            lastReport = SleepExecutionReport(
                sessionId = "sleep-1",
                mode = HibernationMode.GUIDED_PILL,
                finalPhase = SleepSessionPhase.COMPLETED,
                totalCandidates = 2,
                sleptPackages = listOf("com.example.social"),
                skippedPackages = listOf("com.example.shopping"),
                failedPackages = emptyList(),
                cancelledPackages = emptyList(),
                results = emptyList(),
                startedAtMs = FIXED_TIME_MS - 30_000L,
                completedAtMs = FIXED_TIME_MS,
            ),
        )
        launchAndCaptureV3(
            name = "sleep-mode-guided",
            content = {
                SleepModeContent(
                    state = state,
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
            },
            verify = { variant ->
                compose.onNodeWithTag("guided-dynamic-island-pill").assertIsDisplayed()
                compose.onNodeWithText("Guided Sleep").assertIsDisplayed()
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithTag("sleep-report-panel").assertExists()
                }
            },
        )
    }

    @Test
    fun accordionExpandCollapseInteractionWorksCleanly() {
        var expanded by mutableStateOf(emptySet<CleanerCategory>())
        val baseState = CleanerDashboardUiState(
            groups = listOf(sampleSafeGroup),
            selectedItemIds = setOf("HIDDEN_CACHE:com.example.browser"),
            breakdown = sampleBreakdown,
        )
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CleanerDashboardContent(
                                state = baseState.copy(expandedCategories = expanded),
                                onBack = {},
                                onRescan = {},
                                onGrantUsageAccess = {},
                                onToggleCategoryExpand = { cat ->
                                    expanded = if (cat in expanded) expanded - cat else expanded + cat
                                },
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
            pump(6)

            assertTrue(
                compose.onAllNodesWithTag("cleaner-item-HIDDEN_CACHE:com.example.browser")
                    .fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithTag("cleaner-accordion-header-hidden_cache").performClick()
            pump(10)
            compose.onNodeWithTag("cleaner-item-HIDDEN_CACHE:com.example.browser").assertIsDisplayed()
        }
    }

    private companion object {
        const val FIXED_TIME_MS = 1_763_766_900_000L
    }
}
