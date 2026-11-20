package com.drivelift.app.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Turn control's steps and the fit of a turned picture. Pure functions, no Android needed. */
class VideoTurnTest {
    @Test
    fun eachTapTurnsNinetyDegreesClockwiseAndWrapsAfterThreeQuarters() {
        assertEquals(1, nextQuarterTurn(0))
        assertEquals(2, nextQuarterTurn(1))
        assertEquals(3, nextQuarterTurn(2))
        assertEquals(0, nextQuarterTurn(3))
    }

    @Test
    fun turnsReadAsDegrees() {
        assertEquals(listOf(0, 90, 180, 270), (0..3).map { quarterTurnDegrees(it) })
        // Out-of-range input is folded back into one full turn rather than failing.
        assertEquals(90, quarterTurnDegrees(5))
        assertEquals(270, quarterTurnDegrees(-1))
    }

    @Test
    fun onlyQuarterAndThreeQuarterTurnsAreSideways() {
        assertEquals(listOf(false, true, false, true), (0..3).map { isSideways(it) })
        assertEquals(true, isSideways(-1))
    }

    @Test
    fun theAspectFlipsOnlyWhenSideways() {
        val wide = 16f / 9f
        assertEquals(wide, turnedAspect(wide, 0), 0.0001f)
        assertEquals(9f / 16f, turnedAspect(wide, 1), 0.0001f)
        assertEquals(wide, turnedAspect(wide, 2), 0.0001f)
        assertEquals(9f / 16f, turnedAspect(wide, 3), 0.0001f)
    }

    @Test
    fun theTurnedDescriptionNamesTheActionAndTheAngleNow() {
        assertEquals("Turn the video clockwise, now 0 degrees", turnDescription(0))
        assertEquals("Turn the video clockwise, now 90 degrees", turnDescription(1))
        assertEquals("Turn the video clockwise, now 270 degrees", turnDescription(3))
    }

    @Test
    fun unturnedBoxSwapsSidesOnlyWhenSideways() {
        val visible = Size(1080f, 1920f)
        assertEquals(visible, unturnedSize(visible, 0))
        assertEquals(Size(1920f, 1080f), unturnedSize(visible, 1))
        assertEquals(visible, unturnedSize(visible, 2))
    }

    @Test
    fun aWideVideoTurnedOnceFitsAPortraitScreenAsATallPicture() {
        // Phone viewport 1080 x 2400 px, a 16:9 video. Unturned it fits the width: 1080 x 607.5.
        val viewport = IntSize(1080, 2400)
        val unturned = fittedVideoSize(viewport, turnedAspect(16f / 9f, 0))
        assertEquals(1080f, unturned.width, 0.5f)
        assertEquals(607.5f, unturned.height, 0.5f)

        // Turned once it is 9:16 on screen and fits the width: 1080 x 1920, nothing cut off.
        val turned = fittedVideoSize(viewport, turnedAspect(16f / 9f, 1))
        assertEquals(1080f, turned.width, 0.5f)
        assertEquals(1920f, turned.height, 0.5f)

        // The texture box that gets turned back to exactly that on-screen size.
        val box = unturnedSize(turned, 1)
        assertEquals(1920f, box.width, 0.5f)
        assertEquals(1080f, box.height, 0.5f)
        assertEquals(16f / 9f, box.width / box.height, 0.001f)
    }

    @Test
    fun aTurnedPictureNeverExceedsTheViewport() {
        val viewport = IntSize(1080, 2400)
        for (aspect in listOf(16f / 9f, 4f / 3f, 1f, 9f / 16f, 2.35f)) {
            for (turns in 0..3) {
                val visible = fittedVideoSize(viewport, turnedAspect(aspect, turns))
                assertEquals(true, visible.width <= viewport.width + 0.5f)
                assertEquals(true, visible.height <= viewport.height + 0.5f)
                // The turned shape keeps the video's own proportion.
                assertEquals(turnedAspect(aspect, turns), visible.width / visible.height, 0.001f)
            }
        }
    }
}
