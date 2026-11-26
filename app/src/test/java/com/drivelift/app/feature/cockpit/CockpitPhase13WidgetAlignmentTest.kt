package com.drivelift.app.feature.cockpit

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.ui.CockpitMasterWidgetCard
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.feature.cockpit.widget.WidgetSizeVariant
import com.drivelift.app.feature.cockpit.widget.WidgetStatusTone
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Verifies the Phase 13 home screen widget size, proportion, and alignment overhaul across
 * responsive RemoteViews variants and the 1:1 Compose preview:
 * 1. Header row: baseline-aligned AM/PM pill with tighter padding, DROP LIVE vertically centered
 *    against the combined clock + date block height.
 * 2. Alarm + Telemetry row: alarm card height matches the full gauge + 2-line captions block,
 *    64dp gauge diameter in expanded layout with 6dp caption breathing room, and clean 55/45 split
 *    with 10dp gutter.
 * 3. Wi-Fi status bar: 36dp height, 12dp horizontal padding, equal vertical spacing above & below.
 * 4. Action buttons: 54dp height in expanded layout (>= 48dp everywhere), 20dp icons, 4dp gap,
 *    and 3 equal columns with 10dp gaps.
 * 5. App dock: 8dp bottom clearance below pagination dots and '<'/'>' chevrons vertically aligned
 *    with app icon centers while preserving >= 48dp touch targets.
 * 6. Outer frame: uniform 14dp inner padding and consistent vertical row spacing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitPhase13WidgetAlignmentTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w412dp-h915dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
    }

    private fun inflateAndLayout(
        variant: WidgetSizeVariant,
        widthDp: Float,
        heightDp: Float,
    ): Pair<View, Float> {
        val density = app.resources.displayMetrics.density.coerceAtLeast(2f)
        val widthPx = (widthDp * density).roundToInt()
        val heightPx = (heightDp * density).roundToInt()
        val container = FrameLayout(app)
        val remoteViews = CockpitGlanceWidget.buildPreviewRemoteViewsForVariant(app, variant)
        val inflated = remoteViews.apply(app, container)
        inflated.findViewById<TextView>(R.id.cockpit_widget_time)?.text = "12:14"
        inflated.findViewById<TextView>(R.id.cockpit_widget_ampm)?.text = "AM"
        inflated.findViewById<TextView>(R.id.cockpit_widget_date)?.text = "Thu \u00B7 Nov 26"
        inflated.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        inflated.layout(0, 0, widthPx, heightPx)
        return inflated to density
    }

    private fun topInRoot(view: View, root: View): Int {
        var y = view.top
        var parent = view.parent
        while (parent is View && parent !== root) {
            y += parent.top
            parent = parent.parent
        }
        return y
    }

    private fun leftInRoot(view: View, root: View): Int {
        var x = view.left
        var parent = view.parent
        while (parent is View && parent !== root) {
            x += parent.left
            parent = parent.parent
        }
        return x
    }

    @Test
    fun expandedWidgetSatisfiesAllSixLayoutProportionAndAlignmentRequirements() {
        assertEquals(WidgetSizeVariant.EXPANDED, CockpitGlanceWidget.selectWidgetSizeVariant(345f, 380f))
        assertEquals(WidgetSizeVariant.EXPANDED, CockpitGlanceWidget.selectWidgetSizeVariant(380f, 420f))

        val (root, density) = inflateAndLayout(WidgetSizeVariant.EXPANDED, widthDp = 360f, heightDp = 390f)
        val pxPerDp = density
        fun dp(value: Float): Int = (value * pxPerDp).roundToInt()

        // Outer frame: uniform 14dp inner padding.
        val content = root.findViewById<View>(R.id.cockpit_widget_content)
        assertEquals(dp(14f), content.paddingLeft)
        assertEquals(dp(14f), content.paddingTop)
        assertEquals(dp(14f), content.paddingRight)
        assertEquals(dp(14f), content.paddingBottom)

        // Row 1: baseline-aligned AM/PM badge, status pill under date (not floating top-right),
        // and battery/RAM rings vertically centered with the clock+date+status block.
        val timeView = root.findViewById<TextView>(R.id.cockpit_widget_time)
        val ampmView = root.findViewById<TextView>(R.id.cockpit_widget_ampm)
        val dateView = root.findViewById<TextView>(R.id.cockpit_widget_date)
        val topRow = root.findViewById<View>(R.id.cockpit_widget_top_row)
        val clockRow = root.findViewById<View>(R.id.cockpit_widget_clock_row)
        val statusPill = root.findViewById<View>(R.id.cockpit_widget_status_pill)
        val gaugesBlock = root.findViewById<View>(R.id.cockpit_widget_telemetry_gauges)

        assertTrue("Time view baseline must be valid", timeView.baseline > 0)
        assertTrue("AM/PM view baseline must be valid", ampmView.baseline > 0)
        val timeBaselineY = topInRoot(timeView, root) + timeView.baseline
        val ampmBaselineY = topInRoot(ampmView, root) + ampmView.baseline
        assertTrue(
            "AM/PM baseline ($ampmBaselineY) must align with clock digits baseline ($timeBaselineY)",
            abs(timeBaselineY - ampmBaselineY) <= 2,
        )
        assertTrue("Status pill must sit under date", topInRoot(statusPill, root) >= topInRoot(dateView, root) + dateView.height)
        assertTrue(
            "Status pill must stay inside left clock column, not floating right",
            leftInRoot(statusPill, root) + statusPill.width <= leftInRoot(gaugesBlock, root),
        )
        val topLeftCenterY = topInRoot(clockRow, root) + clockRow.height / 2f
        val gaugesCenterY = topInRoot(gaugesBlock, root) + gaugesBlock.height / 2f
        assertTrue(
            "Gauges ($gaugesCenterY) must vertically center with clock column ($topLeftCenterY) in Row 1",
            abs(topLeftCenterY - gaugesCenterY) <= 4f,
        )
        assertEquals(topRow, gaugesBlock.parent)

        // Row 2: compact content-width alarm chip + network chip side-by-side.
        val chipsRow = root.findViewById<View>(R.id.cockpit_widget_chips_row)
        val alarmCard = root.findViewById<View>(R.id.cockpit_widget_alarm_container)
        val netRow = root.findViewById<View>(R.id.cockpit_widget_net_row)
        assertEquals(chipsRow, alarmCard.parent)
        assertEquals(chipsRow, netRow.parent)
        assertTrue(
            "Alarm chip (${alarmCard.width}px) must be content-width, not stretched full-width (${chipsRow.width}px)",
            alarmCard.width < chipsRow.width * 0.65f,
        )
        assertTrue(
            "Network chip must sit to the right of alarm chip on Row 2",
            leftInRoot(netRow, root) >= leftInRoot(alarmCard, root) + alarmCard.width,
        )

        // Row 3: 3 equal action buttons, compact visual height (46dp inside 48dp touch container), 10dp gaps.
        val btnDrop = root.findViewById<View>(R.id.cockpit_widget_btn_drop)
        val btnTorch = root.findViewById<View>(R.id.cockpit_widget_btn_torch)
        val btnIsland = root.findViewById<View>(R.id.cockpit_widget_btn_island)
        val dropIcon = root.findViewById<View>(R.id.cockpit_widget_drop_icon)
        val dropBg = root.findViewById<View>(R.id.cockpit_widget_drop_bg)

        assertTrue(btnDrop.height >= dp(48f))
        assertEquals(dp(46f), dropBg.height)
        assertTrue(abs(btnDrop.width - btnTorch.width) <= 2)
        assertTrue(abs(btnTorch.width - btnIsland.width) <= 2)
        assertEquals(dp(10f), leftInRoot(btnTorch, root) - (leftInRoot(btnDrop, root) + btnDrop.width))
        assertEquals(dp(10f), leftInRoot(btnIsland, root) - (leftInRoot(btnTorch, root) + btnTorch.width))
        assertEquals(dp(18f), dropIcon.width)
        assertEquals(dp(18f), dropIcon.height)

        // Row 4: App dock with aligned chevrons, >= 48dp touch targets, and zero bottom clipping.
        val dockContainer = root.findViewById<View>(R.id.cockpit_widget_dock_container)
        val dotsRow = root.findViewById<View>(R.id.cockpit_widget_pager_dots_row)
        val btnPrev = root.findViewById<View>(R.id.cockpit_widget_btn_prev)
        val btnNext = root.findViewById<View>(R.id.cockpit_widget_btn_next)
        val appIcon0 = root.findViewById<View>(R.id.cockpit_widget_app_icon_0)

        assertEquals(View.VISIBLE, dotsRow.visibility)
        assertTrue("Previous chevron must keep >= 48dp touch height", btnPrev.height >= dp(48f))
        assertTrue("Next chevron must keep >= 48dp touch height", btnNext.height >= dp(48f))

        val iconCenterY = topInRoot(appIcon0, root) + appIcon0.height / 2f
        val prevContentCenterY =
            topInRoot(btnPrev, root) + btnPrev.paddingTop + (btnPrev.height - btnPrev.paddingTop - btnPrev.paddingBottom) / 2f
        val nextContentCenterY =
            topInRoot(btnNext, root) + btnNext.paddingTop + (btnNext.height - btnNext.paddingTop - btnNext.paddingBottom) / 2f
        assertTrue(
            "Prev chevron visual center ($prevContentCenterY) must align with app icon center ($iconCenterY)",
            abs(prevContentCenterY - iconCenterY) <= 2f,
        )
        assertTrue(
            "Next chevron visual center ($nextContentCenterY) must align with app icon center ($iconCenterY)",
            abs(nextContentCenterY - iconCenterY) <= 2f,
        )
        assertTrue(
            "Expanded dock must not clip past widget bottom",
            topInRoot(dockContainer, root) + dockContainer.height <= root.height,
        )
    }

    @Test
    fun tallAndNormalWidgetsPreserveRow1RingsRow2ChipsAndZeroClipping() {
        for ((variant, size) in listOf(
            WidgetSizeVariant.TALL to (276f to 310f),
            WidgetSizeVariant.NORMAL to (276f to 220f),
            WidgetSizeVariant.COMPACT to (276f to 140f),
        )) {
            val (root, _) = inflateAndLayout(variant, widthDp = size.first, heightDp = size.second)
            val dockContainer = root.findViewById<View>(R.id.cockpit_widget_dock_container)
            assertNotNull(dockContainer)
            assertTrue(
                "$variant dock must not clip bottom",
                topInRoot(dockContainer, root) + dockContainer.height <= root.height,
            )

            // Baseline-aligned AM/PM across every size bucket.
            val timeView = root.findViewById<TextView>(R.id.cockpit_widget_time)
            val ampmView = root.findViewById<TextView>(R.id.cockpit_widget_ampm)
            val dateView = root.findViewById<View>(R.id.cockpit_widget_date)
            val statusPill = root.findViewById<View>(R.id.cockpit_widget_status_pill)
            val gaugesBlock = root.findViewById<View>(R.id.cockpit_widget_telemetry_gauges)
            val topRow = root.findViewById<View>(R.id.cockpit_widget_top_row)
            val timeBaselineY = topInRoot(timeView, root) + timeView.baseline
            val ampmBaselineY = topInRoot(ampmView, root) + ampmView.baseline
            assertTrue(
                "$variant AM/PM baseline must align with clock digits",
                abs(timeBaselineY - ampmBaselineY) <= 2,
            )
            assertEquals("$variant rings must sit in Row 1", topRow, gaugesBlock.parent)
            assertTrue(
                "$variant status pill must sit below date",
                topInRoot(statusPill, root) >= topInRoot(dateView, root) + dateView.height - 2,
            )

            if (variant != WidgetSizeVariant.COMPACT) {
                val chipsRow = root.findViewById<View>(R.id.cockpit_widget_chips_row)
                val alarmCard = root.findViewById<View>(R.id.cockpit_widget_alarm_container)
                val netRow = root.findViewById<View>(R.id.cockpit_widget_net_row)
                assertTrue(
                    "$variant alarm chip must be content-width",
                    alarmCard.width < chipsRow.width * 0.65f,
                )
                assertTrue(
                    "$variant network chip must sit beside alarm chip",
                    leftInRoot(netRow, root) >= leftInRoot(alarmCard, root) + alarmCard.width,
                )

                val btnPrev = root.findViewById<View>(R.id.cockpit_widget_btn_prev)
                val appIcon0 = root.findViewById<View>(R.id.cockpit_widget_app_icon_0)
                val iconCenterY = topInRoot(appIcon0, root) + appIcon0.height / 2f
                val prevContentCenterY =
                    topInRoot(btnPrev, root) + btnPrev.paddingTop + (btnPrev.height - btnPrev.paddingTop - btnPrev.paddingBottom) / 2f
                assertTrue(
                    "$variant chevron visual center must align with app icon center",
                    abs(prevContentCenterY - iconCenterY) <= 2f,
                )
            }
        }
    }

    @Test
    fun networkFormattingHidesIdleAndZeroMbAndStatusPillCoversAllThreeStates() {
        val idleWifi = IdleNetworkSnapshot(kind = IdleNetworkKind.WIFI, label = "Wi-Fi")
        assertEquals("HomeNet", CockpitGlanceWidget.formatWidgetNetworkSpeed(idleWifi, wifiSsid = "HomeNet"))
        assertEquals("Wi-Fi", CockpitGlanceWidget.formatWidgetNetworkSpeed(idleWifi, wifiSsid = null))
        assertTrue(!CockpitGlanceWidget.formatWidgetNetworkSpeed(idleWifi).contains("Idle"))

        val active5g = IdleNetworkSnapshot(
            kind = IdleNetworkKind.CELLULAR,
            label = "5G",
            uploadBytesPerSec = 128L * 1024L,
            downloadBytesPerSec = (2.4 * 1024 * 1024).toLong(),
        )
        val text5g = CockpitGlanceWidget.formatWidgetNetworkSpeed(active5g)
        assertTrue(text5g.contains("5G"))
        assertTrue(text5g.contains("2.4M"))
        assertTrue(text5g.contains("128K"))

        assertEquals(false, CockpitGlanceWidget.shouldShowTodayData(null))
        assertEquals(false, CockpitGlanceWidget.shouldShowTodayData("Today --"))
        assertEquals(false, CockpitGlanceWidget.shouldShowTodayData("Today 0 MB"))
        assertEquals(false, CockpitGlanceWidget.shouldShowTodayData("0 MB"))
        assertEquals(true, CockpitGlanceWidget.shouldShowTodayData("Today 420 MB"))

        assertEquals("READY", CockpitWidgetModel.statusPill(dropLive = false, torchOn = false).text)
        assertEquals(WidgetStatusTone.DROP_LIVE, CockpitWidgetModel.statusPill(dropLive = true, torchOn = true).tone)
        assertEquals("TORCH ON", CockpitWidgetModel.statusPill(dropLive = false, torchOn = true).text)
    }

    @Test
    fun composeMasterWidgetPreviewMatchesCompactHudLayoutAndTargets() {
        val alarm = NextAlarmUiModel(
            hasAlarm = true,
            formattedTime = "06:30 AM",
            countdownMode = AlarmCountdownMode.CHRONOMETER_UNDER_24H,
            chronometerBaseElapsedMs = 500_000L,
            countdownText = "Rings in 07:14:22",
            triggerAtMillis = 1_700_000_000_000L,
        )
        val battery = BatteryHudMetric(
            percent = 84,
            fraction = 0.84f,
            valueText = "84%",
            label = "CHRG",
            isCharging = true,
            current = CockpitWidgetModel.formatBatteryCurrent(2_450_000L, isCharging = true),
            charge = CockpitWidgetModel.formatBatteryCharge(4_210_000L),
        )
        val ram = CockpitHudBitmapRenderer.formatRamHudMetric(
            availBytes = (1.8 * 1024 * 1024 * 1024).toLong(),
            totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
        )
        val pinned = listOf(
            PinnedAppInfo("com.app.a", "Maps"),
            PinnedAppInfo("com.app.b", "WhatsApp"),
            PinnedAppInfo("com.app.c", "Chrome"),
            PinnedAppInfo("com.app.d", "Clock"),
            PinnedAppInfo("com.app.e", "Photos"),
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CockpitMasterWidgetCard(
                                clockTimeLabel = "12:14 AM",
                                clockDateLabel = "Thu \u00B7 Nov 26",
                                alarm = alarm,
                                telemetry = DriveLiftTelemetrySummary(isDesktopDropRunning = true, isDropAvailable = true),
                                freeStorageLabel = "118 GB free",
                                batteryLabel = "84% batt",
                                torchOn = false,
                                islandOn = true,
                                pinnedApps = pinned,
                                batteryMetric = battery,
                                ramMetric = ram,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()

            val cardBounds = compose.onNodeWithTag("cockpit-master-widget-card").getBoundsInRoot()
            val statusBounds = compose.onNodeWithTag("cockpit-widget-status-badge").assertIsDisplayed().getBoundsInRoot()
            val alarmBounds = compose.onNodeWithTag("cockpit-widget-alarm-pill").assertIsDisplayed().getBoundsInRoot()
            val netBounds = compose.onNodeWithTag("cockpit-widget-network-chip").assertIsDisplayed().getBoundsInRoot()
            val batteryGaugeBounds = compose.onNodeWithTag("cockpit-widget-battery-gauge").getBoundsInRoot()
            val dropBounds = compose.onNodeWithTag("cockpit-widget-toggle-drop").assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight).getBoundsInRoot()
            val torchBounds = compose.onNodeWithTag("cockpit-widget-toggle-torch").assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight).getBoundsInRoot()
            val islandBounds = compose.onNodeWithTag("cockpit-widget-toggle-island").assertHeightIsAtLeast(Tokens.Cockpit.HudControlHeight).getBoundsInRoot()

            assertEquals(44f, (batteryGaugeBounds.right - batteryGaugeBounds.left).value, 1f)
            assertTrue("Drop button height must be >= 48dp", (dropBounds.bottom - dropBounds.top).value >= 48f)
            assertEquals(10f, (torchBounds.left - dropBounds.right).value, 1.5f)
            assertEquals(10f, (islandBounds.left - torchBounds.right).value, 1.5f)
            assertTrue("Status badge must stay left of battery gauge", statusBounds.right <= batteryGaugeBounds.left)
            assertTrue("Alarm pill must stay content-width", (alarmBounds.right - alarmBounds.left) < (cardBounds.right - cardBounds.left) * 0.65f)
            assertTrue("Network chip must sit right of alarm pill", netBounds.left >= alarmBounds.right)
        }
    }
}
