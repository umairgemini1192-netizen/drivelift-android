package com.drivelift.app.ui

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.data.Prefs
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DriveFolders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Tests pinch-to-density gestures, accessible size switching, Prefs persistence, focal anchoring,
 * gesture conflict isolation, and viewer zoom separation across Phone and Drive media grids.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class GridDensityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    class SyntheticPhoneMediaProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: emptyArray()
            val cursor = MatrixCursor(cols)
            for (i in 1..30) {
                cursor.addRow(
                    cols.map { col ->
                        when (col) {
                            FileColumns._ID -> i.toLong()
                            FileColumns.DISPLAY_NAME -> "IMG_%04d.jpg".format(i)
                            FileColumns.SIZE -> 2_000_000L + i * 1_000L
                            FileColumns.MIME_TYPE -> "image/jpeg"
                            FileColumns.DATE_TAKEN -> 1_773_576_000_000L - i * 1_000L
                            FileColumns.DATE_MODIFIED -> (1_773_576_000_000L - i * 1_000L) / 1000L
                            FileColumns.BUCKET_ID -> 100L
                            FileColumns.BUCKET_DISPLAY_NAME -> "Camera"
                            FileColumns.RELATIVE_PATH -> "DCIM/Camera/"
                            FileColumns.MEDIA_TYPE -> FileColumns.MEDIA_TYPE_IMAGE
                            FileColumns.DURATION -> 0L
                            else -> null
                        }
                    },
                )
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private class FakeDriveGallery(private val count: Int = 24) : DriveFolders {
        private val files = (1..count).map { i ->
            DriveFile("img-$i", "Photo_%02d.jpg".format(i), "image/jpeg", 1_500_000L + i, "00", false)
        }

        override suspend fun listChildren(folderId: String, foldersOnly: Boolean) =
            if (foldersOnly) emptyList() else files

        override suspend fun createFolder(name: String, parentId: String) = error("not used")

        override suspend fun loadThumbnail(file: DriveFile, maxEdgePx: Int): ByteArray = syntheticPng()

        override suspend fun loadPreviewBytes(
            file: DriveFile,
            maxBytes: Long,
            onProgress: (Long, Long) -> Unit,
        ): ByteArray {
            val bytes = syntheticPng()
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return bytes
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Graph.get(app).prefs.setGridDensity(GridDensity.MEDIUM)
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Robolectric.setupContentProvider(SyntheticPhoneMediaProvider::class.java, "media")
    }

    @After
    fun tearDown() {
        Graph.get(app).prefs.setGridDensity(GridDensity.MEDIUM)
        pump(10)
    }

    private fun pump(times: Int = 10) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(25)
    }

    /**
     * The fully visible grid cell nearest the centre of [gridTag], its bounds, and its centre in the
     * grid's own coordinates. The test pinches on that centre so the fingers are over one cell and
     * never in the gap between two columns, where the focal cell would be ambiguous.
     */
    private fun focalCell(gridTag: String, cells: SemanticsMatcher): Triple<String, Rect, Offset> {
        val grid = compose.onNodeWithTag(gridTag).fetchSemanticsNode().boundsInRoot
        val node = compose.onAllNodes(cells).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height >= it.size.height - 1f }
            .minBy { (it.boundsInRoot.center - grid.center).getDistance() }
        val bounds = node.boundsInRoot
        return Triple(
            node.config[SemanticsProperties.ContentDescription].first(),
            bounds,
            bounds.center - grid.topLeft,
        )
    }

    /**
     * Asserts the cell that was under the pinch is still on screen at the height where the fingers
     * were: the grid re-anchored it rather than only avoiding a jump to the top.
     */
    private fun assertFocalCellAnchored(label: String, before: Rect) {
        val after = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
            .map { it.boundsInRoot }.filter { it.height > 0f }
        assertTrue("Focal cell $label scrolled off screen after the density change", after.isNotEmpty())
        val focalY = before.center.y
        val tolerance = before.height / 2f
        assertTrue(
            "Focal cell $label moved away from the pinch point (before $before, after $after)",
            after.any { it.top <= focalY + tolerance && it.bottom >= focalY - tolerance },
        )
    }

    private fun launchPhoneFolder(test: (AppViewModel) -> Unit) {
        val vm = AppViewModel(app)
        compose.mainClock.autoAdvance = false
        pump(15)
        vm.refreshMedia()
        compose.waitUntil(10_000) {
            pump(1)
            vm.mediaLoaded.value && vm.media.value.isNotEmpty()
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        FolderScreen(vm = vm, bucketId = 100L, onBack = {}, onMove = {})
                    }
                }
            }
            pump()
            compose.waitUntil(10_000) {
                pump(1)
                compose.onAllNodesWithText("Camera").fetchSemanticsNodes().isNotEmpty()
            }
            test(vm)
        }
    }

    private fun launchDriveGallery(test: (AppViewModel) -> Unit) {
        val vm = AppViewModel(app)
        compose.mainClock.autoAdvance = false
        pump(15)
        vm.driveFolders = FakeDriveGallery()
        vm.driveConn.value = DriveConn.CONNECTED
        vm.account.value = DriveAbout("Test User", "test.user@example.com", 1, 2)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        DriveBrowser(vm = vm, pickMode = false)
                    }
                }
            }
            pump()
            compose.waitUntil(10_000) {
                pump(1)
                compose.onAllNodesWithText("Photo_01.jpg").fetchSemanticsNodes().isNotEmpty()
            }
            test(vm)
        }
    }

    @Test
    fun stepHelpersFocalLookupAndPrefsPersistenceWork() {
        assertEquals(GridDensity.LARGE, GridDensity.MEDIUM.zoomOutward())
        assertEquals(GridDensity.LARGE, GridDensity.LARGE.zoomOutward())
        assertEquals(GridDensity.SMALL, GridDensity.MEDIUM.zoomInward())
        assertEquals(GridDensity.COMPACT, GridDensity.SMALL.zoomInward())
        assertEquals(GridDensity.COMPACT, GridDensity.COMPACT.zoomInward())

        assertEquals(GridDensity.LARGE, stepDensityForPinch(GridDensity.MEDIUM, 1.35f))
        assertEquals(GridDensity.SMALL, stepDensityForPinch(GridDensity.MEDIUM, 0.70f))
        assertNull(stepDensityForPinch(GridDensity.MEDIUM, 1.05f))

        val items = listOf(
            GridItemBounds(10, IntOffset(0, 0), IntSize(120, 120)),
            GridItemBounds(11, IntOffset(124, 0), IntSize(120, 120)),
            GridItemBounds(12, IntOffset(0, 124), IntSize(120, 120)),
        )
        assertEquals(11, findFocalItemBounds(items, Offset(160f, 40f))?.index)
        assertEquals(12, findFocalItemBounds(items, Offset(40f, 300f))?.index)

        val prefs = Prefs(app)
        prefs.setGridDensity(GridDensity.COMPACT)
        assertEquals(GridDensity.COMPACT, Prefs(app).gridDensity.value)
    }

    @Test
    fun phoneGridPinchOutAndInChangesCellSizesAndPreservesSelection() = launchPhoneFolder { vm ->
        vm.setSelected(listOf(1L), true)
        pump(4)
        val cellQuery = hasContentDescription("IMG_", substring = true)
        val mediumWidth = compose.onAllNodes(cellQuery)
            .fetchSemanticsNodes().first().boundsInRoot.width

        compose.onNodeWithTag("phone-media-grid").performTouchInput {
            val c = center
            pinch(
                start0 = Offset(c.x - 50f, c.y),
                end0 = Offset(c.x - 75f, c.y),
                start1 = Offset(c.x + 50f, c.y),
                end1 = Offset(c.x + 75f, c.y),
                durationMillis = 200,
            )
        }
        compose.waitUntil(5_000) {
            pump(1)
            vm.gridDensity.value == GridDensity.LARGE
        }
        pump(4)
        val largeWidth = compose.onAllNodes(cellQuery)
            .fetchSemanticsNodes().first().boundsInRoot.width
        assertTrue("Expected larger cells in LARGE density ($largeWidth <= $mediumWidth)", largeWidth > mediumWidth)
        assertEquals("Pinch release must not alter existing selection", setOf(1L), vm.selection.value)

        repeat(2) {
            compose.onNodeWithTag("phone-media-grid").performTouchInput {
                val c = center
                pinch(
                    start0 = Offset(c.x - 100f, c.y),
                    end0 = Offset(c.x - 65f, c.y),
                    start1 = Offset(c.x + 100f, c.y),
                    end1 = Offset(c.x + 65f, c.y),
                    durationMillis = 200,
                )
            }
            pump(4)
        }
        compose.waitUntil(5_000) {
            pump(1)
            vm.gridDensity.value == GridDensity.SMALL
        }
        pump(4)
        val smallWidth = compose.onAllNodes(cellQuery)
            .fetchSemanticsNodes().first().boundsInRoot.width
        assertTrue("Expected smaller cells in SMALL density ($smallWidth >= $mediumWidth)", smallWidth < mediumWidth)
        assertEquals("Pinching inward must keep existing selection", setOf(1L), vm.selection.value)
    }

    @Test
    fun phoneGridAccessibleSizeControlCyclesAndPersists() = launchPhoneFolder { _ ->
        compose.onNodeWithContentDescription("Thumbnail size: Medium thumbnails")
            .assertWidthIsAtLeast(Tokens.TouchTarget)
            .assertHeightIsAtLeast(Tokens.TouchTarget)
        compose.onNodeWithContentDescription("Thumbnail size: Medium thumbnails").performClick()
        compose.waitUntil(5_000) {
            pump(1)
            compose.onAllNodesWithContentDescription("Thumbnail size: Large thumbnails").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(GridDensity.LARGE, Prefs(app).gridDensity.value)

        compose.onNodeWithContentDescription("Thumbnail size: Large thumbnails").performClick()
        compose.waitUntil(5_000) {
            pump(1)
            compose.onAllNodesWithContentDescription("Thumbnail size: Compact thumbnails").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Thumbnail size: Compact thumbnails").performClick()
        compose.waitUntil(5_000) {
            pump(1)
            compose.onAllNodesWithContentDescription("Thumbnail size: Small thumbnails").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Thumbnail size: Small thumbnails").performClick()
        compose.waitUntil(5_000) {
            pump(1)
            compose.onAllNodesWithContentDescription("Thumbnail size: Medium thumbnails").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun phoneGridPinchKeepsScrolledAnchorInsteadOfJumpingToTop() = launchPhoneFolder { vm ->
        repeat(2) {
            compose.onNodeWithTag("phone-media-grid").performTouchInput { swipeUp() }
            pump(6)
        }
        assertTrue(
            "Expected top item IMG_0001.jpg to be scrolled offscreen before pinch",
            compose.onAllNodesWithContentDescription("IMG_0001.jpg").fetchSemanticsNodes().isEmpty(),
        )
        val (focalLabel, focalBefore, focalPoint) = focalCell("phone-media-grid", hasContentDescription("IMG_", substring = true))

        compose.onNodeWithTag("phone-media-grid").performTouchInput {
            val c = focalPoint
            pinch(
                start0 = Offset(c.x - 50f, c.y),
                end0 = Offset(c.x - 75f, c.y),
                start1 = Offset(c.x + 50f, c.y),
                end1 = Offset(c.x + 75f, c.y),
                durationMillis = 200,
            )
        }
        compose.waitUntil(5_000) {
            pump(1)
            vm.gridDensity.value == GridDensity.LARGE
        }
        pump(6)
        assertTrue(
            "Pinch density change jumped back to top of grid",
            compose.onAllNodesWithContentDescription("IMG_0001.jpg").fetchSemanticsNodes().isEmpty(),
        )
        assertFocalCellAnchored(focalLabel, focalBefore)
    }

    @Test
    fun driveGridPinchChangesDensityWithoutOpeningFileOrLosingScrollAnchor() = launchDriveGallery { vm ->
        val mediumWidth = compose.onAllNodesWithText("Photo_01.jpg")
            .fetchSemanticsNodes().first().boundsInRoot.width

        repeat(2) {
            compose.onNodeWithTag("drive-media-grid").performTouchInput { swipeUp() }
            pump(6)
        }
        assertTrue(
            "Expected Photo_01.jpg scrolled offscreen before Drive pinch",
            compose.onAllNodesWithText("Photo_01.jpg").fetchSemanticsNodes().isEmpty(),
        )
        val (focalLabel, focalBefore, focalPoint) = focalCell("drive-media-grid", hasContentDescription("Photo_", substring = true))

        compose.onNodeWithTag("drive-media-grid").performTouchInput {
            val c = focalPoint
            pinch(
                start0 = Offset(c.x - 50f, c.y),
                end0 = Offset(c.x - 75f, c.y),
                start1 = Offset(c.x + 50f, c.y),
                end1 = Offset(c.x + 75f, c.y),
                durationMillis = 200,
            )
        }
        compose.waitUntil(5_000) {
            pump(1)
            vm.gridDensity.value == GridDensity.LARGE
        }
        pump(6)
        assertTrue(
            "Pinching Drive grid must not open a file viewer",
            compose.onAllNodesWithContentDescription("Close viewer").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Pinching Drive grid jumped back to top of folder",
            compose.onAllNodesWithText("Photo_01.jpg").fetchSemanticsNodes().isEmpty(),
        )
        assertFocalCellAnchored(focalLabel, focalBefore)
        compose.onNodeWithContentDescription("Thumbnail size: Large thumbnails")
            .assertWidthIsAtLeast(Tokens.TouchTarget)
            .assertHeightIsAtLeast(Tokens.TouchTarget)

        compose.onNodeWithContentDescription("Thumbnail size: Large thumbnails").performClick()
        compose.waitUntil(5_000) {
            pump(1)
            vm.gridDensity.value == GridDensity.COMPACT
        }
        compose.waitUntil(5_000) {
            pump(1)
            compose.onAllNodesWithText("Photo_08.jpg").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Photo_16.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        val compactNode = compose.onAllNodesWithText("Photo_08.jpg").fetchSemanticsNodes().firstOrNull()
            ?: compose.onAllNodesWithText("Photo_16.jpg").fetchSemanticsNodes().first()
        assertTrue(
            "Expected narrower cells in COMPACT density (${compactNode.boundsInRoot.width} >= $mediumWidth)",
            compactNode.boundsInRoot.width < mediumWidth,
        )
    }

    @Test
    fun pinchingInsideImageViewerZoomsImageAndLeavesGridDensityAlone() = launchDriveGallery { vm ->
        vm.setGridDensity(GridDensity.SMALL)
        pump(4)
        compose.onAllNodesWithText("Photo_01.jpg").onFirst().performClick()
        compose.waitUntil(5_000) {
            pump(1)
            compose.onAllNodesWithTag("viewer-image").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("viewer-image").performTouchInput {
            val c = center
            pinch(
                start0 = Offset(c.x - 40f, c.y),
                end0 = Offset(c.x - 140f, c.y),
                start1 = Offset(c.x + 40f, c.y),
                end1 = Offset(c.x + 140f, c.y),
                durationMillis = 200,
            )
        }
        compose.waitUntil(5_000) {
            pump(1)
            compose.onAllNodesWithContentDescription("Reset zoom").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(GridDensity.SMALL, vm.gridDensity.value)
    }

    private companion object {
        fun syntheticPng(): ByteArray {
            val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.rgb(98, 198, 255))
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
