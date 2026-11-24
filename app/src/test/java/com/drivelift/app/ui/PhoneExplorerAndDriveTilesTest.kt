package com.drivelift.app.ui

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import coil.Coil
import coil.ImageLoader
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.data.FileSystemLocalExplorerSource
import com.drivelift.app.data.LocalFileCategory
import com.drivelift.app.data.LocalFileOpener
import com.drivelift.app.data.LocalFileType
import com.drivelift.app.data.buildBreadcrumbs
import com.drivelift.app.data.classifyLocalFile
import com.drivelift.app.data.isPathWithinRoot
import com.drivelift.app.data.localFileTypeLabel
import com.drivelift.app.data.resolveLocalMimeType
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DriveFileVisualKind
import com.drivelift.app.drive.DriveFolders
import com.drivelift.app.drive.classifyDriveVisualKind
import com.drivelift.app.drive.driveDownloadFileName
import com.drivelift.app.drive.driveDownloadMimeType
import com.drivelift.app.drive.drivePreviewTypeLabel
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Verifies PR #94:
 * 1. Phone Tab Command Center (compact Drive status pill, 6-tile Bento grid, Settings cleanup).
 * 2. Full All-Files Local Explorer (MANAGE_EXTERNAL_STORAGE prompt, category filters, breadcrumbs,
 *    file-type icons, and FileProvider open intent).
 * 3. My Drive 2-column futuristic GlassSurface folder/file tile grid and all non-trashed file types
 *    (PDF, Excel, PPT, Word, Google Docs/Sheets/Slides, ZIP, APK, media) with Download & open.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhoneExplorerAndDriveTilesTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val record = System.getProperty("roborazzi.test.record") == "true"

    private enum class Variant(val qualifiers: String, val fontScale: Float) {
        STANDARD("w360dp-h780dp-xhdpi", 1f),
        LARGE_FONT("w360dp-h780dp-xhdpi", 1.5f),
        COMPACT("w320dp-h640dp-xhdpi", 1f),
    }

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        RuntimeEnvironment.setFontScale(Variant.STANDARD.fontScale)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Coil.setImageLoader(
            ImageLoader.Builder(app)
                .crossfade(false)
                .allowHardware(false)
                .build(),
        )
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
        Graph.get(app).localExplorer = FileSystemLocalExplorerSource()
        Graph.get(app).prefs.setGridDensity(GridDensity.MEDIUM)
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers(Variant.STANDARD.qualifiers)
        pump(8)
    }

    private fun pump(times: Int = 10) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(15)
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithText(text, substring = substring).assertExists()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithTag(tag).assertExists()
    }

    private fun waitForContentDescription(desc: String, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithContentDescription(desc).assertExists()
    }

    // ------------------------------------------------------------------ Pure logic tests

    @Test
    fun localFileClassificationMimeAndBreadcrumbsWorkSafely() {
        assertEquals(LocalFileType.PDF, classifyLocalFile("Report.pdf", false))
        assertEquals(LocalFileType.SPREADSHEET, classifyLocalFile("Budget.xlsx", false))
        assertEquals(LocalFileType.PRESENTATION, classifyLocalFile("Deck.pptx", false))
        assertEquals(LocalFileType.DOCUMENT, classifyLocalFile("Proposal.docx", false))
        assertEquals(LocalFileType.APK, classifyLocalFile("DriveLift.apk", false))
        assertEquals(LocalFileType.ARCHIVE, classifyLocalFile("Archive.zip", false))
        assertEquals(LocalFileType.IMAGE, classifyLocalFile("Sunset.jpg", false))
        assertEquals(LocalFileType.VIDEO, classifyLocalFile("Clip.mp4", false))
        assertEquals(LocalFileType.FOLDER, classifyLocalFile("Documents", true))

        assertEquals("XLSX", localFileTypeLabel(LocalFileType.SPREADSHEET, "Budget.xlsx"))
        assertEquals("PPTX", localFileTypeLabel(LocalFileType.PRESENTATION, "Deck.pptx"))
        assertEquals("DOCX", localFileTypeLabel(LocalFileType.DOCUMENT, "Proposal.docx"))
        assertEquals("PDF", localFileTypeLabel(LocalFileType.PDF, "Report.pdf"))
        assertEquals("APK", localFileTypeLabel(LocalFileType.APK, "DriveLift.apk"))
        assertEquals("ZIP", localFileTypeLabel(LocalFileType.ARCHIVE, "Archive.zip"))

        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            resolveLocalMimeType("Budget.xlsx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            resolveLocalMimeType("Deck.pptx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            resolveLocalMimeType("Proposal.docx"),
        )
        assertEquals("application/pdf", resolveLocalMimeType("Report.pdf"))
        assertEquals("application/vnd.android.package-archive", resolveLocalMimeType("DriveLift.apk"))
        assertEquals("application/zip", resolveLocalMimeType("Archive.zip"))

        val root = "/storage/emulated/0"
        assertTrue(isPathWithinRoot(root, "$root/Documents/Work"))
        assertFalse(isPathWithinRoot(root, "/storage/emulated/0/../other"))
        assertFalse(isPathWithinRoot(root, "/data/local"))

        val crumbs = buildBreadcrumbs(root, "$root/Documents/Work")
        assertEquals(listOf("Internal storage", "Documents", "Work"), crumbs.map { it.label })
        assertEquals(listOf(root, "$root/Documents", "$root/Documents/Work"), crumbs.map { it.path })

        // Out-of-root paths clamp safely to root.
        val clamped = buildBreadcrumbs(root, "/etc/hosts")
        assertEquals(1, clamped.size)
        assertEquals("Internal storage", clamped.first().label)
    }

    @Test
    fun fileSystemExplorerListsFoldersCategoryFiltersAndBuildsFileProviderIntent(): Unit = runBlocking {
        val root = seedSyntheticStorageTree()
        val source = FileSystemLocalExplorerSource(rootProvider = { root }, permissionCheck = { true })

        val rootEntries = source.listDirectory(source.rootPath())
        assertTrue("Directories must sort before files", rootEntries.first().isDirectory)
        assertTrue(rootEntries.any { it.name == "Documents" && it.isDirectory })
        assertTrue(rootEntries.any { it.name == "Download" && it.isDirectory })
        assertFalse("Hidden dotfiles must be skipped", rootEntries.any { it.name.startsWith(".") })

        val docs = source.listCategory(LocalFileCategory.DOCUMENTS)
        assertEquals(
            setOf("Quarterly_Report.pdf", "Budget_2026.xlsx", "Launch_Deck.pptx", "Contract.docx"),
            docs.map { it.name }.toSet(),
        )

        val downloads = source.listCategory(LocalFileCategory.DOWNLOADS)
        assertEquals(setOf("DriveLift_preview.apk", "Receipts_2026.zip"), downloads.map { it.name }.toSet())

        val apksZips = source.listCategory(LocalFileCategory.APKS_ZIPS)
        assertEquals(setOf("DriveLift_preview.apk", "Receipts_2026.zip"), apksZips.map { it.name }.toSet())

        val photos = source.listCategory(LocalFileCategory.PHOTOS)
        assertEquals(setOf("SYNTH_Beach.jpg"), photos.map { it.name }.toSet())

        val videos = source.listCategory(LocalFileCategory.VIDEOS)
        assertEquals(setOf("SYNTH_Trip.mp4"), videos.map { it.name }.toSet())

        // Verify FileProvider open intent works for an app-cache file (configured in file_paths.xml).
        val cachedPdf = File(app.cacheDir, "test_open.pdf").apply { writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)) }
        try {
            val openIntent = LocalFileOpener.buildOpenIntent(app, cachedPdf, "application/pdf")
            assertEquals(Intent.ACTION_VIEW, openIntent.action)
            assertEquals("application/pdf", openIntent.type)
            assertTrue((openIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            assertEquals("${app.packageName}.fileprovider", openIntent.data?.authority)
        } finally {
            cachedPdf.delete()
        }
    }

    @Test
    fun driveAllFileTypeClassificationAndDownloadMetadataWork() {
        val cases = listOf(
            DriveFile("1", "Report.pdf", "application/pdf", 12_000, null, false) to
                Triple(DriveFileVisualKind.PDF, "PDF", "Report.pdf"),
            DriveFile("2", "Sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 24_000, null, false) to
                Triple(DriveFileVisualKind.EXCEL, "Excel", "Sheet.xlsx"),
            DriveFile("3", "Slides.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", 36_000, null, false) to
                Triple(DriveFileVisualKind.POWERPOINT, "PowerPoint", "Slides.pptx"),
            DriveFile("4", "Notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 18_000, null, false) to
                Triple(DriveFileVisualKind.WORD, "Word", "Notes.docx"),
            DriveFile("5", "Roadmap", "application/vnd.google-apps.document", 0, null, false) to
                Triple(DriveFileVisualKind.GOOGLE_DOC, "Google Doc", "Roadmap.pdf"),
            DriveFile("6", "Expenses", "application/vnd.google-apps.spreadsheet", 0, null, false) to
                Triple(DriveFileVisualKind.GOOGLE_SHEET, "Google Sheet", "Expenses.pdf"),
            DriveFile("7", "Pitch", "application/vnd.google-apps.presentation", 0, null, false) to
                Triple(DriveFileVisualKind.GOOGLE_SLIDES, "Google Slides", "Pitch.pdf"),
            DriveFile("8", "Backup.zip", "application/zip", 92_000, null, false) to
                Triple(DriveFileVisualKind.ARCHIVE, "ZIP", "Backup.zip"),
            DriveFile("9", "Build.apk", "application/vnd.android.package-archive", 14_000_000, null, false) to
                Triple(DriveFileVisualKind.APK, "APK", "Build.apk"),
            DriveFile("10", "Work", DriveClient.FOLDER_MIME, 0, null, false) to
                Triple(DriveFileVisualKind.FOLDER, "Folder", "Work"),
        )
        for ((file, expected) in cases) {
            assertEquals(file.name, expected.first, classifyDriveVisualKind(file))
            assertEquals(file.name, expected.second, drivePreviewTypeLabel(file))
            assertEquals(file.name, expected.third, driveDownloadFileName(file))
        }
        assertEquals(
            "application/pdf",
            driveDownloadMimeType(DriveFile("5", "Roadmap", "application/vnd.google-apps.document", 0, null, false)),
        )
        assertEquals(
            "application/zip",
            driveDownloadMimeType(DriveFile("8", "Backup.zip", "application/zip", 92_000, null, false)),
        )
    }

    // ------------------------------------------------------------------ UI & Screenshot tests

    @Test
    fun phoneCommandCenterRoutesAllBentoTilesAndSettingsStaysClean() {
        val root = seedSyntheticStorageTree()
        Graph.get(app).localExplorer = FileSystemLocalExplorerSource(rootProvider = { root }, permissionCheck = { true })

        val vm = AppViewModel(app)
        compose.mainClock.autoAdvance = false
        pump(12)
        vm.driveFolders = AllTypesFakeDrive()
        vm.driveConn.value = DriveConn.CONNECTED
        vm.account.value = DriveAbout("Test User", "test.user@example.com", 21_000_000_000, 100_000_000_000)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity {
                activity = it
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                it.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                it.setContent { DriveLiftTheme { GlassBackdrop { AppRoot(vm) } } }
            }
            waitForTag("phone-bento-grid")
            waitForTag("phone-drive-status-pill")
            // Oversized legacy banner must be gone from Phone tab.
            assertTrue(
                compose.onAllNodesWithText("Google Drive connected").fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithTag("phone-drive-status-pill").assertHeightIsAtLeast(Tokens.TouchTarget)
            compose.onNodeWithTag("bento-applock-tile").assertHeightIsAtLeast(Tokens.TouchTarget)
            compose.onNodeWithTag("bento-cockpit-tile").assertHeightIsAtLeast(Tokens.TouchTarget)
            compose.onNodeWithTag("bento-cleaner-tile").assertHeightIsAtLeast(Tokens.TouchTarget)
            compose.onNodeWithTag("storage-entry").assertHeightIsAtLeast(Tokens.TouchTarget)
            compose.onNodeWithTag("bento-favorites-tile").assertHeightIsAtLeast(Tokens.TouchTarget)
            compose.onNodeWithTag("bento-explorer-tile").assertHeightIsAtLeast(Tokens.TouchTarget)

            // App Lock opens directly from Phone tab bento grid.
            compose.onNodeWithTag("bento-applock-tile").performClick()
            waitForTag("applock-manager-screen")
            activity.onBackPressedDispatcher.onBackPressed()
            waitForTag("phone-bento-grid")

            // Cockpit opens directly from Phone tab bento grid.
            compose.onNodeWithTag("bento-cockpit-tile").performClick()
            waitForTag("cockpit-setup-screen")
            activity.onBackPressedDispatcher.onBackPressed()
            waitForTag("phone-bento-grid")

            // All Files Explorer opens directly from Phone tab category filter chip.
            compose.onNodeWithTag("phone-cat-documents").performClick()
            waitForTag("local-explorer-screen")
            waitForText("Quarterly_Report.pdf")
            activity.onBackPressedDispatcher.onBackPressed()
            waitForTag("phone-bento-grid")

            // Settings strictly contains Transfer/Removal rules + About/Version chip (no App Lock / Cockpit).
            vm.tab.value = AppViewModel.TAB_TRANSFERS
            waitForContentDescription("Settings")
            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("Upload on Wi-Fi only")
            compose.onNodeWithTag("settings-version-chip").assertExists()
            assertTrue(compose.onAllNodesWithTag("applock-settings-entry").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("cockpit-settings-entry").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun localExplorerPermissionPromptAllVariants() {
        val root = seedSyntheticStorageTree()
        Graph.get(app).localExplorer = FileSystemLocalExplorerSource(rootProvider = { root }, permissionCheck = { false })

        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            val vm = AppViewModel(app)
            pump(10)

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                LocalFileExplorerScreen(
                                    vm = vm,
                                    initialCategory = LocalFileCategory.ALL,
                                    onBack = {},
                                )
                            }
                        }
                    }
                }
                waitForTag("explorer-permission-card")
                compose.onNodeWithText("All-Files Access Required").assertIsDisplayed()
                compose.onNodeWithTag("explorer-grant-permission")
                    .assertIsDisplayed()
                    .assertHeightIsAtLeast(Tokens.TouchTarget)
                pump(4)
                if (record) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/screenshots/local-explorer-permission-${variant.name.lowercase()}.png",
                    )
                }
            }
        }
    }

    @Test
    fun localExplorerBrowseBreadcrumbsAndCategoriesAllVariants() {
        val root = seedSyntheticStorageTree()
        Graph.get(app).localExplorer = FileSystemLocalExplorerSource(rootProvider = { root }, permissionCheck = { true })

        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            val vm = AppViewModel(app)
            pump(10)

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                LocalFileExplorerScreen(
                                    vm = vm,
                                    initialCategory = LocalFileCategory.ALL,
                                    onBack = {},
                                )
                            }
                        }
                    }
                }
                waitForTag("explorer-breadcrumbs")
                waitForText("Documents")
                compose.onNodeWithContentDescription("Reload files").assertExists()
                // Enter Documents folder (row shows "4 items") to show nested breadcrumbs + Office/PDF icons.
                waitForText("4 items")
                compose.onNodeWithText("4 items").performClick()
                waitForText("Quarterly_Report.pdf")
                waitForText("Budget_2026.xlsx")
                waitForText("Launch_Deck.pptx")
                waitForText("Contract.docx")
                pump(4)

                if (record) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/screenshots/local-explorer-browse-${variant.name.lowercase()}.png",
                    )
                }

                if (variant == Variant.STANDARD) {
                    // Breadcrumb jump back to root.
                    compose.onNodeWithText("Internal storage").performClick()
                    waitForText("Download")
                    // Quick filter to APKs & ZIPs (scroll LazyRow first so the chip is placed).
                    compose.onNodeWithTag("explorer-category-row").performScrollToKey(LocalFileCategory.APKS_ZIPS.name)
                    pump(4)
                    compose.onNodeWithTag("explorer-cat-apks_zips").performClick()
                    waitForText("DriveLift_preview.apk")
                    waitForText("Receipts_2026.zip")
                    // Live search narrows results.
                    compose.onNodeWithTag("explorer-search").performTextReplacement("apk")
                    pump(6)
                    assertTrue(compose.onAllNodesWithText("Receipts_2026.zip").fetchSemanticsNodes().isEmpty())
                    assertTrue(compose.onAllNodesWithText("DriveLift_preview.apk").fetchSemanticsNodes().isNotEmpty())
                }
            }
        }
    }

    @Test
    fun driveTwoColumnFuturisticFolderTilesAndAllFileTypesAllVariants() {
        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            val vm = AppViewModel(app)
            pump(10)
            vm.driveFolders = AllTypesFakeDrive()
            vm.driveConn.value = DriveConn.CONNECTED
            vm.account.value = DriveAbout("Test User", "test.user@example.com", 21_000_000_000, 100_000_000_000)

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                DriveBrowser(vm = vm, pickMode = false)
                            }
                        }
                    }
                }
                waitForTag("drive-folder-tile-f1")
                waitForTag("drive-folder-tile-f2")
                waitForText("Quarterly_Report.pdf")
                pump(4)

                // Verify 2-column folder tile grid: f1 and f2 sit side-by-side on the same row.
                val f1Bounds = compose.onNodeWithTag("drive-folder-tile-f1").fetchSemanticsNode().boundsInRoot
                val f2Bounds = compose.onNodeWithTag("drive-folder-tile-f2").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "Folder tiles f1 ($f1Bounds) and f2 ($f2Bounds) must sit side-by-side in a 2-column grid",
                    kotlin.math.abs(f1Bounds.top - f2Bounds.top) < 8f && f2Bounds.left > f1Bounds.left,
                )

                if (record) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/screenshots/drive-tiles-all-types-${variant.name.lowercase()}.png",
                    )
                }

                if (variant == Variant.STANDARD) {
                    // Open a non-media Office file and verify Download & open + Open in Google Drive actions.
                    compose.onNodeWithText("Quarterly_Report.pdf").performClick()
                    waitForTag("viewer-download-open")
                    compose.onNodeWithText("Download and open").assertIsDisplayed()
                    compose.onNodeWithText("Open in Google Drive").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Close viewer").performClick()
                    pump(6)

                    // Toggle to list view and back to grid view.
                    compose.onNodeWithContentDescription("Show as list").performClick()
                    waitForContentDescription("Show as grid")
                    compose.onNodeWithContentDescription("Show as grid").performClick()
                    waitForTag("drive-folder-tile-f1")
                }
            }
        }
    }

    private fun seedSyntheticStorageTree(): File {
        val root = tempFolder.newFolder("emulated_0_${System.nanoTime()}")
        val docs = File(root, "Documents").apply { mkdirs() }
        File(docs, "Quarterly_Report.pdf").writeBytes(ByteArray(14_200))
        File(docs, "Budget_2026.xlsx").writeBytes(ByteArray(6_400))
        File(docs, "Launch_Deck.pptx").writeBytes(ByteArray(21_000))
        File(docs, "Contract.docx").writeBytes(ByteArray(4_800))

        val dl = File(root, "Download").apply { mkdirs() }
        File(dl, "DriveLift_preview.apk").writeBytes(ByteArray(18_400))
        File(dl, "Receipts_2026.zip").writeBytes(ByteArray(12_500))

        val pics = File(root, "Pictures").apply { mkdirs() }
        File(pics, "SYNTH_Beach.jpg").writeBytes(ByteArray(3_100))

        val movies = File(root, "Movies").apply { mkdirs() }
        File(movies, "SYNTH_Trip.mp4").writeBytes(ByteArray(24_000))

        File(root, ".hidden_cache").writeBytes(ByteArray(128))
        return root
    }

    private class AllTypesFakeDrive : DriveFolders {
        private val entries = listOf(
            DriveFile("f1", "Work Projects", DriveClient.FOLDER_MIME, 0, null, false),
            DriveFile("f2", "Tax Archive", DriveClient.FOLDER_MIME, 0, null, false),
            DriveFile("d1", "Quarterly_Report.pdf", "application/pdf", 184_000, "00", false),
            DriveFile(
                "d2",
                "Budget_2026.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                92_000,
                "00",
                false,
            ),
            DriveFile(
                "d3",
                "Launch_Deck.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                3_400_000,
                "00",
                false,
            ),
            DriveFile(
                "d4",
                "Contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                54_000,
                "00",
                false,
            ),
            DriveFile("d5", "Roadmap", "application/vnd.google-apps.document", 0, null, false),
            DriveFile("d6", "Receipts.zip", "application/zip", 1_400_000, "00", false),
            DriveFile("d7", "DriveLift.apk", "application/vnd.android.package-archive", 19_200_000, "00", false),
        )

        override suspend fun listChildren(folderId: String, foldersOnly: Boolean): List<DriveFile> =
            if (folderId != DriveClient.ROOT) emptyList()
            else if (foldersOnly) entries.filter { it.isFolder }
            else entries

        override suspend fun createFolder(name: String, parentId: String): DriveFile =
            DriveFile("new", name, DriveClient.FOLDER_MIME, 0, null, false)

        override suspend fun loadPreviewBytes(
            file: DriveFile,
            maxBytes: Long,
            onProgress: (Long, Long) -> Unit,
        ): ByteArray = ByteArray(128)
    }

    class SyntheticPhoneMediaProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: emptyArray()
            val cursor = MatrixCursor(cols)
            cursor.addRow(
                cols.map { col ->
                    when (col) {
                        FileColumns._ID -> 1L
                        FileColumns.DISPLAY_NAME -> "SYNTH_IMG_01.jpg"
                        FileColumns.SIZE -> 3_400_000L
                        FileColumns.MIME_TYPE -> "image/jpeg"
                        FileColumns.DATE_TAKEN -> 1_773_576_000_000L
                        FileColumns.DATE_MODIFIED -> 1_773_576_000L
                        FileColumns.BUCKET_ID -> 100L
                        FileColumns.BUCKET_DISPLAY_NAME -> "Camera"
                        FileColumns.RELATIVE_PATH -> "DCIM/Camera/"
                        FileColumns.MEDIA_TYPE -> FileColumns.MEDIA_TYPE_IMAGE
                        FileColumns.DURATION -> 0L
                        FileColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                        else -> null
                    }
                },
            )
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
