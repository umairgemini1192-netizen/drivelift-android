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
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt
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
    private val createCalls = AtomicInteger()
    private var currentActivity: ComponentActivity? = null
    private var currentVm: AppViewModel? = null

    @Before
    fun fakeWorkManager() {
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
        FakeMediaStore.gate?.countDown()
        FakeMediaStore.gate = null
        Graph.get(app).prefs.setGridDensity(GridDensity.MEDIUM)
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
        eachVariant("video-controls", expect = "Original  ·  3840x2160  ·  60 fps") { _ ->
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                VideoControls(
                    state = VideoUiState(
                        playing = true, position = 83_000, duration = 754_000,
                        speed = 1.25f, quality = qualityLabel(3840, 2160, 60f),
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
            "build-id", expect = "DriveLift 1.0.0-preview.14 (build 13)",
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
        act: (() -> Unit)? = null,
        content: @Composable (AppViewModel) -> Unit,
    ) {
        for (variant in Variant.entries) {
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
                DriveAbout("Test User", "test.user@example.com", 21_000_000_000, 100_000_000_000)
            } else null

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
    ) : DriveFolders {
        override suspend fun listChildren(folderId: String, foldersOnly: Boolean) = list(folderId, foldersOnly)
        override suspend fun createFolder(name: String, parentId: String) = create(name)

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
