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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitSlotState
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.BluetoothTileState
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsState
import com.drivelift.app.feature.cockpit.hardware.CockpitRingerMode
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.MobileDataTileState
import com.drivelift.app.feature.cockpit.hardware.RingerTileState
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import com.drivelift.app.feature.cockpit.hardware.WifiTileState
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.ui.CollapsedSplitIslandRow
import com.drivelift.app.feature.cockpit.ui.ExpandedCockpitCard
import com.drivelift.app.feature.cockpit.ui.LocalIdleNetworkOverride
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

            // DROP LIVE status pill must live inside the clock row (aligned with clock, not floating).
            assertTrue(
                "Status pill must be inside clock row in ${spec.name}",
                statusPill.parent === clockRow,
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
                val todayDataText = inflated.findViewById<TextView>(R.id.cockpit_widget_today_data).text.toString()
                assertTrue("Expected live speed in tall row, got '$netSpeedText'", netSpeedText.contains("1.4M"))
                assertEquals("Today 420 MB", todayDataText)

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
}
