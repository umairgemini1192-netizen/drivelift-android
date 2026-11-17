package com.drivelift.app.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.drivelift.app.data.frameFileName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The custom player controls and their helpers (issue 21 follow-up). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class VideoControlsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun timesSpeedsQualityAndFileNames() {
        assertEquals("0:07", formatPlaybackTime(7_000))
        assertEquals("4:05", formatPlaybackTime(245_000))
        assertEquals("1:02:09", formatPlaybackTime(3_729_000))
        assertEquals("0:00", formatPlaybackTime(-5))

        assertEquals(1.25f, nextVideoSpeed(1f))
        assertEquals(1f, nextVideoSpeed(0.75f))
        assertEquals(1f, nextVideoSpeed(3f))
        assertEquals("1x", speedLabel(1f))
        assertEquals("1.25x", speedLabel(1.25f))
        assertEquals("0.5x", speedLabel(0.5f))

        assertEquals("Original  ·  3840x2160  ·  60 fps", qualityLabel(3840, 2160, 59.94f))
        assertEquals("Original  ·  1920x1080", qualityLabel(1920, 1080, -1f))
        assertEquals("Original quality", qualityLabel(0, 0, 30f))

        assertEquals(0L, skipTarget(4_000, 60_000, -SKIP_MS))
        assertEquals(60_000L, skipTarget(55_000, 60_000, SKIP_MS))
        assertEquals(25_000L, skipTarget(15_000, 60_000, SKIP_MS))

        assertEquals("Trip_to_the_coast_01-23.jpg", frameFileName("Trip to the coast.mp4", 83_000))
        assertEquals("long_1-02-09.jpg", frameFileName("long.mov", 3_729_000))
        assertEquals("video_00-00.jpg", frameFileName(".mp4", 0))
    }

    @Test
    fun controlsSeekSkipToggleAndCaptureWithLargeTargets() {
        val seeks = mutableListOf<Long>()
        var toggles = 0
        var speeds = 0
        var captures = 0
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    DriveLiftTheme {
                        VideoControls(
                            state = VideoUiState(
                                playing = true, position = 30_000, duration = 120_000,
                                speed = 1f, quality = qualityLabel(3840, 2160, 60f),
                            ),
                            onPlayPause = { toggles++ },
                            onSeek = { seeks += it },
                            onSpeed = { speeds++ },
                            onCapture = { captures++ },
                        )
                    }
                }
            }
            pump()
            compose.onNodeWithText("Original  ·  3840x2160  ·  60 fps").assertExists()
            compose.onNodeWithText("0:30").assertExists()
            compose.onNodeWithText("-1:30").assertExists()
            for (label in listOf("Back 10 seconds", "Pause", "Forward 10 seconds", "Save this frame as a photo")) {
                compose.onNodeWithContentDescription(label)
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
            }
            compose.onNodeWithContentDescription("Back 10 seconds").performClick()
            compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.onNodeWithContentDescription("Playback speed 1x").performClick()
            compose.onNodeWithContentDescription("Save this frame as a photo").performClick()
            pump()
            assertEquals(listOf(20_000L, 40_000L), seeks)
            assertEquals(1, toggles)
            assertEquals(1, speeds)
            assertEquals(1, captures)
        }
    }

    @Test
    fun allControlsKeepFullTargetsOnACompactPhoneWithLargeText() {
        // The production layout: 320 dp wide, 1.5x text, the player's outer Tokens.Edge padding.
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        try {
            compose.mainClock.autoAdvance = false
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.setContent {
                        DriveLiftTheme {
                            VideoControls(
                                state = VideoUiState(
                                    playing = true, position = 3_729_000, duration = 7_200_000,
                                    speed = 1.25f, quality = qualityLabel(3840, 2160, 60f), zoomed = true,
                                ),
                                onPlayPause = {}, onSeek = {}, onSpeed = {}, onCapture = {}, onFit = {},
                                modifier = androidx.compose.ui.Modifier.padding(Tokens.Edge),
                            )
                        }
                    }
                }
                pump()
                val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
                for (label in listOf(
                    "Back 10 seconds", "Pause", "Forward 10 seconds", "Save this frame as a photo",
                    "Playback speed 1.25x", "Fit the whole picture",
                )) {
                    val node = compose.onNodeWithContentDescription(label)
                    node.assertWidthIsAtLeast(Tokens.TouchTarget).assertHeightIsAtLeast(Tokens.TouchTarget)
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    org.junit.Assert.assertTrue("$label is cut off ($bounds, screen $rootWidth)", bounds.right <= rootWidth + 1f)
                }
            }
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun captureIsDisabledWhileAFrameIsBeingSaved() {
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    DriveLiftTheme {
                        VideoControls(
                            state = VideoUiState(
                                playing = false, position = 0, duration = 10_000,
                                speed = 1f, quality = qualityLabel(1920, 1080, 30f), capturing = true,
                            ),
                            onPlayPause = {}, onSeek = {}, onSpeed = {}, onCapture = {},
                        )
                    }
                }
            }
            pump()
            compose.onNodeWithContentDescription("Save this frame as a photo").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Play").assertExists()
        }
    }

    @Test
    fun fitAppearsOnlyWhenZoomedAndResets() {
        var fits = 0
        var zoomed by androidx.compose.runtime.mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    DriveLiftTheme {
                        VideoControls(
                            state = VideoUiState(
                                playing = true, position = 1_000, duration = 10_000,
                                speed = 1f, quality = qualityLabel(1920, 1080, 30f), zoomed = zoomed,
                            ),
                            onPlayPause = {}, onSeek = {}, onSpeed = {}, onCapture = {},
                            onFit = { fits++; zoomed = false },
                        )
                    }
                }
            }
            pump()
            assertEquals(0, compose.onAllNodesWithText("Fit").fetchSemanticsNodes().size)
            zoomed = true
            pump()
            compose.onNodeWithContentDescription("Fit the whole picture").performClick()
            pump()
            assertEquals(1, fits)
            assertEquals(0, compose.onAllNodesWithText("Fit").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun savingAFrameNeverThrowsWhenStorageFails() {
        // Robolectric has no MediaStore provider, so the insert is refused: the helper must return
        // null for the caller's failure message rather than throw.
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        assertEquals(null, com.drivelift.app.data.saveFrameToPictures(app, bitmap, "x.jpg"))
    }

    @Test
    fun zoomedWideVideoInATallScreenCannotBeDraggedOutOfView() {
        val viewport = androidx.compose.ui.unit.IntSize(320, 640)
        val picture = fittedVideoSize(viewport, 16f / 9f)
        assertEquals(320f, picture.width)
        assertEquals(180f, picture.height)
        val zoom = ImageTransformState()
        zoom.applyVideoGesture(androidx.compose.ui.geometry.Offset(10_000f, 10_000f), 4f, picture, viewport)
        assertEquals(4f, zoom.scale)
        // 4x picture is 1280 x 720 in a 320 x 640 screen: edges stop at the screen edges.
        assertEquals(480f, zoom.offset.x)
        assertEquals(40f, zoom.offset.y)
        // At 2x the picture (360 tall) is still shorter than the screen, so it stays centred vertically.
        zoom.reset()
        zoom.applyVideoGesture(androidx.compose.ui.geometry.Offset(0f, 500f), 2f, picture, viewport)
        assertEquals(0f, zoom.offset.y)
    }

    @Test
    fun rotatingAZoomedVideoKeepsThePictureOnScreen() {
        val landscape = androidx.compose.ui.unit.IntSize(640, 320)
        val portrait = androidx.compose.ui.unit.IntSize(320, 640)
        val zoom = ImageTransformState()
        zoom.applyVideoGesture(
            androidx.compose.ui.geometry.Offset(10_000f, 0f), 4f, fittedVideoSize(landscape, 16f / 9f), landscape,
        )
        val before = zoom.offset.x
        // The re-clamp the player runs when the viewport changes: no movement, same zoom.
        zoom.applyVideoGesture(androidx.compose.ui.geometry.Offset.Zero, 1f, fittedVideoSize(portrait, 16f / 9f), portrait)
        assertEquals(4f, zoom.scale)
        assertEquals("Offset must shrink to the portrait limit", 480f, zoom.offset.x)
        org.junit.Assert.assertTrue(before > zoom.offset.x)
    }

    @Test
    fun zoomClampsSoThePictureNeverLeavesTheScreen() {
        val zoom = ImageTransformState()
        val bounds = androidx.compose.ui.unit.IntSize(1000, 500)
        zoom.applyGesture(pan = androidx.compose.ui.geometry.Offset(5_000f, 5_000f), zoom = 2f, bounds = bounds)
        assertEquals(2f, zoom.scale)
        assertEquals(500f, zoom.offset.x)
        assertEquals(250f, zoom.offset.y)
        zoom.applyGesture(pan = androidx.compose.ui.geometry.Offset.Zero, zoom = 10f, bounds = bounds)
        assertEquals("Zoom is capped at 4x", 4f, zoom.scale)
        zoom.reset()
        assertEquals(1f, zoom.scale)
    }

    private fun pump() = repeat(6) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
    }
}
