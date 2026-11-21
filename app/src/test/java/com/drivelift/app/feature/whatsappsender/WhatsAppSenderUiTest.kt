package com.drivelift.app.feature.whatsappsender

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import coil.Coil
import coil.ImageLoader
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.whatsappsender.engine.WhatsAppAvailabilityChecker
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.MAX_WHATSAPP_FILE_BYTES
import com.drivelift.app.feature.whatsappsender.model.QualityMode
import com.drivelift.app.feature.whatsappsender.model.WhatsAppAvailability
import com.drivelift.app.feature.whatsappsender.model.WhatsAppSendPayload
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget
import com.drivelift.app.feature.whatsappsender.ui.WhatsAppQuickSenderScreen
import com.drivelift.app.feature.whatsappsender.ui.WhatsAppSenderSheet
import com.drivelift.app.feature.whatsappsender.ui.WhatsAppSenderViewModel
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WhatsAppSenderUiTest {

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
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Coil.setImageLoader(
            ImageLoader.Builder(app)
                .crossfade(false)
                .allowHardware(false)
                .build(),
        )
        val graph = Graph.get(app)
        graph.clock = { FIXED_TIME_MS }
        graph.whatsAppSender.prefs.clearPresets()
        graph.whatsAppSender.prefs.setDefaultTarget(WhatsAppTarget.PERSONAL)
        graph.whatsAppSender.prefs.setDefaultQualityMode(QualityMode.ORIGINAL_QUALITY)
        graph.whatsAppSender.availabilityChecker = object : WhatsAppAvailabilityChecker {
            override fun check(): WhatsAppAvailability = WhatsAppAvailability.BOTH
        }
    }

    @After
    fun tearDown() {
        Graph.get(app).clock = System::currentTimeMillis
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        pump(10)
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

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithText(text, substring = substring).assertExists()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun whatsAppSenderSheetRendersAllVariantsAndWarnings() {
        val items = buildList {
            add(
                LocalSendItem(
                    id = "media:990",
                    name = "SYNTH_4K_EXPORT.mp4",
                    sizeBytes = MAX_WHATSAPP_FILE_BYTES + 250_000_000L,
                    mimeType = "video/mp4",
                    uriString = "content://media/external/video/media/990",
                    bucketName = "Camera",
                ),
            )
            for (i in 1..31) {
                add(
                    LocalSendItem(
                        id = "media:$i",
                        name = "SYNTH_IMG_%02d.jpg".format(i),
                        sizeBytes = 3_200_000L,
                        mimeType = "image/jpeg",
                        uriString = "content://media/external/images/media/$i",
                        bucketName = "Camera",
                    ),
                )
            }
            add(
                LocalSendItem(
                    id = "media:999",
                    name = "SYNTH_EMPTY.jpg",
                    sizeBytes = 0L,
                    mimeType = "image/jpeg",
                    uriString = "content://media/external/images/media/999",
                    bucketName = "Camera",
                ),
            )
        }
        val payload = WhatsAppSendPayload(
            title = "Camera",
            items = items,
            isFolderSource = true,
            sourceBucketId = 100L,
            mediaItemIds = listOf(990L, 1L, 2L),
        )

        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            val senderVm = WhatsAppSenderViewModel(app)

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                WhatsAppSenderSheet(
                                    payload = payload,
                                    onDismiss = {},
                                    onSent = {},
                                    onHandoffToDrive = {},
                                    onHandoffToDesktopDrop = {},
                                    senderVm = senderVm,
                                )
                            }
                        }
                    }
                }
                waitForTag("wa-sender-sheet")
                waitForTag("wa-oversized-warning")
                pump(40)

                compose.onNodeWithText("Send to WhatsApp").assertIsDisplayed()
                compose.onNodeWithTag("wa-target-personal").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("wa-target-business").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("wa-quality-original_quality").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("wa-oversized-warning").assertExists()
                compose.onNodeWithTag("wa-batch-card").assertExists()
                compose.onNodeWithTag("wa-send-cta").assertHeightIsAtLeast(Tokens.TouchTarget)
                waitForText("Skipped 1 empty file.", substring = true)

                if (record) {
                    captureScreenRoboImage(
                        "build/outputs/screenshots/whatsapp-sender-sheet-${variant.name.lowercase()}.png",
                    )
                }

                if (variant == Variant.STANDARD) {
                    compose.onNodeWithTag("wa-target-business").performSemanticsAction(SemanticsActions.OnClick)
                    waitForText("to WhatsApp Business", substring = true)

                    compose.onNodeWithTag("wa-toggle-exclude-oversized").performSemanticsAction(SemanticsActions.OnClick)
                    waitForText("31 files ready")

                    compose.onNodeWithTag("wa-strategy-split").performSemanticsAction(SemanticsActions.OnClick)
                    waitForText("Send batch 1 of 2 (30 files) to WhatsApp Business")
                }
            }
        }
    }

    @Test
    fun whatsAppQuickSenderScreenRendersAllVariants() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Robolectric.setupContentProvider(SyntheticMediaProvider::class.java, "media")

        for (variant in Variant.entries) {
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            val appVm = AppViewModel(app)
            val senderVm = WhatsAppSenderViewModel(app)
            pump(12)
            appVm.refreshMedia()

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                WhatsAppQuickSenderScreen(
                                    vm = appVm,
                                    onBack = {},
                                    senderVm = senderVm,
                                )
                            }
                        }
                    }
                }
                waitForTag("wa-quick-sender-screen")
                waitForText("Camera")
                pump(10)

                compose.onNodeWithText("WhatsApp Quick Sender").assertIsDisplayed()
                compose.onNodeWithText("WhatsApp and WhatsApp Business installed").assertIsDisplayed()
                compose.onNodeWithTag("wa-pick-saf-folder").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithTag("wa-pick-saf-files").assertHeightIsAtLeast(Tokens.TouchTarget)
                compose.onNodeWithText("Screenshots").assertExists()

                if (record) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/screenshots/whatsapp-quick-sender-${variant.name.lowercase()}.png",
                    )
                }

                if (variant == Variant.STANDARD) {
                    compose.onNodeWithText("Camera").performClick()
                    waitForTag("wa-sender-sheet")
                    pump(10)
                    assertTrue(compose.onAllNodesWithText("Send to WhatsApp").fetchSemanticsNodes().isNotEmpty())
                }
            }
        }
    }

    class SyntheticMediaProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: emptyArray()
            val cursor = MatrixCursor(columns)
            val rows = listOf(
                Triple(100L, "Camera", "DCIM/Camera/" to 4_200_000L),
                Triple(200L, "Screenshots", "Pictures/Screenshots/" to 950_000L),
                Triple(300L, "Downloads", "Download/" to 18_400_000L),
            )
            for ((idx, entry) in rows.withIndex()) {
                val id = (idx + 1).toLong()
                val bucketId = entry.first
                val bucketName = entry.second
                val (relPath, size) = entry.third
                cursor.addRow(
                    columns.map { col ->
                        when (col) {
                            FileColumns._ID -> id
                            FileColumns.DISPLAY_NAME -> "SYNTH_${bucketName}_01.jpg"
                            FileColumns.SIZE -> size
                            FileColumns.MIME_TYPE -> "image/jpeg"
                            FileColumns.DATE_TAKEN -> FIXED_TIME_MS - idx * 60_000L
                            FileColumns.DATE_MODIFIED -> (FIXED_TIME_MS - idx * 60_000L) / 1000L
                            FileColumns.BUCKET_ID -> bucketId
                            FileColumns.BUCKET_DISPLAY_NAME -> bucketName
                            FileColumns.RELATIVE_PATH -> relPath
                            FileColumns.MEDIA_TYPE -> FileColumns.MEDIA_TYPE_IMAGE
                            FileColumns.DURATION -> 0L
                            FileColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                            else -> null
                        }
                    },
                )
            }
            return cursor
        }

        override fun getType(uri: Uri): String = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    companion object {
        private const val FIXED_TIME_MS = 1_773_583_500_000L
    }
}
