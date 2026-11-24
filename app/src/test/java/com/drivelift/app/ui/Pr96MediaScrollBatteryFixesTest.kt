package com.drivelift.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.data.MediaItem
import com.drivelift.app.feature.applock.monitor.UsageStatsForegroundFallbackMonitor
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.TelemetryHudSnapshot
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitHudBitmapRenderer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Unit and Compose UI verification for PR #96:
 * 1. Scroll position retention across route/tab disposal and folder navigation.
 * 2. Swipeable HorizontalPager full-screen image viewer with swipe-down to dismiss.
 * 3. Immersive video player controls (icon-only toolbar, Fit/Fill toggle, top gradient scrim,
 *    ACTION_SEND share button, direct -10s/+10s skip callback, and filledVideoSize math).
 * 4. Background battery drain fix (pausing widget, network speed, battery telemetry, quick
 *    controls usage, and App Lock fallback polling immediately on ACTION_SCREEN_OFF and
 *    resuming cleanly on ACTION_SCREEN_ON).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class Pr96MediaScrollBatteryFixesTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun pump(times: Int = 8) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(15)
    }

    // ------------------------------------------------------------------ 1. Scroll position retention

    @Test
    fun scrollPositionStoreIsolatesKeysAndClampsNegativeOffsets() {
        val store = ScrollPositionStore()
        assertEquals(ScrollPosition(0, 0), store.get("tab:phone:root"))

        store.save("tab:phone:root", 14, 92)
        store.save("tab:drive:browse:grid:folder-1", 7, 40)
        store.save("tab:drop:root", -3, -15)

        assertEquals(ScrollPosition(14, 92), store.get("tab:phone:root"))
        assertEquals(ScrollPosition(7, 40), store.get("tab:drive:browse:grid:folder-1"))
        assertEquals(ScrollPosition(0, 0), store.get("tab:drop:root"))
        assertEquals(ScrollPosition(0, 0), store.get("tab:transfers:root"))

        store.clear()
        assertEquals(ScrollPosition(0, 0), store.get("tab:phone:root"))
    }

    @Test
    fun persistentLazyListStateRestoresScrollPositionAfterBackNavigation() {
        val store = ScrollPositionStore()
        var showChild by mutableStateOf(false)
        val rows = (0 until 60).map { "Folder $it" }

        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        if (showChild) {
                            Box(Modifier.fillMaxSize()) {
                                Text("Child Screen")
                            }
                        } else {
                            val listState = rememberPersistentLazyListState(store, "tab:phone:root")
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag("test-list"),
                            ) {
                                items(rows, key = { it }) { label ->
                                    Box(Modifier.fillMaxWidth().height(72.dp)) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pump()
            compose.onNodeWithTag("test-list").performScrollToIndex(24)
            pump()
            assertTrue("Scroll index must be persisted before leaving", store.get("tab:phone:root").firstVisibleItemIndex >= 20)

            // Navigate into a child screen (disposes the LazyColumn).
            showChild = true
            pump()
            compose.onNodeWithText("Child Screen").assertExists()

            // Return via Back (recomposes LazyColumn fresh).
            showChild = false
            pump()
            compose.onNodeWithText("Folder 24").assertExists()
            assertTrue(
                "Returned list must keep its saved scroll offset",
                store.get("tab:phone:root").firstVisibleItemIndex >= 20,
            )
        }
    }

    @Test
    fun persistentLazyGridStateRetainsDistinctOffsetsPerFolderPath() {
        val store = ScrollPositionStore()
        var activeFolder by mutableStateOf("folder-a")
        val itemsA = (0 until 80).map { "A_$it" }
        val itemsB = (0 until 80).map { "B_$it" }

        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        val folder = activeFolder
                        val gridState = rememberPersistentLazyGridState(store, "tab:drive:browse:grid:$folder")
                        val data = if (folder == "folder-a") itemsA else itemsB
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = gridState,
                            modifier = Modifier.fillMaxSize().testTag("test-grid"),
                        ) {
                            items(data, key = { it }) { label ->
                                Box(Modifier.fillMaxWidth().height(110.dp)) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }
            pump()
            compose.onNodeWithTag("test-grid").performScrollToIndex(30)
            pump()
            val savedA = store.get("tab:drive:browse:grid:folder-a").firstVisibleItemIndex
            assertTrue("Expected folder-a scroll index to be saved ($savedA)", savedA >= 24)

            // Open subfolder B: starts at top without clobbering folder A.
            activeFolder = "folder-b"
            pump()
            compose.onNodeWithText("B_0").assertExists()
            assertEquals(savedA, store.get("tab:drive:browse:grid:folder-a").firstVisibleItemIndex)

            // Navigate back to folder A: restores exact previous grid offset.
            activeFolder = "folder-a"
            pump()
            compose.onNodeWithText("A_30").assertExists()
        }
    }

    // ------------------------------------------------------------------ 2. Swipeable image viewer

    @Test
    fun localImageViewerSwipesBetweenFolderImagesAndDismissesOnSwipeDown() {
        val pngBytes = syntheticPngBytes()
        val photo1 = syntheticMediaItem(id = 101L, name = "IMG_0101.jpg", color = Color.RED, pngBytes = pngBytes)
        val photo2 = syntheticMediaItem(id = 102L, name = "IMG_0102.jpg", color = Color.GREEN, pngBytes = pngBytes)
        val video3 = syntheticMediaItem(id = 103L, name = "VID_0103.mp4", isVideo = true, pngBytes = pngBytes)
        val photo4 = syntheticMediaItem(id = 104L, name = "IMG_0104.jpg", color = Color.BLUE, pngBytes = pngBytes)
        val folderItems = listOf(photo1, photo2, video3, photo4)

        val vm = AppViewModel(app)
        var activeId = photo1.id
        var closed = false

        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        LocalMediaViewer(
                            vm = vm,
                            item = photo1,
                            onClose = { closed = true },
                            folderItems = folderItems,
                            onItemChanged = { activeId = it.id },
                        )
                    }
                }
            }
            compose.waitUntil(5_000) {
                pump(1)
                compose.onAllNodesWithText("IMG_0101.jpg").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("local-image-pager").assertExists()
            compose.onNodeWithText("1 of 3", substring = true).assertExists()

            // Swipe left to advance to IMG_0102.jpg.
            compose.onNodeWithTag("local-image-pager").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) {
                pump(1)
                compose.onAllNodesWithText("IMG_0102.jpg").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(photo2.id, activeId)
            compose.onNodeWithText("2 of 3", substring = true).assertExists()

            // Swipe down from the center of the photo to dismiss the viewer cleanly below the top bar.
            compose.onNodeWithTag("viewer-image").performTouchInput {
                swipeDown(startY = centerY, endY = bottom)
            }
            pump(10)
            assertTrue("Swipe-down gesture must dismiss the full-screen image viewer", closed)
        }
    }

    // ------------------------------------------------------------------ 3. Video player & header overhaul

    @Test
    fun viewerTopBarRendersTopScrimAndShareButtonDispatchesActionSend() {
        val item = syntheticMediaItem(id = 201L, name = "Sunset_Clip.mp4", isVideo = true, pngBytes = syntheticPngBytes())

        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity {
                activity = it
                it.setContent {
                    DriveLiftTheme {
                        ViewerTopBar(
                            title = item.name,
                            subtitle = "Video on this phone  ·  12 MB",
                            showResetZoom = false,
                            onResetZoom = {},
                            onClose = {},
                            externalLabel = "Open in another app",
                            onOpenExternally = {},
                            onShare = { shareLocalMedia(it, item) },
                            onDelete = {},
                            favorite = false,
                            onToggleFavorite = {},
                            showTopScrim = true,
                        )
                    }
                }
            }
            pump()
            compose.onNodeWithTag("video-header-scrim").assertExists()
            compose.onNodeWithTag("viewer-share")
                .assertWidthIsAtLeast(Tokens.TouchTarget)
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            pump()

            val started = shadowOf(activity).nextStartedActivity
            assertNotNull("Share icon must launch Android share chooser", started)
            assertEquals(Intent.ACTION_CHOOSER, started.action)
            @Suppress("DEPRECATION")
            val target = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(target)
            assertEquals(Intent.ACTION_SEND, target!!.action)
            assertEquals("video/mp4", target.type)
            @Suppress("DEPRECATION")
            assertEquals(item.uri, target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertTrue((target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        }
    }

    @Test
    fun videoControlsFitFillToggleAndDirectSkipCallbacksWorkCleanly() {
        // Pure geometry check: filledVideoSize fills both viewport edges without distorting aspect.
        val viewport = IntSize(1080, 2400)
        val wideFilled = filledVideoSize(viewport, 16f / 9f)
        assertEquals(2400f, wideFilled.height, 0.5f)
        assertTrue("16:9 Fill in portrait must scale width beyond 1080px", wideFilled.width > viewport.width)
        assertEquals(16f / 9f, wideFilled.width / wideFilled.height, 0.001f)

        var fillMode by mutableStateOf(false)
        val skips = mutableListOf<Long>()

        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        VideoControls(
                            state = VideoUiState(
                                playing = true,
                                position = 30_000L,
                                duration = 120_000L,
                                speed = 1f,
                                quality = qualityLabel(1920, 1080, 60f),
                                fillMode = fillMode,
                            ),
                            onPlayPause = {},
                            onSeek = {},
                            onSkip = { skips += it },
                            onSpeed = {},
                            onCapture = {},
                            onToggleFillMode = { fillMode = !fillMode },
                        )
                    }
                }
            }
            pump()
            // Aspect toggle switches Fit -> Fill -> Fit cleanly.
            compose.onNodeWithContentDescription("Fill screen")
                .assertWidthIsAtLeast(Tokens.TouchTarget)
                .assertHeightIsAtLeast(Tokens.TouchTarget)
                .performClick()
            pump()
            assertTrue(fillMode)
            compose.onNodeWithContentDescription("Fit the whole picture").performClick()
            pump()
            assertFalse(fillMode)

            // -10s / +10s buttons wire directly to onSkip for immediate player.seekTo() sync.
            compose.onNodeWithContentDescription("Back 10 seconds").performClick()
            compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
            pump()
            assertEquals(listOf(-SKIP_MS, SKIP_MS), skips)
        }
    }

    // ------------------------------------------------------------------ 4. Background battery drain fix

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun screenOffImmediatelyPausesTelemetrySpeedQuickControlsAndAppLockLoops() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var screenOn = true
        var batteryPct = 75
        var pushCount = 0
        var txBytes = 10_000L
        var rxBytes = 20_000L
        var nowMs = 1_000L

        val monitor = CockpitTelemetryMonitor(
            context = app,
            scope = scope,
            isScreenInteractive = { screenOn },
            snapshotReader = { intent ->
                val pct = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, batteryPct) ?: batteryPct
                batteryPct = pct
                TelemetryHudSnapshot(
                    battery = BatteryHudMetric(percent = pct, fraction = pct / 100f, valueText = "$pct%"),
                    ram = CockpitHudBitmapRenderer.formatRamHudMetric(
                        availBytes = 4L * 1024L * 1024L * 1024L,
                        totalBytes = 12L * 1024L * 1024L * 1024L,
                    ),
                    isLoadingInitial = false,
                )
            },
            widgetPusher = { pushCount++ },
            trafficReader = { txBytes to rxBytes },
            networkReader = { IdleNetworkKind.WIFI to null },
            elapsedClock = { nowMs },
        )

        monitor.acquire()
        monitor.acquireIdleNetwork()
        scope.runCurrent()
        assertTrue(monitor.isScreenOn.value)
        assertTrue(monitor.isTelemetryTickerActive)
        assertTrue(monitor.isSpeedTickerActive)
        assertEquals(1, pushCount)

        // ACTION_SCREEN_OFF immediately pauses both widget telemetry and 1 Hz network speed loops.
        screenOn = false
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_OFF))
        scope.runCurrent()
        assertFalse(monitor.isScreenOn.value)
        assertFalse(monitor.isTelemetryTickerActive)
        assertFalse(monitor.isSpeedTickerActive)

        // Battery broadcasts arriving while screen is off must NOT wake RemoteViews widget pushes.
        val batteryOffIntent = Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 74)
        monitor.onBatteryOrScreenEvent(batteryOffIntent)
        nowMs += 30_000L
        txBytes += 5_000_000L
        rxBytes += 9_000_000L
        scope.advanceTimeBy(30_000L)
        scope.runCurrent()
        assertEquals("No widget pushes allowed during screen-off battery broadcasts", 1, pushCount)
        assertEquals(0L, monitor.network.value.downloadBytesPerSec)

        // ACTION_SCREEN_ON cleanly resumes both polling loops and refreshes the widget once.
        screenOn = true
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_ON))
        scope.runCurrent()
        assertTrue(monitor.isScreenOn.value)
        assertTrue(monitor.isTelemetryTickerActive)
        assertTrue(monitor.isSpeedTickerActive)
        assertEquals(2, pushCount)

        monitor.releaseIdleNetwork()
        monitor.release()

        // CockpitQuickControlsController usage polling pauses on screen off and resumes on screen on.
        val quickControls = CockpitQuickControlsController(
            context = app,
            scope = scope,
            isScreenInteractive = { screenOn },
        )
        quickControls.start()
        assertTrue(quickControls.isUsagePollingActive)
        screenOn = false
        quickControls.onScreenOff()
        assertFalse(quickControls.isUsagePollingActive)
        screenOn = true
        quickControls.onScreenOn()
        assertTrue(quickControls.isUsagePollingActive)
        quickControls.stop()

        // App Lock UsageStats fallback 500ms polling pauses on screen off and resumes on screen on.
        var polls = 0
        val fallback = UsageStatsForegroundFallbackMonitor(
            context = app,
            scope = scope,
            isAccessibilityConnected = { false },
            hasUsageAccessProvider = { true },
            eventReader = { _, _ -> polls++; null },
            onForegroundEvent = { _, _ -> },
        )
        fallback.sync(appLockEnabled = true)
        assertTrue(fallback.isRunning)
        fallback.onScreenOff()
        assertFalse(fallback.isRunning)
        val pollsBeforeSleep = polls
        scope.advanceTimeBy(5_000L)
        scope.runCurrent()
        assertEquals("App Lock fallback must not poll while screen is off", pollsBeforeSleep, polls)

        fallback.onScreenOn(appLockEnabled = true)
        assertTrue(fallback.isRunning)
        fallback.stop()
    }

    // ------------------------------------------------------------------ helpers

    private fun syntheticPngBytes(color: Int = Color.CYAN): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun syntheticMediaItem(
        id: Long,
        name: String,
        isVideo: Boolean = false,
        color: Int = Color.CYAN,
        pngBytes: ByteArray = syntheticPngBytes(color),
    ): MediaItem {
        val uri = Uri.parse("content://media/external/${if (isVideo) "video" else "images"}/media/$id")
        if (!isVideo) {
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
                ByteArrayInputStream(pngBytes)
            }
        }
        return MediaItem(
            id = id,
            uri = uri,
            name = name,
            size = pngBytes.size.toLong(),
            mime = if (isVideo) "video/mp4" else "image/jpeg",
            dateTakenMs = 1_700_000_000_000L + id,
            dateModifiedMs = 1_700_000_000_000L + id,
            bucketId = 42L,
            bucketName = "Camera",
            relativePath = "DCIM/Camera/",
            isVideo = isVideo,
            durationMs = if (isVideo) 12_000L else 0L,
        )
    }
}
