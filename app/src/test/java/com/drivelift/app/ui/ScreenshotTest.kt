package com.drivelift.app.ui

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.transfer.LiveProgress
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens, with the real view model, database and theme, on the JVM. No phone
 * or emulator is involved. Every name, size and account below is made up.
 *
 * What these images do not show: thumbnails (the fake media store has no picture data, so cells
 * are placeholders), the Drive folder listing (it needs the network), animation, and system
 * dialogs. Each scenario is captured at a standard phone size, at font scale 1.5, and on a
 * compact 320 dp wide phone.
 *
 * Images are written only with -PrecordScreenshots. Without it the tests still run the screens
 * and their assertions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val record = System.getProperty("roborazzi.test.record") == "true"

    private enum class Variant(val qualifiers: String, val fontScale: Float) {
        STANDARD("w360dp-h780dp-xhdpi", 1f),
        LARGE_FONT("w360dp-h780dp-xhdpi", 1.5f),
        COMPACT("w320dp-h640dp-xhdpi", 1f),
    }

    @Before
    fun fakeWorkManager() {
        // The app restarts unfinished transfers on launch. Here that must only queue a job in a
        // test WorkManager, which never runs it: nothing may try to upload during a test.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun reset() {
        LiveProgress.state.value = LiveProgress.State()
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    fun permissionGate() = eachVariant("permission-gate", expect = "Your photos, moved to Drive") { vm ->
        AppRoot(vm)
    }

    @Test
    fun phoneFolders() {
        grantMedia()
        eachVariant("phone-folders", expect = "Screenshots", connected = true) { vm -> AppRoot(vm) }
    }

    @Test
    fun phoneFoldersDriveNotConnected() {
        grantMedia()
        eachVariant("phone-not-connected", expect = "Google Drive is not connected") { vm -> AppRoot(vm) }
    }

    @Test
    fun folderWithSelection() {
        grantMedia()
        eachVariant(
            "folder-selection", expect = "2 selected", connected = true,
            act = {
                settle { compose.onAllNodesWithText("Camera").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Camera").performClick()
                settle { compose.onAllNodesWithText("Select all").fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performClick()
                compose.onAllNodesWithContentDescription("VID_0003.mp4").onFirst().performClick()
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun transfersEmpty() {
        grantMedia()
        eachVariant("transfers-empty", expect = "Nothing in transit", tab = AppViewModel.TAB_TRANSFERS) { vm ->
            AppRoot(vm)
        }
    }

    @Test
    fun transfersUploading() {
        grantMedia()
        seedTransfers(
            row(1, "IMG_0001.jpg", TransferStatus.REMOVED),
            row(2, "IMG_0002.jpg", TransferStatus.VERIFIED),
            row(3, "VID_0003.mp4", TransferStatus.UPLOADED, size = 48_000_000),
            row(4, "IMG_0004.jpg", TransferStatus.UPLOADING, size = 12_000_000),
            row(5, "IMG_0005.jpg", TransferStatus.QUEUED),
            row(6, "IMG_0006.jpg", TransferStatus.QUEUED),
        )
        LiveProgress.state.value = LiveProgress.State(
            phase = LiveProgress.Phase.UPLOADING, itemId = 4, name = "IMG_0004.jpg",
            sent = 7_000_000, total = 12_000_000, bytesPerSec = 1_400_000,
        )
        eachVariant("transfers-uploading", expect = "Uploading IMG_0004.jpg", tab = AppViewModel.TAB_TRANSFERS) { vm ->
            AppRoot(vm)
        }
    }

    @Test
    fun transfersRetryingAfterError() {
        grantMedia()
        seedTransfers(
            row(1, "IMG_0001.jpg", TransferStatus.VERIFIED),
            row(2, "IMG_0002.jpg", TransferStatus.QUEUED, error = "Connection problem: network unavailable", attempts = 3),
            row(3, "IMG_0003.jpg", TransferStatus.FAILED, error = "The file is no longer on this phone"),
            row(4, "IMG_0004.jpg", TransferStatus.QUEUED),
        )
        LiveProgress.state.value = LiveProgress.State(
            phase = LiveProgress.Phase.RETRY_WAIT, itemId = 2, name = "IMG_0002.jpg",
            message = "Connection problem: network unavailable",
            retryAtMs = System.currentTimeMillis() + 30_000,
        )
        eachVariant("transfers-retrying", expect = "Retry failed", tab = AppViewModel.TAB_TRANSFERS) { vm ->
            AppRoot(vm)
        }
    }

    @Test
    fun transfersVerifiedReadyToRemove() {
        grantMedia()
        // Off, so the screen does not try to open Android's removal prompt during the test.
        Graph.get(app).prefs.setAutoRemove(false)
        seedTransfers(
            row(1, "IMG_0001.jpg", TransferStatus.VERIFIED),
            row(2, "IMG_0002.jpg", TransferStatus.VERIFIED),
            row(3, "VID_0003.mp4", TransferStatus.VERIFIED, size = 48_000_000),
        )
        eachVariant(
            "transfers-verified", expect = "Remove 3 verified files from phone", tab = AppViewModel.TAB_TRANSFERS,
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveNotConnected() {
        grantMedia()
        eachVariant("drive-not-connected", expect = "My Drive", tab = AppViewModel.TAB_DRIVE) { vm -> AppRoot(vm) }
    }

    // ------------------------------------------------------------------ harness

    private fun eachVariant(
        name: String,
        expect: String,
        connected: Boolean = false,
        tab: Int = AppViewModel.TAB_PHONE,
        act: (() -> Unit)? = null,
        content: @Composable (AppViewModel) -> Unit,
    ) {
        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            // Frozen clock: an indeterminate spinner or a ticking countdown never blocks a capture.
            compose.mainClock.autoAdvance = false
            val vm = AppViewModel(app)
            // Let the start-up sign-in attempt (there is no Google Play services here) finish
            // before the connection state is set for the scenario.
            pump(12)
            vm.tab.value = tab
            vm.clearSelection()
            vm.driveConn.value = if (connected) DriveConn.CONNECTED else DriveConn.DISCONNECTED
            vm.account.value = if (connected) {
                DriveAbout("Test User", "test.user@example.com", 21_000_000_000, 100_000_000_000)
            } else null

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density, variant.fontScale),
                        ) {
                            DriveLiftTheme { GlassBackdrop { content(vm) } }
                        }
                    }
                }
                act?.invoke()
                settle { compose.onAllNodesWithText(expect).fetchSemanticsNodes().isNotEmpty() }
                if (record) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/screenshots/$name-${variant.name.lowercase()}.png"
                    )
                }
            }
        }
    }

    private fun pump(times: Int) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(25)
    }

    /** Runs the UI until [done], so data arriving from the database has reached the screen. */
    private fun settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            pump(1)
            if (done()) break
            assertTrue("The screen never reached the expected state", System.currentTimeMillis() < deadline)
        }
        pump(4)
    }

    // ------------------------------------------------------------------ synthetic data

    private fun grantMedia() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Robolectric.setupContentProvider(FakeMediaStore::class.java, "media")
    }

    private fun seedTransfers(vararg rows: TransferEntity) = runBlocking {
        Graph.get(app).db.transfers().insertAll(rows.toList())
    }

    private fun row(
        id: Long, name: String, status: TransferStatus,
        size: Long = 3_400_000, error: String? = null, attempts: Int = 0,
    ) = TransferEntity(
        id = id, mediaUri = "content://media/external/images/media/$id", name = name,
        mime = if (name.endsWith(".mp4")) "video/mp4" else "image/jpeg", size = size,
        dateModifiedMs = DAY_TWO, dateTakenMs = DAY_TWO, bucketName = "Camera",
        destFolderId = null, destLabel = "DriveLift Backup / Camera", status = status.name,
        driveFileId = if (status == TransferStatus.QUEUED) null else "drive-$id",
        attempts = attempts, lastError = error,
    )

    /** Answers the app's media query with thirteen made-up files in three folders. */
    class FakeMediaStore : ContentProvider() {
        private data class Fake(
            val id: Long, val name: String, val size: Long, val takenMs: Long,
            val bucketId: Long, val bucket: String, val video: Boolean,
        )

        private val files = buildList {
            for (i in 1..8) add(
                Fake(
                    i.toLong(), if (i == 3) "VID_0003.mp4" else "IMG_%04d.jpg".format(i),
                    if (i == 3) 48_000_000 else 3_400_000 + i * 10_000L,
                    if (i <= 5) DAY_TWO else DAY_ONE, 100, "Camera", video = i == 3,
                )
            )
            for (i in 9..11) add(Fake(i.toLong(), "Screenshot_$i.png", 900_000, DAY_ONE, 200, "Screenshots", false))
            for (i in 12..13) add(Fake(i.toLong(), "IMG-WA$i.jpg", 240_000, DAY_ONE, 300, "WhatsApp Images", false))
        }

        override fun onCreate() = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val columns = projection ?: emptyArray()
            val cursor = MatrixCursor(columns)
            for (f in files) {
                cursor.addRow(columns.map { column ->
                    when (column) {
                        FileColumns._ID -> f.id
                        FileColumns.DISPLAY_NAME -> f.name
                        FileColumns.SIZE -> f.size
                        FileColumns.MIME_TYPE -> if (f.video) "video/mp4" else "image/jpeg"
                        FileColumns.DATE_TAKEN -> f.takenMs
                        FileColumns.DATE_MODIFIED -> f.takenMs / 1000
                        FileColumns.BUCKET_ID -> f.bucketId
                        FileColumns.BUCKET_DISPLAY_NAME -> f.bucket
                        FileColumns.RELATIVE_PATH ->
                            if (f.bucket == "Camera") "DCIM/Camera/" else "Pictures/${f.bucket}/"
                        FileColumns.MEDIA_TYPE ->
                            if (f.video) FileColumns.MEDIA_TYPE_VIDEO else FileColumns.MEDIA_TYPE_IMAGE
                        FileColumns.DURATION -> if (f.video) 83_000L else 0L
                        else -> null
                    }
                })
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        // Fixed dates, so day headers are the same on every run: 14 and 15 March 2026, noon UTC.
        const val DAY_ONE = 1_773_489_600_000L
        const val DAY_TWO = 1_773_576_000_000L
    }
}
