package com.drivelift.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Renders the launcher icon from the app's own resources (the foreground vector and the
 * background colour) and checks the two rules an adaptive icon has to keep.
 *
 * The masks here are drawn by this test. They stand in for what a launcher does; no launcher is
 * involved, and themed (monochrome) icons are not covered because the app does not ship one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LauncherIconTest {
    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val record = System.getProperty("roborazzi.test.record") == "true"

    @Before
    fun fakeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    /** The whole 108 dp layer pair at [layerPx] pixels, unmasked. */
    private fun layers(layerPx: Int, background: Boolean = true): Bitmap {
        val bitmap = Bitmap.createBitmap(layerPx, layerPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (background) canvas.drawColor(ContextCompat.getColor(app, R.color.launcher_background))
        val foreground = checkNotNull(ContextCompat.getDrawable(app, R.drawable.ic_launcher_foreground))
        foreground.setBounds(0, 0, layerPx, layerPx)
        foreground.draw(canvas)
        return bitmap
    }

    /** The icon as a launcher shows it: the middle 72 of 108 dp, cut to [mask], [sizePx] wide. */
    private fun icon(sizePx: Int, mask: (Float) -> Path): Bitmap {
        val layerPx = sizePx * LAYER_DP / VIEWPORT_DP
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.clipPath(mask(sizePx.toFloat()))
        val offset = -(layerPx - sizePx) / 2f
        canvas.drawBitmap(layers(layerPx), offset, offset, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun circle(size: Float) = Path().apply { addCircle(size / 2, size / 2, size / 2, Path.Direction.CW) }

    private fun roundedSquare(size: Float) =
        Path().apply { addRoundRect(RectF(0f, 0f, size, size), size * 0.22f, size * 0.22f, Path.Direction.CW) }

    private fun squircle(size: Float) = Path().apply {
        val r = size / 2
        for (step in 0..360) {
            val t = Math.toRadians(step.toDouble())
            val x = r + r * sign(cos(t)) * abs(cos(t)).pow(0.5)
            val y = r + r * sign(sin(t)) * abs(sin(t)).pow(0.5)
            if (step == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
        }
        close()
    }

    @Test
    fun markStaysInsideTheAdaptiveIconSafeZone() {
        // Every launcher shows at least a circle of 66 dp across in the middle of the 108 dp layer.
        val scale = 4
        val mark = layers(LAYER_DP * scale, background = false)
        val centre = LAYER_DP * scale / 2f
        var farthest = 0f
        for (y in 0 until mark.height) for (x in 0 until mark.width) {
            if (Color.alpha(mark.getPixel(x, y)) > 8) {
                farthest = maxOf(farthest, hypot(x + 0.5f - centre, y + 0.5f - centre))
            }
        }
        assertTrue("The foreground draws nothing", farthest > 0f)
        assertTrue(
            "The mark reaches ${farthest / scale} dp from the centre; the safe zone ends at ${SAFE_DP / 2} dp",
            farthest / scale <= SAFE_DP / 2f,
        )
    }

    @Test
    fun iconColoursComeFromTheTheme() {
        // An XML drawable cannot read Compose colours, so it repeats them. Each one must be a
        // colour that ui/Theme.kt defines, so the icon cannot drift from the app's palette.
        val theme = File("src/main/java/com/drivelift/app/ui/Theme.kt").readText().uppercase()
        val hex = Regex("\"#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})\"")
        val icon = File("src/main/res/drawable/ic_launcher_foreground.xml").readText()
        val colours = hex.findAll(icon).map { it.groupValues[1].uppercase() }.filter { it != "00000000" }.toSet()
        assertTrue("The icon has no colours", colours.isNotEmpty())
        for (colour in colours) {
            val argb = if (colour.length == 6) "FF$colour" else colour
            assertTrue("#$colour in the launcher icon is not a colour in Theme.kt", theme.contains("0X$argb"))
        }
        val background = "%06X".format(ContextCompat.getColor(app, R.color.launcher_background) and 0xFFFFFF)
        assertTrue("The launcher background #$background is not a colour in Theme.kt", theme.contains("0XFF$background"))
    }

    @Test
    fun renderForReview() {
        val tile = 432
        // Row 1: the unmasked layers with the visible square and the safe circle marked, then
        // the three common launcher shapes.
        val guide = layers(tile).also { bitmap ->
            val canvas = Canvas(bitmap)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE }
            val unit = tile / LAYER_DP.toFloat()
            val inset = (LAYER_DP - VIEWPORT_DP) / 2 * unit
            canvas.drawRect(inset, inset, tile - inset, tile - inset, line)
            canvas.drawCircle(tile / 2f, tile / 2f, SAFE_DP / 2f * unit, line)
        }
        val shapes = listOf(guide, icon(tile, ::circle), icon(tile, ::squircle), icon(tile, ::roundedSquare))
        save("launcher-icon-standard", row(shapes, LIGHT_WALLPAPER, DARK_WALLPAPER))

        // Row 2: real launcher sizes in pixels (48 dp at mdpi to xxxhdpi), then the smallest
        // one again enlarged without smoothing, to show what survives at that size.
        val sizes = listOf(48, 72, 96, 144, 192).map { icon(it, ::circle) }
        val smallest = Bitmap.createScaledBitmap(icon(48, ::circle), 48 * 6, 48 * 6, false)
        val settingsSize = Bitmap.createScaledBitmap(icon(24, ::circle), 24 * 6, 24 * 6, false)
        save("launcher-icon-compact", row(sizes + smallest + settingsSize, LIGHT_WALLPAPER, DARK_WALLPAPER))

        assertEquals(4, shapes.size)
    }

    /** [tiles] side by side, once on each wallpaper colour. */
    private fun row(tiles: List<Bitmap>, vararg wallpapers: Int): Bitmap {
        val gap = 24
        val height = tiles.maxOf { it.height } + 2 * gap
        val width = tiles.sumOf { it.width + gap } + gap
        val out = Bitmap.createBitmap(width, height * wallpapers.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val fill = Paint()
        wallpapers.forEachIndexed { index, wallpaper ->
            val top = index * height
            fill.color = wallpaper
            canvas.drawRect(0f, top.toFloat(), width.toFloat(), (top + height).toFloat(), fill)
            var x = gap
            for (tile in tiles) {
                canvas.drawBitmap(tile, x.toFloat(), top + (height - tile.height) / 2f, null)
                x += tile.width + gap
            }
        }
        return out
    }

    private fun save(name: String, bitmap: Bitmap) {
        if (!record) return
        val file = File("build/outputs/screenshots/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val LAYER_DP = 108
        const val VIEWPORT_DP = 72
        const val SAFE_DP = 66

        // Stand-ins for a light and a dark home screen. Test-only values.
        val LIGHT_WALLPAPER = Color.rgb(232, 234, 240)
        val DARK_WALLPAPER = Color.rgb(18, 18, 22)
    }
}
