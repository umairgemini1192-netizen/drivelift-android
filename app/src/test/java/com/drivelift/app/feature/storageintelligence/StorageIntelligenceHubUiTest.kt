package com.drivelift.app.feature.storageintelligence

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.storageintelligence.db.DuplicateGroupEntity
import com.drivelift.app.feature.storageintelligence.db.DuplicateItemEntity
import com.drivelift.app.feature.storageintelligence.db.LargeFileEntity
import com.drivelift.app.feature.storageintelligence.db.OrphanArtifactEntity
import com.drivelift.app.feature.storageintelligence.db.StorageScanSessionEntity
import com.drivelift.app.feature.storageintelligence.db.encodeScanErrors
import com.drivelift.app.feature.storageintelligence.model.DuplicateMatchKind
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.LargeFileCategory
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.ScanPhase
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.ui.StorageIntelligenceHubScreen
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.DriveConn
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StorageIntelligenceHubUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val recordSi = System.getProperty("roborazzi.test.record") == "true"

    private enum class Variant(val qualifiers: String, val fontScale: Float) {
        STANDARD("w360dp-h780dp-xhdpi", 1f),
        LARGE_FONT("w360dp-h780dp-xhdpi", 1.5f),
        COMPACT("w320dp-h640dp-xhdpi", 1f),
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Graph.get(app).clock = { FIXED_TIME_MS }
        Graph.get(app).prefs.accountEmail = "test.user@example.com"
        seedSyntheticSession()
    }

    private fun seedSyntheticSession() = runBlocking {
        val dao = Graph.get(app).storageIntelligence.db.dao()
        dao.persistScanResult(
            session = StorageScanSessionEntity(
                scope = ScanScope.CROSS_SOURCE.name,
                startedAtMs = FIXED_TIME_MS - 12_000L,
                completedAtMs = FIXED_TIME_MS,
                status = ScanPhase.COMPLETED.name,
                scannedFilesCount = 48,
                bytesAnalyzed = 680_000_000L,
                duplicateReclaimableBytes = 145_000_000L,
                largeFilesTotalBytes = 320_000_000L,
                orphanReclaimableBytes = 30_000_000L,
                totalReclaimableBytes = 175_000_000L,
                partialErrorsJson = encodeScanErrors(
                    listOf(
                        SourceScanError(
                            sourceId = "gdrive:secondary",
                            sourceLabel = "Secondary Cloud",
                            message = "Token expired. Reconnect to include this source.",
                            authExpired = true,
                        ),
                    ),
                ),
                thresholdBytes = 50L * 1024L * 1024L,
            ),
            groups = listOf(
                DuplicateGroupEntity(
                    groupKey = "exact:145000000:MD5:a1b2c3d4e5f6",
                    sessionId = 0L,
                    matchKind = DuplicateMatchKind.EXACT_HASH.name,
                    hashAlgorithm = HashAlgorithm.MD5.name,
                    hashValue = "a1b2c3d4e5f600112233",
                    normalizedName = null,
                    sizeBytes = 145_000_000L,
                    itemCount = 2,
                    reclaimableBytes = 145_000_000L,
                    isCrossSource = true,
                ),
            ),
            duplicateItems = listOf(
                DuplicateItemEntity(
                    sessionId = 0L,
                    groupKey = "exact:145000000:MD5:a1b2c3d4e5f6",
                    nodeId = "local:901",
                    nativeId = "901",
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    name = "SYNTH_CLIP_01.mp4",
                    path = "DCIM/Camera/SYNTH_CLIP_01.mp4",
                    sizeBytes = 145_000_000L,
                    mimeType = "video/mp4",
                    modifiedAt = FIXED_TIME_MS - 10_000L,
                    hashAlgorithm = HashAlgorithm.SHA256.name,
                    hashValue = "ff0011223344",
                    thumbnailRef = null,
                    localUri = "content://media/external/video/media/901",
                    bucketId = 10L,
                    parentFolderId = null,
                    isTrashed = false,
                ),
                DuplicateItemEntity(
                    sessionId = 0L,
                    groupKey = "exact:145000000:MD5:a1b2c3d4e5f6",
                    nodeId = "gdrive:d901",
                    nativeId = "d901",
                    sourceId = "gdrive:primary",
                    sourceLabel = "Google Drive",
                    sourceType = StorageSourceType.GOOGLE_DRIVE.name,
                    name = "Backup_Clip_01.mp4",
                    path = "My Drive/Backups/Backup_Clip_01.mp4",
                    sizeBytes = 145_000_000L,
                    mimeType = "video/mp4",
                    modifiedAt = FIXED_TIME_MS - 5_000L,
                    hashAlgorithm = HashAlgorithm.MD5.name,
                    hashValue = "a1b2c3d4e5f600112233",
                    thumbnailRef = null,
                    localUri = null,
                    bucketId = null,
                    parentFolderId = "folder-backups",
                    isTrashed = false,
                ),
            ),
            largeFiles = listOf(
                LargeFileEntity(
                    sessionId = 0L,
                    nodeId = "local:901",
                    nativeId = "901",
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    name = "SYNTH_CLIP_01.mp4",
                    path = "DCIM/Camera/SYNTH_CLIP_01.mp4",
                    sizeBytes = 145_000_000L,
                    mimeType = "video/mp4",
                    category = LargeFileCategory.VIDEO.name,
                    modifiedAt = FIXED_TIME_MS - 10_000L,
                    thumbnailRef = null,
                    localUri = "content://media/external/video/media/901",
                    bucketId = 10L,
                    parentFolderId = null,
                ),
                LargeFileEntity(
                    sessionId = 0L,
                    nodeId = "gdrive:d902",
                    nativeId = "d902",
                    sourceId = "gdrive:primary",
                    sourceLabel = "Google Drive",
                    sourceType = StorageSourceType.GOOGLE_DRIVE.name,
                    name = "Synthetic_Archive.zip",
                    path = "My Drive/Archives/Synthetic_Archive.zip",
                    sizeBytes = 175_000_000L,
                    mimeType = "application/zip",
                    category = LargeFileCategory.ARCHIVES.name,
                    modifiedAt = FIXED_TIME_MS - 20_000L,
                    thumbnailRef = null,
                    localUri = null,
                    bucketId = null,
                    parentFolderId = "folder-archives",
                ),
            ),
            orphans = listOf(
                OrphanArtifactEntity(
                    artifactId = "local-cache:/cache/stale_preview.bin",
                    sessionId = 0L,
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    category = OrphanCategory.LOCAL_APP_CACHE.name,
                    name = "stale_preview.bin",
                    path = "/cache/drive_preview/stale_preview.bin",
                    sizeBytes = 24_000_000L,
                    modifiedAt = FIXED_TIME_MS - 100_000_000L,
                    itemCount = 1,
                    nativeId = null,
                    detail = "Cached Drive thumbnail or preview",
                    localUri = null,
                    transferStatus = null,
                    isLocked = false,
                    canClean = true,
                ),
                OrphanArtifactEntity(
                    artifactId = "local-cache:/cache/stale_thumb.bin",
                    sessionId = 0L,
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    category = OrphanCategory.LOCAL_APP_CACHE.name,
                    name = "stale_thumb.bin",
                    path = "/cache/drive_preview/stale_thumb.bin",
                    sizeBytes = 6_000_000L,
                    modifiedAt = FIXED_TIME_MS - 120_000_000L,
                    itemCount = 1,
                    nativeId = null,
                    detail = "Cached Drive thumbnail",
                    localUri = null,
                    transferStatus = null,
                    isLocked = false,
                    canClean = true,
                ),
                OrphanArtifactEntity(
                    artifactId = "cloud-trash:gdrive:primary",
                    sessionId = 0L,
                    sourceId = "gdrive:primary",
                    sourceLabel = "Google Drive",
                    sourceType = StorageSourceType.GOOGLE_DRIVE.name,
                    category = OrphanCategory.CLOUD_TRASH_SUMMARY.name,
                    name = "Google Drive trash",
                    path = "Trash",
                    sizeBytes = 64_000_000L,
                    modifiedAt = FIXED_TIME_MS,
                    itemCount = 4,
                    nativeId = null,
                    detail = "4 trashed items. Manage in Google Drive.",
                    localUri = null,
                    transferStatus = null,
                    isLocked = false,
                    canClean = false,
                ),
            ),
        )
    }

    private fun pump(times: Int = 10) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(15)
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithTag(tag).assertExists()
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun waitForTextGone(text: String, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) return
        }
        assertTrue(compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun storageIntelligenceHubRendersAllVariantsAndExercisesTabsAutoSelectAndConfirmation() {
        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    val appVm = ViewModelProvider(activity)[AppViewModel::class.java]
                    appVm.driveConn.value = DriveConn.CONNECTED
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                StorageIntelligenceHubScreen(
                                    appViewModel = appVm,
                                    onBack = {},
                                    onRevealLocalFolder = {},
                                    onRevealDriveFolder = {},
                                )
                            }
                        }
                    }
                }
                waitForText("Some sources could not finish scanning")

                compose.onNodeWithTag("si-hub").assertIsDisplayed()
                compose.onNodeWithText("Storage intelligence").assertIsDisplayed()
                compose.onNodeWithText("Some sources could not finish scanning").assertIsDisplayed()
                compose.onNodeWithText("Reconnect Google Drive").assertHeightIsAtLeast(Tokens.TouchTarget)

                // Scroll to duplicates group & verify it composes cleanly across all screen sizes.
                compose.onNodeWithTag("si-list").performScrollToKey("dup-exact:145000000:MD5:a1b2c3d4e5f6")
                pump(4)
                compose.onNodeWithText("SYNTH_CLIP_01.mp4").assertExists()

                // Verify TabSelectionBar Select all is present on Duplicates across all screen sizes.
                compose.onNodeWithTag("si-list").performScrollToKey("dup-select-bar")
                pump(4)
                compose.onNodeWithTag("si-select-all").assertExists()

                // Scroll to smart auto-select presets and click Keep cloud copy.
                compose.onNodeWithTag("si-list").performScrollToKey("dup-presets")
                pump(4)
                compose.onNodeWithTag("si-presets-row").performScrollToIndex(2)
                pump(4)
                compose.onNodeWithText("Keep cloud copy").performClick()
                waitForText("1 item selected")
                pump(12)
                compose.onNodeWithText("1 item selected").assertIsDisplayed()
                compose.onNodeWithText("Clean selected").assertIsDisplayed()

                if (recordSi) {
                    compose.onNodeWithTag("si-list").performScrollToIndex(0)
                    pump(6)
                    compose.onRoot().captureRoboImage(
                        "build/outputs/si-screenshots/si-hub-${variant.name.lowercase()}.png",
                    )
                }

                // Exercise confirmation modal, Select all / Select none across all tabs, and StickyActionBar Select all.
                if (variant == Variant.STANDARD) {
                    compose.onNodeWithText("Clean selected").performClick()
                    waitForTag("si-cleanup-confirm")
                    compose.onNodeWithTag("si-cleanup-confirm").assertIsDisplayed()
                    compose.onNodeWithText("Move duplicate copies to trash?").assertIsDisplayed()
                    compose.onNodeWithText("This phone: 1 item", substring = true).assertIsDisplayed()
                    compose.onNodeWithText("Cancel").performClick()
                    waitForText("1 item selected")

                    compose.onNodeWithTag("si-list").performScrollToKey("dup-select-bar")
                    pump(4)
                    compose.onNodeWithText("Select none").performClick()
                    waitForText("Select all")
                    waitForTextGone("Clean selected")
                    compose.onNodeWithTag("si-select-all").performClick()
                    waitForText("1 item selected")
                    compose.onNodeWithText("Select none").performClick()
                    waitForText("Select all")
                    waitForTextGone("Clean selected")

                    // Exercise Large files tab and Select all / Select none.
                    compose.onNodeWithTag("si-list").performScrollToKey("tabs")
                    pump(4)
                    compose.onNodeWithTag("si-tab-large").performClick()
                    waitForText("Synthetic_Archive.zip")
                    compose.onNodeWithText("Synthetic_Archive.zip").assertExists()
                    compose.onNodeWithTag("si-list").performScrollToKey("large-select-bar")
                    pump(4)
                    compose.onNodeWithTag("si-select-all").performClick()
                    waitForText("2 items selected")
                    compose.onNodeWithText("Select none").performClick()
                    waitForText("Select all")
                    waitForTextGone("Clean selected")

                    // Exercise Orphans tab: partial manual selection offers StickyActionBar Select all, which selects both cleanable orphans only.
                    compose.onNodeWithTag("si-list").performScrollToKey("tabs")
                    pump(4)
                    compose.onNodeWithTag("si-tab-orphans").performClick()
                    waitForText("stale_preview.bin")
                    compose.onNodeWithText("stale_preview.bin").assertExists()
                    compose.onNodeWithText("stale_preview.bin").performClick()
                    waitForText("1 item selected")
                    compose.onNodeWithTag("si-bar-select-all").assertIsDisplayed()
                    compose.onNodeWithTag("si-bar-select-all").performClick()
                    waitForText("2 items selected")
                    pump(12)
                    compose.onNodeWithTag("si-list").performScrollToKey("orphan-select-bar")
                    pump(6)
                    compose.onNodeWithText("Select none").assertIsDisplayed()
                    if (recordSi) {
                        compose.onRoot().captureRoboImage(
                            "build/outputs/si-screenshots/si-orphans-standard.png",
                        )
                    }
                    compose.onNodeWithTag("si-select-all").performClick()
                    waitForText("Select all")
                    waitForTextGone("Clean selected")

                    compose.onNodeWithTag("si-list").performScrollToKey("orphan-cloud-trash:gdrive:primary")
                    pump(4)
                    compose.onNodeWithText("Google Drive trash").assertExists()
                }
            }
        }
    }

    companion object {
        private const val FIXED_TIME_MS = 1_773_583_500_000L
    }
}
