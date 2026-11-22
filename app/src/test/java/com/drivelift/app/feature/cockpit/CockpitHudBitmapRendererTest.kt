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
    fun batteryArcColorFollowsHealthThresholds() {
        assertEquals(GlassPalette.HudEmerald, CockpitHudBitmapRenderer.batteryArcColor(100))
        assertEquals(GlassPalette.HudEmerald, CockpitHudBitmapRenderer.batteryArcColor(31))
        assertEquals(GlassPalette.HudAmber, CockpitHudBitmapRenderer.batteryArcColor(30))
        assertEquals(GlassPalette.HudAmber, CockpitHudBitmapRenderer.batteryArcColor(15))
        assertEquals(GlassPalette.HudCoral, CockpitHudBitmapRenderer.batteryArcColor(14))
        assertEquals(GlassPalette.HudCoral, CockpitHudBitmapRenderer.batteryArcColor(1))
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
    fun arcGaugeBitmapRendersNonEmptyPixelsForBatteryAndRam() {
        val battBmp = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = 128,
            fraction = 0.84f,
            valueText = "84%",
            subLabel = "BATT",
            accentColor = CockpitHudBitmapRenderer.batteryArcColor(84),
            density = 2f,
        )
        val ramBmp = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
            sizePx = 128,
            fraction = 0.48f,
            valueText = "4.8G",
            subLabel = "RAM",
            accentColor = GlassPalette.HudElectricCyan,
            density = 2f,
        )
        assertEquals(128, battBmp.width)
        assertEquals(128, ramBmp.width)
        assertTrue(((battBmp.getPixel(64, 64) ushr 24) and 0xFF) > 0)
        assertTrue(((ramBmp.getPixel(64, 64) ushr 24) and 0xFF) > 0)
    }

    @Test
    fun controlTilePlatesVisiblyDifferBetweenActiveAndInactiveStates() {
        val offPlate = CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = 200,
            heightPx = 96,
            active = false,
            accentColor = GlassPalette.HudElectricCyan,
            density = 2f,
        )
        val onPlate = CockpitHudBitmapRenderer.renderControlTileBitmap(
            widthPx = 200,
            heightPx = 96,
            active = true,
            accentColor = GlassPalette.HudElectricCyan,
            density = 2f,
        )
        assertNotEquals(offPlate.getPixel(100, 48), onPlate.getPixel(100, 48))
        assertNotEquals(offPlate.getPixel(100, 2), onPlate.getPixel(100, 2))
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
