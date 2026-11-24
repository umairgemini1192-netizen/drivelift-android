package com.drivelift.app.feature.cockpit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import com.drivelift.app.ui.GlassPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CockpitHudBitmapRendererTest {

    @Test
    fun batteryArcColorFollowsHealthAndChargingThresholds() {
        // Updated per Cockpit v2 Pillar 3: <= 20% uses vivid warning red (#FB7185), charging overrides to emerald.
        assertEquals(GlassPalette.HudEmerald, CockpitHudBitmapRenderer.batteryArcColor(100))
        assertEquals(GlassPalette.HudEmerald, CockpitHudBitmapRenderer.batteryArcColor(31))
        assertEquals(GlassPalette.HudAmber, CockpitHudBitmapRenderer.batteryArcColor(30))
        assertEquals(GlassPalette.HudAmber, CockpitHudBitmapRenderer.batteryArcColor(21))
        assertEquals(GlassPalette.HudRoseLow, CockpitHudBitmapRenderer.batteryArcColor(20))
        assertEquals(GlassPalette.HudRoseLow, CockpitHudBitmapRenderer.batteryArcColor(1))
        assertEquals(GlassPalette.HudEmerald, CockpitHudBitmapRenderer.batteryArcColor(12, isCharging = true))

        assertEquals("CHRG", CockpitHudBitmapRenderer.batterySubLabel(18, isCharging = true))
        assertEquals("LOW", CockpitHudBitmapRenderer.batterySubLabel(20, isCharging = false))
        assertEquals("BATT", CockpitHudBitmapRenderer.batterySubLabel(21, isCharging = false))
    }

    @Test
    fun shellBitmapRespectsDimensionsAndHasNonTransparentCenter() {
        val bmp = CockpitHudBitmapRenderer.renderShellBitmap(
            widthPx = 680,
            heightPx = 440,
            cornerRadiusDp = 26f,
            density = 2f,
        )
        assertEquals(680, bmp.width)
        assertEquals(440, bmp.height)
        val centerPixel = bmp.getPixel(340, 220)
        assertTrue(((centerPixel ushr 24) and 0xFF) > 150)
    }

    @Test
    fun arcGaugeBitmapRendersChargingHaloBoltLowStateAndUsedOverTotalRam() {
        val normalBatt = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = 128,
            fraction = 0.84f,
            valueText = "84%",
            subLabel = "BATT",
            accentColor = CockpitHudBitmapRenderer.batteryArcColor(84, isCharging = false),
            density = 2f,
            isCharging = false,
        )
        val chargingBatt = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = 128,
            fraction = 0.84f,
            valueText = "84%",
            subLabel = "CHRG",
            accentColor = CockpitHudBitmapRenderer.batteryArcColor(84, isCharging = true),
            density = 2f,
            isCharging = true,
        )
        val lowBatt = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = 128,
            fraction = 0.18f,
            valueText = "18%",
            subLabel = "LOW",
            accentColor = CockpitHudBitmapRenderer.batteryArcColor(18, isCharging = false),
            density = 2f,
            isCharging = false,
        )
        val ramMetric = CockpitHudBitmapRenderer.formatRamHudMetric(
            availBytes = (1.5 * 1024 * 1024 * 1024).toLong(),
            totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
        )
        assertEquals("10.5/12G", ramMetric.valueText)
        assertEquals("RAM", ramMetric.label)
        assertEquals("88%", ramMetric.usedPercentText)
        assertEquals("1.5G free", ramMetric.freeText)

        val ramBmp = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = 128,
            fraction = ramMetric.fraction,
            valueText = ramMetric.usedPercentText,
            subLabel = ramMetric.label,
            accentColor = GlassPalette.HudElectricCyan,
            density = 2f,
        )
        assertEquals(128, normalBatt.width)
        assertEquals(128, chargingBatt.width)
        assertEquals(128, lowBatt.width)
        assertEquals(128, ramBmp.width)
        // Outer halo pixel on charging battery gauge differs from non-charging.
        assertNotEquals(normalBatt.getPixel(64, 2), chargingBatt.getPixel(64, 2))
        assertTrue(((lowBmpAlpha(lowBatt)) > 0))
        assertTrue(((ramBmp.getPixel(64, 64) ushr 24) and 0xFF) > 0)
    }

    private fun lowBmpAlpha(bmp: Bitmap): Int = (bmp.getPixel(64, 64) ushr 24) and 0xFF

    @Test
    fun controlTilePlatesVisiblyDifferBetweenActiveInactiveAndDisabledStates() {
        val offPlate = CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = 200,
            heightPx = 96,
            active = false,
            accentColor = GlassPalette.HudElectricCyan,
            density = 2f,
            enabled = true,
        )
        val onPlate = CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = 200,
            heightPx = 96,
            active = true,
            accentColor = GlassPalette.HudElectricCyan,
            density = 2f,
            enabled = true,
        )
        val disabledPlate = CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = 200,
            heightPx = 96,
            active = false,
            accentColor = GlassPalette.HudAmber,
            density = 2f,
            enabled = false,
        )
        assertNotEquals(offPlate.getPixel(100, 48), onPlate.getPixel(100, 48))
        assertNotEquals(offPlate.getPixel(100, 2), onPlate.getPixel(100, 2))
        assertNotEquals(offPlate.getPixel(100, 48), disabledPlate.getPixel(100, 48))
    }

    @Test
    fun appHaloBitmapCompositesAppIconSafely() {
        val icon = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawCircle(24f, 24f, 20f, Paint().apply { color = android.graphics.Color.CYAN })
        }
        val halo = CockpitHudBitmapRenderer.renderAppHaloBitmap(
            sizePx = 64,
            appIcon = icon,
            accentColor = GlassPalette.HudElectricCyan,
            density = 2f,
        )
        assertEquals(64, halo.width)
        assertEquals(64, halo.height)
        assertTrue(((halo.getPixel(32, 32) ushr 24) and 0xFF) > 0)
    }
}
