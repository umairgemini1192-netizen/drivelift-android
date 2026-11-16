package com.drivelift.app.ui

import android.Manifest
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.data.PresetEntity
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DriveFolders
import com.drivelift.app.transfer.LiveProgress
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.awaitCancellation
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders the real screens, with the real view model, database and theme, on the JVM. No phone
 * or emulator is involved. Every name, size and account below is made up.
 *
 * Thumbnails are small generated pictures (gradients, no photographs), so the selection frame
 * is seen over picture data, including a coral one and a near-white one. The Drive browser, the
 * folder picker and the new-folder dialog are fed by a fake [DriveFolders] with made-up entries.
 *
 * What these images do not show: real photographs, video frames, the real Drive service,
 * animation, scrolling, and Android's own dialogs (permission, Google consent, removal). Each
 * scenario is captured at a standard phone size, at font scale 1.5, and on a compact 320 dp
 * wide phone.
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

    private val imagesStarted = AtomicInteger()
    private val imagesFinished = AtomicInteger()

    @Before
    fun fakeWorkManager() {
        // The app restarts unfinished transfers on launch. Here that must only queue a job in a
        // test WorkManager, which never runs it: nothing may try to upload during a test.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Before
    fun countingImageLoader() {
        // Same loader as the app, minus the crossfade (the clock is frozen, so a fade would never
        // finish) and hardware bitmaps (they cannot be drawn into a screenshot). The listener
        // lets a capture wait until every thumbnail has loaded or failed.
        Coil.setImageLoader(
            ImageLoader.Builder(app).crossfade(false).allowHardware(false)
                .eventListener(object : EventListener {
                    override fun onStart(request: ImageRequest) { imagesStarted.incrementAndGet() }
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) { imagesFinished.incrementAndGet() }
                    override fun onError(request: ImageRequest, result: ErrorResult) { imagesFinished.incrementAndGet() }
                    override fun onCancel(request: ImageRequest) { imagesFinished.incrementAndGet() }
                })
                .build()
        )
    }

    @After
    fun reset() {
        LiveProgress.state.value = LiveProgress.State()
        FakeMediaStore.empty = false
        FakeMediaStore.gate?.countDown()
        FakeMediaStore.gate = null
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

    @Test
    fun phoneFoldersLoading() {
        // The media query is held open, so the folder list stays in its loading state.
        FakeMediaStore.gate = CountDownLatch(1)
        grantMedia()
        eachVariant("phone-loading", expect = "Folders", connected = true) { vm -> AppRoot(vm) }
    }

    @Test
    fun phoneFoldersEmpty() {
        FakeMediaStore.empty = true
        grantMedia()
        eachVariant("phone-empty", expect = "No photos or videos found on this phone.", connected = true) { vm ->
            AppRoot(vm)
        }
    }

    @Test
    fun phoneFoldersDriveConnecting() {
        grantMedia()
        eachVariant("phone-connecting", expect = "Screenshots", connecting = true) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveListing() {
        grantMedia()
        eachVariant("drive-listing", expect = "DriveLift Backup", connected = true, tab = AppViewModel.TAB_DRIVE) { vm ->
            AppRoot(vm)
        }
    }

    @Test
    fun driveLoading() {
        grantMedia()
        eachVariant(
            "drive-loading", expect = "My Drive", connected = true, tab = AppViewModel.TAB_DRIVE,
            drive = FakeDrive(list = { _, _ -> awaitCancellation() }),
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveEmpty() {
        grantMedia()
        eachVariant(
            "drive-empty", expect = "This folder is empty.", connected = true, tab = AppViewModel.TAB_DRIVE,
            drive = FakeDrive(list = { _, _ -> emptyList() }),
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveError() {
        grantMedia()
        eachVariant(
            "drive-error", expect = "Could not load this folder", connected = true, tab = AppViewModel.TAB_DRIVE,
            drive = FakeDrive(list = { _, _ -> throw IOException("network unavailable") }),
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun destinationSheet() {
        grantMedia()
        seedPresets()
        eachVariant(
            "destination-sheet", expect = "Auto-organize by phone folder", connected = true, overlay = true,
            act = { openDestinationSheet() },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun destinationSheetDriveNotConnected() {
        grantMedia()
        eachVariant(
            "destination-sheet-not-connected", expect = "Move 2 items to Drive", overlay = true,
            act = { openDestinationSheet() },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun folderPicker() {
        grantMedia()
        eachVariant(
            "folder-picker", expect = "Move here: My Drive", connected = true, overlay = true,
            act = {
                openDestinationSheet()
                tap("Choose a Drive folder")
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun folderPickerEmptyFolder() {
        grantMedia()
        eachVariant(
            "folder-picker-empty", expect = "Move here: Archive 2024", connected = true, overlay = true,
            act = {
                openDestinationSheet()
                tap("Choose a Drive folder")
                tap("Archive 2024")
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun newFolderDialog() {
        grantMedia()
        eachVariant(
            "new-folder", expect = "Holiday 2026", connected = true, tab = AppViewModel.TAB_DRIVE, overlay = true,
            act = { openNewFolderDialog() },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun newFolderDialogError() {
        grantMedia()
        eachVariant(
            "new-folder-error", expect = "Connection problem: network unavailable", connected = true,
            tab = AppViewModel.TAB_DRIVE, overlay = true,
            drive = FakeDrive(create = { throw IOException("network unavailable") }),
            act = {
                openNewFolderDialog()
                tap("Create")
            },
        ) { vm -> AppRoot(vm) }
    }

    // ------------------------------------------------------------------ steps

    private fun waitFor(text: String) =
        settle { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Invokes the click action of a node. Used for nodes inside a sheet or dialog, where a
     * pointer click can miss a control that sits below the fold of a half-open sheet.
     */
    private fun SemanticsNodeInteraction.press() = performSemanticsAction(SemanticsActions.OnClick)

    private fun tap(text: String) {
        waitFor(text)
        compose.onNodeWithText(text).press()
    }

    /** Opens the Camera folder, selects a photo and a video, and asks to move them. */
    private fun openDestinationSheet() {
        waitFor("Camera")
        compose.onNodeWithText("Camera").performClick()
        waitFor("Select all")
        compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performClick()
        compose.onAllNodesWithContentDescription("VID_0003.mp4").onFirst().performClick()
        tap("Move to Drive")
        waitFor("Move 2 items to Drive")
    }

    private fun openNewFolderDialog() {
        waitFor("DriveLift Backup")
        compose.onNodeWithContentDescription("New folder here").press()
        waitFor("New folder in My Drive")
        // The name is set without focusing the field: a focused field keeps the test from
        // ever going idle, and a blinking cursor would differ between runs.
        compose.onNodeWithText("Folder name")
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Holiday 2026")) }
        waitFor("Holiday 2026")
    }

    // ------------------------------------------------------------------ harness

    private fun eachVariant(
        name: String,
        expect: String,
        connected: Boolean = false,
        connecting: Boolean = false,
        tab: Int = AppViewModel.TAB_PHONE,
        /** True when a sheet or dialog is open: those are separate windows, so the whole screen is captured. */
        overlay: Boolean = false,
        drive: DriveFolders = FakeDrive(),
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
            // No scenario may reach the real Drive service.
            vm.driveFolders = drive
            vm.driveConn.value = when {
                connected -> DriveConn.CONNECTED
                connecting -> DriveConn.CONNECTING
                else -> DriveConn.DISCONNECTED
            }
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
                waitFor(expect)
                settleImages()
                // Lets a sheet finish sliding in before it is captured.
                if (overlay) pump(40)
                if (record) capture("build/outputs/screenshots/$name-${variant.name.lowercase()}.png", overlay)
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(path: String, overlay: Boolean) {
        if (overlay) captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
    }

    /** Runs the UI until every thumbnail request that started has loaded or failed. */
    private fun settleImages() {
        val deadline = System.currentTimeMillis() + 15_000
        var quiet = 0
        while (quiet < 6) {
            pump(1)
            quiet = if (imagesStarted.get() == imagesFinished.get()) quiet + 1 else 0
            assertTrue("Thumbnails never finished loading", System.currentTimeMillis() < deadline)
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
        // Picture data for each file, so thumbnails are drawn instead of placeholders.
        val resolver = shadowOf(app.contentResolver)
        for (file in FakeMediaStore.files) {
            val bytes = picture(file.id)
            resolver.registerInputStreamSupplier(file.uri) { ByteArrayInputStream(bytes) }
        }
    }

    /**
     * A small generated picture: a two-colour gradient with a disc. No photograph is involved.
     * File 1 is coral and file 3 is near white, the two hardest cases for a coral selection frame;
     * the test selects exactly those two.
     */
    private fun picture(id: Long): ByteArray {
        val (top, bottom, disc) = PICTURES[(id - 1).toInt() % PICTURES.size]
        val size = PICTURE_SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(PICTURE_SIZE, PICTURE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size, opaque(top), opaque(bottom), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = opaque(disc)
        canvas.drawCircle(size * 0.66f, size * 0.36f, size / 6, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun opaque(rgb: Int) = rgb or 0xFF000000.toInt()

    private fun seedPresets() = runBlocking {
        val presets = Graph.get(app).db.presets()
        presets.upsert(PresetEntity("folder-holiday", "Holiday 2025", "My Drive / Photos / Holiday 2025", createdAt = 1))
        presets.upsert(
            PresetEntity(
                "folder-family", "Family photos shared with everyone",
                "My Drive / Photos / Family photos shared with everyone", createdAt = 2,
            )
        )
    }

    /** Stands in for Google Drive: made-up folders and files, no network. */
    private class FakeDrive(
        private val list: suspend (folderId: String, foldersOnly: Boolean) -> List<DriveFile> = { id, foldersOnly ->
            when {
                id != DriveClient.ROOT -> emptyList()
                foldersOnly -> FOLDERS
                else -> FOLDERS + FILES
            }
        },
        private val create: suspend (name: String) -> DriveFile = { name -> folder("new-folder", name) },
    ) : DriveFolders {
        override suspend fun listChildren(folderId: String, foldersOnly: Boolean) = list(folderId, foldersOnly)
        override suspend fun createFolder(name: String, parentId: String) = create(name)

        private companion object {
            fun folder(id: String, name: String) = DriveFile(id, name, DriveClient.FOLDER_MIME, 0, null, false)
            val FOLDERS = listOf(
                folder("f1", "Archive 2024"),
                folder("f2", "Documents"),
                folder("f3", "DriveLift Backup"),
                folder("f4", "Family photos shared with everyone since 2019"),
            )
            val FILES = listOf(
                DriveFile("d1", "Packing list.pdf", "application/pdf", 84_000, "00", false),
                DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 2_100_000, "00", false),
                DriveFile("d3", "Trip to the coast.mp4", "video/mp4", 182_000_000, "00", false),
            )
        }
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

    data class Fake(
        val id: Long, val name: String, val size: Long, val takenMs: Long,
        val bucketId: Long, val bucket: String, val video: Boolean,
    ) {
        /** The address the app builds for this file, which is where its picture is served. */
        val uri: Uri
            get() = ContentUris.withAppendedId(
                if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id,
            )
    }

    /** Answers the app's media query with thirteen made-up files in three folders. */
    class FakeMediaStore : ContentProvider() {
        companion object {
            val files = buildList {
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

            /** True makes the phone look as if it holds no photos or videos. */
            @Volatile var empty = false

            /** While set and not released, the media query does not return: the loading state. */
            @Volatile var gate: CountDownLatch? = null
        }

        override fun onCreate() = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val columns = projection ?: emptyArray()
            val cursor = MatrixCursor(columns)
            gate?.await()
            for (f in if (empty) emptyList() else files) {
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

        const val PICTURE_SIZE = 256

        /** Top colour, bottom colour and disc colour of each generated picture, as 0xRRGGBB. */
        val PICTURES = listOf(
            Triple(0xFF7A59, 0xFFC48A, 0xFFE9C9), // coral sunset
            Triple(0x4A90D9, 0xBFE0FF, 0xFFFFFF), // blue sky
            Triple(0xFFFFFF, 0xECEFF4, 0xDDE3EC), // snow, near white
            Triple(0x1F7A4D, 0x9BD6A3, 0xF4E285), // meadow
            Triple(0x0E1220, 0x2B2145, 0xE6E6F0), // night
            Triple(0x7A4A2B, 0xD9B38C, 0xF2E3CF), // sand
            Triple(0x5B3FA6, 0xC7A6F0, 0xFFD6E8), // dusk
            Triple(0x2E8C99, 0xA8E3E0, 0xFFFFFF), // sea
        )
    }
}
