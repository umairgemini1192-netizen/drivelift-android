package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.ui.ManeuverIconContrast
import com.drivelift.app.feature.cockpit.ui.ManeuverIconStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Google Maps draws its turn arrow dark on transparent. On the black maneuver tile that arrow is
 * almost invisible, so it is repainted white; a dark multi-colour picture goes on a light tile.
 */
class ManeuverIconContrastTest {

    private val transparent = 0x00000000

    /** A square of [size] x [size] pixels with the middle third painted [glyph] and the rest transparent. */
    private fun glyphOnTransparent(glyph: Int, size: Int = 24): IntArray {
        val pixels = IntArray(size * size) { transparent }
        for (row in size / 3 until size * 2 / 3) {
            for (column in size / 3 until size * 2 / 3) {
                pixels[row * size + column] = glyph
            }
        }
        return pixels
    }

    @Test
    fun darkGreyArrowOnTransparentIsRepaintedWhite() {
        // The grey Google Maps uses for its arrow.
        assertEquals(ManeuverIconStyle.WHITE_TINT, ManeuverIconContrast.classify(glyphOnTransparent(0xFF3C4043.toInt())))
    }

    @Test
    fun pureBlackArrowIsRepaintedWhite() {
        assertEquals(ManeuverIconStyle.WHITE_TINT, ManeuverIconContrast.classify(glyphOnTransparent(0xFF000000.toInt())))
    }

    @Test
    fun whiteArrowIsLeftAlone() {
        assertEquals(ManeuverIconStyle.AS_IS, ManeuverIconContrast.classify(glyphOnTransparent(0xFFFFFFFF.toInt())))
    }

    @Test
    fun brightColourfulPictureIsLeftAlone() {
        assertEquals(ManeuverIconStyle.AS_IS, ManeuverIconContrast.classify(glyphOnTransparent(0xFF34D399.toInt())))
    }

    @Test
    fun darkMultiColourPictureSitsOnALightBadge() {
        val pixels = glyphOnTransparent(0xFF8B1A1A.toInt())
        // Add a second dark hue so the picture is clearly not a single colour.
        for (index in pixels.indices step 2) {
            if (pixels[index] != transparent) pixels[index] = 0xFF1A3A8B.toInt()
        }
        assertEquals(ManeuverIconStyle.LIGHT_BADGE, ManeuverIconContrast.classify(pixels))
    }

    @Test
    fun aFullyTransparentPictureIsLeftAlone() {
        assertEquals(ManeuverIconStyle.AS_IS, ManeuverIconContrast.classify(IntArray(24 * 24) { transparent }))
    }

    @Test
    fun aFewStrayOpaquePixelsAreNotEnoughToJudgeAPicture() {
        val pixels = IntArray(24 * 24) { transparent }
        for (index in 0 until 5) pixels[index] = 0xFF000000.toInt()
        assertEquals(ManeuverIconStyle.AS_IS, ManeuverIconContrast.classify(pixels))
    }

    @Test
    fun faintAntiAliasingPixelsDoNotCountAsTheGlyph() {
        // A white glyph surrounded by very faint dark edge pixels: the faint ones are background.
        val pixels = glyphOnTransparent(0xFFFFFFFF.toInt())
        for (index in pixels.indices) {
            if (pixels[index] == transparent) pixels[index] = 0x10000000
        }
        assertEquals(ManeuverIconStyle.AS_IS, ManeuverIconContrast.classify(pixels))
    }
}
