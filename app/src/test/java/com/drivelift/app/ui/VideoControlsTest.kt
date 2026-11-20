package com.drivelift.app.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.drivelift.app.data.frameFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        // The owner's choice (issue #14, PR #62): two speeds only, 0.5x beside 1x, nothing faster.
        assertEquals(listOf(0.5f, 1f), VIDEO_SPEEDS)
        // Rotate turns portrait into landscape and back.
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, rotatedOrientation(landscapeNow = false))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, rotatedOrientation(landscapeNow = true))
        assertEquals("1x", speedLabel(1f))
        assertEquals("1.25x", speedLabel(1.25f))
        assertEquals("0.5x", speedLabel(0.5f))

        assertEquals("Original  ·  3840x2160  ·  60 fps", qualityLabel(3840, 2160, 59.94f))
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
        val speeds = mutableListOf<Float>()
        var captures = 0
        var rotations = 0
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
                            onSpeed = { speeds += it },
                            onCapture = { captures++ },
                            onRotate = { rotations++ },
                        )
                    }
                }
            }
            pump()
            compose.onNodeWithText("Original  ·  3840x2160  ·  60 fps").assertExists()
            compose.onNodeWithText("0:30").assertExists()
            compose.onNodeWithText("-1:30").assertExists()
            // At normal text size too: Material text buttons default to 40 dp, below the 48 dp rule.
            // The controls are in named groups the owner can find without guessing (issue #14).
            for (group in listOf("Speed", "Step", "Picture")) compose.onNodeWithText(group).assertExists()
            val speedLabels = VIDEO_SPEEDS.map { "Speed ${speedLabel(it)}" }
            for (label in listOf("Back 10 seconds", "Pause", "Forward 10 seconds", "Save this frame as a photo", "Rotate the video") + speedLabels) {
                compose.onNodeWithContentDescription(label)
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
            }
            // At normal text size 0.5x and 1x sit side by side, both fully on screen (issue #14).
            val screenWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            val slow = compose.onNodeWithContentDescription("Speed 0.5x").fetchSemanticsNode().boundsInRoot
            val normal = compose.onNodeWithContentDescription("Speed 1x").fetchSemanticsNode().boundsInRoot
            assertTrue("0.5x and 1x must both be fully visible ($slow, $normal, screen $screenWidth)", normal.right <= screenWidth + 1f)
            assertTrue("0.5x comes before 1x on the same line", slow.right <= normal.left && slow.top == normal.top)
            // The current speed is marked; the others are not.
            compose.onNodeWithContentDescription("Speed 1x").assert(androidx.compose.ui.test.isSelected())
            compose.onNodeWithContentDescription("Speed 0.5x").assert(androidx.compose.ui.test.isNotSelected())
            compose.onNodeWithContentDescription("Back 10 seconds").performClick()
            compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.onNodeWithContentDescription("Speed 0.5x").performClick()
            compose.onNodeWithContentDescription("Speed 1x").performClick()
            compose.onNodeWithContentDescription("Rotate the video").performClick()
            compose.onNodeWithContentDescription("Save this frame as a photo").performClick()
            pump()
            assertEquals(listOf(20_000L, 40_000L), seeks)
            assertEquals(1, toggles)
            assertEquals(listOf(0.5f, 1f), speeds)
            // Tapping 1x stays at 1x: there is no faster speed to cycle on to.
            for (faster in listOf("Speed 1.25x", "Speed 1.5x", "Speed 2x")) {
                assertTrue("$faster must not be offered", compose.onAllNodesWithContentDescription(faster).fetchSemanticsNodes().isEmpty())
            }
            assertEquals(1, rotations)
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
                                    speed = 0.5f, quality = qualityLabel(3840, 2160, 60f), zoomed = true,
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
                    "Rotate the video", "Fit the whole picture",
                    "Step back about one frame", "Step forward about one frame",
                ) + VIDEO_SPEEDS.map { "Speed ${speedLabel(it)}" }) {
                    val node = compose.onNodeWithContentDescription(label)
                    node.assertWidthIsAtLeast(Tokens.TouchTarget).assertHeightIsAtLeast(Tokens.TouchTarget)
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    org.junit.Assert.assertTrue("$label is cut off ($bounds, screen $rootWidth)", bounds.right <= rootWidth + 1f)
                }
                // Owner decision #32: the quality line wraps but is never cut short.
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("video-quality").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layouts)
                org.junit.Assert.assertFalse("The quality line must not be cut short", layouts.single().hasVisualOverflow)
            }
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun frameStepMovesOneFrameAndIsOfferedOnlyWhilePaused() {
        // Single steps from a frame start (frame 60 at 60 fps is 1000 ms). Targets are at or just
        // before the frame's timestamp (1016.67 ms and 983.33 ms), never after it.
        assertEquals(1_016L, frameStepTarget(1_000, 10_000, 60f, forward = true))
        assertEquals(983L, frameStepTarget(1_000, 10_000, 60f, forward = false))
        assertEquals(1_033L, frameStepTarget(1_000, 10_000, -1f, forward = true))
        assertEquals(0L, frameStepTarget(10, 10_000, 30f, forward = false))
        // The last frame of a 10 s clip at 30 fps is frame 299 (9966 ms); Next stays there.
        assertEquals(9_966L, frameStepTarget(9_990, 10_000, 30f, forward = true))
        // Straight after scrubbing between frames: 1001 ms at 60 fps shows frame 61 (1016.67 ms), so
        // Next goes to frame 62 and Previous to frame 60, neither repeating nor skipping.
        assertEquals(61L, frameIndexAt(1_001, 60.0))
        assertEquals(1_033L, frameStepTarget(1_001, 10_000, 60f, forward = true))
        assertEquals(1_000L, frameStepTarget(1_001, 10_000, 60f, forward = false))
        // Straight after pausing ordinary playback: 1250 ms at 2 fps still shows the 1000 ms frame,
        // so Previous goes to 500 ms and Next to 1500 ms.
        assertEquals(2L, frameIndexAt(1_250, 2.0, afterSeek = false))
        assertEquals(500L, frameStepTarget(1_250, 10_000, 2f, forward = false, afterSeek = false))
        assertEquals(1_500L, frameStepTarget(1_250, 10_000, 2f, forward = true, afterSeek = false))
        assertEquals(60L, frameIndexAt(1_010, 60.0, afterSeek = false))
        // At the end: a 2000 ms clip at 2 fps has frames 0 to 3. After it finishes (position 2000)
        // or after stepping onto the last frame, Previous goes to frame 2 and Next stays on frame 3.
        assertEquals(1_000L, frameStepTarget(2_000, 2_000, 2f, forward = false, afterSeek = false))
        assertEquals(1_000L, frameStepTarget(2_000, 2_000, 2f, forward = false, afterSeek = true))
        assertEquals(1_500L, frameStepTarget(1_500, 2_000, 2f, forward = true))
        assertEquals(1_000L, frameStepTarget(frameStepTarget(1_500, 2_000, 2f, forward = true), 2_000, 2f, forward = false))
        // A paused player restored after rotation at 1016 ms (a seek) shows frame 61 at 60 fps.
        assertEquals(1_033L, frameStepTarget(1_016, 10_000, 60f, forward = true, afterSeek = true))
        assertEquals(1_000L, frameStepTarget(1_016, 10_000, 60f, forward = false, afterSeek = true))
        // Repeated steps visit every frame in turn, each seek no later than that frame's timestamp.
        for ((fps, taps) in listOf(60f to 50, 30f to 100, 29.97f to 300, 24f to 240, 59.94f to 120, 120f to 240, 240f to 480)) {
            var position = 0L
            repeat(taps) { i ->
                position = frameStepTarget(position, 3_600_000, fps, forward = true)
                val expected = (i + 1).toLong()
                assertEquals("frame after ${i + 1} taps at $fps fps", expected, frameIndexAt(position, fps.toDouble()))
                org.junit.Assert.assertTrue(
                    "seek $position ms must not pass frame $expected at $fps fps",
                    position <= expected * 1000.0 / fps,
                )
            }
            repeat(taps) { position = frameStepTarget(position, 3_600_000, fps, forward = false) }
            assertEquals("back to the first frame at $fps fps", 0L, position)
        }

        val steps = mutableListOf<Boolean>()
        var playing by androidx.compose.runtime.mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    DriveLiftTheme {
                        VideoControls(
                            state = VideoUiState(
                                playing = playing, position = 1_000, duration = 10_000,
                                speed = 1f, quality = qualityLabel(1920, 1080, 30f),
                            ),
                            onPlayPause = {}, onSeek = {}, onSpeed = {}, onCapture = {},
                            onStepFrame = { steps += it },
                        )
                    }
                }
            }
            pump()
            for (label in listOf("Step back about one frame", "Step forward about one frame")) {
                compose.onNodeWithContentDescription(label)
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
            }
            compose.onNodeWithContentDescription("Step back about one frame").performClick()
            compose.onNodeWithContentDescription("Step forward about one frame").performClick()
            pump()
            assertEquals(listOf(false, true), steps)
        }
        playing = true
    }

    /** Issue #29: Step is offered while playing; the tap reaches the player, which pauses first. */
    @Test
    fun frameStepIsOfferedWhilePlaying() {
        val steps = mutableListOf<Boolean>()
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    DriveLiftTheme {
                        VideoControls(
                            state = VideoUiState(
                                playing = true, position = 1_000, duration = 10_000,
                                speed = 1f, quality = qualityLabel(1920, 1080, 30f),
                            ),
                            onPlayPause = {}, onSeek = {}, onSpeed = {}, onCapture = {},
                            onStepFrame = { steps += it },
                        )
                    }
                }
            }
            pump()
            compose.onNodeWithContentDescription("Pause").assertExists()
            for (label in listOf("Step back about one frame", "Step forward about one frame")) {
                compose.onNodeWithContentDescription(label)
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
            }
            compose.onNodeWithContentDescription("Step forward about one frame").performClick()
            compose.onNodeWithContentDescription("Step back about one frame").performClick()
            pump()
            assertEquals(listOf(true, false), steps)
        }
    }

    /** A fake player clock and seek log around [StepController], driven step by step. */
    private class StepRig(fps: Float = 30f) {
        var clock = 0L
        val seeks = mutableListOf<Pair<Long, Int>>()
        val log = mutableListOf<String>()
        val controller = StepController(
            now = { clock },
            issue = { target, generation -> seeks += target to generation },
            frameDurationMs = { 1000.0 / fps },
            log = { log += it },
        )
        val fps = fps

        /** One tap as the player makes it: counts from the newest target, else the position. */
        fun tap(position: Long, forward: Boolean, afterSeek: Boolean) {
            val queued = controller.latest
            controller.tap(frameStepTarget(queued ?: position, 10_000, fps, forward, afterSeek = queued != null || afterSeek))
        }

        fun frameOf(ms: Long) = frameIndexAt(ms, fps.toDouble())
    }

    /** One step seek at a time; taps meanwhile move the pending target and are never lost. */
    @Test
    fun stepSeeksAreBoundedAndRapidTapsAddUp() {
        val rig = StepRig()
        // Paused at frame 30 (1000 ms); five quick forward taps before any frame is shown.
        repeat(5) { rig.tap(1_000, forward = true, afterSeek = false) }
        assertEquals("only the first tap seeks at once", 1, rig.seeks.size)
        assertEquals(31L, rig.frameOf(rig.seeks[0].first))
        assertEquals("the pending target already counts all five taps", 35L, rig.frameOf(rig.controller.latest!!))
        // The first frame shows: the newest target is sought next, once.
        rig.controller.rendered(rig.seeks[0].first)
        assertEquals(2, rig.seeks.size)
        assertEquals(35L, rig.frameOf(rig.seeks[1].first))
        rig.controller.rendered(rig.seeks[1].first)
        assertEquals(null, rig.controller.latest)
        assertEquals(null, rig.controller.inFlightGeneration)
    }

    /** Rapid taps in both directions while one seek runs end on the right frame. */
    @Test
    fun rapidOppositeTapsLandOnTheNetFrame() {
        val rig = StepRig()
        // From frame 30: forward, forward, back, back, forward = frame 31.
        rig.tap(1_000, forward = true, afterSeek = false)
        rig.tap(1_000, forward = true, afterSeek = false)
        rig.tap(1_000, forward = false, afterSeek = false)
        rig.tap(1_000, forward = false, afterSeek = false)
        rig.tap(1_000, forward = true, afterSeek = false)
        assertEquals(31L, rig.frameOf(rig.controller.latest!!))
        // The taps net out to the frame the first seek shows: one render completes them all,
        // with no second seek to the same position.
        rig.controller.rendered(rig.seeks[0].first)
        assertEquals("one seek in total", 1, rig.seeks.size)
        assertEquals(31L, rig.frameOf(rig.seeks.single().first))
        assertEquals(null, rig.controller.latest)
        assertEquals(null, rig.controller.inFlightGeneration)
    }

    /**
     * Review finding 1: after A times out and B starts, A's late frame must not complete B or
     * start a waiting C. Only B's own frame does.
     */
    @Test
    fun aLateFrameFromATimedOutStepDoesNotReleaseTheNextOne() {
        val rig = StepRig()
        rig.tap(1_000, forward = true, afterSeek = false) // A: frame 31
        val a = rig.seeks.single()
        rig.tap(1_000, forward = true, afterSeek = false) // B waits: frame 32
        rig.clock = 2_000
        rig.controller.timedOut(a.second)
        assertEquals("the timeout starts B", 2, rig.seeks.size)
        val b = rig.seeks[1]
        assertEquals(32L, rig.frameOf(b.first))
        rig.tap(1_000, forward = true, afterSeek = false) // C waits: frame 33
        // A's frame arrives late: it is not B's target, so nothing moves.
        rig.controller.rendered(a.first)
        assertEquals("A's late frame must not start C", 2, rig.seeks.size)
        assertEquals(b.second, rig.controller.inFlightGeneration)
        // A second timeout for A is stale and does nothing either.
        rig.controller.timedOut(a.second)
        assertEquals(2, rig.seeks.size)
        // B's own frame completes B and starts C.
        rig.controller.rendered(b.first)
        assertEquals(3, rig.seeks.size)
        assertEquals(33L, rig.frameOf(rig.seeks[2].first))
        assertTrue(rig.log.any { "timed out" in it && "gen ${a.second}" in it })
    }

    /**
     * Codex finding on 16a43bd: forward, back, forward before the first frame leaves the waiting
     * target equal to the running one. Its frame completes both; no second seek to the same
     * position is made (it would draw nothing), and the next tap seeks at once.
     */
    @Test
    fun tapsThatReturnToTheShownFrameNeedNoSecondSeek() {
        val rig = StepRig()
        rig.tap(1_000, forward = true, afterSeek = false)
        rig.tap(1_000, forward = false, afterSeek = false)
        rig.tap(1_000, forward = true, afterSeek = false)
        val a = rig.seeks.single()
        assertEquals(a.first, rig.controller.latest)
        // The frame at 1033.3 ms (frame 31) is rendered once; no second render is synthesized.
        rig.controller.rendered(1_033)
        assertEquals("no redundant seek", 1, rig.seeks.size)
        assertEquals(null, rig.controller.latest)
        assertEquals(null, rig.controller.inFlightGeneration)
        assertEquals(2, rig.log.count { "frame shown" in it })
        // The next tap is not held back by a timeout.
        rig.tap(1_033, forward = true, afterSeek = true)
        assertEquals(2, rig.seeks.size)
    }

    /** A frame one whole frame away is a different frame: stepping back, A's late frame is rejected. */
    @Test
    fun aNeighbouringFrameNeverCompletesAStep() {
        val rig = StepRig()
        rig.tap(1_000, forward = false, afterSeek = false) // A: frame 29 (966 ms)
        val a = rig.seeks.single()
        rig.tap(1_000, forward = false, afterSeek = false) // B waits: frame 28 (933 ms)
        rig.controller.timedOut(a.second)
        val b = rig.seeks[1]
        assertEquals(28L, rig.frameOf(b.first))
        // A's frame (966.7 ms) appears late, one frame after B's target.
        rig.controller.rendered(966)
        assertEquals(b.second, rig.controller.inFlightGeneration)
        // A frame slightly late within the same frame slot still counts for B.
        rig.controller.rendered(b.first + 20)
        assertEquals(null, rig.controller.inFlightGeneration)
    }

    /** A scrub or skip clears the steps; old timeouts and late frames then do nothing. */
    @Test
    fun clearMakesOldTimeoutsAndFramesInert() {
        val rig = StepRig()
        rig.tap(1_000, forward = true, afterSeek = false)
        val a = rig.seeks.single()
        rig.tap(1_000, forward = true, afterSeek = false)
        rig.controller.clear()
        assertEquals(null, rig.controller.latest)
        rig.controller.timedOut(a.second)
        rig.controller.rendered(a.first)
        assertEquals("nothing is sought after a clear", 1, rig.seeks.size)
        assertTrue(rig.log.any { "superseded" in it })
        // A new step starts at once with a new generation, and the old timeout cannot touch it.
        rig.tap(5_000, forward = true, afterSeek = true)
        assertEquals(2, rig.seeks.size)
        val d = rig.seeks[1]
        assertTrue(d.second != a.second)
        rig.controller.timedOut(a.second)
        assertEquals(d.second, rig.controller.inFlightGeneration)
    }

    /**
     * Review finding 2: each request is timed from its own taps. A tapped at 0 and shown at 1000
     * reports 1000 ms, even though B was tapped at 900; B reports from its own tap at 900.
     */
    @Test
    fun stepLatencyIsTimedPerRequest() {
        val rig = StepRig()
        rig.clock = 0
        rig.tap(1_000, forward = true, afterSeek = false) // A
        rig.clock = 900
        rig.tap(1_000, forward = true, afterSeek = false) // B, waits behind A
        rig.clock = 1_000
        rig.controller.rendered(rig.seeks[0].first)
        assertTrue(rig.log[0], "frame shown 1000 ms after its first tap" in rig.log[0])
        assertTrue(rig.log[0], "1000 ms after the seek started" in rig.log[0])
        rig.clock = 1_300
        rig.controller.rendered(rig.seeks[1].first)
        assertTrue(rig.log[1], "frame shown 400 ms after its first tap" in rig.log[1])
        assertTrue(rig.log[1], "300 ms after the seek started" in rig.log[1])
        assertEquals(2, rig.log.size)
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
        var zoomed by androidx.compose.runtime.mutableStateOf(true)
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
            compose.onNodeWithContentDescription("Fit the whole picture").performClick()
            pump()
            assertEquals(1, fits)
            assertEquals("Fit must disappear once the picture fits", 0, compose.onAllNodesWithText("Fit").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun savingAFrameStoresAPictureAndReportsFailureWithoutThrowing() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        // Robolectric's media store accepts the picture: the helper returns the new item.
        org.junit.Assert.assertNotNull(com.drivelift.app.data.saveFrameToPictures(app, bitmap, "ok.jpg"))
        // A media store that refuses (storage full or unavailable) must give null, not a crash.
        org.robolectric.Robolectric.setupContentProvider(RefusingMediaStore::class.java, "media")
        assertEquals(null, com.drivelift.app.data.saveFrameToPictures(app, bitmap, "fail.jpg"))
    }

    /** Throws on every write, like a full or unavailable shared storage. */
    class RefusingMediaStore : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? =
            throw IllegalStateException("No space left on device")
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
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
