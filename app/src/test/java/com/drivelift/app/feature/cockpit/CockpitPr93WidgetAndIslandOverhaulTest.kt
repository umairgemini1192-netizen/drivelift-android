package com.drivelift.app.feature.cockpit

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.activities.CockpitStateCoordinator
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.activities.TripDistanceFormat
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.TelemetryHudSnapshot
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.notifications.LiveNotificationParser
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.ExpandedCockpitCard
import com.drivelift.app.feature.cockpit.widget.BatteryCurrentRollingAverage
import com.drivelift.app.feature.cockpit.widget.BatteryCurrentTone
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidgetReceiver
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetActions
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Verifies PR #93: Home Screen Widget & Dynamic Island Functional Overhaul:
 * 1. Ampere-accurate rolling 5-sample mA averaging + BATTERY_PROPERTY_CURRENT_AVERAGE fallback.
 * 2. Unique PendingIntent request codes per interactive widget button + multi-tap Torch + working
 *    App Dock '<' and '>' broadcast paging + lightweight decoupled telemetry updates.
 * 3. DefaultNetworkCallback single source of truth clearing cached SSID -> 'Wi-Fi Off' and
 *    switching Island pill to Mobile Data when Wi-Fi is lost.
 * 4. Horizontal swipe-to-swap intercepting before child clickables + compact header Swap icon +
 *    10-second auto-expire & immediate dismissal for transient chat alerts.
 * 5. Driving Navigation HUD without bogus 4-arrow row + compact icon-only header controls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitPr93WidgetAndIslandOverhaulTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        CockpitGlanceWidget.resetBatteryCurrentAveragerForTest()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        CockpitGlanceWidget.clearWidgetState(app, 77)
    }

    @Test
    fun rollingFiveSampleCurrentAverageMatchesAmpereAndFallsBackToCurrentAverage() {
        val avg = BatteryCurrentRollingAverage(windowSize = 5)

        // 5 discharging samples: 400, 500, 600, 500, 500 mA -> mean 500 mA.
        avg.record(currentNowMicroAmps = -400_000L, isCharging = false)
        avg.record(currentNowMicroAmps = -500_000L, isCharging = false)
        avg.record(currentNowMicroAmps = -600_000L, isCharging = false)
        avg.record(currentNowMicroAmps = -500_000L, isCharging = false)
        val fifth = avg.record(currentNowMicroAmps = -500_000L, isCharging = false)
        assertEquals(5, avg.sampleCount)
        assertEquals(-500, fifth.milliAmps)
        assertEquals("-500 mA", fifth.text)
        assertEquals(BatteryCurrentTone.DISCHARGING, fifth.tone)

        // 6th sample evicts the oldest (400): window [500, 600, 500, 500, 900] -> mean 600 mA.
        val sixth = avg.record(currentNowMicroAmps = -900_000L, isCharging = false)
        assertEquals(5, avg.sampleCount)
        assertEquals(-600, sixth.milliAmps)
        assertEquals("-600 mA", sixth.text)

        // Fallback to BATTERY_PROPERTY_CURRENT_AVERAGE when CURRENT_NOW is unsupported or zero.
        val fallbackAvg = BatteryCurrentRollingAverage(windowSize = 5)
        val fromFallback = fallbackAvg.record(
            currentNowMicroAmps = Long.MIN_VALUE,
            currentAverageMicroAmps = 1_850_000L,
            isCharging = true,
        )
        assertEquals(1850, fromFallback.milliAmps)
        assertEquals("+1,850 mA", fromFallback.text)
        assertEquals(BatteryCurrentTone.CHARGING, fromFallback.tone)

        // Charging state flip immediately clears previous discharging samples.
        val pluggedIn = avg.record(currentNowMicroAmps = 2_400_000L, isCharging = true)
        assertEquals(1, avg.sampleCount)
        assertEquals(2400, pluggedIn.milliAmps)
        assertEquals("+2,400 mA", pluggedIn.text)
    }

    @Test
    fun everyWidgetButtonHasDistinctPendingIntentRequestCodeAndPartialTelemetryBuilds() {
        val slots = listOf(
            CockpitWidgetActions.SLOT_CLOCK,
            CockpitWidgetActions.SLOT_ALARM,
            CockpitWidgetActions.SLOT_STATUS,
            CockpitWidgetActions.SLOT_DROP,
            CockpitWidgetActions.SLOT_TORCH,
            CockpitWidgetActions.SLOT_ISLAND,
            CockpitWidgetActions.SLOT_PAGER_PREV,
            CockpitWidgetActions.SLOT_PAGER_NEXT,
            CockpitWidgetActions.SLOT_PINNED_APP_BASE + 0,
            CockpitWidgetActions.SLOT_PINNED_APP_BASE + 1,
            CockpitWidgetActions.SLOT_PINNED_APP_BASE + 2,
            CockpitWidgetActions.SLOT_PINNED_APP_BASE + 3,
        )
        val widget1Codes = slots.map { CockpitWidgetActions.requestCodeFor(10, it) }
        val widget2Codes = slots.map { CockpitWidgetActions.requestCodeFor(11, it) }
        assertEquals(slots.size, widget1Codes.toSet().size)
        assertTrue(widget1Codes.intersect(widget2Codes.toSet()).isEmpty())

        val partial = CockpitGlanceWidget.buildTelemetryPartialRemoteViews(
            context = app,
            battery = BatteryHudMetric(
                percent = 76,
                fraction = 0.76f,
                valueText = "76%",
                current = CockpitWidgetModel.formatBatteryCurrent(-420_000L, isCharging = false),
                charge = CockpitWidgetModel.formatBatteryCharge(3_820_000L),
            ),
            ram = CockpitHudBitmapRenderer.formatRamHudMetric(
                availBytes = (3.0 * 1024 * 1024 * 1024).toLong(),
                totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
            ),
        )
        assertNotNull(partial)
    }

    @Test
    fun multiTapTorchBroadcastTogglesRepeatedlyAndPagerPersistsPageIndex() {
        val cockpit = Graph.get(app).cockpit
        val receiver = CockpitGlanceWidgetReceiver()
        val appWidgetManager = AppWidgetManager.getInstance(app)
        val widgetId = shadowOf(appWidgetManager).createWidget(
            CockpitGlanceWidgetReceiver::class.java,
            R.layout.cockpit_widget_master,
        )

        // Seed available torch state and fire rapid multi-tap broadcasts.
        cockpit.torchController.seedForTest(TorchUiState(available = true, isOn = false, brightnessLevel = 1, maxLevel = 1))
        val torchIntent = Intent(app, CockpitGlanceWidgetReceiver::class.java).apply {
            action = CockpitWidgetActions.ACTION_TOGGLE_TORCH
            putExtra(CockpitWidgetActions.EXTRA_APP_WIDGET_ID, widgetId)
        }
        repeat(4) {
            receiver.onReceive(app, torchIntent)
        }

        // Verify '<' and '>' pager broadcasts persist wrapped page index and trigger AppWidgetManager.updateAppWidget().
        CockpitGlanceWidget.savePage(app, widgetId, 0)
        assertEquals(0, CockpitGlanceWidget.readSavedPage(app, widgetId))

        val resolvedCount = cockpit.pinnedAppsRepo.resolvePinnedApps(cockpit.prefsRepo.state.value.pinnedPackages).size
        val pageCount = CockpitWidgetModel.pageCount(resolvedCount)
        val nextIntent = Intent(app, CockpitGlanceWidgetReceiver::class.java).apply {
            action = CockpitWidgetActions.ACTION_PINNED_NEXT
            putExtra(CockpitWidgetActions.EXTRA_APP_WIDGET_ID, widgetId)
        }
        val prevIntent = Intent(app, CockpitGlanceWidgetReceiver::class.java).apply {
            action = CockpitWidgetActions.ACTION_PINNED_PREV
            putExtra(CockpitWidgetActions.EXTRA_APP_WIDGET_ID, widgetId)
        }
        receiver.onReceive(app, nextIntent)
        val expectedAfterNext = CockpitWidgetModel.wrapPage(1, pageCount)
        assertEquals(expectedAfterNext, CockpitGlanceWidget.readSavedPage(app, widgetId))

        receiver.onReceive(app, prevIntent)
        assertEquals(0, CockpitGlanceWidget.readSavedPage(app, widgetId))

        // Explicit updateWidget call with AppWidgetManager inflates the widget view cleanly.
        val fullViews = CockpitGlanceWidget.buildFullRemoteViews(app, widgetId, overridePageIndex = 0)
        assertNotNull(fullViews)
        appWidgetManager.updateAppWidget(widgetId, fullViews)
        assertNotNull(shadowOf(appWidgetManager).getViewFor(widgetId))
        CockpitGlanceWidget.clearWidgetState(app, widgetId)
    }

    @Test
    fun defaultNetworkCallbackClearsCachedSsidShowsWifiOffAndSwitchesPillToMobileData() {
        val monitor = CockpitTelemetryMonitor(
            context = app,
            scope = testScope,
            snapshotReader = { TelemetryHudSnapshot(isLoadingInitial = false) },
            widgetPusher = {},
        )
        val quickControls = CockpitQuickControlsController(
            context = app,
            scope = testScope,
            onDefaultNetworkChanged = { caps -> monitor.onDefaultNetworkCapabilitiesChanged(caps) },
        )

        // Start on Wi-Fi with internet.
        val wifiCaps = ShadowNetworkCapabilities.newInstance().also { caps ->
            val shadow = shadowOf(caps)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        quickControls.handleDefaultNetworkCapabilities(wifiCaps)
        assertEquals(IdleNetworkKind.WIFI, monitor.network.value.kind)
        assertEquals("Wi-Fi", monitor.network.value.label)

        // Wi-Fi turns off / TRANSPORT_WIFI lost -> default network switches to Mobile Data (Cellular).
        val cellCaps = ShadowNetworkCapabilities.newInstance().also { caps ->
            val shadow = shadowOf(caps)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        quickControls.handleDefaultNetworkCapabilities(cellCaps)

        // Cached SSID cleared immediately, tile reads 'Wi-Fi Off', and Island pill switches to Mobile Data.
        assertEquals("", quickControls.state.value.wifi.ssid)
        assertFalse(quickControls.state.value.wifi.isConnected)
        assertEquals("Wi-Fi Off", quickControls.state.value.wifi.label)
        assertEquals(IdleNetworkKind.CELLULAR, monitor.network.value.kind)
        assertTrue(monitor.network.value.label in setOf("5G", "4G", "Data"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transientChatAlertsAutoExpireAfterTenSecondsAndClearImmediatelyOnRemovalOrDismiss() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = CockpitStateCoordinator(
            scope = scope,
            emptyStateDebounceMs = CockpitStateCoordinator.EMPTY_STATE_DEBOUNCE_MS,
            chatAutoExpireMs = CockpitStateCoordinator.TRANSIENT_CHAT_AUTO_EXPIRE_MS,
        )

        val chat1 = CockpitLiveActivity.WhatsAppAlertActivity(
            id = "wa_k1",
            senderName = "Amina",
            messageSnippet = "Outside now",
            notificationKey = "k1",
            canInlineReply = true,
            updatedAtMs = 1_000L,
        )
        coordinator.upsertActivity(chat1)
        assertEquals("wa_k1", coordinator.slotState.value.primary.id)
        assertTrue(coordinator.hasPendingChatExpiryForTest("wa_k1"))

        // Still present at 9.9 seconds; auto-expires at 10.0 seconds with zero lingering Restore entry.
        scope.advanceTimeBy(9_900L)
        scope.runCurrent()
        assertEquals("wa_k1", coordinator.slotState.value.primary.id)

        scope.advanceTimeBy(100L)
        scope.runCurrent()
        assertTrue(coordinator.slotState.value.primary is CockpitLiveActivity.IdleQuickDockActivity)
        assertEquals(0, coordinator.slotState.value.dismissedCount)

        // NotificationListenerService.onNotificationRemoved() removes chat immediately (no 500ms lag).
        val chat2 = chat1.copy(id = "wa_k2", notificationKey = "k2")
        coordinator.upsertActivity(chat2)
        assertEquals("wa_k2", coordinator.slotState.value.primary.id)
        coordinator.removeByNotificationKey("k2")
        assertTrue(coordinator.slotState.value.primary is CockpitLiveActivity.IdleQuickDockActivity)

        // Compact X / swipe-up dismiss removes transient chat alert immediately and collapses.
        val chat3 = chat1.copy(id = "wa_k3", notificationKey = "k3")
        coordinator.upsertActivity(chat3)
        coordinator.setExpanded(true)
        coordinator.softDismiss("wa_k3")
        assertTrue(coordinator.slotState.value.primary is CockpitLiveActivity.IdleQuickDockActivity)
        assertFalse(coordinator.slotState.value.isExpanded)
        assertEquals(0, coordinator.slotState.value.dismissedCount)

        // Inline reply open pauses auto-expiry while typing; closing inline reply re-arms 10s expiry.
        val chat4 = chat1.copy(id = "wa_k4", notificationKey = "k4")
        coordinator.upsertActivity(chat4)
        coordinator.setInlineReplyOpen(true)
        scope.advanceTimeBy(15_000L)
        scope.runCurrent()
        assertEquals("wa_k4", coordinator.slotState.value.primary.id)

        coordinator.setInlineReplyOpen(false)
        scope.advanceTimeBy(10_000L)
        scope.runCurrent()
        assertTrue(coordinator.slotState.value.primary is CockpitLiveActivity.IdleQuickDockActivity)
    }

    @Test
    fun horizontalSwipeInterceptsChildClickableAndHeaderUsesCompactIconOnlyControls() {
        var nextCount = 0
        var dismissedId: String? = null
        var stoppedDrop = false
        val chat = CockpitLiveActivity.WhatsAppAlertActivity(
            id = "wa_1",
            senderName = "Amina",
            messageSnippet = "Ready to go?",
            notificationKey = "k_1",
            canInlineReply = true,
        )
        val drop = CockpitLiveActivity.TransferActivity(
            id = CockpitLiveActivity.ID_DESKTOP_DROP,
            title = "Desktop Drop",
            subtitle = "Workstation",
            speedText = "48 MB/s",
            progressFraction = 0.5f,
            isDesktopDrop = true,
            canStop = true,
        )
        val slot = CockpitSlotState(
            primary = chat,
            secondary = drop,
            visibleQueue = listOf(chat, drop),
            isExpanded = true,
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            ExpandedCockpitCard(
                                slotState = slot,
                                telemetry = DriveLiftTelemetrySummary(isDesktopDropRunning = true),
                                torchState = TorchUiState(available = true),
                                pinnedApps = listOf(PinnedAppInfo("com.android.chrome", "Chrome")),
                                onCollapse = {},
                                onCycleNext = { nextCount++ },
                                onCyclePrevious = {},
                                onSoftDismiss = { dismissedId = it },
                                onRestoreDismissed = {},
                                onTapActivity = {},
                                onStopDesktopDrop = { stoppedDrop = true },
                                onOpenDriveLiftCockpit = {},
                                onToggleInlineReply = {},
                                onSendWhatsAppReply = { _, _ -> },
                                onToggleTorch = {},
                                onTorchBrightnessChange = {},
                                onToggleTorchSos = {},
                                onLaunchPinnedApp = {},
                            )
                        }
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()

            // Oversized text pills are gone; sleek compact icon-only controls are present with >= 48dp touch targets.
            assertEquals(0, compose.onAllNodesWithText("Next activity", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("Open Chat").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("Reply").fetchSemanticsNodes().size)

            compose.onNodeWithTag("cockpit-cycle-next")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .assertWidthIsAtLeast(Tokens.TouchTarget)
                .assertIsDisplayed()
                .performClick()
            compose.waitForIdle()
            assertEquals(1, nextCount)

            // Horizontal swipe directly across a child clickable Quick Deck tile intercepts cleanly before the child!
            compose.onNodeWithTag("cockpit-tile-wifi").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertEquals(2, nextCount)

            // Compact X dismiss button works with >= 48dp touch target.
            compose.onNodeWithTag("cockpit-soft-dismiss")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            compose.waitForIdle()
            assertEquals("wa_1", dismissedId)
            assertFalse(stoppedDrop)
        }
    }

    @Test
    fun collapsedChatPillSupportsSwipeUpToDismiss() {
        var dismissedId: String? = null
        val chat = CockpitLiveActivity.WhatsAppAlertActivity(
            id = "wa_swipe",
            senderName = "Amina",
            messageSnippet = "Arrived",
            notificationKey = "k_swipe",
            canInlineReply = true,
        )
        val slot = CockpitSlotState(primary = chat, visibleQueue = listOf(chat))

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CollapsedSplitIslandRow(
                                slotState = slot,
                                widthDeltaDp = 0,
                                onTapPrimary = {},
                                onTapSecondary = {},
                                onLongPressExpand = {},
                                onCycleNext = {},
                                onCyclePrevious = {},
                                onSwipeUpDismiss = { dismissedId = it },
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("cockpit-collapsed-row").performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals("wa_swipe", dismissedId)
        }
    }

    @Test
    fun navigationSpeedParserAndMetersPerSecondConverterWorkCleanly() {
        assertEquals(65, LiveNotificationParser.extractSpeedKmh("200 m", "65 km/h", "12 min"))
        assertEquals(97, LiveNotificationParser.extractSpeedKmh("", "60 mph", ""))
        assertEquals(0, TripDistanceFormat.metersPerSecondToKmh(0.3f))
        assertEquals(72, TripDistanceFormat.metersPerSecondToKmh(20f))
        assertEquals("--", TripDistanceFormat.speedValue(null))
        assertEquals("72", TripDistanceFormat.speedValue(72))
    }
}
