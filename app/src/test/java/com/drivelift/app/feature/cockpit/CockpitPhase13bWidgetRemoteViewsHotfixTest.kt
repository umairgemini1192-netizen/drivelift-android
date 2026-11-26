package com.drivelift.app.feature.cockpit

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.data.CockpitPreferencesRepository
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.NextAlarmFormatter
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidgetReceiver
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetActions
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
import com.drivelift.app.feature.cockpit.widget.WidgetSizeVariant
import com.drivelift.app.ui.GlassWidgetStyle
import com.drivelift.app.ui.GlassWidgetTileAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Regression suite for Phase 13b real Android home screen widget (RemoteViews) hotfix:
 * 1. Action buttons (Drop, Torch, Island) keep GlassWidgetStyle pill backgrounds and active/inactive/
 *    disabled outlines across COMPACT, NORMAL, TALL, and EXPANDED RemoteViews layouts.
 * 2. App dock renders its glass container background/border, all 4 pinned app icons + labels, and
 *    pagination dots with >= 8dp bottom clearance without clipping.
 * 3. Pinned app packages mirror synchronously across process cold start / app updates
 *    (`ACTION_MY_PACKAGE_REPLACED`) and bind cleanly across `<` / `>` page changes.
 * 4. Responsive 4-variant RemoteViews bitmap payload stays far below Android's AppWidgetService
 *    bitmap memory limit (eliminating the silent updateAppWidget rejection introduced in preview.48).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitPhase13bWidgetRemoteViewsHotfixTest {

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val record = System.getProperty("roborazzi.test.record") == "true"

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w412dp-h915dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        Graph.get(app).cockpit.pinnedAppsRepo.seedForTest(null)
        Graph.get(app).cockpit.prefsRepo.seedForTest(CockpitPrefsState())
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
    }

    private fun syntheticAppIcon(color: Int): Bitmap =
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawRoundRect(4f, 4f, 44f, 44f, 12f, 12f, paint)
        }

    private fun inflateAndLayout(
        remoteViews: android.widget.RemoteViews,
        widthDp: Float,
        heightDp: Float,
    ): Pair<View, Float> {
        val density = app.resources.displayMetrics.density.coerceAtLeast(2f)
        val widthPx = (widthDp * density).roundToInt()
        val heightPx = (heightDp * density).roundToInt()
        val container = FrameLayout(app)
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

    private fun drawToBitmap(view: View): Bitmap {
        val bmp = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun saveV3ScreenshotIfRecording(name: String, bitmap: Bitmap) {
        if (!record) return
        val outDir = File("build/outputs/screenshots/v3")
        outDir.mkdirs()
        FileOutputStream(File(outDir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun allFourResponsiveVariantsRenderGlassButtonPillsDockContainerPinnedAppsAndDotsWithoutClipping() {
        data class VariantSpec(
            val variant: WidgetSizeVariant,
            val widthDp: Float,
            val heightDp: Float,
            val expectedTileHeightDp: Float,
        )

        val specs = listOf(
            VariantSpec(WidgetSizeVariant.COMPACT, 276f, 140f, 32f),
            VariantSpec(WidgetSizeVariant.NORMAL, 276f, 220f, 44f),
            VariantSpec(WidgetSizeVariant.TALL, 276f, 310f, 48f),
            VariantSpec(WidgetSizeVariant.EXPANDED, 360f, 390f, 54f),
        )
        val expectedLabels = listOf("Maps", "WhatsApp", "Chrome", "Clock")
        val appContainerIds = intArrayOf(
            R.id.cockpit_widget_app_0,
            R.id.cockpit_widget_app_1,
            R.id.cockpit_widget_app_2,
            R.id.cockpit_widget_app_3,
        )
        val appIconIds = intArrayOf(
            R.id.cockpit_widget_app_icon_0,
            R.id.cockpit_widget_app_icon_1,
            R.id.cockpit_widget_app_icon_2,
            R.id.cockpit_widget_app_icon_3,
        )
        val appLabelIds = intArrayOf(
            R.id.cockpit_widget_app_label_0,
            R.id.cockpit_widget_app_label_1,
            R.id.cockpit_widget_app_label_2,
            R.id.cockpit_widget_app_label_3,
        )

        for (spec in specs) {
            val remoteViews = CockpitGlanceWidget.buildPreviewRemoteViewsForVariant(app, spec.variant)
            val (root, density) = inflateAndLayout(remoteViews, spec.widthDp, spec.heightDp)
            fun dp(v: Float): Int = (v * density).roundToInt()

            // Outer glass shell drawable is present and visible.
            val shellBg = root.findViewById<ImageView>(R.id.cockpit_widget_shell_bg)
            assertNotNull("${spec.variant}: shell bg view missing", shellBg)
            assertNotNull("${spec.variant}: shell bg drawable missing", shellBg.drawable)
            assertTrue("${spec.variant}: shell bg collapsed", shellBg.width > 0 && shellBg.height > 0)

            // Regression 1: All 3 action buttons have GlassWidgetStyle pill backgrounds and expected height.
            val dropBg = root.findViewById<View>(R.id.cockpit_widget_drop_bg)
            val torchBg = root.findViewById<View>(R.id.cockpit_widget_torch_bg)
            val islandBg = root.findViewById<View>(R.id.cockpit_widget_island_bg)
            for ((name, bgView) in listOf("drop" to dropBg, "torch" to torchBg, "island" to islandBg)) {
                assertNotNull("${spec.variant}: $name bg missing", bgView)
                assertEquals("${spec.variant}: $name bg must be visible", View.VISIBLE, bgView.visibility)
                assertNotNull("${spec.variant}: $name glass pill background drawable missing", bgView.background)
                assertTrue(
                    "${spec.variant}: $name glass pill background should be LayerDrawable",
                    bgView.background is LayerDrawable,
                )
                assertTrue("${spec.variant}: $name bg width must be > 0", bgView.width > 0)
                assertEquals(
                    "${spec.variant}: $name bg height mismatch",
                    dp(spec.expectedTileHeightDp),
                    bgView.height,
                )
            }

            // 3-column grid spacing preserved on non-compact variants.
            if (spec.variant != WidgetSizeVariant.COMPACT) {
                val btnDrop = root.findViewById<View>(R.id.cockpit_widget_btn_drop)
                val btnTorch = root.findViewById<View>(R.id.cockpit_widget_btn_torch)
                val btnIsland = root.findViewById<View>(R.id.cockpit_widget_btn_island)
                assertTrue(abs(btnDrop.width - btnTorch.width) <= 2)
                assertTrue(abs(btnTorch.width - btnIsland.width) <= 2)
                assertEquals(dp(10f), leftInRoot(btnTorch, root) - (leftInRoot(btnDrop, root) + btnDrop.width))
                assertEquals(dp(10f), leftInRoot(btnIsland, root) - (leftInRoot(btnTorch, root) + btnTorch.width))
            }

            // Regression 2: App dock glass container background is present, visible, and never clips bottom.
            val dockContainer = root.findViewById<View>(R.id.cockpit_widget_dock_container)
            assertNotNull("${spec.variant}: dock container missing", dockContainer)
            assertEquals(View.VISIBLE, dockContainer.visibility)
            assertTrue(
                "${spec.variant}: dock clips past bottom (${topInRoot(dockContainer, root) + dockContainer.height} > ${root.height})",
                topInRoot(dockContainer, root) + dockContainer.height <= root.height,
            )
            if (spec.variant == WidgetSizeVariant.COMPACT) {
                val compactDockBg = root.findViewById<View>(R.id.cockpit_widget_dock_bg)
                assertNotNull("COMPACT dock bg missing", compactDockBg)
                assertNotNull("COMPACT dock bg drawable missing", compactDockBg.background)
                assertTrue("COMPACT dock bg should be LayerDrawable", compactDockBg.background is LayerDrawable)
                assertEquals(dp(34f), compactDockBg.height)
            } else {
                assertNotNull("${spec.variant}: dock container glass background drawable missing", dockContainer.background)
                assertTrue(
                    "${spec.variant}: dock container background should be LayerDrawable",
                    dockContainer.background is LayerDrawable,
                )
                assertTrue("${spec.variant}: dock container height must be > 0", dockContainer.height > 0)
            }

            // All 4 pinned app slots render icons and labels reliably.
            for (slot in 0 until 4) {
                val slotContainer = root.findViewById<View>(appContainerIds[slot])
                val iconView = root.findViewById<ImageView>(appIconIds[slot])
                val labelView = root.findViewById<TextView>(appLabelIds[slot])

                assertEquals("${spec.variant}: app slot $slot must be visible", View.VISIBLE, slotContainer.visibility)
                assertTrue("${spec.variant}: app slot $slot width > 0", slotContainer.width > 0)
                assertEquals("${spec.variant}: app icon $slot must be visible", View.VISIBLE, iconView.visibility)
                val iconDrawable = iconView.drawable
                assertNotNull("${spec.variant}: app icon $slot drawable must be bound", iconDrawable)
                assertTrue("${spec.variant}: app icon $slot must be BitmapDrawable", iconDrawable is BitmapDrawable)
                val iconBmp = (iconDrawable as BitmapDrawable).bitmap
                assertNotNull(iconBmp)
                assertFalse(iconBmp.isRecycled)
                val centerAlpha = Color.alpha(iconBmp.getPixel(iconBmp.width / 2, iconBmp.height / 2))
                assertTrue("${spec.variant}: app icon $slot center pixel must be visible", centerAlpha > 100)

                assertEquals(expectedLabels[slot], labelView.text.toString())
                if (spec.variant.showAppLabels) {
                    assertEquals("${spec.variant}: app label $slot must be visible", View.VISIBLE, labelView.visibility)
                } else {
                    assertEquals("${spec.variant}: app label $slot hidden on compact", View.GONE, labelView.visibility)
                }
            }

            // Pagination chevrons + dots + >= 8dp bottom clearance on non-compact variants.
            val btnPrev = root.findViewById<View>(R.id.cockpit_widget_btn_prev)
            val btnNext = root.findViewById<View>(R.id.cockpit_widget_btn_next)
            val dotsRow = root.findViewById<View>(R.id.cockpit_widget_pager_dots_row)
            assertEquals(View.VISIBLE, btnPrev.visibility)
            assertEquals(View.VISIBLE, btnNext.visibility)
            if (spec.variant.showAppLabels) {
                assertEquals("${spec.variant}: pagination dots row must be visible", View.VISIBLE, dotsRow.visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.cockpit_widget_dot_0).visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.cockpit_widget_dot_1).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.cockpit_widget_dot_2).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.cockpit_widget_dot_3).visibility)
                val bottomClearance =
                    (topInRoot(dockContainer, root) + dockContainer.height) - (topInRoot(dotsRow, root) + dotsRow.height)
                assertTrue(
                    "${spec.variant}: pagination dots bottom clearance (${bottomClearance}px) must be >= ${dp(8f)}px (8dp)",
                    bottomClearance >= dp(8f),
                )
            } else {
                assertEquals(View.GONE, dotsRow.visibility)
            }

            // Render real widget pixels and capture CI evidence screenshot.
            val rendered = drawToBitmap(root)
            // Verify non-transparent pixels inside the active Drop pill border (top edge).
            val dropEdgeX = leftInRoot(dropBg, root) + dropBg.width / 2
            val dropEdgeY = topInRoot(dropBg, root) + 1
            val dropEdgePixel = rendered.getPixel(dropEdgeX, dropEdgeY)
            assertTrue(
                "${spec.variant}: active Drop pill outline must render visible red accent (pixel=${Integer.toHexString(dropEdgePixel)})",
                Color.alpha(dropEdgePixel) > 150 && Color.red(dropEdgePixel) > Color.green(dropEdgePixel),
            )
            saveV3ScreenshotIfRecording("cockpit-widget-remoteviews-${spec.variant.name.lowercase()}", rendered)
            rendered.recycle()
        }
    }

    @Test
    fun actionButtonGlassPillsRenderDistinctActiveInactiveAndDisabledOutlines() {
        val nowMs = System.currentTimeMillis()
        val alarm = NextAlarmFormatter.formatNextAlarm(
            triggerAtMillis = nowMs + 24_045_000L,
            nowMillis = nowMs,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            is24Hour = false,
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
            PinnedAppInfo("com.test.maps", "Maps", iconBitmap = syntheticAppIcon(0xFF34F5A0.toInt())),
            PinnedAppInfo("com.test.wa", "WhatsApp", iconBitmap = syntheticAppIcon(0xFF22C55E.toInt())),
            PinnedAppInfo("com.test.chrome", "Chrome", iconBitmap = syntheticAppIcon(0xFF00D4FF.toInt())),
            PinnedAppInfo("com.test.clock", "Clock", iconBitmap = syntheticAppIcon(0xFFA78BFA.toInt())),
        )

        // Verify GlassWidgetStyle drawable resource mapping.
        assertEquals(R.drawable.cockpit_widget_card_bg, GlassWidgetStyle.shellDrawableRes())
        assertEquals(R.drawable.cockpit_widget_dock_bg, GlassWidgetStyle.dockDrawableRes())
        assertEquals(
            R.drawable.cockpit_widget_tile_active_red_bg,
            GlassWidgetStyle.controlTileDrawableRes(active = true, enabled = true, accent = GlassWidgetTileAccent.DESTRUCTIVE_RED),
        )
        assertEquals(
            R.drawable.cockpit_widget_tile_active_cyan_bg,
            GlassWidgetStyle.controlTileDrawableRes(active = true, enabled = true, accent = GlassWidgetTileAccent.PRIMARY_CYAN),
        )
        assertEquals(
            R.drawable.cockpit_widget_tile_active_violet_bg,
            GlassWidgetStyle.controlTileDrawableRes(active = true, enabled = true, accent = GlassWidgetTileAccent.SECONDARY_VIOLET),
        )
        assertEquals(
            R.drawable.cockpit_widget_tile_inactive_bg,
            GlassWidgetStyle.controlTileDrawableRes(active = false, enabled = true, accent = GlassWidgetTileAccent.PRIMARY_CYAN),
        )
        assertEquals(
            R.drawable.cockpit_widget_tile_disabled_bg,
            GlassWidgetStyle.controlTileDrawableRes(active = false, enabled = false, accent = GlassWidgetTileAccent.PRIMARY_CYAN),
        )

        // State 1: All three pills active (red Stop, cyan Torch On, violet Island On).
        val allActiveRv = CockpitGlanceWidget.renderMasterRemoteViews(
            context = app,
            appWidgetId = 1,
            alarm = alarm,
            dropLive = true,
            dropAvailable = true,
            torchOn = true,
            torchAvailable = true,
            islandOn = true,
            battery = battery,
            ram = ram,
            pinnedApps = pinned,
            pinnedPageIndex = 0,
            pinnedPageCount = 2,
            attachPendingIntents = false,
            variant = WidgetSizeVariant.EXPANDED,
        )
        val (activeRoot, _) = inflateAndLayout(allActiveRv, 360f, 390f)
        val activeBmp = drawToBitmap(activeRoot)

        val dropActiveBg = activeRoot.findViewById<View>(R.id.cockpit_widget_drop_bg)
        val torchActiveBg = activeRoot.findViewById<View>(R.id.cockpit_widget_torch_bg)
        val islandActiveBg = activeRoot.findViewById<View>(R.id.cockpit_widget_island_bg)
        assertEquals("Stop", activeRoot.findViewById<TextView>(R.id.cockpit_widget_drop_label).text.toString())
        assertEquals("Torch On", activeRoot.findViewById<TextView>(R.id.cockpit_widget_torch_label).text.toString())
        assertEquals("Island On", activeRoot.findViewById<TextView>(R.id.cockpit_widget_island_label).text.toString())

        val dropBorderPixel = activeBmp.getPixel(leftInRoot(dropActiveBg, activeRoot) + dropActiveBg.width / 2, topInRoot(dropActiveBg, activeRoot) + 1)
        val torchBorderPixel = activeBmp.getPixel(leftInRoot(torchActiveBg, activeRoot) + torchActiveBg.width / 2, topInRoot(torchActiveBg, activeRoot) + 1)
        val islandBorderPixel = activeBmp.getPixel(leftInRoot(islandActiveBg, activeRoot) + islandActiveBg.width / 2, topInRoot(islandActiveBg, activeRoot) + 1)

        assertTrue("Active Drop border must be red-dominant", Color.red(dropBorderPixel) > Color.green(dropBorderPixel) + 40)
        assertTrue("Active Torch border must be cyan-dominant", Color.blue(torchBorderPixel) > Color.red(torchBorderPixel) + 40 && Color.green(torchBorderPixel) > Color.red(torchBorderPixel) + 20)
        assertTrue("Active Island border must be violet-dominant", Color.blue(islandBorderPixel) > Color.green(islandBorderPixel) + 20)
        saveV3ScreenshotIfRecording("cockpit-widget-expanded-active-states", activeBmp)
        activeBmp.recycle()

        // State 2: All three pills inactive (Drop, Torch, Island).
        val allInactiveRv = CockpitGlanceWidget.renderMasterRemoteViews(
            context = app,
            appWidgetId = 1,
            alarm = alarm,
            dropLive = false,
            dropAvailable = true,
            torchOn = false,
            torchAvailable = true,
            islandOn = false,
            battery = battery,
            ram = ram,
            pinnedApps = pinned,
            pinnedPageIndex = 0,
            pinnedPageCount = 2,
            attachPendingIntents = false,
            variant = WidgetSizeVariant.EXPANDED,
        )
        val (inactiveRoot, _) = inflateAndLayout(allInactiveRv, 360f, 390f)
        val inactiveBmp = drawToBitmap(inactiveRoot)
        val torchInactiveBg = inactiveRoot.findViewById<View>(R.id.cockpit_widget_torch_bg)
        assertTrue(torchInactiveBg.background is LayerDrawable)
        val torchInactiveBorder = inactiveBmp.getPixel(
            leftInRoot(torchInactiveBg, inactiveRoot) + torchInactiveBg.width / 2,
            topInRoot(torchInactiveBg, inactiveRoot) + 1,
        )
        assertNotEquals("Active and inactive Torch borders must visibly differ", torchBorderPixel, torchInactiveBorder)
        assertTrue("Inactive Torch pill border must still be visibly drawn", Color.alpha(torchInactiveBorder) > 150)
        inactiveBmp.recycle()

        // State 3: Disabled Drop & Torch ("No Wi-Fi", "Cam Busy").
        val disabledRv = CockpitGlanceWidget.renderMasterRemoteViews(
            context = app,
            appWidgetId = 1,
            alarm = alarm,
            dropLive = false,
            dropAvailable = false,
            torchOn = false,
            torchAvailable = false,
            islandOn = false,
            battery = battery,
            ram = ram,
            pinnedApps = pinned,
            pinnedPageIndex = 0,
            pinnedPageCount = 1,
            attachPendingIntents = false,
            variant = WidgetSizeVariant.EXPANDED,
        )
        val (disabledRoot, _) = inflateAndLayout(disabledRv, 360f, 390f)
        assertEquals("No Wi-Fi", disabledRoot.findViewById<TextView>(R.id.cockpit_widget_drop_label).text.toString())
        assertEquals("Cam Busy", disabledRoot.findViewById<TextView>(R.id.cockpit_widget_torch_label).text.toString())
        assertTrue(disabledRoot.findViewById<View>(R.id.cockpit_widget_drop_bg).background is GradientDrawable)
        assertTrue(disabledRoot.findViewById<View>(R.id.cockpit_widget_torch_bg).background is GradientDrawable)
    }

    @Test
    fun pinnedAppsMirrorAcrossColdStartAppUpdateAndPageChanges() {
        // 1. Verify CockpitPreferencesRepository mirrors pinnedPackages synchronously for cold start / app updates.
        val token = System.nanoTime()
        val writerStore = File(app.cacheDir, "cockpit_phase13b_writer_$token.preferences_pb")
        val coldStore = File(app.cacheDir, "cockpit_phase13b_cold_$token.preferences_pb")
        val upgradeStore = File(app.cacheDir, "cockpit_phase13b_upgrade_$token.preferences_pb")
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val upgradeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val expectedPackages = listOf(
                "com.test.maps",
                "com.test.whatsapp",
                "com.test.chrome",
                "com.test.clock",
                "com.test.photos",
                "com.test.notes",
            )
            val writerRepo = CockpitPreferencesRepository(
                context = app,
                produceFile = { writerStore },
                scope = writerScope,
            )
            runBlocking {
                writerRepo.setPinnedPackages(expectedPackages)
            }
            // Fresh repository instance against an empty DataStore proves the synchronous
            // SharedPreferences mirror supplies pinned apps immediately on cold start.
            val coldRepo = CockpitPreferencesRepository(
                context = app,
                produceFile = { coldStore },
                scope = coldScope,
            )
            assertEquals(expectedPackages, coldRepo.state.value.pinnedPackages)
            assertEquals(expectedPackages, coldRepo.readPinnedPackagesForWidget())

            // Also verify upgrade path when SharedPreferences mirror key is absent (pre-13b install):
            // copy the persisted DataStore file to a distinct path and confirm
            // readPinnedPackagesForWidget() falls back to reading DataStore and populates the mirror.
            writerStore.copyTo(upgradeStore, overwrite = true)
            app.getSharedPreferences("cockpit_prefs_sync", Context.MODE_PRIVATE)
                .edit()
                .remove("pinned_packages_ordered")
                .commit()
            val upgradeRepo = CockpitPreferencesRepository(
                context = app,
                produceFile = { upgradeStore },
                scope = upgradeScope,
            )
            assertEquals(expectedPackages, upgradeRepo.readPinnedPackagesForWidget())
        } finally {
            writerScope.cancel()
            coldScope.cancel()
            upgradeScope.cancel()
            writerStore.delete()
            coldStore.delete()
            upgradeStore.delete()
        }

        // 2. Verify full RemoteViews dock binds all 4 slots on page 0, steps to page 1 via receiver,
        //    and refreshes on ACTION_MY_PACKAGE_REPLACED.
        val cockpit = Graph.get(app).cockpit
        val seededApps = listOf(
            PinnedAppInfo("com.test.maps", "Maps", iconBitmap = syntheticAppIcon(0xFF34F5A0.toInt())),
            PinnedAppInfo("com.test.whatsapp", "WhatsApp", iconBitmap = syntheticAppIcon(0xFF22C55E.toInt())),
            PinnedAppInfo("com.test.chrome", "Chrome", iconBitmap = syntheticAppIcon(0xFF00D4FF.toInt())),
            PinnedAppInfo("com.test.clock", "Clock", iconBitmap = syntheticAppIcon(0xFFA78BFA.toInt())),
            PinnedAppInfo("com.test.photos", "Photos", iconBitmap = syntheticAppIcon(0xFFFBBF24.toInt())),
            PinnedAppInfo("com.test.notes", "Notes", iconBitmap = syntheticAppIcon(0xFFFF4D6D.toInt())),
        )
        cockpit.pinnedAppsRepo.seedForTest(seededApps)
        cockpit.prefsRepo.seedForTest(
            CockpitPrefsState(
                masterEnabled = true,
                pinnedPackages = seededApps.map { it.packageName },
            ),
        )

        val appWidgetManager = AppWidgetManager.getInstance(app)
        val widgetId = shadowOf(appWidgetManager).createWidget(
            CockpitGlanceWidgetReceiver::class.java,
            R.layout.cockpit_widget_master,
        )
        CockpitGlanceWidget.savePage(app, widgetId, 0)

        val page0Rv = CockpitGlanceWidget.buildFullRemoteViewsForVariant(
            context = app,
            appWidgetId = widgetId,
            variant = WidgetSizeVariant.EXPANDED,
            overridePageIndex = 0,
        )
        val (page0Root, _) = inflateAndLayout(page0Rv, 360f, 390f)
        assertEquals(View.VISIBLE, page0Root.findViewById<View>(R.id.cockpit_widget_dock_container).visibility)
        assertNotNull(page0Root.findViewById<View>(R.id.cockpit_widget_dock_container).background)
        assertEquals("Maps", page0Root.findViewById<TextView>(R.id.cockpit_widget_app_label_0).text.toString())
        assertEquals("WhatsApp", page0Root.findViewById<TextView>(R.id.cockpit_widget_app_label_1).text.toString())
        assertEquals("Chrome", page0Root.findViewById<TextView>(R.id.cockpit_widget_app_label_2).text.toString())
        assertEquals("Clock", page0Root.findViewById<TextView>(R.id.cockpit_widget_app_label_3).text.toString())

        // Tap '>' pager chevron via receiver -> advances to page 1.
        val receiver = CockpitGlanceWidgetReceiver()
        receiver.onReceive(
            app,
            Intent(app, CockpitGlanceWidgetReceiver::class.java).apply {
                action = CockpitWidgetActions.ACTION_PINNED_NEXT
                putExtra(CockpitWidgetActions.EXTRA_APP_WIDGET_ID, widgetId)
            },
        )
        assertEquals(1, CockpitGlanceWidget.readSavedPage(app, widgetId))

        val page1Rv = CockpitGlanceWidget.buildFullRemoteViewsForVariant(
            context = app,
            appWidgetId = widgetId,
            variant = WidgetSizeVariant.EXPANDED,
            overridePageIndex = 1,
        )
        val (page1Root, _) = inflateAndLayout(page1Rv, 360f, 390f)
        assertEquals(View.VISIBLE, page1Root.findViewById<View>(R.id.cockpit_widget_app_0).visibility)
        assertEquals(View.VISIBLE, page1Root.findViewById<View>(R.id.cockpit_widget_app_1).visibility)
        assertEquals(View.GONE, page1Root.findViewById<View>(R.id.cockpit_widget_app_2).visibility)
        assertEquals(View.GONE, page1Root.findViewById<View>(R.id.cockpit_widget_app_3).visibility)
        assertEquals("Photos", page1Root.findViewById<TextView>(R.id.cockpit_widget_app_label_0).text.toString())
        assertEquals("Notes", page1Root.findViewById<TextView>(R.id.cockpit_widget_app_label_1).text.toString())
        assertEquals(View.VISIBLE, page1Root.findViewById<View>(R.id.cockpit_widget_pager_dots_row).visibility)

        val page1Bmp = drawToBitmap(page1Root)
        saveV3ScreenshotIfRecording("cockpit-widget-expanded-page2", page1Bmp)
        page1Bmp.recycle()

        // App update broadcast immediately refreshes all bound widgets.
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNotNull(shadowOf(appWidgetManager).getViewFor(widgetId))
        CockpitGlanceWidget.clearWidgetState(app, widgetId)
    }

    @Test
    fun responsiveRemoteViewsBitmapMemoryStaysWellBelowAndroidAppWidgetLimit() {
        val bytesAt3x = CockpitGlanceWidget.estimateResponsiveRemoteViewsBitmapBytes(
            density = 3.0f,
            pinnedAppsOnPage = CockpitWidgetModel.PINNED_PER_PAGE,
        )
        assertTrue(
            "4-variant responsive RemoteViews bitmap total (${bytesAt3x}B) must stay < ${CockpitGlanceWidget.MAX_SAFE_RESPONSIVE_BITMAP_BYTES}B",
            bytesAt3x < CockpitGlanceWidget.MAX_SAFE_RESPONSIVE_BITMAP_BYTES,
        )
        // Build full 4-variant responsive RemoteViews without error.
        val rv = CockpitGlanceWidget.buildFullRemoteViews(app, appWidgetId = 42)
        assertNotNull(rv)
    }
}
