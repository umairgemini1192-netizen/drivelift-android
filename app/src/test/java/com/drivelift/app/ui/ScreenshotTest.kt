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
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.isRoot
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import com.drivelift.app.data.Prefs
import androidx.work.WorkManager
import androidx.work.NetworkType
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.AnnotatedString
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
import com.drivelift.app.data.DriveEntry
import com.drivelift.app.data.DriveEntryPage
import com.drivelift.app.data.FOLDER_MIME_TYPE
import com.drivelift.app.data.SHORTCUT_MIME_TYPE
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DriveFolders
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.LiveProgress
import com.drivelift.app.drive.AuthRequiredException
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val createCalls = AtomicInteger()
    private var currentActivity: ComponentActivity? = null
    private var currentVm: AppViewModel? = null

    @Before
    fun fakeWorkManager() {
        // The database outlives each test (Graph is one per process): start with no favorites,
        // so the Favorites entry row never shows in a test that did not ask for it.
        runBlocking { Graph.get(app).db.favorites().let { dao -> dao.observe().first().forEach { dao.delete(it.id) } } }
        // The app restarts unfinished transfers on launch. Here that must only queue a job in a
        // test WorkManager, which never runs it: nothing may try to upload during a test.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Graph.get(app).prefs.setGridDensity(GridDensity.MEDIUM)
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
        FakeMediaStore.hidden = emptySet()
        FakeMediaStore.gate?.countDown()
        FakeMediaStore.gate = null
        Graph.get(app).prefs.setGridDensity(GridDensity.MEDIUM)
        Graph.get(app).desktopDrop.controller.seedPreviewStateForTest(
            com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot(),
        )
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
        eachVariant("phone-not-connected", expect = "Connect Drive") { vm -> AppRoot(vm) }
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
                // A tap opens a photo now, so the first item is selected by holding it.
                selectCell("IMG_0001.jpg")
                compose.onAllNodesWithContentDescription("VID_0003.mp4").onFirst().performClick()
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Issue #29: a photo on the phone opens inside DriveLift. */
    @Test
    fun phonePhotoViewer() {
        grantMedia()
        eachVariant(
            "phone-photo-viewer", expect = null, connected = true,
            act = {
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")
                compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performClick()
                settle { compose.onAllNodesWithTag("viewer-image").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Photo on this phone", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #29: tap opens photos and videos from the phone; Back returns to the same folder with
     * nothing selected; holding a thumbnail selects it and taps then add to the selection.
     */
    @Test
    fun phoneMediaOpensInsideDriveLift() {
        grantMedia()
        eachVariant(
            // Drive is not connected: opening phone media must not need the account.
            "phone-open-flow", expect = null, connected = false, capture = false,
            act = {
                // No account at all (the app's silent sign-in attempt has nothing to find here).
                assertEquals(null, currentVm!!.account.value)
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")

                // Photo: opens in the viewer, Back closes it, the folder is still open.
                compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performClick()
                settle { compose.onAllNodesWithContentDescription("Close viewer").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Open in another app").assertExists()
                currentActivity!!.onBackPressedDispatcher.onBackPressed()
                settle { compose.onAllNodesWithContentDescription("Close viewer").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Select all").assertExists()
                assertTrue("Opening a photo must not select it", currentVm!!.selection.value.isEmpty())

                // Video: plays from the phone, no Drive connection used.
                compose.onAllNodesWithContentDescription("VID_0003.mp4").onFirst().performClick()
                settle { compose.onAllNodesWithText("Video on this phone", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Close viewer").performClick()
                settle { compose.onAllNodesWithContentDescription("Close viewer").fetchSemanticsNodes().isEmpty() }

                // Holding a thumbnail selects it; the lift does not toggle it off again.
                compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performTouchInput { longClick() }
                waitFor("1 selected")
                // While selecting, a tap adds to the selection instead of opening.
                compose.onAllNodesWithContentDescription("VID_0003.mp4").onFirst().performClick()
                waitFor("2 selected")
                assertTrue(
                    "A tap while selecting must not open the viewer",
                    compose.onAllNodesWithContentDescription("Close viewer").fetchSemanticsNodes().isEmpty(),
                )
                currentVm!!.clearSelection()
                pump(4)
            },
        ) { vm -> AppRoot(vm) }
    }

    /** A synthetic phone item for the viewer's failure states; no file or photo is involved. */
    private fun localItem(id: Long, name: String, video: Boolean, size: Long) = com.drivelift.app.data.MediaItem(
        id = id,
        uri = android.net.Uri.parse("content://media/external/${if (video) "video" else "images"}/media/$id"),
        name = name, size = size, mime = if (video) "video/mp4" else "image/jpeg",
        dateTakenMs = 0, dateModifiedMs = 0, bucketId = 1, bucketName = "Camera",
        relativePath = "DCIM/Camera/", isVideo = video, durationMs = if (video) 5_000 else 0,
    )

    /** Issue #29 review: the local photo viewer while the file is still being read. */
    @Test
    fun localPhotoLoading() {
        val item = localItem(901, "IMG_0901.jpg", video = false, size = 3_400_000)
        val release = CountDownLatch(1)
        shadowOf(app.contentResolver).registerInputStreamSupplier(item.uri) {
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ByteArrayInputStream(ByteArray(0))
        }
        try {
            eachVariant("local-photo-loading", expect = "Opening photo") { vm -> LocalMediaViewer(vm, item, onClose = {}) }
        } finally {
            release.countDown()
        }
    }

    /** Issue #29 review: a phone photo that cannot be read offers Try again and another app. */
    @Test
    fun localPhotoUnreadable() {
        val item = localItem(902, "IMG_0902.jpg", video = false, size = 3_400_000)
        shadowOf(app.contentResolver).registerInputStreamSupplier(item.uri) { throw java.io.FileNotFoundException() }
        eachVariant("local-photo-unreadable", expect = "Could not open photo") { vm -> LocalMediaViewer(vm, item, onClose = {}) }
    }

    /** Issue #29 review: a photo over the viewer's 32 MB limit is not read at all. */
    @Test
    fun localPhotoTooLarge() {
        val item = localItem(903, "PANO_0903.jpg", video = false, size = 40_000_000)
        eachVariant("local-photo-too-large", expect = "Could not open photo") { vm ->
            LocalMediaViewer(vm, item, onClose = {})
        }
        // Also checked without a capture: the message names the limit.
        eachVariant("local-photo-too-large-copy", expect = null, capture = false, act = {
            waitFor("Could not open photo")
            compose.onNodeWithText("larger than", substring = true).assertExists()
        }) { vm -> LocalMediaViewer(vm, item, onClose = {}) }
    }

    /** Issue #29 review: a phone video that cannot be read shows a plain notice, not an error code. */
    @Test
    fun localVideoError() {
        val item = localItem(904, "VID_0904.mp4", video = true, size = 12_000_000)
        // The file has gone: opening it fails, as for a video moved or deleted after the list loaded.
        shadowOf(app.contentResolver).registerInputStreamSupplier(item.uri) { throw java.io.FileNotFoundException() }
        eachVariant("local-video-error", expect = "Could not play video", act = {
            waitFor("Could not play video")
            compose.onNodeWithText("Open in another app").assertExists()
        }) { vm -> LocalMediaViewer(vm, item, onClose = {}) }
    }

    /** Opens Camera and selects IMG_0001 and IMG_0002 (the second is part of a transfer in these tests). */
    private fun selectTwoForDelete() {
        waitFor("Camera")
        compose.onNodeWithText("Camera").performClick()
        waitFor("Select all")
        selectCell("IMG_0001.jpg")
        compose.onAllNodesWithContentDescription("IMG_0002.jpg").onFirst().performClick()
        waitFor("2 selected")
    }

    /** Records the files handed to Android's trash request; there is no Android dialog here. */
    private fun recordTrashRequests(): MutableList<List<String>> {
        val asked = mutableListOf<List<String>>()
        Graph.get(app).trashRequest = { _, uris ->
            asked += uris.map { it.toString() }
            throw IllegalStateException("No Android trash dialog in tests")
        }
        return asked
    }

    /** Issue #36: DriveLift's text-only trash confirmation, with a file left out by a transfer. */
    @Test
    fun phoneDeleteConfirmation() {
        grantMedia()
        Graph.get(app).canManageMedia = { true }
        seedTransfers(row(2, "IMG_0002.jpg", TransferStatus.QUEUED))
        eachVariant(
            "phone-delete-confirm", expect = "Move 1 file to this phone's trash?", connected = true, overlay = true,
            act = {
                selectTwoForDelete()
                compose.onNodeWithContentDescription("Move selected to this phone's trash").press()
                waitFor("Move 1 file to this phone's trash?")
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #36: Cancel changes nothing; Move to trash hands Android exactly the eligible files and
     * never the one a transfer still needs; a batch of only held files asks nothing of Android.
     */
    @Test
    fun phoneDeleteAsksAndroidForExactlyTheEligibleFiles() {
        grantMedia()
        Graph.get(app).canManageMedia = { true }
        seedTransfers(row(2, "IMG_0002.jpg", TransferStatus.QUEUED))
        val asked = recordTrashRequests()
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }.uri.toString()
        eachVariant(
            "phone-delete-flow", expect = null, connected = true, capture = false,
            act = {
                asked.clear()
                selectTwoForDelete()
                // Cancel: nothing asked of Android, the selection stays.
                compose.onNodeWithContentDescription("Move selected to this phone's trash").press()
                waitFor("Move 1 file to this phone's trash?")
                tap("Cancel")
                pump(DIALOG_FRAMES)
                assertTrue("Cancel must not ask Android to trash anything", asked.isEmpty())
                assertEquals(2, currentVm!!.selection.value.size)

                // Confirm: exactly IMG_0001, never the file in a transfer. (The click action is used
                // because the "Nothing was moved" message from Cancel covers the bottom bar for a while.)
                compose.onNodeWithContentDescription("Move selected to this phone's trash").press()
                waitFor("Move 1 file to this phone's trash?")
                compose.onNodeWithText("Left out because", substring = true).assertExists()
                tap("Move to trash")
                pump(DIALOG_FRAMES)
                assertEquals(listOf(listOf(img1)), asked)

                // Only the held file: DriveLift explains, Android is not asked.
                asked.clear()
                currentVm!!.clearSelection()
                pump(4)
                selectCell("IMG_0002.jpg")
                waitFor("1 selected")
                compose.onNodeWithContentDescription("Move selected to this phone's trash").press()
                waitFor("This file can't go to the trash yet")
                tap("OK")
                pump(DIALOG_FRAMES)
                assertTrue(asked.isEmpty())
                currentVm!!.clearSelection()
                pump(4)
            },
        ) { vm ->
            PhoneTrashHost(vm)
            AppRoot(vm)
        }
    }

    /**
     * Codex review on ed59721: the app locking while Android's trash dialog is open removes the
     * screen, but the pending batch lives in the view model and its answer still completes it.
     */
    @Test
    fun aPendingPhoneTrashRequestSurvivesTheAppLocking() {
        grantMedia()
        val vm = AppViewModel(app)
        pump(12)
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }
        compose.waitUntil(5_000) { pump(1); vm.media.value.any { it.id == img1.id } }
        // IMG_0001 is in the folder; id 999999 left it after the user confirmed.
        vm.requestPhoneTrash(listOf(img1.id, 999_999L))
        assertEquals(listOf(img1.id, 999_999L), vm.pendingTrash.value?.ids)
        // Started once only, even if asked again (as after recomposition), with exactly the file
        // still present; the one that left is counted as not sent.
        Graph.get(app).trashRequest = { _, _ -> throw IllegalStateException("No Android trash dialog in tests") }
        runCatching { vm.startPendingTrash() }
        assertEquals(true, vm.pendingTrash.value?.launched)
        assertEquals(listOf(img1.uri.toString()), vm.pendingTrash.value?.submitted)
        assertEquals(1, vm.pendingTrash.value?.notSent)
        assertEquals(null, vm.startPendingTrash())
        // The lock removes AppRoot; nothing in the screen holds the request. Android answers.
        Graph.get(app).lock.lock()
        vm.pendingTrashFinished(confirmed = false)
        assertEquals(null, vm.pendingTrash.value)

        // Process recreated while Android's dialog was open: a fresh view model has no batch,
        // and the host's saved copy completes it; a restored selection drops files that are gone.
        val fresh = AppViewModel(app)
        pump(12)
        compose.waitUntil(5_000) { pump(1); fresh.media.value.isNotEmpty() }
        assertEquals(null, fresh.pendingTrash.value)
        fresh.setSelected(listOf(img1.id, 999_999L), true)
        compose.waitUntil(5_000) { pump(1); fresh.selection.value == setOf(img1.id) }
        // Android confirmed after recreation: only the saved submitted URI is checked, and its
        // state comes from MediaStore, not from the listing.
        val checked = mutableListOf<String>()
        Graph.get(app).trashState = { _, uri -> checked += uri.toString(); com.drivelift.app.data.TrashState.TRASHED }
        fresh.pendingTrashFinished(confirmed = true, savedSubmitted = listOf(img1.uri.toString()))
        compose.waitUntil(5_000) { pump(1); fresh.lastTrashOutcome != null }
        assertEquals(listOf(img1.uri.toString()), checked)
        assertEquals(com.drivelift.app.data.TrashOutcome(trashed = 1, stillPresent = 0, unknown = 0, notSent = 0), fresh.lastTrashOutcome)
        assertEquals(null, fresh.pendingTrash.value)
    }

    /** Codex review on d3948d5: a delete plan restored before the listing loads waits for it. */
    @Test
    fun aRestoredDeletePlanWaitsForTheListing() {
        grantMedia()
        val vm = AppViewModel(app)
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }
        // Asked at once, as after process recreation, before the listing has necessarily loaded.
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
            vm.planPhoneDelete(listOf(img1.id))
        }
        compose.waitUntil(10_000) { pump(1); job.isCompleted }
        val plan = kotlinx.coroutines.runBlocking { job.await() }
        assertEquals(listOf(img1.id), plan.eligible.map { it.id })
    }

    /**
     * ChatGPT review on 27ea5ba: results are counted only over submitted files and read back from
     * MediaStore. A file that vanished from the listing (here: media access withdrawn, so the
     * listing is empty) is "could not be checked", never "moved to the trash".
     */
    @Test
    fun phoneTrashResultsAreReadBackPerSubmittedFile() {
        grantMedia()
        val vm = AppViewModel(app)
        pump(12)
        val imgs = FakeMediaStore.files.filter { !it.video }.take(3)
        compose.waitUntil(5_000) { pump(1); imgs.all { f -> vm.media.value.any { it.id == f.id } } }
        Graph.get(app).trashRequest = { _, _ -> throw IllegalStateException("No Android trash dialog in tests") }
        vm.requestPhoneTrash(imgs.map { it.id })
        runCatching { vm.startPendingTrash() }
        assertEquals(imgs.map { it.uri.toString() }, vm.pendingTrash.value?.submitted)
        // One confirmed in the trash, one still present, one unreadable after access was withdrawn.
        val states = mapOf(
            imgs[0].uri.toString() to com.drivelift.app.data.TrashState.TRASHED,
            imgs[1].uri.toString() to com.drivelift.app.data.TrashState.PRESENT,
            imgs[2].uri.toString() to com.drivelift.app.data.TrashState.UNKNOWN,
        )
        Graph.get(app).trashState = { _, uri -> states.getValue(uri.toString()) }
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        vm.pendingTrashFinished(confirmed = true)
        compose.waitUntil(5_000) { pump(1); vm.lastTrashOutcome != null }
        val outcome = vm.lastTrashOutcome!!
        assertEquals(com.drivelift.app.data.TrashOutcome(trashed = 1, stillPresent = 1, unknown = 1, notSent = 0), outcome)
        assertEquals("1 moved to this phone's trash; 1 still on the phone; 1 could not be checked", outcome.message())
    }

    /** Without Media management and nothing held back, Android's own dialog confirms (invariant 3). */
    @Test
    fun phoneDeleteWithoutMediaManagementGoesStraightToAndroid() {
        grantMedia()
        Graph.get(app).canManageMedia = { false }
        val asked = recordTrashRequests()
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }.uri.toString()
        eachVariant(
            "phone-delete-android", expect = null, connected = true, capture = false,
            act = {
                asked.clear()
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")
                // From the viewer: its trash icon asks about this one file.
                compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performClick()
                settle { compose.onAllNodesWithContentDescription("Move to this phone's trash").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Move to this phone's trash").performClick()
                pump(DIALOG_FRAMES)
                assertTrue(
                    "No DriveLift dialog without Media management",
                    compose.onAllNodesWithTag("phone-delete").fetchSemanticsNodes().isEmpty(),
                )
                assertEquals(listOf(listOf(img1)), asked)
                currentActivity!!.onBackPressedDispatcher.onBackPressed()
                pump(4)
            },
        ) { vm ->
            PhoneTrashHost(vm)
            AppRoot(vm)
        }
    }

    /**
     * Owner report on Preview 15: the viewer sat inside the page's system-bar padding, leaving
     * bands at the top and bottom. It now fills the whole window, behind the bars, while its top
     * bar stays below the status bar.
     */
    @Test
    fun phoneViewerFillsTheWholeWindowBehindTheSystemBars() {
        grantMedia()
        eachVariant(
            "viewer-full-window", expect = null, connected = true, capture = false,
            act = {
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")
                compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performClick()
                settle { compose.onAllNodesWithTag("local-viewer").fetchSemanticsNodes().isNotEmpty() }
                giveSystemBars(THREE_BUTTON_NAV_DP)
                pump(DIALOG_FRAMES)
                val window = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.size.height }.size
                val viewer = compose.onNodeWithTag("local-viewer").fetchSemanticsNode().boundsInRoot
                assertTrue("Viewer starts at ${viewer.top}, not at the top of the window", viewer.top <= 0.5f)
                assertTrue(
                    "Viewer ends at ${viewer.bottom}, not at the bottom of the window (${window.height})",
                    viewer.bottom >= window.height - 0.5f,
                )
                val close = compose.onNodeWithContentDescription("Close viewer").fetchSemanticsNode().boundsInRoot
                assertTrue("Close viewer (${close.top}) is under the status bar", close.top >= px(STATUS_BAR_DP) - 0.5f)
                currentActivity!!.onBackPressedDispatcher.onBackPressed()
                pump(4)
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Selects a Drive file through its long-press action, as holding it does. */
    private fun selectDriveFile(name: String) {
        compose.onAllNodesWithContentDescription(name).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        pump(4)
    }

    /** A transfer whose Drive copy is [driveId] and whose status is [status]. */
    private fun driveRow(id: Long, name: String, driveId: String, status: TransferStatus) =
        row(id, name, status).copy(driveFileId = driveId)

    /** Issue #36: two Drive files selected, the mark drawn over their thumbnails; no tab bar. */
    @Test
    fun driveSelection() {
        grantMedia()
        eachVariant(
            "drive-selection", expect = "2 selected", connected = true, tab = AppViewModel.TAB_DRIVE,
            act = {
                waitFor("Scan_0042.jpg")
                selectDriveFile("Sunset_0019.jpg")
                compose.onAllNodesWithContentDescription("Scan_0042.jpg").onFirst().performClick()
                waitFor("2 selected")
                assertTrue(
                    "The tab bar steps aside while selecting",
                    compose.onAllNodesWithText("Transfers").fetchSemanticsNodes().isEmpty(),
                )
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Selects Sunset and Scan in the Drive tab and puts them in the move pill with Move. */
    private fun holdDriveFilesForMove() {
        waitFor("Scan_0042.jpg")
        // Sunset first: once the selection bar shows, the shorter grid can scroll it out of view.
        selectDriveFile("Sunset_0019.jpg")
        compose.onAllNodesWithContentDescription("Scan_0042.jpg").onFirst().performClick()
        waitFor("2 selected")
        compose.onNodeWithText("Move").press()
        waitFor("2 files to move")
    }

    /** The pill's "Move here" button; it names the folder only when the pill is stacked. */
    private fun moveHereButton() =
        compose.onNode(hasText("Move here", substring = true) and hasClickAction())

    private fun movePillShown() = compose.onAllNodesWithTag("move-pill").fetchSemanticsNodes().isNotEmpty()

    /** The move pill in the folder the files will go to, above the tab bar. */
    @Test
    fun driveMovePill() {
        grantMedia()
        eachVariant(
            "drive-move-pill", expect = "2 files to move", connected = true, tab = AppViewModel.TAB_DRIVE,
            act = {
                holdDriveFilesForMove()
                // In the folder the files are in, the pill asks for another folder.
                waitFor("Open the folder to move them to")
                moveHereButton().assertIsNotEnabled()
                tap("Archive 2024")
                waitForPart("From My Drive")
                moveHereButton().assertIsEnabled()
                assertTrue(
                    "The tab bar stays while files wait in the pill",
                    compose.onAllNodesWithText("Transfers").fetchSemanticsNodes().isNotEmpty(),
                )
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Issue #36: move confirmation with a file a transfer still depends on left in place. */
    @Test
    fun driveMoveConfirmation() {
        grantMedia()
        seedTransfers(driveRow(4, "Sunset_0019.jpg", "d4", TransferStatus.VERIFIED))
        eachVariant(
            "drive-move-confirm", expect = "Move 1 file to Archive 2024?", connected = true,
            tab = AppViewModel.TAB_DRIVE, overlay = true,
            act = {
                holdDriveFilesForMove()
                tap("Archive 2024")
                waitForPart("From My Drive")
                moveHereButton().press()
                waitFor("Move 1 file to Archive 2024?")
                compose.onNodeWithText("Access can change", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * The move pill: holding files changes nothing; the pill stays while browsing and after Back;
     * "Move here" is off in the source folder; Cancel in the confirmation moves nothing and keeps
     * the pill; confirming moves exactly the eligible file into the folder shown and empties the
     * pill (the file a transfer depends on leaves it too); closing the pill moves nothing.
     */
    @Test
    fun driveMoveMovesExactlyTheConfirmedFiles() {
        grantMedia()
        seedTransfers(driveRow(4, "Sunset_0019.jpg", "d4", TransferStatus.VERIFIED))
        val drive = FakeDrive()
        eachVariant(
            "drive-move-flow", expect = null, connected = true, tab = AppViewModel.TAB_DRIVE,
            capture = false, drive = drive,
            act = {
                drive.moves.clear()
                holdDriveFilesForMove()
                assertTrue(drive.moves.isEmpty())

                // The folder the files are in: nothing to do.
                moveHereButton().assertIsNotEnabled()

                // Into Archive 2024 and Back out again: the pill stays, nothing moved.
                tap("Archive 2024")
                waitForPart("From My Drive")
                currentActivity!!.onBackPressedDispatcher.onBackPressed()
                waitFor("Open the folder to move them to")
                assertTrue(drive.moves.isEmpty())

                // Cancel in the confirmation: nothing moved, the pill keeps both files.
                tap("Archive 2024")
                waitForPart("From My Drive")
                moveHereButton().press()
                waitFor("Move 1 file to Archive 2024?")
                tap("Cancel")
                pump(DIALOG_FRAMES)
                assertTrue(drive.moves.isEmpty())
                waitFor("2 files to move")

                // Confirm: only Scan (d2); Sunset (d4) is held by a verified transfer.
                moveHereButton().press()
                waitFor("Move 1 file to Archive 2024?")
                compose.onNodeWithText("Left in place because a transfer", substring = true).assertExists()
                tap("Move to Archive 2024")
                compose.waitUntil(5_000) { pump(1); compose.onAllNodesWithTag("drive-move").fetchSemanticsNodes().isEmpty() }
                assertEquals(listOf(Triple("d2", DriveClient.ROOT, "f1")), drive.moves.toList())
                compose.waitUntil(5_000) { pump(1); !movePillShown() }

                // Closing the pill lets the files go without moving anything.
                currentActivity!!.onBackPressedDispatcher.onBackPressed()
                waitFor("Scan_0042.jpg")
                drive.moves.clear()
                selectDriveFile("Scan_0042.jpg")
                waitFor("1 selected")
                compose.onNodeWithText("Move").press()
                waitFor("1 file to move")
                compose.onNodeWithContentDescription("Stop moving these files").press()
                compose.waitUntil(5_000) { pump(1); !movePillShown() }
                assertTrue(drive.moves.isEmpty())
            },
        ) { vm -> AppRoot(vm) }
    }

    // ------------------------------------------------------------------ phone move (19 Nov 2026)

    /** Records phone moves sent to MediaStore: (file, folder). Each file reads back where it went. */
    private fun recordPhoneMoves(): MutableList<Pair<String, String>> {
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val folders = java.util.concurrent.ConcurrentHashMap<String, String>()
        Graph.get(app).moveMedia = { _, uri, path ->
            sent += uri.toString() to path
            folders[uri.toString()] = path
        }
        Graph.get(app).mediaFolder = { _, uri -> folders[uri.toString()] }
        return sent
    }

    /** Opens Camera, selects IMG_0001 and IMG_0002 and puts them in the move pill. */
    private fun holdPhoneFilesForMove() {
        selectTwoForDelete()
        compose.onNodeWithContentDescription("Move selected to another folder on this phone").press()
        waitFor("2 files to move")
    }

    /** Opens the Screenshots folder from the folder list. */
    private fun openScreenshotsFolder() {
        currentActivity!!.onBackPressedDispatcher.onBackPressed()
        waitFor("Folders")
        // The folder list is taller than the screen once the pill takes its place at the bottom.
        compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey(200L)
        pump(4)
        compose.onNodeWithText("Screenshots").performClick()
        waitForPart("From Camera")
    }

    /** The phone move pill in the folder the files will go to. */
    @Test
    fun phoneMovePill() {
        grantMedia()
        eachVariant(
            "phone-move-pill", expect = "2 files to move", connected = true,
            act = {
                holdPhoneFilesForMove()
                // In the folder the files are in, the pill asks for another folder.
                waitFor("Open the folder to move them to")
                moveHereButton().assertIsNotEnabled()
                openScreenshotsFolder()
                moveHereButton().assertIsEnabled()
            },
        ) { vm -> AppRoot(vm) }
    }

    /** The phone move confirmation, with a file a transfer still depends on left in place. */
    @Test
    fun phoneMoveConfirmation() {
        grantMedia()
        seedTransfers(row(2, "IMG_0002.jpg", TransferStatus.QUEUED))
        eachVariant(
            "phone-move-confirm", expect = "Move 1 file to Screenshots?", connected = true, overlay = true,
            act = {
                holdPhoneFilesForMove()
                openScreenshotsFolder()
                moveHereButton().press()
                waitFor("Move 1 file to Screenshots?")
                compose.onNodeWithText("Left in place because a transfer", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Holding phone files changes nothing; Cancel moves nothing and keeps the pill; confirming
     * moves exactly the eligible file into the folder shown and reads it back; closing the pill
     * moves nothing.
     */
    @Test
    fun phoneMoveMovesExactlyTheConfirmedFiles() {
        grantMedia()
        seedTransfers(row(2, "IMG_0002.jpg", TransferStatus.QUEUED))
        val sent = recordPhoneMoves()
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }.uri.toString()
        eachVariant(
            "phone-move-flow", expect = null, connected = true, capture = false,
            act = {
                sent.clear()
                currentVm!!.cancelPhoneMoveClip()
                holdPhoneFilesForMove()
                assertTrue(sent.isEmpty())
                moveHereButton().assertIsNotEnabled()

                openScreenshotsFolder()
                moveHereButton().press()
                waitFor("Move 1 file to Screenshots?")
                tap("Cancel")
                pump(DIALOG_FRAMES)
                assertTrue("Cancel must not move anything", sent.isEmpty())
                waitFor("2 files to move")

                moveHereButton().press()
                waitFor("Move 1 file to Screenshots?")
                tap("Move to Screenshots")
                compose.waitUntil(5_000) { pump(1); currentVm!!.lastPhoneMove != null }
                assertEquals(listOf(img1 to "Pictures/Screenshots/"), sent.toList())
                assertEquals("1 file moved to Screenshots", currentVm!!.lastPhoneMove)
                compose.waitUntil(5_000) { pump(1); !movePillShown() }

                // Closing the pill lets the files go without moving anything.
                sent.clear()
                currentActivity!!.onBackPressedDispatcher.onBackPressed()
                holdPhoneFilesForMove()
                compose.onNodeWithContentDescription("Stop moving these files").press()
                compose.waitUntil(5_000) { pump(1); !movePillShown() }
                assertTrue(sent.isEmpty())
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Files DriveLift does not own need Android's write consent: the request names exactly them;
     * a refusal moves nothing; consent moves them once more and reads them back.
     */
    @Test
    fun phoneMoveAsksAndroidForConsentWhenNeeded() {
        grantMedia()
        val vm = AppViewModel(app)
        pump(12)
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }
        compose.waitUntil(5_000) { pump(1); vm.media.value.any { it.id == img1.id } }
        var owned = false
        val sent = mutableListOf<String>()
        Graph.get(app).moveMedia = { _, uri, _ ->
            if (!owned) throw SecurityException("not the owner")
            sent += uri.toString()
        }
        Graph.get(app).mediaFolder = { _, _ -> if (sent.isEmpty()) null else "Pictures/Screenshots/" }
        val asked = mutableListOf<List<String>>()
        Graph.get(app).writeRequest = { _, uris ->
            asked += uris.map { it.toString() }
            throw IllegalStateException("No Android consent dialog in tests")
        }
        val screenshots = vm.media.value.first { it.bucketName == "Screenshots" }.bucketId

        // Refused by Android: nothing moved, the file stays in the pill.
        vm.holdPhoneForMove(listOf(img1.id), img1Bucket(vm, img1.id), "Camera")
        val plan = checkNotNull(runBlocking { vm.planPhoneMove(screenshots) })
        vm.startPhoneMove(plan)
        compose.waitUntil(5_000) { pump(1); vm.pendingPhoneMove.value != null }
        runCatching { vm.startPendingPhoneMove() }
        assertEquals(listOf(listOf(img1.uri.toString())), asked)
        assertEquals(null, vm.startPendingPhoneMove())
        vm.pendingPhoneMoveFinished(confirmed = false)
        compose.waitUntil(5_000) { pump(1); vm.phoneMoveRun.value?.working == false }
        assertTrue(sent.isEmpty())
        assertEquals("Could not move IMG_0001.jpg: You did not allow the move", vm.lastPhoneMove)
        assertEquals(1, vm.phoneMoveClip.value?.items?.size)
        vm.phoneMoveApplied()

        // Consent given: the file is moved and read back.
        vm.startPhoneMove(plan)
        compose.waitUntil(5_000) { pump(1); vm.pendingPhoneMove.value != null }
        runCatching { vm.startPendingPhoneMove() }
        owned = true
        vm.pendingPhoneMoveFinished(confirmed = true)
        compose.waitUntil(5_000) { pump(1); vm.phoneMoveRun.value?.working == false }
        assertEquals(listOf(img1.uri.toString()), sent)
        assertEquals("1 file moved to Screenshots", vm.lastPhoneMove)
        assertEquals(null, vm.phoneMoveClip.value)
    }

    private fun img1Bucket(vm: AppViewModel, id: Long) = vm.media.value.first { it.id == id }.bucketId

    /** Issue #36: a move runs for the account it was confirmed for; on a change the rest are not tried. */
    @Test
    fun driveMoveStaysOnTheAccountItWasConfirmedFor() {
        grantMedia()
        val prefs = Graph.get(app).prefs
        prefs.accountEmail = TEST_EMAIL
        val vm = AppViewModel(app)
        pump(12)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        vm.driveFolders = object : DriveFolders by FakeDrive() {
            override suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String, account: String) {
                calls += fileId to account
                // The saved account changes while the first file is being moved.
                prefs.accountEmail = "other.user@example.com"
            }
        }
        val files = listOf(
            DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 2_100_000, "00", false),
            DriveFile("d1", "Packing list.pdf", "application/pdf", 84_000, "00", false),
        )
        val plan = runBlocking { vm.planDriveMove(files, DriveClient.ROOT, "f1", "My Drive / Archive 2024") }
        val outcome = runBlocking { vm.moveDriveFiles(plan) }
        assertEquals(listOf("d2" to TEST_EMAIL), calls.toList())
        assertEquals(listOf("d2"), outcome.moved.map { it.id })
        assertEquals(listOf("d1"), outcome.notTried.map { it.id })
        assertEquals("1 file moved to Archive 2024; 1 not tried: ${AppViewModel.ACCOUNT_CHANGED}", outcome.message())
        prefs.accountEmail = null
        val none = runBlocking { vm.moveDriveFiles(vm.planDriveMove(files, DriveClient.ROOT, "f1", "Archive 2024")) }
        assertEquals(1, calls.size)
        assertEquals(listOf("d2", "d1"), none.notTried.map { it.id })
    }

    // ------------------------------------------------------------------ rename (issue #42)

    /** Records the phone renames sent to MediaStore: (file, new name). There is no Android dialog here. */
    private fun recordPhoneRenames(): MutableList<Pair<String, String>> {
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val names = java.util.concurrent.ConcurrentHashMap<String, String>()
        Graph.get(app).renameMedia = { _, uri, name ->
            sent += uri.toString() to name
            names[uri.toString()] = name
        }
        Graph.get(app).mediaName = { _, uri -> names[uri.toString()] }
        return sent
    }

    /** Opens Camera, selects only IMG_0001 and opens the rename dialog. */
    private fun openPhoneRename() {
        waitFor("Camera")
        compose.onNodeWithText("Camera").performClick()
        waitFor("Select all")
        selectCell("IMG_0001.jpg")
        waitFor("1 selected")
        openRenameDialog()
    }

    /*
     * The rename dialog opens with its name already in the text field. While such a field is on
     * screen, Compose test calls that wait for idle never return under Robolectric (the same limit
     * as after typing in the new-folder dialog; see openNewFolderDialog). So while the dialog is
     * open, these helpers read its semantics straight from its window, run frames with pump, and
     * touch it directly. Once it has closed, ordinary test calls work again.
     */

    /** Semantics nodes of the rename dialog, if one is showing; read without waiting for idle. */
    private fun renameDialogNodes(): List<SemanticsNode>? {
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } ?: return null
        val root = composeView(dialog.window!!.decorView) as? RootForTest ?: return null
        val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
        return nodes.takeIf { list -> list.any { it.config.getOrNull(SemanticsProperties.TestTag) == "rename" } }
    }

    /** Every text the rename dialog shows, the field's text included. */
    private fun renameDialogTexts(): List<String> = renameDialogNodes().orEmpty().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    private fun untilRenameDialog(open: Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while ((renameDialogNodes() != null) != open) {
            pump(1)
            assertTrue("The rename dialog never ${if (open) "opened" else "closed"}", System.currentTimeMillis() < deadline)
        }
        pump(4)
    }

    /** Presses Rename in the selection bar and waits for the dialog, with no idle wait after it opens. */
    private fun openRenameDialog() {
        compose.onNodeWithTag("rename-selected").press()
        untilRenameDialog(open = true)
    }

    /** Touches the rename dialog's control tagged [tag] or labelled [text], as a finger would. */
    private fun touchInRenameDialog(tag: String? = null, text: String? = null) {
        val node = checkNotNull(renameDialogNodes()).first { n ->
            (tag != null && n.config.getOrNull(SemanticsProperties.TestTag) == tag) ||
                (text != null && n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } &&
                    n.config.getOrNull(SemanticsActions.OnClick) != null)
        }
        touchLatestDialog(node.boundsInRoot.center)
    }

    private fun cancelRenameDialog() {
        touchInRenameDialog(text = "Cancel")
        untilRenameDialog(open = false)
    }

    /** Types [name] into the open rename dialog and touches Rename. */
    private fun typeNameAndRename(name: String) {
        val field = checkNotNull(renameDialogNodes()).first { it.config.getOrNull(SemanticsActions.SetText) != null }
        checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(name))
        pump(DIALOG_FRAMES)
        touchInRenameDialog(tag = "rename-confirm")
        pump(DIALOG_FRAMES)
    }

    /** Issue #42: the phone rename dialog, name filled in, its ending kept. */
    @Test
    fun phoneRenameDialog() {
        grantMedia()
        eachVariant(
            "phone-rename", expect = null, connected = true, overlay = true,
            act = {
                openPhoneRename()
                val texts = renameDialogTexts()
                assertTrue(texts.toString(), "Rename file" in texts && "IMG_0001.jpg" in texts)
                assertTrue(texts.toString(), texts.any { "The ending .jpg stays" in it })
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Issue #42: a name already in the folder is refused with a reason; nothing is sent. */
    @Test
    fun phoneRenameRefusesANameAlreadyInTheFolder() {
        grantMedia()
        val sent = recordPhoneRenames()
        eachVariant(
            "phone-rename-taken", expect = null, connected = true, overlay = true,
            act = {
                sent.clear()
                openPhoneRename()
                typeNameAndRename("IMG_0002.jpg")
                assertTrue("A taken name must never be sent", sent.isEmpty())
                val texts = renameDialogTexts()
                assertTrue(texts.toString(), texts.any { "is already in this folder" in it })
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #42: Cancel renames nothing; confirming renames exactly the one selected file to the
     * typed name; a file a transfer depends on is refused before any dialog asks for a name.
     */
    @Test
    fun phoneRenameRenamesExactlyTheConfirmedFile() {
        grantMedia()
        seedTransfers(row(2, "IMG_0002.jpg", TransferStatus.QUEUED))
        val sent = recordPhoneRenames()
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }.uri.toString()
        eachVariant(
            "phone-rename-flow", expect = null, connected = true, capture = false,
            act = {
                sent.clear()
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")

                // Held by a transfer: refused with the reason, no name asked.
                selectCell("IMG_0002.jpg")
                waitFor("1 selected")
                compose.onNodeWithTag("rename-selected").press()
                waitFor("This file can't be renamed yet")
                assertTrue(compose.onAllNodesWithTag("rename").fetchSemanticsNodes().isEmpty())
                tap("OK")
                pump(DIALOG_FRAMES)
                currentVm!!.clearSelection()
                pump(4)

                // Cancel: nothing sent, the selection stays.
                selectCell("IMG_0001.jpg")
                waitFor("1 selected")
                openRenameDialog()
                cancelRenameDialog()
                assertTrue("Cancel must not rename anything", sent.isEmpty())
                assertEquals(setOf(1L), currentVm!!.selection.value)

                // Confirm: exactly IMG_0001, to exactly the typed name.
                openRenameDialog()
                typeNameAndRename("Beach day.jpg")
                val deadline = System.currentTimeMillis() + 5_000
                while (currentVm!!.lastPhoneRename == null && System.currentTimeMillis() < deadline) pump(1)
                assertEquals(listOf(img1 to "Beach day.jpg"), sent.toList())
                assertEquals("Renamed to Beach day.jpg", currentVm!!.lastPhoneRename)
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #42: a file DriveLift does not own needs Android's write consent. The request names
     * exactly that file; a refusal renames nothing; consent sends the name once more.
     */
    @Test
    fun phoneRenameAsksAndroidForConsentWhenNeeded() {
        grantMedia()
        val vm = AppViewModel(app)
        pump(12)
        val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }
        compose.waitUntil(5_000) { pump(1); vm.media.value.any { it.id == img1.id } }
        var owned = false
        val sent = mutableListOf<String>()
        Graph.get(app).renameMedia = { _, _, name ->
            if (!owned) throw SecurityException("not the owner")
            sent += name
        }
        Graph.get(app).mediaName = { _, _ -> sent.lastOrNull() }
        val asked = mutableListOf<List<String>>()
        Graph.get(app).writeRequest = { _, uris ->
            asked += uris.map { it.toString() }
            throw IllegalStateException("No Android consent dialog in tests")
        }
        val plan = checkNotNull(runBlocking { vm.planPhoneRename(img1.id) })
        assertTrue("Names in the folder are known to the plan", "IMG_0002.jpg" in plan.siblings)
        assertFalse("The file itself is not its own sibling", "IMG_0001.jpg" in plan.siblings)

        // Refused by Android: nothing renamed.
        vm.startPhoneRename(plan, "Beach.jpg")
        compose.waitUntil(5_000) { pump(1); vm.pendingRename.value != null }
        runCatching { vm.startPendingRename() }
        assertEquals(listOf(listOf(img1.uri.toString())), asked)
        assertEquals(null, vm.startPendingRename())
        vm.pendingRenameFinished(confirmed = false)
        compose.waitUntil(5_000) { pump(1); !vm.phoneRenameWorking.value }
        assertTrue(sent.isEmpty())
        assertEquals("Nothing was renamed.", vm.lastPhoneRename)

        // Consent given: the name is sent and read back.
        vm.startPhoneRename(plan, "Beach.jpg")
        compose.waitUntil(5_000) { pump(1); vm.pendingRename.value != null }
        runCatching { vm.startPendingRename() }
        owned = true
        vm.pendingRenameFinished(confirmed = true)
        compose.waitUntil(5_000) { pump(1); !vm.phoneRenameWorking.value }
        assertEquals(listOf("Beach.jpg"), sent)
        assertEquals("Renamed to Beach.jpg", vm.lastPhoneRename)
    }

    /** Issue #42: the Drive rename dialog for one selected file. */
    @Test
    fun driveRenameDialog() {
        grantMedia()
        eachVariant(
            "drive-rename", expect = null, connected = true, tab = AppViewModel.TAB_DRIVE, overlay = true,
            act = {
                waitFor("Scan_0042.jpg")
                selectDriveFile("Scan_0042.jpg")
                waitFor("1 selected")
                openRenameDialog()
                assertTrue(renameDialogTexts().toString(), "Scan_0042.jpg" in renameDialogTexts())
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Top-left corner of the first node described as [name], for comparing grid order. */
    private fun cornerOf(name: String): Pair<Float, Float> {
        val b = compose.onAllNodesWithContentDescription(name).onFirst().fetchSemanticsNode().boundsInRoot
        return b.top to b.left
    }

    private fun comesBefore(first: String, second: String): Boolean {
        val (t1, l1) = cornerOf(first)
        val (t2, l2) = cornerOf(second)
        return t1 < t2 || (t1 == t2 && l1 < l2)
    }

    /** Opens the sort menu of the folder shown and picks [label]. */
    private fun chooseSort(label: String) {
        compose.onNodeWithContentDescription("Sort:", substring = true).performClick()
        pump(DIALOG_FRAMES)
        tap(label)
        pump(DIALOG_FRAMES)
    }

    /** Issue #36: a Phone folder searched by name and sorted Z to A, without day headers. */
    @Test
    fun phoneFolderSearchAndSort() {
        grantMedia()
        eachVariant(
            "phone-folder-search", expect = "Camera", connected = true,
            act = {
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")
                chooseSort("Name, Z to A")
                compose.onNodeWithTag("folder-search").performTextReplacement("img_000")
                pump(DIALOG_FRAMES)
                assertTrue(
                    "A name search leaves out files that do not match",
                    compose.onAllNodesWithContentDescription("VID_0003.mp4").fetchSemanticsNodes().isEmpty(),
                )
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Issue #36: the sort menu of a Drive folder, the current choice marked. */
    @Test
    fun driveSortMenu() {
        grantMedia()
        eachVariant(
            "drive-sort-menu", expect = "Size, largest first", connected = true, tab = AppViewModel.TAB_DRIVE,
            overlay = true,
            act = {
                waitFor("Scan_0042.jpg")
                compose.onNodeWithContentDescription("Sort:", substring = true).performClick()
                pump(DIALOG_FRAMES)
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Issue #36: a Drive search with no match says so instead of showing an empty folder. */
    @Test
    fun driveSearchNoMatch() {
        grantMedia()
        eachVariant(
            "drive-search-empty", expect = "Nothing in this folder matches “beach”.", connected = true,
            tab = AppViewModel.TAB_DRIVE,
            act = {
                waitFor("Scan_0042.jpg")
                compose.onNodeWithTag("folder-search").performTextReplacement("beach")
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #36: sort choices order the files as labelled and are kept per tab; a search narrows
     * the listing and Select all then selects only the files shown.
     */
    @Test
    fun sortAndSearchActOnTheFilesShown() {
        grantMedia()
        eachVariant(
            "sort-search-flow", expect = null, connected = true, capture = false,
            act = {
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")
                // Default: newest first, as before.
                assertEquals(com.drivelift.app.data.FileSort.DATE_NEWEST, currentVm!!.phoneSort.value)
                chooseSort("Name, A to Z")
                assertTrue(comesBefore("IMG_0001.jpg", "IMG_0002.jpg"))
                chooseSort("Name, Z to A")
                assertTrue(comesBefore("IMG_0002.jpg", "IMG_0001.jpg"))
                assertEquals(
                    "The choice is saved",
                    com.drivelift.app.data.FileSort.NAME_ZA,
                    com.drivelift.app.data.Prefs(app).phoneSort.value,
                )
                compose.onNodeWithTag("folder-search").performTextReplacement("IMG_0001")
                pump(DIALOG_FRAMES)
                tap("Select all")
                waitFor("1 selected")
                currentVm!!.clearSelection()
                pump(4)
                compose.onNodeWithContentDescription("Clear search").performClick()
                pump(DIALOG_FRAMES)
                assertTrue(compose.onAllNodesWithContentDescription("VID_0003.mp4").fetchSemanticsNodes().isNotEmpty())
                // Restore the default so later variants start the same way.
                chooseSort("Date, newest first")

                // Drive keeps its own choice: by name until changed.
                currentVm!!.tab.value = AppViewModel.TAB_DRIVE
                waitFor("Scan_0042.jpg")
                assertEquals(com.drivelift.app.data.FileSort.NAME_AZ, currentVm!!.driveSort.value)
                chooseSort("Size, largest first")
                assertTrue(comesBefore("Trip to the coast.mp4", "Sunset_0019.jpg"))
                assertTrue(comesBefore("Sunset_0019.jpg", "Packing list.pdf"))
                compose.onNodeWithTag("folder-search").performTextReplacement("scan")
                pump(DIALOG_FRAMES)
                assertTrue(compose.onAllNodesWithContentDescription("Sunset_0019.jpg").fetchSemanticsNodes().isEmpty())
                assertTrue(compose.onAllNodesWithContentDescription("Scan_0042.jpg").fetchSemanticsNodes().isNotEmpty())
                chooseSort("Name, A to Z")
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * ChatGPT review on 0af3cc9: a search never drops a selection it hides. Select all and Select
     * none act on the matching files only; the count and the trash/move plan cover every selected
     * file. Phone and Drive behave the same.
     */
    @Test
    fun filteredSelectAllKeepsSelectionsTheSearchHides() {
        grantMedia()
        eachVariant(
            "filtered-select-flow", expect = null, connected = true, capture = false,
            act = {
                // Phone: VID_0003 selected, then a search that hides it.
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")
                selectCell("VID_0003.mp4")
                waitFor("1 selected")
                compose.onNodeWithTag("folder-search").performTextReplacement("IMG_0001")
                pump(DIALOG_FRAMES)
                tap("Select all")
                waitFor("2 selected")
                tap("Select none")
                waitFor("1 selected")
                assertEquals(1, currentVm!!.selection.value.size)
                compose.onNodeWithContentDescription("Clear search").performClick()
                pump(DIALOG_FRAMES)
                waitFor("1 selected")
                currentVm!!.clearSelection()
                pump(4)

                // Drive: Packing selected, then a search for scan that hides it.
                currentVm!!.tab.value = AppViewModel.TAB_DRIVE
                waitFor("Scan_0042.jpg")
                selectDriveFile("Packing list.pdf")
                waitFor("1 selected")
                compose.onNodeWithTag("folder-search").performTextReplacement("scan")
                pump(DIALOG_FRAMES)
                tap("Select all")
                waitFor("2 selected")
                tap("Select none")
                waitFor("1 selected")
                tap("Select all")
                waitFor("2 selected")
                compose.onNodeWithContentDescription("Clear search").performClick()
                pump(DIALOG_FRAMES)
                waitFor("2 selected")
                // The trash plan names both selected files, the hidden one included.
                compose.onNodeWithContentDescription("Move selected to Google Drive's trash").press()
                waitFor("Move 2 files to Google Drive's trash?")
                assertTrue(
                    "The hidden selected file is in the confirmation",
                    compose.onAllNodesWithText("Packing list.pdf  ·", substring = true).fetchSemanticsNodes().isNotEmpty(),
                )
                tap("Cancel")
                pump(DIALOG_FRAMES)
                waitFor("2 selected")
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #42: a Drive copy a transfer depends on is refused; Cancel renames nothing; confirming
     * renames exactly the selected file, in this folder, for the confirmed account.
     */
    @Test
    fun driveRenameRenamesExactlyTheConfirmedFile() {
        grantMedia()
        seedTransfers(driveRow(4, "Sunset_0019.jpg", "d4", TransferStatus.VERIFIED))
        val drive = FakeDrive()
        eachVariant(
            "drive-rename-flow", expect = null, connected = true, tab = AppViewModel.TAB_DRIVE,
            capture = false, drive = drive,
            act = {
                drive.renames.clear()
                waitFor("Scan_0042.jpg")
                selectDriveFile("Sunset_0019.jpg")
                waitFor("1 selected")
                compose.onNodeWithTag("rename-selected").press()
                waitFor("This file can't be renamed yet")
                tap("OK")
                pump(DIALOG_FRAMES)
                compose.onNodeWithText("Clear").press()
                pump(4)

                compose.onAllNodesWithContentDescription("Scan_0042.jpg").onFirst()
                    .performSemanticsAction(SemanticsActions.OnLongClick)
                waitFor("1 selected")
                openRenameDialog()
                cancelRenameDialog()
                assertTrue("Cancel must not rename anything", drive.renames.isEmpty())

                openRenameDialog()
                typeNameAndRename("Receipt.jpg")
                val deadline = System.currentTimeMillis() + 5_000
                while (drive.renames.isEmpty() && System.currentTimeMillis() < deadline) pump(1)
                pump(DIALOG_FRAMES)
                assertEquals(listOf(Triple("d2", DriveClient.ROOT, "Receipt.jpg")), drive.renames.toList())
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #42: a Drive rename runs for the account it was confirmed for; a name Drive reports as
     * taken keeps the file as it was; a held file and a name a transfer is uploading are refused.
     */
    /**
     * Issue #42 (review): a taken name reported after the Drive screen was recreated, so no
     * rename dialog is open, is still explained to the user, and nothing is renamed.
     */
    @Test
    fun driveRenameTakenAfterRecreationIsReported() {
        grantMedia()
        eachVariant(
            "drive-rename-taken-recreated", expect = null, connected = true, tab = AppViewModel.TAB_DRIVE, capture = false,
            act = {
                waitFor("Scan_0042.jpg")
                val file = DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 2_100_000, "00", false)
                currentVm!!.driveRenameRun.value = AppViewModel.DriveRenameRun(
                    working = false,
                    outcome = com.drivelift.app.data.DriveRenameOutcome(file, "Receipt.jpg", nameTaken = true, folderId = "root"),
                )
                waitFor("A file named Receipt.jpg is already in this folder. Nothing was renamed.")
                waitFor("Scan_0042.jpg")
                assertEquals(null, currentVm!!.driveRenameRun.value)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveRenameStaysOnTheAccountAndNeverOverwrites() {
        grantMedia()
        val prefs = Graph.get(app).prefs
        prefs.accountEmail = TEST_EMAIL
        val vm = AppViewModel(app)
        pump(12)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        var taken = false
        vm.driveFolders = object : DriveFolders by FakeDrive() {
            override suspend fun renameFile(fileId: String, folderId: String, newName: String, account: String): DriveFile {
                calls += fileId to account
                if (taken) throw com.drivelift.app.drive.NameTakenException(newName)
                return DriveFile(fileId, newName, "image/jpeg", 2_100_000, "00", false)
            }
        }
        val scan = DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 2_100_000, "00", false)
        val plan = runBlocking { vm.planDriveRename(scan, DriveClient.ROOT, listOf("Packing list.pdf")) }
        assertEquals(TEST_EMAIL, plan.account)

        taken = true
        val refused = runBlocking { vm.renameDriveFile(plan, "Receipt.jpg") }
        assertTrue(refused.nameTaken)
        assertEquals(null, refused.renamed)

        taken = false
        prefs.accountEmail = "other.user@example.com"
        val other = runBlocking { vm.renameDriveFile(plan, "Receipt.jpg") }
        assertEquals(AppViewModel.ACCOUNT_CHANGED, other.failure)
        assertEquals(1, calls.size)

        prefs.accountEmail = TEST_EMAIL
        // A file now uploading under the new name: refused before Drive is asked.
        seedTransfers(row(7, "Receipt.jpg", TransferStatus.UPLOADING))
        val uploading = runBlocking { vm.renameDriveFile(plan, "Receipt.jpg") }
        assertEquals(null, uploading.renamed)
        assertEquals(1, calls.size)
        val done = runBlocking { vm.renameDriveFile(plan, "Receipt 2.jpg") }
        assertEquals("Receipt 2.jpg", done.renamed?.name)
        assertEquals(listOf("d2" to TEST_EMAIL, "d2" to TEST_EMAIL), calls.toList())

        // A copy a transfer came to depend on after the plan was made is left alone.
        seedTransfers(driveRow(8, "Scan_0042.jpg", "d2", TransferStatus.UPLOADED))
        val held = runBlocking { vm.renameDriveFile(plan, "Receipt 3.jpg") }
        assertEquals(null, held.renamed)
        assertEquals(2, calls.size)
        prefs.accountEmail = null
    }

    /** Issue #36: Drive trash confirmation with a file a transfer still depends on left out. */
    @Test
    fun driveTrashConfirmation() {
        grantMedia()
        seedTransfers(driveRow(4, "Sunset_0019.jpg", "d4", TransferStatus.VERIFIED))
        eachVariant(
            "drive-trash-confirm", expect = "Move 1 file to Google Drive's trash?", connected = true,
            tab = AppViewModel.TAB_DRIVE, overlay = true,
            act = {
                waitFor("Scan_0042.jpg")
                // Sunset first: once the selection bar shows, the shorter grid can scroll it out of view.
                selectDriveFile("Sunset_0019.jpg")
                compose.onAllNodesWithContentDescription("Scan_0042.jpg").onFirst().performClick()
                waitFor("2 selected")
                compose.onNodeWithContentDescription("Move selected to Google Drive's trash").press()
                waitFor("Move 1 file to Google Drive's trash?")
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #36: Cancel trashes nothing; confirming trashes exactly the eligible files with the
     * Drive client and they leave the folder; a held-only batch asks nothing of Drive; the viewer's
     * trash icon trashes the open file and closes the viewer.
     */
    @Test
    fun driveTrashMovesExactlyTheConfirmedFiles() {
        grantMedia()
        seedTransfers(driveRow(4, "Sunset_0019.jpg", "d4", TransferStatus.VERIFIED))
        val drive = FakeDrive()
        eachVariant(
            "drive-trash-flow", expect = null, connected = true, tab = AppViewModel.TAB_DRIVE,
            capture = false, drive = drive,
            act = {
                drive.trashedIds.clear()
                waitFor("Scan_0042.jpg")
                // Sunset first: once the selection bar shows, the shorter grid can scroll it out of view.
                selectDriveFile("Sunset_0019.jpg")
                compose.onAllNodesWithContentDescription("Scan_0042.jpg").onFirst().performClick()
                waitFor("2 selected")

                // Cancel: nothing trashed, selection kept.
                compose.onNodeWithContentDescription("Move selected to Google Drive's trash").press()
                waitFor("Move 1 file to Google Drive's trash?")
                tap("Cancel")
                pump(DIALOG_FRAMES)
                assertTrue(drive.trashedIds.isEmpty())
                waitFor("2 selected")

                // Confirm: only Scan_0042 (d2); Sunset (d4) is held by a verified transfer.
                compose.onNodeWithContentDescription("Move selected to Google Drive's trash").press()
                waitFor("Move 1 file to Google Drive's trash?")
                compose.onNodeWithText("Left out because a transfer", substring = true).assertExists()
                tap("Move to trash")
                compose.waitUntil(5_000) { pump(1); compose.onAllNodesWithTag("drive-trash").fetchSemanticsNodes().isEmpty() }
                assertEquals(listOf("d2"), drive.trashedIds)
                assertTrue(
                    "A trashed file leaves the folder",
                    compose.onAllNodesWithContentDescription("Scan_0042.jpg").fetchSemanticsNodes().isEmpty(),
                )

                // Only the held file selected: explained, Drive not asked.
                drive.trashedIds.clear()
                waitFor("1 selected")
                compose.onNodeWithContentDescription("Move selected to Google Drive's trash").press()
                waitFor("This item can't go to the trash")
                tap("OK")
                pump(DIALOG_FRAMES)
                assertTrue(drive.trashedIds.isEmpty())
                compose.onNodeWithContentDescription("Clear selection").performClick()
                pump(4)

                // From the viewer: the open file.
                compose.onAllNodesWithContentDescription("Packing list.pdf").onFirst().performClick()
                settle { compose.onAllNodesWithContentDescription("Move to Google Drive's trash").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Move to Google Drive's trash").performClick()
                waitFor("Move 1 file to Google Drive's trash?")
                tap("Move to trash")
                compose.waitUntil(5_000) { pump(1); compose.onAllNodesWithContentDescription("Close viewer").fetchSemanticsNodes().isEmpty() }
                assertEquals(listOf("d1"), drive.trashedIds)
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Codex review on 3be9fc7: a transfer that comes to depend on a confirmed file before its turn
     * (its Drive id recorded, or its upload still running under the same name) leaves it alone.
     */
    @Test
    fun driveTrashRechecksTransfersRightBeforeEachFile() {
        grantMedia()
        Graph.get(app).prefs.accountEmail = TEST_EMAIL
        val vm = AppViewModel(app)
        pump(12)
        val drive = FakeDrive()
        vm.driveFolders = drive
        val scan = DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 2_100_000, "00", false)
        val sunset = DriveFile("d4", "Sunset_0019.jpg", "image/jpeg", 3_400_000, "00", false)
        val trip = DriveFile("d3", "Trip to the coast.mp4", "video/mp4", 182_000_000, "00", false)
        val plan = runBlocking { vm.planDriveTrash(listOf(scan, sunset, trip)) }
        assertEquals(listOf("d2", "d4", "d3"), plan.eligible.map { it.id })
        // After the confirmation was planned: Sunset's upload id is recorded as VERIFIED, and an
        // upload of a file named like Trip starts (its Drive id not known yet).
        seedTransfers(
            driveRow(4, "Sunset_0019.jpg", "d4", TransferStatus.VERIFIED),
            row(3, "Trip to the coast.mp4", TransferStatus.UPLOADING).copy(driveFileId = null),
        )
        val outcome = runBlocking { vm.trashDriveFiles(plan.eligible, plan.account) }
        assertEquals(listOf("d2"), drive.trashedIds)
        assertEquals(listOf("d4", "d3"), outcome.skipped.map { it.id })
        assertEquals("1 file moved to Google Drive's trash; 2 left in Drive because a transfer now depends on them", outcome.message())
    }

    /**
     * Codex review on 33d08d4: a check that finished as VERIFIED while the trash request was in
     * flight is sent back to UPLOADED, so the verifier sees the trashed copy before any removal.
     */
    @Test
    fun aCopyVerifiedDuringTrashIsCheckedAgain() {
        grantMedia()
        Graph.get(app).prefs.accountEmail = TEST_EMAIL
        val vm = AppViewModel(app)
        pump(12)
        val scan = DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 2_100_000, "00", false)
        // The verifier marks the row VERIFIED at the moment Drive is asked to trash the copy.
        vm.driveFolders = object : DriveFolders by FakeDrive() {
            override suspend fun moveToTrash(fileId: String, account: String) {
                Graph.get(app).db.transfers().insertAll(listOf(driveRow(2, "Scan_0042.jpg", "d2", TransferStatus.VERIFIED)))
            }
        }
        runBlocking { vm.trashDriveFiles(listOf(scan), TEST_EMAIL) }
        val after = runBlocking { Graph.get(app).db.transfers().byDriveFileIds(listOf("d2")) }
        assertEquals(listOf(TransferStatus.UPLOADED.name), after.map { it.status })
    }

    /**
     * ChatGPT review on 92a1ba6 [P1]: a confirmed batch runs for the account it was confirmed
     * for. While the first request is slow, switching accounts is refused; if the saved account
     * changes anyway, the remaining files are not tried, and no call ever names another account.
     */
    @Test
    fun driveTrashStaysOnTheAccountItWasConfirmedFor() {
        grantMedia()
        val prefs = Graph.get(app).prefs
        prefs.accountEmail = TEST_EMAIL
        val vm = AppViewModel(app)
        pump(12)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        vm.driveFolders = object : DriveFolders by FakeDrive() {
            override suspend fun moveToTrash(fileId: String, account: String) {
                calls += fileId to account
                if (calls.size == 1) gate.await()
            }
        }
        val files = listOf(
            DriveFile("d2", "Scan_0042.jpg", "image/jpeg", 2_100_000, "00", false),
            DriveFile("d3", "Trip to the coast.mp4", "video/mp4", 182_000_000, "00", false),
            DriveFile("d1", "Packing list.pdf", "application/pdf", 84_000, "00", false),
        )
        val plan = runBlocking { vm.planDriveTrash(files) }
        assertEquals(TEST_EMAIL, plan.account)
        vm.startDriveTrash(plan)
        settle { calls.size == 1 }
        // The first request is in flight: an account switch is refused while the batch runs.
        assertTrue(runBlocking { vm.accountSwitchBlockers() } > 0)
        // The saved account changes anyway; the slow request then finishes.
        prefs.accountEmail = "other.user@example.com"
        gate.complete(Unit)
        settle { vm.driveTrashRun.value?.outcome != null }
        val outcome = vm.driveTrashRun.value!!.outcome!!
        assertEquals(listOf("d2" to TEST_EMAIL), calls.toList())
        assertEquals(listOf("d2"), outcome.trashed.map { it.id })
        assertEquals(listOf("d3", "d1"), outcome.notTried.map { it.id })
        assertEquals(
            "1 file moved to Google Drive's trash; 2 not tried: ${AppViewModel.ACCOUNT_CHANGED}",
            outcome.message(),
        )
        assertEquals(0, runBlocking { vm.accountSwitchBlockers() })
        // A plan made with no account connected moves nothing and says so.
        prefs.accountEmail = null
        val none = runBlocking { vm.trashDriveFiles(files, vm.planDriveTrash(files).account) }
        assertEquals(1, calls.size)
        assertEquals(files.map { it.id }, none.notTried.map { it.id })
    }

    /**
     * ChatGPT review on 92a1ba6 [P2]: while a copy is being trashed, the Transfers removal query
     * leaves out the phone file that relies on it, even when the copy turned VERIFIED during the
     * request; and while a removal request is open, the trash leaves its copies alone.
     */
    @Test
    fun removalAndDriveTrashNeverOverlap() {
        grantMedia()
        Graph.get(app).prefs.accountEmail = TEST_EMAIL
        val vm = AppViewModel(app)
        pump(12)
        val dao = Graph.get(app).db.transfers()
        // File 1 is verified against copy d1 and unrelated to the first trash.
        seedTransfers(driveRow(1, "IMG_0001.jpg", "d1", TransferStatus.VERIFIED))
        val scan = DriveFile("d2", "IMG_0002.jpg", "image/jpeg", 3_420_000, "00", false)
        var offeredDuringTrash: List<String>? = null
        val trashed = java.util.Collections.synchronizedList(mutableListOf<String>())
        vm.driveFolders = object : DriveFolders by FakeDrive() {
            override suspend fun moveToTrash(fileId: String, account: String) {
                trashed += fileId
                if (fileId == "d2") {
                    // The verifier finishes for file 2 while Drive is trashing its copy, and the
                    // Transfers screen asks for files to put in a removal request.
                    dao.insertAll(listOf(driveRow(2, "IMG_0002.jpg", "d2", TransferStatus.VERIFIED)))
                    offeredDuringTrash = vm.verifiedForRemoval(50)
                    Graph.get(app).transfers.removalFinished()
                }
            }
        }
        val first = runBlocking { vm.trashDriveFiles(listOf(scan), TEST_EMAIL) }
        assertEquals(listOf("d2"), first.trashed.map { it.id })
        val uri1 = "content://media/external/images/media/1"
        val uri2 = "content://media/external/images/media/2"
        assertEquals(listOf(uri1), offeredDuringTrash)
        // Afterwards the row is back to be checked and is never offered against the trashed copy.
        assertEquals(
            listOf(TransferStatus.UPLOADED.name),
            runBlocking { dao.byDriveFileIds(listOf("d2")) }.map { it.status },
        )
        assertFalse(uri2 in runBlocking { vm.verifiedForRemoval(50) })
        runBlocking { Graph.get(app).transfers.removalFinished() }

        // A removal request is open for file 1 (the user is deciding). Even if its row stopped
        // being VERIFIED meanwhile, the trash leaves copy d1 alone until Android answers.
        assertEquals(listOf(uri1), runBlocking { vm.verifiedForRemoval(50) })
        runBlocking { dao.recheckTrashedCopy("d1") }
        val first1 = DriveFile("d1", "IMG_0001.jpg", "image/jpeg", 3_410_000, "00", false)
        val held = runBlocking { vm.trashDriveFiles(listOf(first1), TEST_EMAIL) }
        assertEquals(listOf("d1"), held.skipped.map { it.id })
        assertEquals(listOf("d2"), trashed.toList())
        // Android answered: the copy is free for a trash again.
        runBlocking { Graph.get(app).transfers.removalFinished() }
        val fence = Graph.get(app).driveCopyFence
        assertTrue(runBlocking { fence.beginTrash("d1") })
        runBlocking { fence.endTrash("d1", trashedNow = false) }
    }

    // ------------------------------------------------------------------ favorites (issues #36, #42)

    private val favoriteDao get() = Graph.get(app).db.favorites()

    /** Leaves exactly [favorites] saved, so every variant starts from the same list. */
    private fun setFavorites(vararg favorites: com.drivelift.app.data.FavoriteEntity) = runBlocking {
        favoriteDao.observe().first().forEach { favoriteDao.delete(it.id) }
        favorites.forEach { favoriteDao.upsert(it) }
    }

    private fun savedFavoriteIds(): Set<String> = runBlocking { favoriteDao.observe().first().mapTo(HashSet()) { it.id } }

    private val myAccount get() = com.drivelift.app.data.accountHash(TEST_EMAIL)

    private fun phoneFolderFavorite(bucket: Long, name: String, path: String, added: Long) =
        com.drivelift.app.data.FavoriteEntity(
            id = com.drivelift.app.data.phoneFolderKey(bucket), kind = com.drivelift.app.data.FavoriteKind.PHONE_FOLDER.name,
            itemId = bucket.toString(), accountHash = null, uri = null, name = name, mime = "", size = 0,
            location = path, addedAt = added,
        )

    private fun phoneMediaFavorite(id: Long, name: String, added: Long) = com.drivelift.app.data.FavoriteEntity(
        id = com.drivelift.app.data.phoneMediaKey(id), kind = com.drivelift.app.data.FavoriteKind.PHONE_MEDIA.name,
        itemId = id.toString(), accountHash = null, uri = "content://media/external/images/media/$id", name = name,
        mime = "image/jpeg", size = 3_400_000, location = "DCIM/Camera", addedAt = added,
    )

    private fun driveFavorite(driveId: String, name: String, folder: Boolean, added: Long, account: String = myAccount) =
        com.drivelift.app.data.favoriteOf(
            DriveFile(driveId, name, if (folder) DriveClient.FOLDER_MIME else "image/jpeg", 2_100_000, null, false),
            account, "My Drive",
        ).copy(addedAt = added)

    /** Every kind of favorite, including ones that can no longer be opened. */
    private fun seedMixedFavorites() = setFavorites(
        phoneFolderFavorite(100, "Camera", "DCIM/Camera", added = 10),
        phoneMediaFavorite(999, "IMG_0999.jpg", added = 9),
        phoneMediaFavorite(1, "IMG_0001.jpg", added = 8),
        driveFavorite("f1", "Archive 2024", folder = true, added = 7),
        driveFavorite("d2", "Scan_0042.jpg", folder = false, added = 6),
        driveFavorite("binned", "Old receipt.jpg", folder = false, added = 5),
        driveFavorite("gone", "Deleted scan.jpg", folder = false, added = 4),
        driveFavorite(
            "d9", "Their holiday.jpg", folder = false, added = 3,
            account = com.drivelift.app.data.accountHash("someone.else@example.com"),
        ),
    )

    /**
     * Scrolls the Favorites list until [text] is in its upper half, one item at a time with the
     * list's own ScrollToIndex action. performScrollToNode ends in an animated scroll, which never
     * finishes while the test clock is frozen.
     */
    private fun scrollFavoritesTo(text: String) {
        // Rows grow once a Drive lookup ends (a spinner becomes a reason), which would push the
        // item back down after scrolling: wait until every favorite is resolved.
        settle {
            currentVm!!.favoriteRows.value?.none { it.state is com.drivelift.app.data.FavoriteState.Checking } == true
        }
        pump(4)
        val list = compose.onNodeWithTag("favorites-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val half = bounds.top + bounds.height / 2
        for (i in 0 until 40) {
            // A row composed ahead of scrolling is not placed and reports a top of 0: only a placed
            // row inside the list counts.
            val found = compose.onAllNodesWithText(text).fetchSemanticsNodes()
            if (found.any { it.layoutInfo.isPlaced && it.boundsInRoot.top >= bounds.top && it.boundsInRoot.top < half }) return
            // Past the last item the action refuses the index: the end of the list is reached.
            val scrolled = runCatching {
                list.performSemanticsAction(SemanticsActions.ScrollToIndex) { it(i) }
            }.isSuccess
            if (!scrolled) break
            pump(3)
        }
        waitFor(text)
    }

    private fun openFavoritesFromPhone() {
        waitFor("Favorites")
        compose.onNodeWithText("Favorites").performClick()
        waitFor("On this phone")
    }

    /** The Favorites view with available and unavailable phone items, and the start of the Drive part. */
    @Test
    fun favorites() {
        grantMedia()
        eachVariant(
            "favorites", expect = "Unavailable", connected = true,
            act = {
                seedMixedFavorites()
                openFavoritesFromPhone()
                waitFor(com.drivelift.app.data.FavoriteReasons.MEDIA_GONE)
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * The Drive part of Favorites, scrolled into view: trashed and missing items, and the favorite
     * of another account shown only as a count, with nothing saved for it on screen.
     */
    @Test
    fun favoritesDriveSection() {
        grantMedia()
        eachVariant(
            "favorites-drive", expect = "1 favorite hidden", connected = true,
            act = {
                seedMixedFavorites()
                openFavoritesFromPhone()
                scrollFavoritesTo("1 favorite hidden")
                assertNothingMentions("Their holiday")
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Removing the last favorite leaves the empty state, which says how to add one. */
    @Test
    fun favoritesEmpty() {
        grantMedia()
        eachVariant(
            // The empty-state text is a sentence; act waits for it by substring.
            "favorites-empty", expect = null, connected = true,
            act = {
                setFavorites(phoneFolderFavorite(100, "Camera", "DCIM/Camera", added = 1))
                openFavoritesFromPhone()
                settle {
                    compose.onAllNodesWithContentDescription("Remove Camera from favorites").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Remove Camera from favorites").press()
                settle { savedFavoriteIds().isEmpty() }
                settle { compose.onAllNodesWithText("No favorites yet", substring = true).fetchSemanticsNodes().isNotEmpty() }
            },
        ) { vm -> AppRoot(vm) }
    }

    /** The way into Favorites at the top of My Drive, with a starred folder. */
    @Test
    fun driveFavoritesEntry() {
        grantMedia()
        eachVariant(
            "drive-favorites-entry", expect = "1 folder or file", connected = true,
            tab = AppViewModel.TAB_DRIVE,
            act = {
                setFavorites(driveFavorite("f1", "Archive 2024", folder = true, added = 1))
                waitFor("Scan_0042.jpg")
                settle {
                    compose.onAllNodesWithContentDescription("Remove Archive 2024 from favorites")
                        .fetchSemanticsNodes().isNotEmpty()
                }
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Real composables end to end: star a photo in the viewer and a Drive folder in its row, find
     * both in Favorites (saved in Room), open the photo in the viewer and the folder in Drive.
     */
    @Test
    fun starringAndOpeningFavorites() {
        grantMedia()
        eachVariant(
            "favorites-flow", expect = null, connected = true, capture = false,
            act = {
                setFavorites()
                // A photo, starred in the phone viewer.
                waitFor("Camera")
                compose.onNodeWithText("Camera").performClick()
                waitFor("Select all")
                compose.onAllNodesWithContentDescription("IMG_0001.jpg").onFirst().performClick()
                settle { compose.onAllNodesWithTag("viewer-image").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Add IMG_0001.jpg to favorites").press()
                settle { com.drivelift.app.data.phoneMediaKey(1) in savedFavoriteIds() }
                settle {
                    compose.onAllNodesWithContentDescription("Remove IMG_0001.jpg from favorites")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Close viewer").press()
                waitFor("Select all")
                compose.onNodeWithContentDescription("Back to folders").press()

                // A Drive folder, starred in its row; the key binds it to the connected account.
                currentVm!!.tab.value = AppViewModel.TAB_DRIVE
                waitFor("Scan_0042.jpg")
                settle {
                    compose.onAllNodesWithContentDescription("Add Archive 2024 to favorites").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Add Archive 2024 to favorites").press()
                settle { com.drivelift.app.data.driveKey(myAccount, "f1") in savedFavoriteIds() }
                assertEquals(2, savedFavoriteIds().size)

                // Favorites lists both; the photo opens in the viewer.
                waitFor("2 folders and files")
                compose.onNodeWithText("2 folders and files").performClick()
                waitFor("On this phone")
                waitFor("Google Drive")
                compose.onNodeWithText("IMG_0001.jpg").performClick()
                settle { compose.onAllNodesWithTag("viewer-image").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Photo on this phone", substring = true).assertExists()
                compose.onNodeWithContentDescription("Close viewer").press()

                // The folder opens in the Drive tab.
                // Clickable once Drive has confirmed the folder is still there.
                val archive = hasText("Archive 2024") and hasClickAction()
                settle { compose.onAllNodes(archive).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(archive).press()
                waitFor("This folder is empty.")
                compose.onNodeWithContentDescription("Up one folder").assertExists()
                assertEquals(AppViewModel.TAB_DRIVE, currentVm!!.tab.value)
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Fails when any text or label on screen (messages included) contains [text], ignoring case. */
    private fun assertNothingMentions(text: String) {
        val found = compose.onAllNodes(
            hasText(text, substring = true, ignoreCase = true) or
                hasContentDescription(text, substring = true, ignoreCase = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("\"$text\" must not be on screen", found.isEmpty())
    }

    /**
     * Issue #42 account isolation (ChatGPT review of 891f1e7): a Drive favorite saved with account
     * A shows nothing saved for it (name, type, place) while B is connected or after signing out,
     * is never looked up as B, and returns usable once A is connected again. Removing hidden
     * favorites names none of them.
     */
    @Test
    fun favoritesOfAnotherAccountRevealNothingUntilItIsConnected() {
        grantMedia()
        val drive = FakeDrive()
        val accountA = "first.account@example.com"
        eachVariant(
            "favorites-isolation", expect = null, connected = true, capture = false, drive = drive,
            act = {
                drive.lookupAccount = TEST_EMAIL
                drive.lookedUp.clear()
                setFavorites(
                    com.drivelift.app.data.favoriteOf(
                        DriveFile("d1", "Packing list.pdf", "application/pdf", 84_000, null, false),
                        com.drivelift.app.data.accountHash(accountA), "My Drive / Private",
                    ).copy(addedAt = 2),
                    driveFavorite("d2", "Scan_0042.jpg", folder = false, added = 1),
                )
                val vm = currentVm!!

                // B (the test account) is connected: B's favorite opens, A's is only counted.
                currentVm!!.tab.value = AppViewModel.TAB_DRIVE
                waitFor("1 folder or file")
                compose.onNodeWithText("1 folder or file").performClick()
                waitFor("1 favorite hidden")
                settle {
                    compose.onAllNodes(hasText("Scan_0042.jpg") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                }
                assertNothingMentions("Packing")
                assertNothingMentions("Private")
                assertNothingMentions("PDF")
                assertTrue("A's favorite is never looked up as B", "d1" !in drive.lookedUp)

                // Signed out: no Drive favorite is named, both are counted.
                vm.driveConn.value = DriveConn.DISCONNECTED
                waitFor("2 favorites hidden")
                assertNothingMentions("Packing")
                assertNothingMentions("Scan_0042")
                assertNothingMentions("Private")

                // A connected again: A's favorite is back and usable, B's is now the hidden one.
                drive.lookupAccount = accountA
                Graph.get(app).prefs.accountEmail = accountA
                vm.driveConn.value = DriveConn.CONNECTED
                waitFor("1 favorite hidden")
                settle {
                    compose.onAllNodes(hasText("Packing list.pdf") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue("A's favorite is looked up as A once A is connected", "d1" in drive.lookedUp)
                assertNothingMentions("Scan_0042")

                // Removing the hidden one (B's) is confirmed and names nothing.
                compose.onAllNodesWithText("Remove").onFirst().press()
                waitFor("Remove 1 hidden favorite?")
                compose.onNode(hasText("Remove") and hasClickAction() and hasAnyAncestor(hasTestTag("remove-hidden-favorites"))).press()
                waitFor("Removed 1 hidden favorite")
                assertNothingMentions("Scan_0042")
                assertEquals(
                    setOf(com.drivelift.app.data.driveKey(com.drivelift.app.data.accountHash(accountA), "d1")),
                    savedFavoriteIds(),
                )
                Graph.get(app).prefs.accountEmail = TEST_EMAIL
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * With photo access off and Drive not connected the Phone tab shows only the access gate, so
     * the Favorites row stays in the Drive tab: each favorite shows why it cannot open and can be
     * removed, with nothing looked up.
     */
    @Test
    fun favoritesStayReachableWithoutPhotoAccessOrDrive() {
        val drive = FakeDrive()
        eachVariant(
            "favorites-no-access", expect = null, capture = false, tab = AppViewModel.TAB_DRIVE, drive = drive,
            act = {
                setFavorites(
                    phoneFolderFavorite(100, "Camera", "DCIM/Camera", added = 2),
                    driveFavorite("d2", "Scan_0042.jpg", folder = false, added = 1),
                )
                waitFor("1 folder or file")
                compose.onNodeWithText("1 folder or file").performClick()
                waitFor(com.drivelift.app.data.FavoriteReasons.PHOTO_ACCESS_OFF)
                waitFor("1 favorite hidden")
                assertNothingMentions("Scan_0042")
                assertTrue("Nothing is looked up without a connected account", drive.lookedUp.isEmpty())
                compose.onAllNodesWithText("Remove").onFirst().press()
                settle { savedFavoriteIds().size == 1 }
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Recovery: favorites that cannot be opened stay listed with their reason and can be removed;
     * the Drive favorite of another account is never looked up.
     */
    @Test
    fun unavailableFavoritesShowWhyAndCanBeRemoved() {
        grantMedia()
        val drive = FakeDrive()
        eachVariant(
            "favorites-recovery", expect = null, connected = true, capture = false, drive = drive,
            act = {
                seedMixedFavorites()
                openFavoritesFromPhone()
                for (reason in listOf(
                    com.drivelift.app.data.FavoriteReasons.MEDIA_GONE,
                    com.drivelift.app.data.FavoriteReasons.DRIVE_TRASHED,
                    com.drivelift.app.data.FavoriteReasons.DRIVE_MISSING,
                    "1 favorite hidden",
                )) {
                    scrollFavoritesTo(reason)
                    waitFor(reason)
                }
                assertTrue("Only the connected account's favorites are looked up", "d9" !in drive.lookedUp)
                assertTrue(drive.lookedUp.containsAll(listOf("f1", "d2", "binned", "gone")))

                // Remove the missing phone photo: only its row goes; the phone listing is untouched.
                scrollFavoritesTo("IMG_0999.jpg")
                compose.onAllNodesWithText("Remove").onFirst().press()
                settle { com.drivelift.app.data.phoneMediaKey(999) !in savedFavoriteIds() }
                assertEquals(7, savedFavoriteIds().size)
                assertEquals(13, currentVm!!.media.value.size)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun folderLargeDensity() {
        grantMedia()
        Graph.get(app).prefs.setGridDensity(GridDensity.LARGE)
        eachVariant(
            "folder-large-density", expect = "Camera", connected = true,
            act = {
                settle { compose.onAllNodesWithText("Camera").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Camera").performClick()
                settle {
                    compose.onAllNodesWithContentDescription("Thumbnail size: Large thumbnails")
                        .fetchSemanticsNodes().isNotEmpty()
                }
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

    /**
     * Issue 24: with Media management granted, Remove opens DriveLift's text-only confirmation
     * (names and sizes, no pictures) instead of Android's preview dialog; Cancel removes nothing.
     */
    @Test
    fun textOnlyRemovalConfirmation() {
        grantMedia()
        Graph.get(app).prefs.setAutoRemove(false)
        Graph.get(app).canManageMedia = { true }
        // Rows whose address is really in the fake media store; a missing one would correctly be
        // closed out as already gone before the confirmation.
        seedTransfers(
            row(1, "IMG_0001.jpg", TransferStatus.VERIFIED),
            row(2, "IMG_0002.jpg", TransferStatus.VERIFIED),
            row(4, "IMG_0004.jpg", TransferStatus.VERIFIED, size = 48_000_000),
        )
        eachVariant(
            "removal-text-only", expect = "Remove 3 files from this phone?", tab = AppViewModel.TAB_TRANSFERS,
            overlay = true,
            act = {
                waitFor("Remove 3 verified files from phone")
                compose.onNodeWithText("Remove 3 verified files from phone").performClick()
                waitFor("Remove 3 files from this phone?")
                // The dialog line ("name  ·  size"); the file list behind it also shows the name alone.
                compose.onNodeWithText("IMG_0004.jpg  ·  ", substring = true).assertExists()
                compose.onNodeWithText("Each Drive copy matched", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun textOnlyRemovalCancelRemovesNothingAndSettingsShowsTheAccess() {
        grantMedia()
        Graph.get(app).prefs.setAutoRemove(false)
        Graph.get(app).canManageMedia = { true }
        seedTransfers(row(2, "IMG_0002.jpg", TransferStatus.VERIFIED))
        eachVariant(
            "removal-cancel", expect = null, tab = AppViewModel.TAB_TRANSFERS, capture = false,
            act = {
                waitFor("Remove 1 verified file from phone")
                compose.onNodeWithText("Remove 1 verified file from phone").performClick()
                waitFor("Remove 1 file from this phone?")
                compose.onNodeWithText("Cancel").performClick()
                pump(10)
                assertTrue(
                    "Cancel must close the confirmation",
                    compose.onAllNodesWithText("Remove 1 file from this phone?").fetchSemanticsNodes().isEmpty(),
                )
                compose.onNodeWithText("Remove 1 verified file from phone").assertExists()
                assertEquals(
                    TransferStatus.VERIFIED.name,
                    kotlinx.coroutines.runBlocking { Graph.get(app).db.transfers().get(2) }?.status,
                )
                compose.onNodeWithContentDescription("Settings").performClick()
                waitFor("Text-only removal confirmation")
                compose.onNodeWithText("On. DriveLift lists the files", substring = true).assertExists()
                compose.onNodeWithContentDescription("Back to transfers").performClick()
                pump(6)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun withoutMediaManagementAndroidsDialogIsKept() {
        grantMedia()
        Graph.get(app).prefs.setAutoRemove(false)
        Graph.get(app).canManageMedia = { false }
        seedTransfers(row(2, "IMG_0002.jpg", TransferStatus.VERIFIED))
        eachVariant(
            "removal-system", expect = null, tab = AppViewModel.TAB_TRANSFERS, capture = false,
            act = {
                waitFor("Remove 1 verified file from phone")
                compose.onNodeWithContentDescription("Settings").performClick()
                waitFor("Text-only removal confirmation")
                compose.onNodeWithText("Off. Android's confirmation shows a preview", substring = true).assertExists()
                compose.onNodeWithContentDescription("Back to transfers").performClick()
                waitFor("Remove 1 verified file from phone")
                compose.onNodeWithText("Remove 1 verified file from phone").performClick()
                pump(10)
                assertTrue(
                    "Without the access DriveLift must not show its own dialog",
                    compose.onAllNodesWithText("Remove 1 file from this phone?").fetchSemanticsNodes().isEmpty(),
                )
            },
        ) { vm -> AppRoot(vm) }
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
        eachVariant(
            "drive-listing", expect = "Scan_0042.jpg", connected = true, tab = AppViewModel.TAB_DRIVE,
            act = {
                waitFor("Scan_0042.jpg")
                pump(50)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveImageViewer() {
        grantMedia()
        eachVariant(
            "drive-image-viewer", expect = null, connected = true, tab = AppViewModel.TAB_DRIVE,
            act = {
                waitFor("Scan_0042.jpg")
                compose.onNodeWithText("Scan_0042.jpg").performClick()
                settle {
                    compose.onAllNodesWithContentDescription("Close viewer").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Loading image preview").fetchSemanticsNodes().isEmpty()
                }
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveUnsupportedViewer() {
        grantMedia()
        eachVariant(
            "drive-unsupported-viewer", expect = "Open in Google Drive", connected = true, tab = AppViewModel.TAB_DRIVE,
            act = {
                waitFor("Packing list.pdf")
                compose.onNodeWithText("Packing list.pdf").performClick()
                waitFor("Open in Google Drive")
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun videoControls() {
        eachVariant("video-controls", expect = "Original  ·  3840x2160  ·  60 fps") { _ ->
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                VideoControls(
                    state = VideoUiState(
                        playing = true, position = 83_000, duration = 754_000,
                        speed = 1.5f, quality = qualityLabel(3840, 2160, 60f),
                    ),
                    onPlayPause = {}, onSeek = {}, onSpeed = {}, onCapture = {},
                    modifier = androidx.compose.ui.Modifier
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                        .padding(Tokens.Edge),
                )
            }
        }
    }

    @Test
    fun driveVideoError() {
        grantMedia()
        eachVariant(
            "drive-video-error",
            expect = "Could not play video",
            connected = true,
            tab = AppViewModel.TAB_DRIVE,
            act = {
                waitFor("Trip to the coast.mp4")
                compose.onNodeWithText("Trip to the coast.mp4").performClick()
                waitFor("Could not play video")
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * A Google Vids render that ran past the 15 minute wait. The card says Drive is still
     * preparing the video, not "Connection problem" (finding 3 of the Preview 27 audit).
     */
    @Test
    fun driveVidsStillPreparing() {
        grantMedia()
        val vids = DriveFile("v1", "Untitled video", com.drivelift.app.drive.GOOGLE_VIDS_MIME, 0, null, false)
        eachVariant(
            "drive-vids-still-preparing",
            expect = com.drivelift.app.drive.VIDS_STILL_PREPARING,
            connected = true,
            tab = AppViewModel.TAB_DRIVE,
            drive = FakeDrive(
                list = { id, _ -> if (id == DriveClient.ROOT) listOf(vids) else emptyList() },
                videoError = com.drivelift.app.drive.VidsRenderException(
                    com.drivelift.app.drive.VIDS_STILL_PREPARING,
                    operationStillValid = true,
                ),
            ),
            act = {
                waitFor("Untitled video")
                compose.onNodeWithText("Untitled video").performClick()
                waitFor("Could not play video")
                waitFor(com.drivelift.app.drive.VIDS_STILL_PREPARING)
                assertTrue(compose.onAllNodesWithText("Connection problem", substring = true).fetchSemanticsNodes().isEmpty())
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveViewerAuthExpired() {
        grantMedia()
        eachVariant(
            "drive-viewer-auth-expired",
            expect = "Google Drive authorization expired",
            connected = true,
            tab = AppViewModel.TAB_DRIVE,
            drive = FakeDrive(failAuthOnPreview = true),
            act = {
                waitFor("Scan_0042.jpg")
                compose.onNodeWithText("Scan_0042.jpg").performClick()
                waitFor("Reconnect")
            },
        ) { vm -> AppRoot(vm) }
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
            act = {
                openDestinationSheet()
                waitFor("Choose a Drive folder")
            },
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
            "folder-picker", expect = "Move here: My Drive", connected = true,
            act = {
                openDestinationSheet()
                tap("Choose a Drive folder")
                giveSystemBars(GESTURE_NAV_DP)
                assertClearOfSystemBars("Move here: My Drive", GESTURE_NAV_DP)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun folderPickerEmptyFolder() {
        grantMedia()
        eachVariant(
            "folder-picker-empty", expect = "Move here: Archive 2024", connected = true,
            act = {
                openDestinationSheet()
                tap("Choose a Drive folder")
                tap("Archive 2024")
                giveSystemBars(GESTURE_NAV_DP)
                assertClearOfSystemBars("Move here: Archive 2024", GESTURE_NAV_DP)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun newFolderDialog() {
        grantMedia()
        eachVariant(
            "new-folder", expect = null, connected = true, tab = AppViewModel.TAB_DRIVE, overlay = true,
            act = { openNewFolderDialog() },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun newFolderDialogError() {
        grantMedia()
        eachVariant(
            "new-folder-error", expect = null, connected = true,
            tab = AppViewModel.TAB_DRIVE, overlay = true,
            drive = FakeDrive(create = {
                createCalls.incrementAndGet()
                throw IOException("network unavailable")
            }),
            act = {
                val create = openNewFolderDialog()
                val before = createCalls.get()
                touchLatestDialog(create)
                pump(DIALOG_FRAMES)
                assertEquals("Create was not pressed", before + 1, createCalls.get())
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun moveHereIsTappableAboveTheNavigationArea() {
        grantMedia()
        val configs = listOf(
            Triple(STATUS_BAR_DP, GESTURE_NAV_DP, false),
            Triple(STATUS_BAR_DP, THREE_BUTTON_NAV_DP, false),
            Triple(TALL_CUTOUT_STATUS_DP, THREE_BUTTON_NAV_DP, true),
        )
        for ((statusDp, navDp, enterEmpty) in configs) {
            eachVariant(
                "picker-tap-$statusDp-$navDp-$enterEmpty", expect = null, connected = true, capture = false,
                act = {
                    openDestinationSheet()
                    tap("Choose a Drive folder")
                    val buttonLabel = if (enterEmpty) {
                        tap("Archive 2024")
                        "Move here: Archive 2024"
                    } else {
                        "Move here: My Drive"
                    }
                    giveSystemBars(navDp, statusDp)
                    assertClearOfSystemBars(buttonLabel, navDp, statusDp)
                    // Real pointer tap near the bottom edge of the actionable Move here button.
                    compose.onNode(hasText(buttonLabel) and hasClickAction()).performTouchInput {
                        click(Offset(center.x, bottom - 4f))
                    }
                    pump(DIALOG_FRAMES)
                    assertTrue(
                        "The picker is still open after Move here",
                        compose.onAllNodesWithText(buttonLabel).fetchSemanticsNodes().isEmpty(),
                    )
                    val queued = runBlocking { Graph.get(app).db.transfers().count(listOf(TransferStatus.QUEUED.name)) }
                    assertEquals("Move here did not queue the two selected files", 2, queued)
                },
            ) { vm -> AppRoot(vm) }
        }
    }

    @Test
    fun pickerCloseReturnsToDestinationSheetWithSelection() {
        grantMedia()
        eachVariant(
            "picker-close", expect = null, connected = true, capture = false,
            act = {
                openDestinationSheet()
                tap("Choose a Drive folder")
                tap("Archive 2024")
                giveSystemBars(THREE_BUTTON_NAV_DP, TALL_CUTOUT_STATUS_DP)
                assertClearOfSystemBars("Move here: Archive 2024", THREE_BUTTON_NAV_DP, TALL_CUTOUT_STATUS_DP)
                // System Back from subfolder returns to My Drive, then Back again returns to DestinationSheet.
                currentActivity?.onBackPressedDispatcher?.onBackPressed()
                waitFor("Move here: My Drive")
                currentActivity?.onBackPressedDispatcher?.onBackPressed()
                waitFor("Move 2 items to Drive")
                assertEquals(2, currentVm?.selection?.value?.size)

                // Re-enter picker and tap Close (X) also returns to DestinationSheet with selection intact.
                tap("Choose a Drive folder")
                waitFor("Move here: My Drive")
                compose.onNodeWithContentDescription("Close").performClick()
                waitFor("Move 2 items to Drive")
                assertEquals(2, currentVm?.selection?.value?.size)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun destinationSheetActionsClearOfNavigation() {
        grantMedia()
        seedPresets()
        eachVariant(
            "destination-clear", expect = null, connected = true, overlay = true, capture = false,
            act = {
                openDestinationSheet()
                giveSystemBars(THREE_BUTTON_NAV_DP, TALL_CUTOUT_STATUS_DP)
                waitFor("Choose a Drive folder")
                val buttonBounds = compose.onNode(hasText("Choose a Drive folder") and hasClickAction())
                    .fetchSemanticsNode().boundsInRoot
                val sheetWindowHeight = compose.onNode(
                    isRoot() and hasAnyDescendant(hasText("Choose a Drive folder"))
                ).fetchSemanticsNode().size.height
                assertTrue(
                    "\"Choose a Drive folder\" ends at ${buttonBounds.bottom} px inside nav area ${sheetWindowHeight - px(THREE_BUTTON_NAV_DP)} px",
                    buttonBounds.bottom <= sheetWindowHeight - px(THREE_BUTTON_NAV_DP) + 0.5f,
                )
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun settings() {
        grantMedia()
        eachVariant(
            "settings", expect = "Offer removal when verified", tab = AppViewModel.TAB_TRANSFERS,
            act = {
                waitFor("Nothing in transit")
                compose.onNodeWithContentDescription("Settings").performClick()
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Settings names who confirms removal according to Media management access (issue #25 follow-up). */
    @Test
    fun settingsRemovalWordingFollowsMediaManagementAccess() {
        grantMedia()
        for (granted in listOf(false, true)) {
            Graph.get(app).canManageMedia = { granted }
            eachVariant(
                "settings-wording-$granted", expect = null, tab = AppViewModel.TAB_TRANSFERS, capture = false,
                act = {
                    waitFor("Nothing in transit")
                    compose.onNodeWithContentDescription("Settings").performClick()
                    waitFor("Offer removal when verified")
                    compose.onNodeWithText(autoRemoveDescription(granted)).assertExists()
                    assertTrue(
                        "The other access state's wording must not show",
                        compose.onAllNodesWithText(autoRemoveDescription(!granted)).fetchSemanticsNodes().isEmpty(),
                    )
                    // Settings is a plain scrolling column, so the About note is composed without
                    // scrolling. (Scrolling animates, and the frozen test clock would never finish it.)
                    compose.onNodeWithText(REMOVAL_ABOUT).assertExists()
                    assertTrue(
                        "No text may claim Android always confirms",
                        compose.onAllNodesWithText("always asks Android", substring = true).fetchSemanticsNodes().isEmpty(),
                    )
                },
            ) { vm -> AppRoot(vm) }
        }
    }

    @Test
    fun lockScreen() {
        eachVariant("lock-screen", expect = "DriveLift is locked") { _ ->
            LockedScreen(state = AppLock.State(), onUnlock = {}, onEnroll = {})
        }
    }

    @Test
    fun lockScreenNotEnrolled() {
        eachVariant("lock-not-enrolled", expect = "Set up fingerprint or face") { _ ->
            LockedScreen(state = AppLock.State(problem = LockProblem.NotEnrolled), onUnlock = {}, onEnroll = {})
        }
    }

    @Test
    fun buildIdentifierIsVisibleInSettings() {
        grantMedia()
        eachVariant(
            "build-id", expect = "DriveLift 1.0.0-preview.41 (build 40)",
            tab = AppViewModel.TAB_TRANSFERS, capture = false,
            act = {
                waitFor("Nothing in transit")
                compose.onNodeWithContentDescription("Settings").performClick()
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue 13: the two preferences left Transfers for Settings without changing what they store
     * or do. Checked at standard size, 1.5x text and 320 dp.
     */
    @Test
    fun settingsHoldTransferPreferencesThatPersistAndKeepTheirEffect() {
        grantMedia()
        seedTransfers(row(1, "IMG_0001.jpg", TransferStatus.QUEUED))
        val prefs = Graph.get(app).prefs
        eachVariant(
            "settings-flow", expect = null, tab = AppViewModel.TAB_TRANSFERS, capture = false,
            act = {
                waitFor("Files")
                assertTrue(
                    "Transfers must no longer show the preference switches",
                    compose.onAllNodesWithText("Upload on Wi-Fi only").fetchSemanticsNodes().isEmpty(),
                )
                compose.onNodeWithContentDescription("Settings")
                    .assertWidthIsAtLeast(Tokens.TouchTarget)
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
                    .performClick()
                waitFor("Upload on Wi-Fi only")
                assertTrue(
                    "The tab bar hides while Settings is open, as inside a folder",
                    compose.onAllNodesWithText("Phone").fetchSemanticsNodes().isEmpty(),
                )

                // Defaults are unchanged: Wi-Fi only off, offer removal on.
                compose.onNodeWithText("Upload on Wi-Fi only").assertIsOff().performClick()
                compose.waitUntil(5_000) { pump(1); prefs.wifiOnly.value }
                compose.onNodeWithText("Upload on Wi-Fi only").assertIsOn()
                // Same effect as before: queued uploads are rescheduled to wait for an unmetered network.
                compose.waitUntil(5_000) {
                    pump(1)
                    WorkManager.getInstance(app).getWorkInfosForUniqueWork("drivelift-upload").get()
                        .any { it.constraints.requiredNetworkType == NetworkType.UNMETERED }
                }
                compose.onNodeWithText("Offer removal when verified").assertIsOn().performClick()
                compose.waitUntil(5_000) { pump(1); !prefs.autoRemove.value }

                // Stored, not only in memory: a fresh reader (as after a restart) sees both values.
                val reread = Prefs(app)
                assertTrue(reread.wifiOnly.value)
                assertTrue(!reread.autoRemove.value)

                // Back returns to Transfers, with the tab bar.
                currentActivity!!.onBackPressedDispatcher.onBackPressed()
                waitFor("Files")
                compose.onNodeWithText("Phone").assertExists()

                // Reopening shows the stored values.
                compose.onNodeWithContentDescription("Settings").performClick()
                waitFor("Upload on Wi-Fi only")
                compose.onNodeWithText("Upload on Wi-Fi only").assertIsOn()
                compose.onNodeWithText("Offer removal when verified").assertIsOff()
                compose.onNodeWithContentDescription("Back to transfers").performClick()
                waitFor("Files")

                prefs.setWifiOnly(false)
                prefs.setAutoRemove(true)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun pickerRestoresSelectionAfterProcessRecreation() {
        grantMedia()
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        compose.mainClock.autoAdvance = false

        val initialVm = AppViewModel(app).apply {
            pump(12)
            driveFolders = FakeDrive()
            driveConn.value = DriveConn.CONNECTED
            account.value = DriveAbout("Test User", "test.user@example.com", 21_000_000_000, 100_000_000_000)
        }
        fun <T> launchWithRegistry(
            vm: AppViewModel,
            registry: SaveableStateRegistry,
            block: () -> T,
        ): T = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                currentActivity = activity
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                                AppRoot(vm)
                            }
                        }
                    }
                }
            }
            block()
        }

        val initialRegistry = SaveableStateRegistry(restoredValues = null) { true }
        val saved = launchWithRegistry(initialVm, initialRegistry) {
            openDestinationSheet()
            tap("Choose a Drive folder")
            waitFor("Move here: My Drive")
            assertEquals(2, initialVm.selection.value.size)
            initialRegistry.performSave()
        }

        // Simulate process death: create a fresh ViewModel (empty selection) and restore only the
        // saved state bundle into a newly launched Activity composition.
        val restoredVm = AppViewModel(app).apply {
            pump(12)
            driveFolders = FakeDrive()
            driveConn.value = DriveConn.CONNECTED
            account.value = DriveAbout("Test User", "test.user@example.com", 21_000_000_000, 100_000_000_000)
        }
        assertTrue(restoredVm.selection.value.isEmpty())
        val restoredRegistry = SaveableStateRegistry(restoredValues = saved) { true }

        launchWithRegistry(restoredVm, restoredRegistry) {
            waitFor("Move here: My Drive")
            assertEquals(
                "Selection was not restored alongside saved picker navigation",
                2,
                restoredVm.selection.value.size,
            )
            compose.onNode(hasText("Move here: My Drive") and hasClickAction()).performClick()
            pump(DIALOG_FRAMES)
            val queued = runBlocking { Graph.get(app).db.transfers().count(listOf(TransferStatus.QUEUED.name)) }
            assertEquals("Move here did not queue restored selection after recreation", 2, queued)
        }
        currentActivity = null
    }

    @Test
    fun startTransferWaitsForInFlightMediaScan() {
        grantMedia()
        val gate = CountDownLatch(1)
        FakeMediaStore.gate = gate
        val vm = AppViewModel(app)
        pump(12)
        // Media scan is still blocked on the gate, so mediaLoaded is false and media is empty.
        assertTrue(vm.media.value.isEmpty())
        vm.setSelected(listOf(1L, 3L), true)
        vm.startTransfer(Destination.Auto)
        pump(6)

        // Release the media scan gate and verify the deferred transfer enqueues both files.
        gate.countDown()
        settle {
            runBlocking { Graph.get(app).db.transfers().count(listOf(TransferStatus.QUEUED.name)) } == 2
        }
        val queued = runBlocking { Graph.get(app).db.transfers().count(listOf(TransferStatus.QUEUED.name)) }
        assertEquals(2, queued)
    }

    // ------------------------------------------------------------------ storage insights (issue #42)

    private fun openStorage() {
        waitFor("Storage")
        compose.onNodeWithTag("storage-entry").press()
        waitFor("Photos and videos")
    }

    private fun scrollStorageTo(text: String) = scrollStorageTo(hasText(text, substring = true))

    /**
     * Scrolls the Storage list until a node matching [target] sits in its upper part, so a panel
     * is captured whole and above the cleanup bar. performScrollToNode animates the lazy list, and
     * that animation never ends while the test clock is paused, so this jumps one item at a time
     * instead (scrollToItem, no animation). At the end of the list it stops where it can.
     */
    private fun scrollStorageTo(target: SemanticsMatcher) {
        val list = compose.onNodeWithTag("storage-list")
        fun targetTop(): Float? {
            val bounds = list.fetchSemanticsNode().boundsInRoot
            return compose.onAllNodes(target).fetchSemanticsNodes()
                .map { it.boundsInRoot.top }
                .filter { it >= bounds.top && it < bounds.bottom }
                .minOrNull()
        }
        var index = 1
        var previous: Float? = null
        while (true) {
            val bounds = list.fetchSemanticsNode().boundsInRoot
            val top = targetTop()
            if (top != null && (top <= bounds.top + bounds.height * 0.4f || top == previous)) break
            previous = top
            // The screen has a few dozen items at most; past the last one the scroll itself fails.
            assertTrue("$target is not in the Storage list", index < 100)
            list.performScrollToIndex(index++)
            pump(2)
        }
        pump(4)
    }

    /** Runs the duplicate check and waits for its result. */
    private fun checkDuplicates() {
        scrollStorageTo("Check for duplicates")
        tap("Check for duplicates")
        waitFor("1 group of files with matching content.")
        showDuplicateGroup()
    }

    /**
     * Scrolls the first duplicate group as high as the list allows (scrollToItem by its key, no
     * animation). Called again after a copy is ticked: the cleanup bar shortens the list.
     */
    private fun showDuplicateGroup() {
        val key = currentVm!!.duplicateGroups.value.first().key
        compose.onNodeWithTag("storage-list").performScrollToKey("group-$key")
        pump(4)
    }

    /** Taps Move to trash inside the cleanup dialog, not the one in the bar behind it. */
    private fun confirmCleanup() {
        compose.onNode(hasText("Move to trash") and hasAnyAncestor(hasTestTag("duplicate-cleanup"))).press()
    }

    /** Takes a seeded transfer row out again, so the next variant starts without it. */
    private fun dropTransfer(id: Long) = runBlocking {
        Graph.get(app).db.transfers().cancel(id)
        Graph.get(app).db.transfers().clearFinished()
    }

    @Test
    fun storageBreakdown() {
        grantMedia()
        eachVariant(
            "storage-breakdown", expect = "Photos and videos", connected = true,
            act = {
                currentVm!!.accountReadAt.value = FIXED_NOW
                openStorage()
                compose.onNodeWithText("Phone photos and videos DriveLift can see", substring = true).assertExists()
                compose.onNodeWithText("Photos").assertExists()
                compose.onNodeWithText("Videos").assertExists()
                compose.onNodeWithText("Listed at", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    // ------------------------------------------------------------------ Drive folder details (issue #42)

    private fun waitForPart(text: String) =
        settle { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** Opens Storage, scrolls to the Drive folders section and, if asked, starts the count. */
    private fun openDriveFolders(count: Boolean) {
        currentVm!!.driveScanRetryDelayMs = 0
        openStorage()
        scrollStorageTo(hasText("Google Drive folders"))
        if (count) tap("Count Drive folders")
    }

    @Test
    fun storageDriveFoldersNotCounted() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-idle", expect = "Count Drive folders", connected = true, drive = CountedDrive(),
            act = {
                openDriveFolders(count = false)
                compose.onNodeWithText("Read only: nothing on Drive is changed.", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFoldersCounting() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-counting", expect = "Counting My Drive: 40 items read", connected = true,
            drive = CountedDrive(hangAtPage = 2),
            act = {
                openDriveFolders(count = true)
                waitFor("Counting My Drive: 40 items read")
                compose.onNodeWithText("Stop").assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFoldersCounted() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-counted", expect = "Count again", connected = true, drive = CountedDrive(),
            act = {
                openDriveFolders(count = true)
                waitForPart("Counted at")
                val done = currentVm!!.driveFolderScan.value as AppViewModel.DriveFolderScan.Done
                assertTrue(done.complete)
                assertEquals(CountedDrive.COUNTED_FILES, done.tree.root.fileCount)
                assertEquals(CountedDrive.COUNTED_BYTES, done.tree.root.bytes)
                assertEquals(TEST_EMAIL, done.account)
                for (text in listOf(
                    DRIVE_FOLDERS_SUBFOLDERS,
                    "5 files report no size",
                    "1 shortcut is counted as a link",
                    "of this is in files other people own",
                    "2 items shared with you but not added to My Drive are not counted.",
                    "not the account quota below, which Drive, Gmail and Google Photos share",
                )) compose.onNodeWithText(text, substring = true).assertExists()
                scrollStorageTo(hasText("Google Drive folders"))
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFoldersShowMore() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-more", expect = "Recipes", connected = true, drive = CountedDrive(),
            act = {
                openDriveFolders(count = true)
                waitForPart("Counted at")
                scrollStorageTo(hasTestTag("drive-folder-list"))
                // Eight folders first, the largest at the top; the other three behind the button.
                compose.onNodeWithText("Camera uploads").assertExists()
                compose.onNodeWithText("Recipes").assertDoesNotExist()
                compose.onNodeWithTag("drive-folders-more").press()
                waitFor("Recipes")
                compose.onNodeWithTag("drive-folders-more").assertDoesNotExist()
                scrollStorageTo(hasTestTag("drive-folder-list"))
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFolderOpened() {
        grantMedia()
        eachVariant(
            "storage-drive-folder-open", expect = "Back to My Drive", connected = true, drive = CountedDrive(),
            act = {
                openDriveFolders(count = true)
                waitForPart("Counted at")
                scrollStorageTo(hasTestTag("drive-folder-list"))
                compose.onNodeWithText("Camera uploads").press()
                waitFor("Back to My Drive")
                compose.onNodeWithText("Inside Camera uploads").assertExists()
                compose.onNodeWithText("Files directly in Camera uploads").assertExists()
                compose.onNodeWithText("2026").assertExists()
                // The summary above names the open folder and its totals.
                scrollStorageTo(hasText("Google Drive folders"))
                compose.onNodeWithText("My Drive / Camera uploads").assertExists()
                // Back on the list returns to My Drive.
                scrollStorageTo(hasTestTag("drive-folder-list"))
                compose.onNodeWithText("Back to My Drive").press()
                settle { currentVm!!.driveFolderOpen.value == null }
                compose.onNodeWithText("Camera uploads").press()
                waitFor("Back to My Drive")
                scrollStorageTo(hasTestTag("drive-folder-list"))
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFoldersIncompleteAfterConnectionError() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-incomplete", expect = "Count again", connected = true,
            drive = CountedDrive(failAtPage = 2),
            act = {
                openDriveFolders(count = true)
                waitForPart("Incomplete: the count stopped after 40 items.")
                val done = currentVm!!.driveFolderScan.value as AppViewModel.DriveFolderScan.Done
                assertFalse(done.complete)
                compose.onNodeWithText("Connection problem", substring = true).assertExists()
                // An incomplete count is not called counted (audit P3 on PR 50).
                compose.onNodeWithText("Partly counted at", substring = true).assertExists()
                compose.onNodeWithText("Counted at", substring = true).assertDoesNotExist()
                scrollStorageTo(hasText("Google Drive folders"))
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFoldersFailedNeedsSignIn() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-failed", expect = "Reconnect", connected = true,
            drive = CountedDrive(failRoot = AuthRequiredException()),
            act = {
                openDriveFolders(count = true)
                waitFor("Drive folders could not be counted.")
                compose.onNodeWithText("Google Drive needs you to sign in again.").assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun aSignInFailureCanBeRecoveredAfterReconnectingTheSameAccount() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-recover", expect = null, connected = true, capture = false,
            drive = CountedDrive(failRoot = AuthRequiredException()),
            act = {
                val vm = currentVm!!
                openDriveFolders(count = true)
                waitFor("Reconnect")
                compose.onNodeWithText("Try again").assertExists()
                // Reconnecting the same account clears the failure; the count can start again.
                vm.driveConn.value = DriveConn.CONNECTING
                pump(2)
                vm.driveConn.value = DriveConn.CONNECTED
                settle { vm.driveFolderScan.value == AppViewModel.DriveFolderScan.Idle }
                vm.driveFolders = CountedDrive()
                tap("Count Drive folders")
                waitForPart("Counted at")
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveFolderCountIsDroppedWhenTheAccountChangesAndStopKeepsWhatWasRead() {
        grantMedia()
        val drive = CountedDrive(hangAtPage = 2)
        eachVariant(
            "storage-drive-folders-account", expect = null, connected = true, capture = false, drive = drive,
            act = {
                val vm = currentVm!!
                openDriveFolders(count = true)
                waitFor("Counting My Drive: 40 items read")
                // The page in flight never answers here, so Stop waits for it; an account change
                // ends the count at once, without asking for another page.
                vm.stopDriveFolderScan()
                waitFor("Stopping after this page")
                Graph.get(app).prefs.accountEmail = "someone.else@example.com"
                settle { vm.driveFolderScan.value == AppViewModel.DriveFolderScan.Idle }
                Graph.get(app).prefs.accountEmail = TEST_EMAIL
                val pagesBefore = drive.pagesAsked.size
                vm.driveFolders = CountedDrive()
                vm.startDriveFolderScan()
                settle { vm.driveFolderScan.value is AppViewModel.DriveFolderScan.Done }
                Graph.get(app).prefs.accountEmail = "someone.else@example.com"
                settle { vm.driveFolderScan.value == AppViewModel.DriveFolderScan.Idle }
                waitFor("Count Drive folders")
                assertEquals("the old account's listing is not asked for again", pagesBefore, drive.pagesAsked.size)
                Graph.get(app).prefs.accountEmail = TEST_EMAIL
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun driveFolderStopKeepsWhatWasReadAsIncomplete() {
        grantMedia()
        val stopAfterSecondPage = object : DriveFolders by FakeDrive() {
            var vm: AppViewModel? = null
            override suspend fun myDriveRootId() = CountedDrive.ROOT_ID
            override suspend fun listEntriesPage(pageToken: String?): DriveEntryPage {
                val page = CountedDrive().listEntriesPage(pageToken)
                if (pageToken == "page-1") vm?.stopDriveFolderScan()
                return page
            }
        }
        eachVariant(
            "storage-drive-folders-stopped", expect = null, connected = true, capture = false, drive = stopAfterSecondPage,
            act = {
                stopAfterSecondPage.vm = currentVm
                openDriveFolders(count = true)
                waitForPart("Incomplete: you stopped the count after 40 items.")
                val done = currentVm!!.driveFolderScan.value as AppViewModel.DriveFolderScan.Done
                assertEquals(com.drivelift.app.data.ScanStop.ByUser, done.stop)
                assertEquals(40, done.items)
                compose.onNodeWithText("Partly counted at", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    // ------------------------------------------------------------------ saved Drive folder count (issue #14)

    /**
     * Saves a Drive folder count on the phone as an earlier launch would have: the first 40 items
     * of the made-up Drive, counted a day before the test clock. [stop] makes it incomplete.
     */
    private fun saveEarlierCount(stop: com.drivelift.app.data.ScanStop? = null) {
        Graph.get(app).prefs.accountEmail = TEST_EMAIL
        Graph.get(app).driveFolderStore.save(
            com.drivelift.app.data.SavedDriveCount(TEST_EMAIL, earlierTree(), 40, FIXED_NOW - DAY_MS, stop),
        )
    }

    private fun earlierTree() = com.drivelift.app.data.driveFolderTotals(
        CountedDrive.ROOT_ID, AppViewModel.MY_DRIVE, CountedDrive.ENTRIES.take(40),
    )

    private fun savedCount() = Graph.get(app).driveFolderStore.load()

    @Test
    fun storageDriveFoldersSavedCountShowsAtLaunchWhileItRefreshes() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-saved", expect = null, connected = true, drive = CountedDrive(hangAtPage = 2),
            beforeLaunch = { saveEarlierCount() },
            act = {
                val vm = currentVm!!
                // Shown at launch, before Storage is even opened: no Count tap.
                settle { vm.driveFolderScan.value is AppViewModel.DriveFolderScan.Done }
                openDriveFolders(count = false)
                waitForPart("Counting again in the background: 40 items read.")
                val running = vm.driveFolderScan.value as AppViewModel.DriveFolderScan.Running
                assertEquals(earlierTree().root.fileCount, running.previous!!.tree.root.fileCount)
                compose.onNodeWithText("Count Drive folders").assertDoesNotExist()
                compose.onNodeWithText("Count again").assertDoesNotExist()
                compose.onNodeWithText("Counted on", substring = true).assertExists()
                compose.onNodeWithText("The figures below stay until it finishes.", substring = true).assertExists()
                scrollStorageTo(hasText("Google Drive folders"))
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun aBackgroundRefreshReplacesAndSavesTheFiguresOncePerLaunch() {
        grantMedia()
        val drive = CountedDrive()
        eachVariant(
            "storage-drive-folders-refreshed", expect = null, connected = true, capture = false, drive = drive,
            beforeLaunch = { saveEarlierCount() },
            act = {
                val vm = currentVm!!
                val pagesBefore = drive.pagesAsked.size
                settle { vm.driveFolderScan.value is AppViewModel.DriveFolderScan.Done }
                assertEquals("nothing is read from Drive before Storage opens", pagesBefore, drive.pagesAsked.size)
                openDriveFolders(count = false)
                settle { (vm.driveFolderScan.value as? AppViewModel.DriveFolderScan.Done)?.at == FIXED_NOW }
                val done = vm.driveFolderScan.value as AppViewModel.DriveFolderScan.Done
                assertEquals(CountedDrive.COUNTED_FILES, done.tree.root.fileCount)
                assertEquals(CountedDrive.COUNTED_BYTES, done.tree.root.bytes)
                waitForPart("Counted at")
                // The new figures are what the next launch shows.
                settle { savedCount()?.at == FIXED_NOW }
                assertEquals(CountedDrive.COUNTED_FILES, savedCount()!!.tree.root.fileCount)
                // Leaving Storage and opening it again within ten minutes does not read Drive again.
                val pagesAfter = drive.pagesAsked.size
                compose.onNodeWithContentDescription("Back to folders").press()
                pump(8)
                openStorage()
                pump(12)
                assertEquals(pagesAfter, drive.pagesAsked.size)
                assertTrue(vm.driveFolderScan.value is AppViewModel.DriveFolderScan.Done)
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun aCountIsSavedForTheNextLaunchAndDroppedWhenTheAccountChanges() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-relaunch", expect = null, connected = true, capture = false, drive = CountedDrive(),
            act = {
                openDriveFolders(count = true)
                waitForPart("Counted at")
                settle { savedCount() != null }
                assertEquals(TEST_EMAIL, savedCount()!!.account)
                // A new launch shows the saved figures straight away.
                val relaunched = AppViewModel(app)
                settle { relaunched.driveFolderScan.value is AppViewModel.DriveFolderScan.Done }
                val shown = relaunched.driveFolderScan.value as AppViewModel.DriveFolderScan.Done
                assertEquals(CountedDrive.COUNTED_FILES, shown.tree.root.fileCount)
                assertTrue(shown.complete)
                // Another account: the saved count is deleted and not shown.
                Graph.get(app).prefs.accountEmail = "someone.else@example.com"
                settle { savedCount() == null && relaunched.driveFolderScan.value == AppViewModel.DriveFolderScan.Idle }
                val otherLaunch = AppViewModel(app)
                pump(12)
                assertEquals(AppViewModel.DriveFolderScan.Idle, otherLaunch.driveFolderScan.value)
                Graph.get(app).prefs.accountEmail = TEST_EMAIL
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun aSavedCountOfAnotherAccountIsNeverShown() {
        grantMedia()
        // Not connected, so the scenario leaves the pin as the earlier launch set it.
        eachVariant(
            "storage-drive-folders-other-account", expect = "Google Drive is not connected, so its folders are not counted.",
            capture = false, drive = CountedDrive(hangAtPage = 0),
            beforeLaunch = {
                saveEarlierCount()
                Graph.get(app).prefs.accountEmail = "someone.else@example.com"
            },
            act = {
                val vm = currentVm!!
                settle { savedCount() == null }
                assertEquals(AppViewModel.DriveFolderScan.Idle, vm.driveFolderScan.value)
                openDriveFolders(count = false)
                Graph.get(app).prefs.accountEmail = TEST_EMAIL
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFoldersRecountFailureKeepsTheSavedFigures() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-recount-failed", expect = null, connected = true, drive = CountedDrive(failAtPage = 1),
            beforeLaunch = { saveEarlierCount() },
            act = {
                val vm = currentVm!!
                openDriveFolders(count = false)
                waitForPart("The recount did not finish.")
                val done = vm.driveFolderScan.value as AppViewModel.DriveFolderScan.Done
                assertEquals(FIXED_NOW - DAY_MS, done.at)
                assertEquals(earlierTree().root.fileCount, done.tree.root.fileCount)
                assertTrue(done.refreshMiss!!.stop is com.drivelift.app.data.ScanStop.Error)
                compose.onNodeWithText("Connection problem", substring = true).assertExists()
                compose.onNodeWithText("These figures are from the earlier count.", substring = true).assertExists()
                compose.onNodeWithText("Count again").assertExists()
                // The saved count is not replaced by the part the recount read.
                assertEquals(FIXED_NOW - DAY_MS, savedCount()!!.at)
                scrollStorageTo(hasText("Google Drive folders"))
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun aRecountThatNeedsSignInKeepsTheFiguresAndOffersReconnect() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-recount-signin", expect = null, connected = true, capture = false,
            drive = CountedDrive(failRoot = AuthRequiredException()),
            beforeLaunch = { saveEarlierCount() },
            act = {
                openDriveFolders(count = false)
                waitForPart("The recount did not finish. Google Drive needs you to sign in again.")
                compose.onNodeWithText("Reconnect").assertExists()
                compose.onNodeWithText("Counted on", substring = true).assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun anIncompleteRecountReplacesAnIncompleteSavedCount() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-recount-partial", expect = null, connected = true, capture = false,
            drive = CountedDrive(failAtPage = 3),
            beforeLaunch = { saveEarlierCount(stop = com.drivelift.app.data.ScanStop.ByUser) },
            act = {
                val vm = currentVm!!
                openDriveFolders(count = false)
                settle { (vm.driveFolderScan.value as? AppViewModel.DriveFolderScan.Done)?.at == FIXED_NOW }
                val done = vm.driveFolderScan.value as AppViewModel.DriveFolderScan.Done
                assertEquals(60, done.items)
                assertEquals(null, done.refreshMiss)
                waitForPart("Partly counted at")
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDriveFoldersSavedCountShowsWhileDriveIsNotConnected() {
        grantMedia()
        eachVariant(
            "storage-drive-folders-saved-offline", expect = null, connected = false, drive = CountedDrive(),
            beforeLaunch = { saveEarlierCount() },
            act = {
                openDriveFolders(count = false)
                waitForPart("These are the figures saved from the last count.")
                compose.onNodeWithText("Counted on", substring = true).assertExists()
                compose.onNodeWithText("Count again").assertDoesNotExist()
                scrollStorageTo(hasText("Google Drive folders"))
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * A made-up My Drive for the folder count: eleven top-level folders, nested years, Google
     * files with no size, a shortcut, files someone else owns, loose files and two items shared
     * from outside My Drive. Served [PAGE] items per page. No network.
     */
    private class CountedDrive(
        private val failRoot: Exception? = null,
        private val failAtPage: Int = -1,
        private val hangAtPage: Int = -1,
    ) : DriveFolders by FakeDrive() {
        val pagesAsked: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())

        override suspend fun myDriveRootId(): String {
            failRoot?.let { throw it }
            return ROOT_ID
        }

        override suspend fun listEntriesPage(pageToken: String?): DriveEntryPage {
            pagesAsked += pageToken
            val index = pageToken?.removePrefix("page-")?.toInt() ?: 0
            if (index == hangAtPage) awaitCancellation()
            if (index == failAtPage) throw IOException("timeout")
            val next = if ((index + 1) * PAGE < ENTRIES.size) "page-${index + 1}" else null
            return DriveEntryPage(ENTRIES.drop(index * PAGE).take(PAGE), next)
        }

        companion object {
            const val ROOT_ID = "drive-root"
            const val PAGE = 20
            private const val OUTSIDE = "someone-elses-folder"

            val ENTRIES: List<DriveEntry> = buildList {
                fun folder(id: String, name: String, parent: String) =
                    add(DriveEntry(id, name, FOLDER_MIME_TYPE, null, listOf(parent)))
                fun file(id: String, size: Long?, parent: String, mime: String = "image/jpeg", mine: Boolean = true) =
                    add(DriveEntry(id, "", mime, size, listOf(parent), mine))
                folder("cam", "Camera uploads", ROOT_ID)
                folder("cam25", "2025", "cam")
                folder("cam26", "2026", "cam")
                folder("cam26trip", "Coast trip", "cam26")
                repeat(6) { file("c25-$it", 180_000_000L + it * 7_000_000L, "cam25", "video/mp4") }
                repeat(9) { file("c26-$it", 4_200_000L + it * 310_000L, "cam26") }
                repeat(4) { file("trip-$it", 96_000_000L, "cam26trip", "video/mp4") }
                file("cam-loose", 3_100_000, "cam")
                folder("docs", "Documents", ROOT_ID)
                repeat(5) { file("gdoc-$it", null, "docs", "application/vnd.google-apps.document") }
                repeat(3) { file("pdf-$it", 1_250_000L, "docs", "application/pdf") }
                folder("family", "Shared with family", ROOT_ID)
                repeat(4) { file("fam-$it", 22_000_000L, "family", mine = false) }
                folder("proj", "Projects", ROOT_ID)
                add(DriveEntry("short-1", "", SHORTCUT_MIME_TYPE, null, listOf("proj")))
                file("proj-1", 640_000, "proj", "application/zip")
                listOf(
                    "Archive 2024" to 48_000_000L, "Music" to 31_000_000L, "Receipts" to 900_000L,
                    "Scans" to 12_500_000L, "Wallpapers" to 8_800_000L, "Old phone backup" to 410_000_000L,
                    "Recipes" to 210_000L,
                ).forEachIndexed { i, (name, bytes) ->
                    folder("misc$i", name, ROOT_ID)
                    file("misc$i-a", bytes / 2, "misc$i")
                    file("misc$i-b", bytes - bytes / 2, "misc$i")
                }
                file("root-1", 2_400_000, ROOT_ID)
                file("root-2", 760_000, ROOT_ID, "application/pdf")
                file("outside-1", 55_000_000, OUTSIDE)
                file("outside-2", 1_000, OUTSIDE)
            }

            /** Files inside My Drive: everything but folders and the two outside items. */
            val COUNTED_FILES = ENTRIES.count { !it.isFolder && OUTSIDE !in it.parents }
            val COUNTED_BYTES = ENTRIES.filter { !it.isFolder && OUTSIDE !in it.parents }.sumOf { it.size ?: 0L }
        }
    }

    @Test
    fun storageQuotaAndLargestFiles() {
        grantMedia()
        eachVariant(
            "storage-large-files", expect = "Largest files", connected = true,
            act = {
                currentVm!!.accountReadAt.value = FIXED_NOW
                openStorage()
                scrollStorageTo("used of")
                compose.onNodeWithText("used of", substring = true).assertExists()
                compose.onNodeWithText("Read at", substring = true).assertExists()
                // Quota at the top, the largest files below it.
                scrollStorageTo(hasText("Google Drive quota"))
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageDuplicateGroup() {
        grantMedia()
        eachVariant(
            "storage-duplicates", expect = "1 copy selected", connected = true,
            act = {
                openStorage()
                checkDuplicates()
                // Nothing is selected until the user picks a copy.
                assertTrue(currentVm!!.duplicateSelection.value.isEmpty())
                // The three screenshots share a size but not their content: no group for them.
                assertEquals(1, currentVm!!.duplicateGroups.value.size)
                assertEquals(
                    listOf("IMG-WA12.jpg", "IMG-WA13.jpg"),
                    currentVm!!.duplicateGroups.value.single().items.map { it.name },
                )
                compose.onNodeWithText("IMG-WA12.jpg").press()
                waitFor("Stays: one copy is always kept")
                showDuplicateGroup()
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun storageCleanupConfirmation() {
        grantMedia()
        Graph.get(app).canManageMedia = { true }
        eachVariant(
            "storage-cleanup-confirm", expect = "Move 1 copy to this phone's trash?", connected = true, overlay = true,
            act = {
                openStorage()
                checkDuplicates()
                compose.onNodeWithText("IMG-WA12.jpg").press()
                waitFor("1 copy selected")
                tap("Move to trash")
                waitFor("Move 1 copy to this phone's trash?")
                compose.onNodeWithText("Staying on this phone:").assertExists()
            },
        ) { vm -> AppRoot(vm) }
    }

    /**
     * Issue #42, through the real screen: the last copy of a group cannot be picked; Cancel sends
     * nothing; a confirmed cleanup hands Android's trash request exactly the selected copy; a copy
     * whose bytes change after the check, or that becomes part of a transfer, is not sent.
     */
    @Test
    fun storageCleanupSendsExactlyTheCheckedCopyAndProtectsTheRest() {
        grantMedia()
        Graph.get(app).canManageMedia = { true }
        val asked = recordTrashRequests()
        val wa12 = FakeMediaStore.files.first { it.name == "IMG-WA12.jpg" }
        val wa13 = FakeMediaStore.files.first { it.name == "IMG-WA13.jpg" }
        val original = Graph.get(app).openOriginalStream
        eachVariant(
            "storage-cleanup-flow", expect = null, connected = true, capture = false,
            act = {
                asked.clear()
                Graph.get(app).openOriginalStream = original
                val vm = currentVm!!
                openStorage()
                checkDuplicates()

                // One copy picked; the other cannot be: every group keeps a copy.
                compose.onNodeWithText("IMG-WA12.jpg").press()
                waitFor("1 copy selected")
                vm.toggleDuplicate(wa13.id)
                pump(4)
                assertEquals(setOf(wa12.id), vm.duplicateSelection.value)

                // Cancel: nothing asked of Android, the selection stays.
                tap("Move to trash")
                waitFor("Move 1 copy to this phone's trash?")
                tap("Cancel")
                pump(DIALOG_FRAMES)
                assertTrue(asked.isEmpty())
                assertEquals(setOf(wa12.id), vm.duplicateSelection.value)

                // Confirm: exactly IMG-WA12, after both copies were read again.
                tap("Move to trash")
                waitFor("Move 1 copy to this phone's trash?")
                confirmCleanup()
                settle { vm.lastCleanupSent != null && vm.duplicateCleanup.value == null }
                pump(DIALOG_FRAMES)
                assertEquals(listOf(wa12.id), vm.lastCleanupSent)
                assertEquals(listOf(listOf(wa12.uri.toString())), asked)

                // The selected copy's bytes change between the confirmation dialog and the tap.
                asked.clear()
                tap("Move to trash")
                waitFor("Move 1 copy to this phone's trash?")
                Graph.get(app).openOriginalStream = { context, uri ->
                    if (uri == wa12.uri) ByteArrayInputStream(byteArrayOf(1, 2, 3))
                    else checkNotNull(context.contentResolver.openInputStream(uri))
                }
                confirmCleanup()
                settle { vm.duplicateCleanup.value == null }
                pump(DIALOG_FRAMES)
                assertEquals(emptyList<Long>(), vm.lastCleanupSent)
                assertTrue(asked.isEmpty())

                // The selected copy becomes part of a transfer before the plan is made: left out.
                Graph.get(app).openOriginalStream = original
                seedTransfers(row(wa12.id, wa12.name, TransferStatus.QUEUED, size = wa12.size))
                pump(4)
                tap("Move to trash")
                waitFor("Nothing can go to the trash")
                compose.onNodeWithText("Left out because they are part of a transfer:").assertExists()
                tap("OK")
                pump(DIALOG_FRAMES)
                assertTrue(asked.isEmpty())
                vm.duplicateSelection.value = emptySet()
                dropTransfer(wa12.id)
                pump(4)
            },
        ) { vm ->
            PhoneTrashHost(vm)
            AppRoot(vm)
        }
    }

    /**
     * Review of 0a06df1 (P1, P2): the cleanup's last check happens at the trash handoff, after the
     * slow re-reads. Each case changes something while the selected copy is being read again on
     * Confirm; the copy must never reach Android's trash request.
     */
    @Test
    fun storageCleanupChecksTransfersAndTheKeptCopyAtTheTrashHandoff() {
        grantMedia()
        Graph.get(app).canManageMedia = { true }
        val asked = recordTrashRequests()
        val graph = Graph.get(app)
        val wa12 = FakeMediaStore.files.first { it.name == "IMG-WA12.jpg" }
        val wa13 = FakeMediaStore.files.first { it.name == "IMG-WA13.jpg" }
        val original = graph.openOriginalStream
        eachVariant(
            "storage-cleanup-handoff", expect = null, connected = true, capture = false,
            act = {
                val vm = currentVm!!
                openStorage()
                checkDuplicates()

                /** Confirms a cleanup of IMG-WA12 and runs [during] while its bytes are being read. */
                fun confirmWhile(during: () -> Unit) {
                    asked.clear()
                    selectOnlyCopy(vm, wa12.id)
                    tap("Move to trash")
                    waitFor("Move 1 copy to this phone's trash?")
                    val reading = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    graph.openOriginalStream = { context, uri ->
                        if (uri == wa12.uri) {
                            reading.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                        checkNotNull(context.contentResolver.openInputStream(uri))
                    }
                    try {
                        confirmCleanup()
                        settle { reading.count == 0L }
                        during()
                    } finally {
                        release.countDown()
                    }
                    settle { vm.duplicateCleanup.value == null }
                    pump(DIALOG_FRAMES)
                    graph.openOriginalStream = original
                    assertEquals(emptyList<Long>(), vm.lastCleanupSent)
                    assertTrue(asked.isEmpty())
                }

                // P1: IMG-WA12 is queued for Drive after the plan read the transfers.
                confirmWhile { seedTransfers(row(wa12.id, wa12.name, TransferStatus.QUEUED, size = wa12.size)) }
                dropTransfer(wa12.id)

                // P2: the copy that stays leaves the phone (outside DriveLift).
                confirmWhile { FakeMediaStore.hidden = setOf(wa13.id) }
                // The refused cleanup listed the phone again without IMG-WA13, so its group is gone.
                // The groups follow the listing off the main thread, so wait until they show that
                // before IMG-WA13 comes back. Otherwise the old groups (from before it left) pass the
                // wait below, the late "group gone" update lands after the next case has picked
                // IMG-WA12, the selection is rightly dropped, and the cleanup bar never shows.
                settle { vm.duplicateGroups.value.none { g -> g.items.any { it.id == wa13.id } } }
                FakeMediaStore.hidden = emptySet()
                runBlocking { graph.media.refresh() }
                settle {
                    vm.duplicateGroups.value.any { g ->
                        g.items.map { it.id }.containsAll(listOf(wa12.id, wa13.id))
                    }
                }

                // P2: the copy that stays is in an open removal request (verified on Drive).
                seedTransfers(row(wa13.id, wa13.name, TransferStatus.VERIFIED, size = wa13.size))
                confirmWhile {
                    assertEquals(listOf(wa13.uri.toString()), runBlocking { graph.transfers.verifiedForRemoval(10) })
                }
                runBlocking { graph.transfers.removalFinished() }
                runBlocking { graph.db.transfers().markRemoved(listOf(wa13.uri.toString())) }
                runBlocking { graph.db.transfers().clearFinished() }
                vm.duplicateSelection.value = emptySet()
                pump(4)
            },
        ) { vm ->
            PhoneTrashHost(vm)
            AppRoot(vm)
        }
    }

    /**
     * Review of 0a06df1 (P1, P2): while Android's trash dialog is open for a cleanup, the copy
     * being trashed cannot be queued for Drive and the copy that stays cannot be put in a removal
     * request. Both are released once Android answers.
     */
    @Test
    fun aCleanupWaitingForAndroidHoldsItsFilesUntilAnswered() {
        grantMedia()
        Graph.get(app).canManageMedia = { true }
        val graph = Graph.get(app)
        val asked = mutableListOf<List<String>>()
        graph.trashRequest = { _, uris ->
            asked += uris.map { it.toString() }
            android.app.PendingIntent.getActivity(
                app, 0, android.content.Intent(app, ComponentActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val wa12 = FakeMediaStore.files.first { it.name == "IMG-WA12.jpg" }
        val wa13 = FakeMediaStore.files.first { it.name == "IMG-WA13.jpg" }
        fun rowsFor(uri: Uri) = runBlocking { graph.db.transfers().byMediaUris(listOf(uri.toString())) }
        eachVariant(
            "storage-cleanup-consent", expect = null, connected = true, capture = false,
            act = {
                asked.clear()
                val vm = currentVm!!
                openStorage()
                checkDuplicates()

                // Codex on 5ca2a2d: a cleanup confirmed while another trash dialog is open does not
                // replace that batch; it is refused and its reservation released.
                val img1 = FakeMediaStore.files.first { it.name == "IMG_0001.jpg" }
                assertTrue(vm.requestPhoneTrash(listOf(img1.id)))
                assertTrue(vm.startPendingTrash() != null)
                selectOnlyCopy(vm, wa12.id)
                tap("Move to trash")
                waitFor("Move 1 copy to this phone's trash?")
                confirmCleanup()
                settle { vm.duplicateCleanup.value == null }
                assertEquals(emptyList<Long>(), vm.lastCleanupSent)
                assertEquals(listOf(img1.id), vm.pendingTrash.value?.ids)
                assertEquals(listOf(listOf(img1.uri.toString())), asked)
                assertTrue(runBlocking { graph.phoneFileFence.whileEnqueueing { it } } == setOf(img1.uri.toString()))
                vm.pendingTrashFinished(confirmed = false)
                pump(4)
                asked.clear()

                selectOnlyCopy(vm, wa12.id)
                tap("Move to trash")
                waitFor("Move 1 copy to this phone's trash?")
                confirmCleanup()
                settle { vm.pendingTrash.value != null }
                // Android's dialog is shown and not answered yet (no trash host here).
                assertTrue(vm.startPendingTrash() != null)
                assertEquals(listOf(listOf(wa12.uri.toString())), asked)

                // The copy being trashed cannot be queued for Drive meanwhile.
                vm.tab.value = AppViewModel.TAB_PHONE
                vm.selection.value = setOf(wa12.id)
                vm.startTransfer(Destination.Auto)
                settle { vm.tab.value == AppViewModel.TAB_TRANSFERS }
                assertTrue(rowsFor(wa12.uri).isEmpty())

                // A failed transfer of the copy being trashed is not retried meanwhile.
                vm.tab.value = AppViewModel.TAB_PHONE
                seedTransfers(row(wa12.id, wa12.name, TransferStatus.FAILED, size = wa12.size, error = "Network"))
                runBlocking { graph.transfers.retryFailed() }
                assertEquals(listOf(TransferStatus.FAILED.name), rowsFor(wa12.uri).map { it.status })
                runBlocking { graph.db.transfers().clearFinished() }

                // Another trash request cannot replace the open one meanwhile.
                assertFalse(vm.requestPhoneTrash(listOf(img1.id)))
                assertEquals(listOf(wa12.id), vm.pendingTrash.value?.ids)

                // The copy that stays cannot be put in a removal request meanwhile.
                seedTransfers(row(wa13.id, wa13.name, TransferStatus.VERIFIED, size = wa13.size))
                assertEquals(emptyList<String>(), runBlocking { graph.transfers.verifiedForRemoval(10) })
                runBlocking { graph.transfers.removalFinished() }

                // Android answered: both are free again.
                vm.pendingTrashFinished(confirmed = false)
                pump(4)
                seedTransfers(row(wa12.id, wa12.name, TransferStatus.FAILED, size = wa12.size, error = "Network"))
                runBlocking { graph.transfers.retryFailed() }
                assertEquals(listOf(TransferStatus.QUEUED.name), rowsFor(wa12.uri).map { it.status })
                runBlocking {
                    graph.db.transfers().cancelAllPending()
                    graph.db.transfers().clearFinished()
                }
                assertEquals(listOf(wa13.uri.toString()), runBlocking { graph.transfers.verifiedForRemoval(10) })
                runBlocking { graph.transfers.removalFinished() }
                vm.tab.value = AppViewModel.TAB_PHONE
                vm.selection.value = setOf(wa12.id)
                vm.startTransfer(Destination.Auto)
                settle { vm.tab.value == AppViewModel.TAB_TRANSFERS }
                assertEquals(1, rowsFor(wa12.uri).size)

                runBlocking {
                    graph.db.transfers().markRemoved(listOf(wa13.uri.toString()))
                    graph.db.transfers().cancelAllPending()
                    graph.db.transfers().clearFinished()
                }
                vm.duplicateSelection.value = emptySet()
                pump(4)
            },
        ) { vm -> AppRoot(vm) }
    }

    /** Stopping the duplicate check leaves no partial result and changes nothing. */
    @Test
    fun theDuplicateCheckCanBeStopped() {
        grantMedia()
        val vm = AppViewModel(app)
        pump(12)
        compose.waitUntil(5_000) { pump(1); vm.media.value.size == FakeMediaStore.files.size }
        val release = CountDownLatch(1)
        val original = Graph.get(app).openOriginalStream
        Graph.get(app).openOriginalStream = { context, uri ->
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            original(context, uri)
        }
        try {
            vm.startDuplicateScan()
            assertTrue(vm.duplicateScan.value is AppViewModel.DuplicateScan.Running)
            vm.cancelDuplicateScan()
        } finally {
            release.countDown()
        }
        pump(12)
        assertEquals(AppViewModel.DuplicateScan.Idle, vm.duplicateScan.value)
        assertTrue(vm.duplicateGroups.value.isEmpty())

        // Codex on 30fffd1: Stop while a read is blocked inside the file. When that read returns
        // after Stop, its progress must not bring Reading back, and a new check must start.
        val reading = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        Graph.get(app).openOriginalStream = { _, _ ->
            object : java.io.InputStream() {
                private var served = false
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served) return -1
                    reading.countDown()
                    unblock.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    served = true
                    readReturned.countDown()
                    return 1
                }
            }
        }
        try {
            vm.startDuplicateScan()
            assertTrue(reading.await(10, java.util.concurrent.TimeUnit.SECONDS))
            vm.cancelDuplicateScan()
        } finally {
            unblock.countDown()
        }
        assertTrue(readReturned.await(10, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(200)
        pump(12)
        assertEquals(AppViewModel.DuplicateScan.Idle, vm.duplicateScan.value)
        Graph.get(app).openOriginalStream = original
        vm.startDuplicateScan()
        compose.waitUntil(10_000) { pump(1); vm.duplicateScan.value is AppViewModel.DuplicateScan.Done }
        Graph.get(app).openOriginalStream = original
    }

    @Test
    fun desktopDropBiometricRunning() {
        grantMedia()
        eachVariant(
            "desktop-drop-biometric-running",
            expect = "http://drivelift.local:8080",
            connected = true,
            tab = 3,
            beforeLaunch = {
                Graph.get(app).desktopDrop.controller.seedPreviewStateForTest(
                    com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot(
                        status = com.drivelift.app.feature.desktopdrop.model.ServerStatus.RUNNING,
                        mode = com.drivelift.app.feature.desktopdrop.model.ConnectivityMode.SAME_WIFI,
                        bindIp = "192.168.1.44",
                        port = 8080,
                        dropUrl = "http://192.168.1.44:8080",
                        bookmarkUrl = "http://drivelift.local:8080",
                        peers = listOf(
                            com.drivelift.app.feature.desktopdrop.model.PeerSession(
                                sessionId = "peer-1",
                                peerIp = "192.168.1.88",
                                userAgent = "Mozilla/5.0 (Windows NT 10.0) Edg/131.0",
                                deviceLabel = "Edge on Windows",
                                connectedAtMs = FIXED_NOW - 30_000L,
                                lastSeenMs = FIXED_NOW,
                                status = com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus.APPROVED,
                                trusted = true,
                            ),
                        ),
                        sharedItems = listOf(
                            com.drivelift.app.feature.desktopdrop.model.SharedDropItem(
                                id = "s-1",
                                name = "SYNTH_DROP_ARCHIVE.zip",
                                relativePath = "Exports",
                                sizeBytes = 420_000_000L,
                                mimeType = "application/zip",
                                addedAtMs = FIXED_NOW,
                            ),
                        ),
                        transfers = listOf(
                            com.drivelift.app.feature.desktopdrop.model.DropTransferProgress(
                                transferId = "x-1",
                                filename = "SYNTH_PROJECT_RAW.mkv",
                                relativePath = "",
                                direction = com.drivelift.app.feature.desktopdrop.model.TransferDirection.PC_TO_PHONE,
                                totalBytes = 2_400_000_000L,
                                transferredBytes = 1_200_000_000L,
                                speedBytesPerSec = 58_000_000L,
                                etaSeconds = 21L,
                                status = com.drivelift.app.feature.desktopdrop.model.DropTransferStatus.ACTIVE,
                                updatedAtMs = FIXED_NOW,
                            ),
                        ),
                        aggregateSpeedBytesPerSec = 58_000_000L,
                        activeTransferCount = 1,
                    ),
                )
            },
        ) { vm -> AppRoot(vm) }
    }

    @Test
    fun desktopDropBiometricKnockDialog() {
        grantMedia()
        eachVariant(
            "desktop-drop-biometric-knock-dialog",
            expect = "Match code 74-29",
            connected = true,
            tab = 3,
            overlay = true,
            beforeLaunch = {
                Graph.get(app).desktopDrop.controller.seedPreviewStateForTest(
                    com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot(
                        status = com.drivelift.app.feature.desktopdrop.model.ServerStatus.RUNNING,
                        mode = com.drivelift.app.feature.desktopdrop.model.ConnectivityMode.SAME_WIFI,
                        bindIp = "192.168.1.44",
                        port = 8080,
                        dropUrl = "http://192.168.1.44:8080",
                        bookmarkUrl = "http://drivelift.local:8080",
                        pendingApproval = com.drivelift.app.feature.desktopdrop.model.PeerSession(
                            sessionId = "peer-pending",
                            peerIp = "192.168.1.104",
                            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0",
                            deviceLabel = "Chrome on Windows",
                            connectedAtMs = FIXED_NOW,
                            lastSeenMs = FIXED_NOW,
                            status = com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus.PENDING,
                            matchCode = "74-29",
                        ),
                    ),
                )
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

    /**
     * Selects a thumbnail through its "Select" accessibility action, the same path TalkBack uses.
     * The touch path (holding a thumbnail) is covered by [phoneMediaOpensInsideDriveLift].
     */
    private fun selectCell(name: String) {
        val node = compose.onAllNodesWithContentDescription(name).onFirst().fetchSemanticsNode()
        val select = node.config[SemanticsActions.CustomActions].first { it.label == "Select" }
        compose.runOnUiThread { select.action() }
        pump(4)
    }

    private fun tap(text: String) {
        waitFor(text)
        compose.onNodeWithText(text).press()
    }

    /** Opens the Camera folder, selects a photo and a video, and asks to move them. */
    private fun openDestinationSheet() {
        waitFor("Camera")
        compose.onNodeWithText("Camera").performClick()
        waitFor("Select all")
        selectCell("IMG_0001.jpg")
        compose.onAllNodesWithContentDescription("VID_0003.mp4").onFirst().performClick()
        tap("Move to Drive")
        waitFor("Move 2 items to Drive")
    }

    /**
     * Opens the new-folder dialog and fills in a name. Returns where the Create button is.
     *
     * Once the text of a field inside a dialog window has changed, the Compose test framework
     * never reports idle again under Robolectric, so every query or action that waits for idle
     * times out. The dialog itself keeps working. So from that point the test uses no Compose
     * test call: it runs frames, touches the window directly, and captures the screen.
     */
    private fun openNewFolderDialog(): Offset {
        waitFor("DriveLift Backup")
        compose.onNodeWithContentDescription("New folder here").press()
        pump(DIALOG_FRAMES)
        waitFor("New folder in My Drive")
        val create = compose.onNodeWithText("Create").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithText("Folder name")
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Holiday 2026")) }
        pump(DIALOG_FRAMES)
        return create
    }

    /** A tap at [at] inside the dialog opened last, sent to its view without the test framework. */
    private fun composeView(view: View): View? = when {
        view.javaClass.name.endsWith("AndroidComposeView") -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { composeView(view.getChildAt(it)) }
        else -> null
    }

    private fun latestDialogView(): View =
        checkNotNull(composeView(ShadowDialog.getLatestDialog().window!!.decorView)) { "No dialog is open" }

    private fun px(dp: Int) = (dp * app.resources.displayMetrics.density).roundToInt()

    /**
     * Gives every open sheet and dialog the system bars a phone gives them: a status bar on top and a
     * navigation area of [navDp] at the bottom (about 24 dp with gesture navigation, 48 dp with
     * three buttons). The JVM has no system bars of its own, so without this a window that
     * ignores them looks fine here and is cut off on a phone.
     */
    private fun giveSystemBars(navDp: Int, statusBarDp: Int = STATUS_BAR_DP) {
        // A window is listed as shown a few frames after its content first appears.
        pump(DIALOG_FRAMES)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(statusBarDp), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(navDp)))
            .build()
        val roots = buildList {
            (currentActivity?.window?.decorView as? ViewGroup)?.let(::add)
            for (dialog in ShadowDialog.getShownDialogs()) {
                (dialog.window?.decorView as? ViewGroup)?.let(::add)
            }
        }
        // Installed on each window's root view, so every later pass of the insets through the
        // window (each layout can start one) carries these bars instead of the JVM's none. A
        // pass is also made now, for a window that has just opened and has no layout pending.
        for (decor in roots) {
            ViewCompat.setOnApplyWindowInsetsListener(decor) { root, _ ->
                val group = root as ViewGroup
                for (i in 0 until group.childCount) ViewCompat.dispatchApplyWindowInsets(group.getChildAt(i), insets)
                WindowInsetsCompat.CONSUMED
            }
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
            ViewCompat.requestApplyInsets(decor)
        }
        pump(DIALOG_FRAMES)
    }

    /** [button] and the top bar of the window it is in sit clear of the status and navigation bars. */
    private fun assertClearOfSystemBars(button: String, navDp: Int, statusBarDp: Int = STATUS_BAR_DP) {
        val windowHeight = compose.onNode(isRoot() and hasAnyDescendant(hasText(button))).fetchSemanticsNode().size.height
        val buttonBounds = compose.onNode(hasText(button) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "\"$button\" is clipped: height ${buttonBounds.height} px is smaller than a full button",
            buttonBounds.height >= px(38),
        )
        assertTrue(
            "\"$button\" starts at ${buttonBounds.top} px, inside the top bar",
            buttonBounds.top >= px(statusBarDp) - 0.5f,
        )
        val metrics = checkNotNull(currentVm?.pickerMetrics?.value) { "Picker window metrics were not recorded" }
        assertTrue(
            "Expected topInsetPx >= ${px(statusBarDp)}, got ${metrics.topInsetPx}",
            metrics.topInsetPx >= px(statusBarDp),
        )
        assertTrue(
            "Expected bottomInsetPx >= ${px(navDp)}, got ${metrics.bottomInsetPx}",
            metrics.bottomInsetPx >= px(navDp),
        )
        assertTrue(
            "\"$button\" ends at ${buttonBounds.bottom} px; the navigation area starts at ${windowHeight - px(navDp)} px (metrics=$metrics)",
            buttonBounds.bottom <= windowHeight - px(navDp) + 0.5f,
        )
        assertTrue(
            "Recorded picker metric bottom ${metrics.moveHereBottomPx} exceeds safe bottom ${windowHeight - px(navDp)}",
            metrics.moveHereBottomPx <= windowHeight - px(navDp) + 1,
        )
        assertTrue(
            "Recorded picker metric height ${metrics.moveHereHeightPx} is smaller than a full button",
            metrics.moveHereHeightPx >= px(38),
        )
        val top = listOf("Close", "Up one folder").firstNotNullOf { label ->
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        }
        assertTrue("The picker's top bar starts at $top px, under the status bar", top >= px(statusBarDp) - 0.5f)
        assertTrue(
            "The picker's top bar starts at $top px, which indicates double status-bar padding",
            top < px(statusBarDp) + px(28),
        )
    }

    private fun touchLatestDialog(at: Offset) {
        val target = latestDialogView()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, at.x, at.y, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
            pump(2)
        }
    }

    // ------------------------------------------------------------------ harness

    private fun eachVariant(
        name: String,
        /** Text that must be on screen before the capture. Null when [act] has checked the state itself. */
        expect: String?,
        connected: Boolean = false,
        connecting: Boolean = false,
        tab: Int = AppViewModel.TAB_PHONE,
        /** True when a sheet or dialog is open: those are separate windows, so the whole screen is captured. */
        overlay: Boolean = false,
        /**
         * True for the full-screen folder picker: only the window that shows [expect] is
         * captured. A whole-screen capture draws the sheet under it on top of it.
         */
        ownWindow: Boolean = false,
        drive: DriveFolders = FakeDrive(),
        /** False for a scenario that only checks behaviour and writes no image. */
        capture: Boolean = true,
        /** Runs before the view model is made, as before an app launch: for state saved on the phone. */
        beforeLaunch: (() -> Unit)? = null,
        act: (() -> Unit)? = null,
        content: @Composable (AppViewModel) -> Unit,
    ) {
        for (variant in Variant.entries) {
            // Each variant starts without a Drive folder count saved by the one before.
            Graph.get(app).driveFolderStore.clear()
            beforeLaunch?.invoke()
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            // Set on the device, not on one composition: a sheet or dialog is its own window and
            // takes its text size from the system, as it does on a phone.
            RuntimeEnvironment.setFontScale(variant.fontScale)
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
                DriveAbout("Test User", TEST_EMAIL, 21_000_000_000, 100_000_000_000)
            } else null
            // The saved account pin, which a confirmed Drive trash is bound to.
            if (connected) Graph.get(app).prefs.accountEmail = TEST_EMAIL

            currentVm = vm
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    currentActivity = activity
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme { GlassBackdrop { content(vm) } }
                    }
                }
                act?.invoke()
                if (expect != null) waitFor(expect) else pump(DIALOG_FRAMES)
                settleImages()
                // Lets a sheet finish sliding in before it is captured.
                if (overlay) pump(40)
                if (record && capture) {
                    val path = "build/outputs/screenshots/$name-${variant.name.lowercase()}.png"
                    when {
                        ownWindow -> compose.onNode(isRoot() and hasAnyDescendant(hasText(expect!!))).captureRoboImage(path)
                        overlay -> captureScreen(path)
                        else -> compose.onRoot().captureRoboImage(path)
                    }
                }
            }
            currentActivity = null
            currentVm = null
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(path: String) = captureScreenRoboImage(path)

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

    /**
     * Leaves [id] as the only picked copy, the way a user picks one: through the view model's
     * toggle, then a deterministic wait until the cleanup bar's semantic node reports 1 copy
     * selected. Checking the bar's semantic stateDescription avoids text-wrapping mismatches at
     * large font scales and eliminates wall-clock deadline races under CI load.
     */
    private fun selectOnlyCopy(vm: AppViewModel, id: Long) {
        fun state(): String {
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString().take(800) }
                .getOrDefault("<no root>")
            return "groups=${vm.duplicateGroups.value.map { g -> g.items.map { it.id } }} " +
                "scan=${vm.duplicateScan.value.javaClass.simpleName} selected=${vm.duplicateSelection.value} tree=$tree"
        }
        // Let any clear emission propagate before re-selecting so StateFlow never conflates
        // [id] -> [] -> [id] synchronously, and advance Robolectric's paused Looper/Choreographer
        // + Compose Snapshot notifications so collectAsStateWithLifecycle always delivers.
        vm.duplicateSelection.value = emptySet()
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
        Snapshot.sendApplyNotifications()
        pump(2)
        vm.toggleDuplicate(id)
        assertTrue("The toggle did not select $id. ${state()}", id in vm.duplicateSelection.value)
        val cleanupBar = hasTestTag("storage-cleanup-bar") and hasStateDescription("1 copy selected")
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
        Snapshot.sendApplyNotifications()
        pump(4)
        settle(
            maxPumps = 400,
            describe = { "The cleanup bar never showed 1 copy selected. ${state()}" },
        ) {
            shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
            Snapshot.sendApplyNotifications()
            compose.onAllNodes(cleanupBar, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pump(times: Int) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(64)
        shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        Thread.sleep(10)
    }

    /**
     * Runs the UI deterministically by pump count (Compose clock + Main Looper idling) until
     * [done] becomes true, instead of failing on a tight wall-clock deadline under CI CPU load.
     */
    private fun settle(
        describe: () -> String = { "The screen never reached the expected state" },
        maxPumps: Int = 320,
        done: () -> Boolean,
    ) {
        val safetyDeadlineMs = System.currentTimeMillis() + 60_000L
        var pumps = 0
        while (true) {
            pump(1)
            pumps++
            if (done()) break
            if (pumps >= maxPumps || (pumps >= 120 && System.currentTimeMillis() >= safetyDeadlineMs)) {
                fail("${describe()} (after $pumps pumps)")
            }
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
            // IMG-WA13 is a byte-for-byte copy of IMG-WA12, the one duplicate pair (issue #42).
            // The three screenshots share a size but differ in content.
            val bytes = picture(if (file.id == 13L) 12L else file.id)
            resolver.registerInputStreamSupplier(file.uri) { ByteArrayInputStream(bytes) }
        }
        // The duplicate check reads the original bytes; the JVM has no original-file access, so it
        // reads the same generated pictures through the ordinary stream.
        Graph.get(app).openOriginalStream = { context, uri -> checkNotNull(context.contentResolver.openInputStream(uri)) }
        Graph.get(app).clock = { FIXED_NOW }
    }

    /**
     * A small generated picture: a two-colour gradient with a disc. No photograph is involved.
     * File 1 is coral and file 3 is near white, the two hardest cases for a coral selection frame;
     * the test selects exactly those two.
     */
    private fun picture(id: Long): ByteArray = pictureBytes(id)

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
        private val failAuthOnPreview: Boolean = false,
        /** What opening a video throws; the plain "not available" error unless a test sets it. */
        private val videoError: Exception = IOException("Video playback is not available"),
    ) : DriveFolders {
        override suspend fun listChildren(folderId: String, foldersOnly: Boolean) = list(folderId, foldersOnly)

        override suspend fun openVideoStream(file: DriveFile): com.drivelift.app.drive.DriveVideoStream = throw videoError

        /** Files the app asked to move to Drive's trash, in order. */
        val trashedIds = mutableListOf<String>()
        override suspend fun moveToTrash(fileId: String, account: String) {
            assertEquals("Every trash call names the confirmed account", TEST_EMAIL, account)
            trashedIds += fileId
        }

        /** Files the app asked to move: (file, from folder, to folder), in order. */
        val moves = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
        override suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String, account: String) {
            assertEquals("Every move call names the confirmed account", TEST_EMAIL, account)
            moves += Triple(fileId, fromFolderId, toFolderId)
        }
        /** Files the app asked to rename: (file, folder, new name), in order. */
        val renames = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
        override suspend fun renameFile(fileId: String, folderId: String, newName: String, account: String): DriveFile {
            assertEquals("Every rename call names the confirmed account", TEST_EMAIL, account)
            renames += Triple(fileId, folderId, newName)
            return FILES.first { it.id == fileId }.copy(name = newName)
        }
        override suspend fun createFolder(name: String, parentId: String) = create(name)

        /** Drive ids the app looked up for favorites, in order. */
        val lookedUp = java.util.Collections.synchronizedList(mutableListOf<String>())

        /** The account lookups must be made as: the connected one. */
        @Volatile var lookupAccount = TEST_EMAIL
        override suspend fun fileInfo(fileId: String, account: String): DriveFile? {
            assertEquals("Favorites are looked up only as the connected account", lookupAccount, account)
            lookedUp += fileId
            return when (fileId) {
                "gone" -> null
                "binned" -> DriveFile("binned", "Old receipt.jpg", "image/jpeg", 90_000, null, trashed = true)
                else -> (FOLDERS + FILES).firstOrNull { it.id == fileId }
            }
        }

        override suspend fun loadThumbnail(file: DriveFile, maxEdgePx: Int): ByteArray? = when (file.id) {
            "d2" -> pictureBytes(2)
            "d3" -> pictureBytes(4)
            "d4" -> pictureBytes(1)
            else -> null
        }

        override suspend fun loadPreviewBytes(
            file: DriveFile,
            maxBytes: Long,
            onProgress: (Long, Long) -> Unit,
        ): ByteArray {
            if (failAuthOnPreview) throw AuthRequiredException()
            val bytes = when (file.id) {
                "d2" -> pictureBytes(2)
                "d4" -> pictureBytes(1)
                else -> pictureBytes(5)
            }
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return bytes
        }

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
                DriveFile("d3", "Trip to the coast.mp4", "video/mp4", 182_000_000, "00", false, durationMillis = 83_000L),
                DriveFile("d4", "Sunset_0019.jpg", "image/jpeg", 3_400_000, "00", false),
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

            /** Files the media query leaves out, as if deleted outside DriveLift. */
            @Volatile var hidden: Set<Long> = emptySet()

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
            for (f in if (empty) emptyList() else files.filter { it.id !in hidden }) {
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
        const val TEST_EMAIL = "test.user@example.com"

        /** The time the Storage screen shows for its figures: 15 March 2026, 14:05 UTC. */
        const val FIXED_NOW = 1_773_583_500_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L

        const val PICTURE_SIZE = 256

        /** Frames run after a change in the new-folder dialog, enough for it to settle. */
        const val DIALOG_FRAMES = 20

        /** System bar sizes of a typical phone, in dp. */
        const val STATUS_BAR_DP = 24
        const val TALL_CUTOUT_STATUS_DP = 54
        const val GESTURE_NAV_DP = 24
        const val THREE_BUTTON_NAV_DP = 48

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

        private fun opaque(rgb: Int) = rgb or 0xFF000000.toInt()

        private fun pictureBytes(id: Long): ByteArray {
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
    }
}
