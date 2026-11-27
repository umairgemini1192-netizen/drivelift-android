package com.drivelift.app.feature.cockpit

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.NetworkCapabilities
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.ui.IdlePillLayoutMath
import com.drivelift.app.feature.cockpit.ui.IdleSpeedDisplayMode
import com.drivelift.app.feature.cockpit.widget.TodayMobileDataCache
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.BluetoothTileState
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsState
import com.drivelift.app.feature.cockpit.hardware.CockpitRingerMode
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.MobileDataTileState
import com.drivelift.app.feature.cockpit.hardware.RingerTileState
import com.drivelift.app.feature.cockpit.hardware.TelemetryHudSnapshot
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.hardware.WifiTileState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.ExpandedCockpitCard
import com.drivelift.app.feature.cockpit.ui.LocalIdleNetworkOverride
import com.drivelift.app.feature.cockpit.ui.canShowSatelliteBubble
import com.drivelift.app.feature.cockpit.ui.computeSafePillMaxWidthDp
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.feature.cockpit.widget.WidgetSizeVariant
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.io.FileOutputStream

/**
 * Verifies PR #99:
 * - Wi-Fi SSID live updates across A -> B switches, Wi-Fi -> mobile -> Wi-Fi, switches while
 *   screen is off, redacted `<unknown ssid>` fallback, and location-off plain hint.
 * - Expanded Dynamic Island card: single compact header action row, 1-line subtitle, live Desktop
 *   Drop & default info strips, aligned 2-column quick deck grid with labelled Torch tile, Wi-Fi
 *   marquee with reduced-motion respect, and pinned apps scroll chevron indicator.
 * - All collapsed activity pills respect status-pill width cap, cutout centring, and prioritize
 *   live network speeds over static text like "LAN ready".
 * - Responsive Home Screen Master Widget across 4x2, 4x3, 5x3, and 4x4 cell sizes with zero
 *   clipping, no large empty bands, aligned DROP LIVE status badge, and tall network/data row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitPr99StackedSpeedsWifiCardWidgetTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val record = System.getProperty("roborazzi.test.record") == "true"

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w412dp-h915dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
    }

    private fun wifiCapabilities(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also { caps ->
        val shadow = shadowOf(caps)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun cellularCapabilities(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also { caps ->
        val shadow = shadowOf(caps)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    // ------------------------------------------------------------------ 3. Wi-Fi SSID live updates

    @Test
    fun wifiSsidUpdatesAcrossNetworkSwitchesScreenOffRedactedFallbackAndLocationOff() {
        var screenOn = true
        var wifiEnabled = true
        var locationAccess = true to true
        var transportSsid: String? = "\"StormFiber-46B6-5G\""
        var managerSsid: String? = "\"StormFiber-46B6-5G\""
        var activeCaps: NetworkCapabilities? = wifiCapabilities()

        val controller = CockpitQuickControlsController(
            context = app,
            scope = testScope,
            isScreenInteractive = { screenOn },
            activeCapabilitiesProvider = { activeCaps },
            wifiEnabledProvider = { wifiEnabled },
            wifiManagerSsidProvider = { managerSsid },
            locationAccessProvider = { locationAccess },
            transportSsidExtractor = { caps ->
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) transportSsid else null
            },
        )
        controller.start()

        // Initial Wi-Fi A
        assertEquals("StormFiber-46B6-5G", controller.state.value.wifi.label)
        assertEquals("StormFiber-46B6-5G", controller.state.value.wifi.ssid)
        assertFalse(controller.state.value.wifi.needsLocationHint)

        // (1) Direct A -> B Wi-Fi switch replaces cached SSID immediately on capabilities change.
        transportSsid = "\"Office-Mesh-6E\""
        managerSsid = "\"Office-Mesh-6E\""
        activeCaps = wifiCapabilities()
        controller.handleDefaultNetworkCapabilities(activeCaps)
        assertEquals("Office-Mesh-6E", controller.state.value.wifi.label)
        assertEquals("Office-Mesh-6E", controller.state.value.wifi.ssid)

        // (2) Wi-Fi -> Mobile -> Wi-Fi (Network C) clears SSID on mobile and loads C on reconnect.
        activeCaps = cellularCapabilities()
        controller.handleDefaultNetworkCapabilities(activeCaps)
        assertEquals("Wi-Fi Off", controller.state.value.wifi.label)
        assertEquals("", controller.state.value.wifi.ssid)
        assertFalse(controller.state.value.wifi.isConnected)

        transportSsid = "\"Home-Fiber-5G\""
        managerSsid = "\"Home-Fiber-5G\""
        activeCaps = wifiCapabilities()
        controller.handleDefaultNetworkCapabilities(activeCaps)
        assertEquals("Home-Fiber-5G", controller.state.value.wifi.label)

        // (3) Redacted "<unknown ssid>" from transportInfo MUST NOT block WifiManager fallback.
        transportSsid = "<unknown ssid>"
        managerSsid = "\"Cafe-Guest-WiFi\""
        activeCaps = wifiCapabilities()
        controller.handleDefaultNetworkCapabilities(activeCaps)
        assertEquals("Cafe-Guest-WiFi", controller.state.value.wifi.label)
        assertEquals("Cafe-Guest-WiFi", controller.state.value.wifi.ssid)
        assertNull(CockpitQuickControlsController.extractValidSsid("<unknown ssid>"))
        assertNull(CockpitQuickControlsController.extractValidSsid("\"<unknown ssid>\""))
        assertNull(CockpitQuickControlsController.extractValidSsid("0x"))

        // (4) Switch while screen is off: onScreenOn re-reads activeNetwork capabilities immediately.
        screenOn = false
        controller.onScreenOff()
        transportSsid = "\"Airport-Lounge-5G\""
        managerSsid = "\"Airport-Lounge-5G\""
        activeCaps = wifiCapabilities()
        screenOn = true
        controller.onScreenOn()
        assertEquals("Airport-Lounge-5G", controller.state.value.wifi.label)

        // (5) Location permission/services off: shows plain "Wi-Fi connected" + one-tap hint, never stale SSID.
        locationAccess = true to false
        controller.refreshWifiState()
        assertEquals("Wi-Fi connected", controller.state.value.wifi.label)
        assertEquals("", controller.state.value.wifi.ssid)
        assertTrue(controller.state.value.wifi.needsLocationHint)
        assertNotNull(controller.state.value.wifi.locationHint)

        locationAccess = false to true
        controller.refreshWifiState()
        assertEquals("Wi-Fi connected", controller.state.value.wifi.label)
        assertTrue(controller.state.value.wifi.needsLocationHint)

        // Turning Wi-Fi off clears everything cleanly.
        wifiEnabled = false
        controller.refreshWifiState()
        assertEquals("Wi-Fi Off", controller.state.value.wifi.label)
        assertFalse(controller.state.value.wifi.needsLocationHint)

        controller.stop()
    }

    // ------------------------------------------------------------------ 4. Expanded card & marquee

    @Test
    fun expandedCardShowsDesktopDropInfoStripAlignedGridTorchLabelScrollChevronAndWifiHint() {
        var locationHintClicks = 0
        val dropActivity = CockpitLiveActivity.TransferActivity(
            id = CockpitLiveActivity.ID_DESKTOP_DROP,
            title = "Desktop Drop",
            subtitle = "Workstation connected over local Wi-Fi",
            speedText = "LAN ready",
            progressFraction = null,
            isDesktopDrop = true,
            canStop = true,
        )
        val telemetry = DriveLiftTelemetrySummary(
            isDesktopDropRunning = true,
            isDropAvailable = true,
            connectedPeerName = "Workstation",
            desktopDropUrl = "http://192.168.1.42:8080",
            desktopDropPairingCode = "74-29",
            desktopDropUploadBytesPerSec = 12L * 1024L * 1024L,
            desktopDropDownloadBytesPerSec = 4L * 1024L * 1024L,
            desktopDropSentCount = 3,
            desktopDropReceivedCount = 2,
            statusHeadline = "Drop listening",
        )
        val quickControls = CockpitQuickControlsState(
            wifi = WifiTileState(
                isEnabled = true,
                isConnected = true,
                ssid = "",
                hasLocationPermission = false,
                label = "Wi-Fi connected",
                needsLocationHint = true,
                locationHint = "Tap to allow location for name",
            ),
            mobileData = MobileDataTileState(
                isEnabled = true,
                hasUsageAccess = true,
                monthlyBytes = (14.2 * 1024 * 1024 * 1024).toLong(),
                label = "14.2 GB / mo",
            ),
            bluetooth = BluetoothTileState(
                isEnabled = true,
                hasPermission = true,
                connectedCount = 1,
                label = "1 connected",
            ),
            ringer = RingerTileState(
                mode = CockpitRingerMode.NORMAL,
                hasDndAccess = true,
                label = "Ringer",
            ),
        )
        val manyPinned = listOf(
            PinnedAppInfo("com.app.one", "Maps"),
            PinnedAppInfo("com.app.two", "WhatsApp"),
            PinnedAppInfo("com.app.three", "Chrome"),
            PinnedAppInfo("com.app.four", "Photos"),
            PinnedAppInfo("com.app.five", "Clock"),
            PinnedAppInfo("com.app.six", "Calendar"),
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalReducedMotion provides true) {
                                ExpandedCockpitCard(
                                    slotState = CockpitSlotState(primary = dropActivity, isExpanded = true),
                                    telemetry = telemetry,
                                    torchState = TorchUiState(available = true, isOn = false),
                                    quickControls = quickControls,
                                    pinnedApps = manyPinned,
                                    onCollapse = {},
                                    onCycleNext = {},
                                    onCyclePrevious = {},
                                    onSoftDismiss = {},
                                    onRestoreDismissed = {},
                                    onTapActivity = {},
                                    onStopDesktopDrop = {},
                                    onOpenDriveLiftCockpit = {},
                                    onToggleInlineReply = {},
                                    onSendWhatsAppReply = { _, _ -> },
                                    onTapWifiLocationHint = { locationHintClicks++ },
                                    onToggleTorch = {},
                                    onTorchBrightnessChange = {},
                                    onToggleTorchSos = {},
                                    onLaunchPinnedApp = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()

            // Single compact icon action row & single-line subtitle.
            compose.onNodeWithTag("cockpit-header-actions").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-expanded-subtitle").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-expanded-open-app").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-stop-drop").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-soft-dismiss").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-collapse").assertIsDisplayed()

            // Live Desktop Drop info strip: URL + Copy action, PIN, session counts, speeds.
            compose.onNodeWithTag("cockpit-expanded-info-strip").assertIsDisplayed()
            compose.onNodeWithText("http://192.168.1.42:8080").assertIsDisplayed()
            compose.onNodeWithText("PIN 74-29").assertIsDisplayed()
            compose.onNodeWithText("Sent 3 \u00B7 Recv 2").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-drop-copy-url")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            compose.waitForIdle()
            val clipText = app.getSystemService(ClipboardManager::class.java)
                ?.primaryClip?.getItemAt(0)?.text?.toString()
            assertEquals("http://192.168.1.42:8080", clipText)
            compose.onNodeWithText("Copied").assertIsDisplayed()

            // One-tap Wi-Fi location hint works cleanly.
            compose.onNodeWithTag("cockpit-wifi-location-hint")
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            compose.waitForIdle()
            assertEquals(1, locationHintClicks)

            // Aligned 2-column Quick Deck Grid: Row 1 & Row 2 share exact column edges, Torch has
            // label and spans the exact grid outer edges (no lone odd-sized square).
            val wifiBounds = compose.onNodeWithTag("cockpit-tile-wifi").getBoundsInRoot()
            val dataBounds = compose.onNodeWithTag("cockpit-tile-data").getBoundsInRoot()
            val btBounds = compose.onNodeWithTag("cockpit-tile-bluetooth").getBoundsInRoot()
            val ringerBounds = compose.onNodeWithTag("cockpit-tile-ringer").getBoundsInRoot()
            val torchBounds = compose.onNodeWithTag("cockpit-torch-toggle").getBoundsInRoot()

            assertEquals(wifiBounds.left.value, btBounds.left.value, 1f)
            assertEquals(wifiBounds.right.value, btBounds.right.value, 1f)
            assertEquals(dataBounds.left.value, ringerBounds.left.value, 1f)
            assertEquals(dataBounds.right.value, ringerBounds.right.value, 1f)
            assertEquals(wifiBounds.left.value, torchBounds.left.value, 1f)
            assertEquals(dataBounds.right.value, torchBounds.right.value, 1f)
            compose.onNodeWithText("Torch").assertIsDisplayed()

            // Full SSID/status is present in the tile content description.
            compose.onNodeWithContentDescription("Wi-Fi, Wi-Fi connected").assertIsDisplayed()

            // Overflowing pinned apps row shows the scroll chevron hint.
            compose.onNodeWithTag("cockpit-pinned-scroll-hint", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun expandedCardWrapsSixHeaderActionsOnCompact320DpSoCollapseRemainsTappable() {
        val drop = CockpitLiveActivity.TransferActivity(
            id = CockpitLiveActivity.ID_DESKTOP_DROP,
            title = "Desktop Drop",
            subtitle = "Waiting for transfer",
            speedText = "LAN ready",
            progressFraction = null,
            isDesktopDrop = true,
            canStop = true,
        )
        val nav = CockpitLiveActivity.MapsNavActivity(
            id = "nav:compact",
            maneuverText = "Turn left",
            distanceText = "300 m",
            etaText = "4 min",
        )
        var collapsedCount = 0

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            Box(modifier = Modifier.width(320.dp)) {
                                ExpandedCockpitCard(
                                    slotState = CockpitSlotState(
                                        primary = drop,
                                        secondary = nav,
                                        visibleQueue = listOf(drop, nav),
                                        dismissedCount = 1,
                                    ),
                                    telemetry = DriveLiftTelemetrySummary(
                                        isDesktopDropRunning = true,
                                        desktopDropUrl = "http://192.168.1.42:8080",
                                    ),
                                    torchState = TorchUiState(available = true, isOn = false),
                                    pinnedApps = emptyList(),
                                    onCollapse = { collapsedCount++ },
                                    onCycleNext = {},
                                    onCyclePrevious = {},
                                    onSoftDismiss = {},
                                    onRestoreDismissed = {},
                                    onTapActivity = {},
                                    onStopDesktopDrop = {},
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
            }
            compose.waitForIdle()

            // All 6 header buttons (Swap, Restore, Open, Stop, Close, Collapse) must be reachable
            // without horizontal scrolling even inside a 320 dp viewport.
            compose.onNodeWithTag("cockpit-cycle-next").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-restore-hidden").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-expanded-open-app").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-stop-drop").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-soft-dismiss").assertIsDisplayed()
            compose.onNodeWithTag("cockpit-collapse").assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals(1, collapsedCount)
        }
    }

    @Test
    fun expandedTimerActivityPreservesTimeReadoutInInfoStrip() {
        val timer = CockpitLiveActivity.TimerRecordingActivity(
            id = "timer:test",
            label = "Kitchen Timer",
            timeText = "04:32",
            isRecording = false,
            packageName = "com.google.android.deskclock",
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            ExpandedCockpitCard(
                                slotState = CockpitSlotState(primary = timer),
                                telemetry = DriveLiftTelemetrySummary(),
                                torchState = TorchUiState(available = true, isOn = false),
                                pinnedApps = emptyList(),
                                onCollapse = {},
                                onCycleNext = {},
                                onCyclePrevious = {},
                                onSoftDismiss = {},
                                onRestoreDismissed = {},
                                onTapActivity = {},
                                onStopDesktopDrop = {},
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
            compose.waitForIdle()
            compose.onNodeWithText("04:32").assertIsDisplayed()
        }
    }

    // ------------------------------------------------------------------ 5. Activity pill cap & speeds

    @Test
    fun collapsedDesktopDropPillRespectsWidthCapCutoutGapAndShowsLiveSpeedsOverStaticText() {
        val dropActivity = CockpitLiveActivity.TransferActivity(
            id = CockpitLiveActivity.ID_DESKTOP_DROP,
            title = "Desktop Drop",
            subtitle = "Waiting for transfer",
            speedText = "LAN ready",
            progressFraction = null,
            isDesktopDrop = true,
            canStop = true,
        )
        val liveTraffic = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 48L * 1024L,
            downloadBytesPerSec = (2.6 * 1024 * 1024).toLong(),
        )
        val capDp = computeSafePillMaxWidthDp(screenWidthDp = 412, widthDeltaDp = 0, hasSatelliteBubble = false)
        // With a satellite bubble + symmetric start counterweight, total split row width stays within safe margins.
        val bothCounterweights = ((Tokens.TouchTarget + Tokens.Cockpit.SplitGap).value.toInt()) * 2
        val compactSplitPrimary = computeSafePillMaxWidthDp(screenWidthDp = 320, widthDeltaDp = 0, hasSatelliteBubble = true)
        assertTrue(
            "320dp split row ${compactSplitPrimary + bothCounterweights}dp must preserve 48dp side margins",
            compactSplitPrimary + bothCounterweights <= 320 - 96,
        )
        val pixelSplitPrimary = computeSafePillMaxWidthDp(screenWidthDp = 412, widthDeltaDp = 0, hasSatelliteBubble = true)
        assertTrue(
            "412dp split row ${pixelSplitPrimary + bothCounterweights}dp must respect hard cap",
            pixelSplitPrimary + bothCounterweights <= Tokens.Cockpit.PillHardMaxWidth.value.toInt(),
        )
        // With a physical cutout where split width would starve speeds, satellite is suppressed so
        // the primary pill keeps full safe width and renders speeds without clipping.
        assertFalse(canShowSatelliteBubble(screenWidthDp = 320, widthDeltaDp = 0, cutoutWidthDp = 32, fontScale = 1f))
        assertFalse(canShowSatelliteBubble(screenWidthDp = 412, widthDeltaDp = 0, cutoutWidthDp = 34, fontScale = 1f))
        assertTrue(canShowSatelliteBubble(screenWidthDp = 412, widthDeltaDp = 0, cutoutWidthDp = 0, fontScale = 1f))

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalIdleNetworkOverride provides liveTraffic) {
                                CollapsedSplitIslandRow(
                                    slotState = CockpitSlotState(primary = dropActivity),
                                    widthDeltaDp = 0,
                                    cutoutWidthDp = 34,
                                    onTapPrimary = {},
                                    onTapSecondary = {},
                                    onLongPressExpand = {},
                                    onCycleNext = {},
                                    onCyclePrevious = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()

            val pillBounds = compose.onNodeWithTag("cockpit-primary-pill").assertIsDisplayed().getBoundsInRoot()
            val gapBounds = compose.onNodeWithTag("cockpit-pill-cutout-gap", useUnmergedTree = true).getBoundsInRoot()
            val speedBounds = compose.onNodeWithTag("cockpit-idle-speed").assertIsDisplayed().getBoundsInRoot()

            assertTrue(
                "Activity pill width ${pillBounds.right - pillBounds.left} must stay <= ${capDp}dp cap",
                (pillBounds.right - pillBounds.left).value <= capDp + 1f,
            )
            assertTrue("Speeds must sit right of cutout gap", speedBounds.left >= gapBounds.right)
            // Live speeds replace static "LAN ready" text when traffic is active.
            compose.onNodeWithText("48K").assertIsDisplayed()
            compose.onNodeWithText("2.6M").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithText("LAN ready").fetchSemanticsNodes().size)
        }
    }

    // ------------------------------------------------------------------ 6. Responsive Home Widget

    @Test
    fun widgetSizeVariantMappingAndResponsiveRemoteViewsBuildersWorkCleanly() {
        assertEquals(WidgetSizeVariant.COMPACT, CockpitGlanceWidget.selectWidgetSizeVariant(276f, 140f))
        assertEquals(WidgetSizeVariant.NORMAL, CockpitGlanceWidget.selectWidgetSizeVariant(276f, 220f))
        assertEquals(WidgetSizeVariant.NORMAL, CockpitGlanceWidget.selectWidgetSizeVariant(345f, 220f))
        assertEquals(WidgetSizeVariant.TALL, CockpitGlanceWidget.selectWidgetSizeVariant(276f, 310f))

        assertEquals("Today 420 MB", CockpitQuickControlsController.formatTodayDataUsage(420L * 1024L * 1024L))
        assertEquals(
            "Today 1.5 GB",
            CockpitQuickControlsController.formatTodayDataUsage((1.5 * 1024 * 1024 * 1024).toLong()),
        )

        val preview = CockpitGlanceWidget.buildPreviewRemoteViews(app)
        assertNotNull(preview)
        val partial = CockpitGlanceWidget.buildTelemetryPartialRemoteViews(
            context = app,
            battery = BatteryHudMetric(
                percent = 82,
                fraction = 0.82f,
                valueText = "82%",
                current = CockpitWidgetModel.formatBatteryCurrent(-420_000L, isCharging = false),
                charge = CockpitWidgetModel.formatBatteryCharge(3_900_000L),
            ),
            ram = CockpitHudBitmapRenderer.formatRamHudMetric(
                availBytes = (3.0 * 1024 * 1024 * 1024).toLong(),
                totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
            ),
        )
        assertNotNull(partial)
        val currentPartial = CockpitGlanceWidget.buildBatteryCurrentPartialRemoteViews(
            context = app,
            battery = BatteryHudMetric(
                percent = 82,
                fraction = 0.82f,
                valueText = "82%",
                current = CockpitWidgetModel.formatBatteryCurrent(-460_000L, isCharging = false),
                charge = CockpitWidgetModel.formatBatteryCharge(3_880_000L),
            ),
        )
        assertNotNull(currentPartial)
    }

    @Test
    fun widgetResponsiveSizesRenderWithoutClippingOrLargeEmptyBands() {
        data class CellSpec(
            val name: String,
            val widthDp: Float,
            val heightDp: Float,
            val expectedVariant: WidgetSizeVariant,
        )

        val specs = listOf(
            CellSpec("4x2", 276f, 140f, WidgetSizeVariant.COMPACT),
            CellSpec("4x3", 276f, 220f, WidgetSizeVariant.NORMAL),
            CellSpec("5x3", 345f, 220f, WidgetSizeVariant.NORMAL),
            CellSpec("4x4", 276f, 310f, WidgetSizeVariant.TALL),
        )
        val density = app.resources.displayMetrics.density.coerceAtLeast(2f)
        val outDir = File("build/outputs/screenshots")
        if (record) outDir.mkdirs()

        for (spec in specs) {
            val variant = CockpitGlanceWidget.selectWidgetSizeVariant(spec.widthDp, spec.heightDp)
            assertEquals("Unexpected variant for ${spec.name}", spec.expectedVariant, variant)

            val widthPx = (spec.widthDp * density).toInt()
            val heightPx = (spec.heightDp * density).toInt()
            val container = FrameLayout(app)
            val remoteViews = CockpitGlanceWidget.buildPreviewRemoteViewsForVariant(app, variant)
            val inflated = remoteViews.apply(app, container)
            // TextClock only formats automatically once attached to a real window; seed fixed
            // display time on the detached Robolectric view so layout bounds and screenshots reflect real text.
            inflated.findViewById<TextView>(R.id.cockpit_widget_time)?.text = "11:15"
            inflated.findViewById<TextView>(R.id.cockpit_widget_ampm)?.text = "PM"
            inflated.findViewById<TextView>(R.id.cockpit_widget_date)?.text = "Wed \u00B7 Nov 25"
            inflated.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            inflated.layout(0, 0, widthPx, heightPx)

            // Verify zero clipping on primary zones.
            val clockRow = inflated.findViewById<View>(R.id.cockpit_widget_clock_row)
            val statusPill = inflated.findViewById<View>(R.id.cockpit_widget_status_pill)
            val controlsRow = inflated.findViewById<View>(R.id.cockpit_widget_controls_row)
            val dockContainer = inflated.findViewById<View>(R.id.cockpit_widget_dock_container)
            assertNotNull("clockRow missing in ${spec.name}", clockRow)
            assertNotNull("statusPill missing in ${spec.name}", statusPill)
            assertNotNull("controlsRow missing in ${spec.name}", controlsRow)
            assertNotNull("dockContainer missing in ${spec.name}", dockContainer)

            // DROP LIVE status pill must sit under date inside the clock column (not floating top-right).
            var parentView = statusPill.parent
            var insideClockCol = false
            while (parentView is View) {
                if (parentView === clockRow) {
                    insideClockCol = true
                    break
                }
                parentView = parentView.parent
            }
            assertTrue(
                "Status pill must sit inside clock column in ${spec.name}",
                insideClockCol,
            )
            assertEquals(
                "DROP LIVE",
                inflated.findViewById<TextView>(R.id.cockpit_widget_status_text).text.toString(),
            )

            // No section may clip past the widget's bottom or right edges.
            assertTrue(
                "Dock clips bottom in ${spec.name}: dockBottom=${dockContainer.bottom} > height=$heightPx",
                dockContainer.bottom <= heightPx,
            )
            assertTrue(
                "Controls clip bottom in ${spec.name}: controlsBottom=${controlsRow.bottom} > height=$heightPx",
                controlsRow.bottom <= heightPx,
            )
            assertTrue(
                "Dock height collapsed in ${spec.name}: height=${dockContainer.height}",
                dockContainer.height > 0,
            )

            // Every primary control button and dock target retains >= 48 dp touch target dimensions in all sizes.
            val minTouchPx = (48f * density).toInt()
            for (btnId in intArrayOf(
                R.id.cockpit_widget_btn_drop,
                R.id.cockpit_widget_btn_torch,
                R.id.cockpit_widget_btn_island,
                R.id.cockpit_widget_btn_prev,
                R.id.cockpit_widget_btn_next,
                R.id.cockpit_widget_app_0,
            )) {
                val btn = inflated.findViewById<View>(btnId)
                assertNotNull("Interactive widget target missing in ${spec.name}", btn)
                assertTrue(
                    "Interactive widget target height ${btn.height}px < ${minTouchPx}px (48dp) in ${spec.name}",
                    btn.height >= minTouchPx,
                )
            }
            val dockPrev = inflated.findViewById<View>(R.id.cockpit_widget_btn_prev)
            val dockNext = inflated.findViewById<View>(R.id.cockpit_widget_btn_next)
            val dockApp0 = inflated.findViewById<View>(R.id.cockpit_widget_app_0)
            val dockApp3 = inflated.findViewById<View>(R.id.cockpit_widget_app_3)
            assertTrue("Prev chevron width < 48dp in ${spec.name}", dockPrev.width >= minTouchPx)
            assertTrue("Next chevron width < 48dp in ${spec.name}", dockNext.width >= minTouchPx)
            assertTrue("Prev chevron overlaps app_0 in ${spec.name}", dockPrev.right <= dockApp0.left)
            assertTrue("Next chevron overlaps app_3 in ${spec.name}", dockApp3.right <= dockNext.left)

            // Compact 4x2 hides app labels so short height never clips them.
            val appLabel0 = inflated.findViewById<View>(R.id.cockpit_widget_app_label_0)
            if (variant == WidgetSizeVariant.COMPACT) {
                assertEquals(View.GONE, appLabel0.visibility)
            } else {
                assertEquals(View.VISIBLE, appLabel0.visibility)
            }

            // Tall 4x4 shows the extra network speed + today's mobile data row and leaves no large
            // empty top/bottom bands (content spans from top padding to bottom padding).
            if (variant == WidgetSizeVariant.TALL) {
                val netRow = inflated.findViewById<View>(R.id.cockpit_widget_net_row)
                assertNotNull("Tall widget must have network/data row", netRow)
                assertEquals(View.VISIBLE, netRow.visibility)
                val netSpeedText = inflated.findViewById<TextView>(R.id.cockpit_widget_net_speed).text.toString()
                val todayDataView = inflated.findViewById<View>(R.id.cockpit_widget_today_data)
                assertTrue("Expected live speed in network chip, got '$netSpeedText'", netSpeedText.contains("1.4M"))
                assertEquals(View.GONE, todayDataView.visibility)

                val topGapFraction = clockRow.top.toFloat() / heightPx.toFloat()
                val bottomGapFraction = (heightPx - dockContainer.bottom).toFloat() / heightPx.toFloat()
                assertTrue(
                    "Tall widget top empty band too large: ${(topGapFraction * 100).toInt()}%",
                    topGapFraction <= 0.12f,
                )
                assertTrue(
                    "Tall widget bottom empty band too large: ${(bottomGapFraction * 100).toInt()}%",
                    bottomGapFraction <= 0.12f,
                )
            }

            // Render actual bitmap and save screenshot when recording.
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            inflated.draw(canvas)
            if (record) {
                FileOutputStream(File(outDir, "cockpit-widget-size-${spec.name}.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            bitmap.recycle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun tallWidgetReceivesLiveNetworkUpdatesWithoutDynamicIsland() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var nowMs = 1_000L
        var txBytes = 10_000L
        var rxBytes = 20_000L
        var pushCount = 0

        val fixedSnapshot = TelemetryHudSnapshot(
            battery = BatteryHudMetric(
                percent = 80,
                fraction = 0.80f,
                valueText = "80%",
                isCharging = false,
                current = CockpitWidgetModel.formatBatteryCurrent(-500_000L, isCharging = false),
                charge = CockpitWidgetModel.formatBatteryCharge(4_000_000L),
            ),
            ram = CockpitHudBitmapRenderer.formatRamHudMetric(
                availBytes = (4.0 * 1024 * 1024 * 1024).toLong(),
                totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
            ),
            isLoadingInitial = false,
        )

        val monitor = CockpitTelemetryMonitor(
            context = app,
            scope = scope,
            isScreenInteractive = { true },
            snapshotReader = { fixedSnapshot },
            widgetPusher = { pushCount++ },
            trafficReader = { txBytes to rxBytes },
            networkReader = { IdleNetworkKind.WIFI to null },
            elapsedClock = { nowMs },
        )

        // Binding a widget alone (with NO Dynamic Island consumer) must start live network tracking.
        monitor.setWidgetBound(true)
        scope.runCurrent()
        assertTrue("Widget bind must start telemetry ticker", monitor.isTelemetryTickerActive)
        assertTrue("Widget bind must start speed ticker for Tall widget", monitor.isSpeedTickerActive)
        assertEquals(IdleNetworkKind.WIFI, monitor.network.value.kind)
        assertEquals(1, pushCount)

        // First speed tick establishes baseline; sustained traffic ramps up across ticks so the
        // 2s telemetry tick observes active throughput regardless of equal-timestamp scheduling.
        nowMs += 1_000L
        scope.advanceTimeBy(1_000L)
        scope.runCurrent()
        repeat(3) {
            nowMs += 1_000L
            txBytes += 32L * 1024L
            rxBytes += (2.4 * 1024 * 1024).toLong()
            scope.advanceTimeBy(1_000L)
            scope.runCurrent()
        }
        assertTrue("Network snapshot must reflect live throughput", monitor.network.value.hasActiveTraffic)
        assertTrue("Network rate change alone must push Tall widget update (pushCount=$pushCount)", pushCount >= 2)

        // Verify the lightweight Tall partial RemoteViews patches network speed & today's data.
        val container = FrameLayout(app)
        val inflated = CockpitGlanceWidget.buildPreviewRemoteViewsForVariant(app, WidgetSizeVariant.TALL)
            .apply(app, container)
        val partial = CockpitGlanceWidget.buildTallNetworkPartialRemoteViewsForTest(
            context = app,
            network = monitor.network.value,
            todayDataLabel = "Today 512 MB",
        )
        partial.reapply(app, inflated)
        val netText = inflated.findViewById<TextView>(R.id.cockpit_widget_net_speed).text.toString()
        val dataView = inflated.findViewById<View>(R.id.cockpit_widget_today_data)
        assertTrue("Expected live download speed in patched Tall row, got '$netText'", netText.contains("2.4M"))
        assertEquals(View.GONE, dataView.visibility)

        // Unbinding cleanly stops both telemetry and speed tickers.
        monitor.setWidgetBound(false)
        scope.runCurrent()
        assertFalse(monitor.isTelemetryTickerActive)
        assertFalse(monitor.isSpeedTickerActive)
    }

    // ------------------------------------------------------------------ 7. PR #99 carry-over fixes

    @Test
    fun collapsedRingingCallAndActiveNavigationKeepActivityRightClusterDuringLiveTraffic() {
        val traffic = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 64L * 1024L,
            downloadBytesPerSec = (1.8 * 1024 * 1024).toLong(),
        )
        val incomingCall = CockpitLiveActivity.CallActivity(
            id = "call:incoming",
            callerName = "Alex",
            sourceLabel = "Phone",
            isVideo = false,
            state = CallState.INCOMING,
            startedAtMs = 1_000L,
            packageName = "com.google.android.dialer",
        )
        val activeNav = CockpitLiveActivity.MapsNavActivity(
            id = "nav:active",
            maneuverText = "Turn right onto Main St",
            distanceText = "450 m",
            etaText = "12 min",
        )
        val screenRec = CockpitLiveActivity.ScreenRecordingActivity(
            id = "rec:active",
            startedAtMs = 1_000L,
        )

        // Ringing call + traffic > 1K with Pixel cutout must still display "Incoming".
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalIdleNetworkOverride provides traffic) {
                                CollapsedSplitIslandRow(
                                    slotState = CockpitSlotState(primary = incomingCall),
                                    widthDeltaDp = 0,
                                    cutoutWidthDp = 34,
                                    onTapPrimary = {},
                                    onTapSecondary = {},
                                    onLongPressExpand = {},
                                    onCycleNext = {},
                                    onCyclePrevious = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Incoming").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTag("cockpit-idle-speed").fetchSemanticsNodes().size)
        }

        // Active navigation + traffic > 1K with Pixel cutout must still display ETA ("12 min").
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalIdleNetworkOverride provides traffic) {
                                CollapsedSplitIslandRow(
                                    slotState = CockpitSlotState(primary = activeNav),
                                    widthDeltaDp = 0,
                                    cutoutWidthDp = 34,
                                    onTapPrimary = {},
                                    onTapSecondary = {},
                                    onLongPressExpand = {},
                                    onCycleNext = {},
                                    onCyclePrevious = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("12 min").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTag("cockpit-idle-speed").fetchSemanticsNodes().size)
        }

        // Screen recording + traffic > 1K must keep the screen-recording stop box.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalIdleNetworkOverride provides traffic) {
                                CollapsedSplitIslandRow(
                                    slotState = CockpitSlotState(primary = screenRec),
                                    widthDeltaDp = 0,
                                    cutoutWidthDp = 34,
                                    onTapPrimary = {},
                                    onTapSecondary = {},
                                    onLongPressExpand = {},
                                    onCycleNext = {},
                                    onCyclePrevious = {},
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Screen recording in progress").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTag("cockpit-idle-speed").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun todayMobileDataCacheAvoidsRepeatedSystemQueriesWithin60SecondsAndRefreshesOnInvalidate() {
        var nowMs = 10_000L
        var queries = 0
        val cache = TodayMobileDataCache(
            ttlMs = 60_000L,
            elapsedClock = { nowMs },
            reader = {
                queries++
                "Today ${queries * 100} MB"
            },
        )

        assertEquals("Today 100 MB", cache.get(app))
        assertEquals(1, queries)

        // Repeated 1-2 s network patches within 60 s reuse cached value with zero extra system calls.
        nowMs += 2_000L
        assertEquals("Today 100 MB", cache.get(app))
        nowMs += 57_000L
        assertEquals("Today 100 MB", cache.get(app))
        assertEquals(1, queries)

        // Past 60 s TTL refreshes once.
        nowMs += 2_000L
        assertEquals("Today 200 MB", cache.get(app))
        assertEquals(2, queries)

        // Screen-on invalidation forces fresh read immediately.
        cache.invalidate()
        assertEquals("Today 300 MB", cache.get(app))
        assertEquals(3, queries)
    }

    @Test
    fun cutoutWidthBudgetPreservesSpeedWidthAndAccountsForCalibrationDelta() {
        // Positive widthDeltaDp widens the cutout gap; satellite must stay suppressed when it would starve speeds.
        assertFalse(canShowSatelliteBubble(screenWidthDp = 412, widthDeltaDp = 12, cutoutWidthDp = 0, fontScale = 1f))
        assertFalse(canShowSatelliteBubble(screenWidthDp = 412, widthDeltaDp = 8, cutoutWidthDp = 32, fontScale = 1f))

        // Tight cutout symmetrically reclaims excess safety padding (still >= cutout + 8dp) to avoid
        // starving compact speed figures while keeping left and right zones centered.
        val layout = IdlePillLayoutMath.compute(
            maxContentWidthDp = 120f,
            minContentWidthDp = 100f,
            measuredCutoutWidthDp = 32f,
            cutoutWidthDeltaDp = 0f,
            hasUploadTraffic = true,
            hasDownloadTraffic = true,
            uploadBytesPerSec = 48L * 1024L,
            downloadBytesPerSec = 1_500_000L,
            fontScale = 1f,
        )
        assertTrue(layout.speedDisplayMode != IdleSpeedDisplayMode.NONE)
        assertTrue(
            "Tight cutout must preserve >= 4dp safety margin per side (gap=${layout.centerGapDp})",
            layout.centerGapDp >= 32f + (Tokens.Tiny.value * 2f) - 0.01f,
        )
        assertTrue(
            "Right zone ${layout.rightZoneWidthDp}dp must fit compact speed figure (${IdlePillLayoutMath.stackedSpeedFigureWidthDp(1f)}dp)",
            layout.rightZoneWidthDp >= IdlePillLayoutMath.stackedSpeedFigureWidthDp(1f) - 0.01f,
        )
        assertEquals(layout.leftZoneWidthDp, layout.rightZoneWidthDp, 0.01f)
    }
}
