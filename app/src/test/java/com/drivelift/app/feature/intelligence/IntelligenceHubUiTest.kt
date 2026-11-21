package com.drivelift.app.feature.intelligence

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.intelligence.data.ExtractionStatus
import com.drivelift.app.feature.intelligence.data.IndexedDocumentEntity
import com.drivelift.app.feature.intelligence.data.RuleExecutionLogEntity
import com.drivelift.app.feature.intelligence.data.RuleLogStatus
import com.drivelift.app.feature.intelligence.nlsearch.ArabicUrduTextNormalizer
import com.drivelift.app.feature.intelligence.rules.RuleTemplates
import com.drivelift.app.feature.intelligence.ui.IntelligenceHubScreen
import com.drivelift.app.feature.intelligence.ui.IntelligenceViewModel
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Intelligence hub with synthetic documents and rules (no real files or names)
 * at standard size, font scale 1.5 and a compact 320 dp phone. Writes six screenshots into
 * `build/outputs/screenshots/` when recording.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IntelligenceHubUiTest {

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
        Graph.get(app).clock = { FIXED_TIME_MS }
        seed()
    }

    private fun seed() = runBlocking {
        val dao = Graph.get(app).intelligence.db.dao()
        fun doc(uri: String, name: String, folder: String, mime: String, size: Long, text: String, status: ExtractionStatus = ExtractionStatus.INDEXED) =
            IndexedDocumentEntity(
                uri = uri, displayName = name, folderPath = folder, mimeType = mime, sizeBytes = size,
                modifiedAt = FIXED_TIME_MS - 3_600_000L, indexedAt = FIXED_TIME_MS, extractionStatus = status.name,
                pageCount = 1, extractedText = text,
                normalizedText = ArabicUrduTextNormalizer.normalize(text) + "\n" + ArabicUrduTextNormalizer.normalize("$name $folder"),
            )
        dao.upsertDocument(
            doc(
                "content://synthetic/1", "SYNTH_DUA_CARD.jpg", "Pictures/Synthetic/", "image/jpeg", 820_000L,
                "رَبَّنَا آتِنَا فِي " +
                    "الدُّنْيَا حَسَنَةً",
            ),
        )
        dao.upsertDocument(
            doc(
                "content://synthetic/2", "SYNTH_SAFAR_DUA.pdf", "Documents/Synthetic/", "application/pdf", 240_000L,
                "دعائے سفر: یا اللہ " +
                    "میرے سفر کو آسان فرما",
            ),
        )
        dao.upsertDocument(doc("content://synthetic/3", "SYNTH_INVOICE.pdf", "Download/", "application/pdf", 96_000L, "Synthetic Utility invoice total 4,250"))
        dao.upsertDocument(doc("content://synthetic/4", "SYNTH_PENDING.png", "Pictures/Screenshots/", "image/png", 60_000L, "", ExtractionStatus.PENDING))

        val upload = RuleTemplates.all[0].rule.copy(enabled = true, lastRunAt = FIXED_TIME_MS - 7_200_000L, totalProcessedCount = 6)
        val uploadId = dao.insertRule(upload)
        dao.insertRule(RuleTemplates.all[1].rule)
        dao.insertLogs(
            listOf(
                RuleExecutionLogEntity(ruleId = uploadId, ruleName = upload.name, fileName = "SYNTH_CLIP_4K.mp4", actionType = upload.actionType,
                    status = RuleLogStatus.SUCCESS.name, message = "Queued for upload to DriveLift Backup / phone folder name", timestamp = FIXED_TIME_MS - 7_200_000L),
                RuleExecutionLogEntity(ruleId = uploadId, ruleName = upload.name, fileName = "SYNTH_RECORDING.mp4", actionType = upload.actionType,
                    status = RuleLogStatus.SKIPPED.name, message = "Changed in the last minute (still recording or downloading)", timestamp = FIXED_TIME_MS - 7_200_000L),
            ),
        )
    }

    /** Leaves the device as the next test expects it (see DesktopDropUiTest). */
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

    private fun waitForText(text: String, timeoutMs: Long = 15_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithText(text).assertExists()
    }

    /** Results sit below the status panel; scroll them into view while the search finishes. */
    private fun waitForResults(text: String, timeoutMs: Long = 20_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            runCatching { compose.onNodeWithTag("intel-search-list").performScrollToKey("count") }
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 15_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().assertExists()
    }

    private fun launch(variant: Variant, block: () -> Unit) {
        RuntimeEnvironment.setQualifiers(variant.qualifiers)
        RuntimeEnvironment.setFontScale(variant.fontScale)
        compose.mainClock.autoAdvance = false
        // Built directly with this test's application, not through the process-wide factory.
        val appVm = AppViewModel(app)
        val hubVm = IntelligenceViewModel(app)
        pump(12)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            IntelligenceHubScreen(appViewModel = appVm, onBack = {}, vm = hubVm)
                        }
                    }
                }
            }
            block()
        }
    }

    private fun capture(name: String, variant: Variant) {
        if (record) compose.onRoot().captureRoboImage("build/outputs/screenshots/$name-${variant.name.lowercase()}.png")
    }

    @Test
    fun offlineSearchShowsRightToLeftArabicAndUrduResults() {
        for (variant in Variant.entries) {
            launch(variant) {
                waitForText("3 indexed")
                compose.onNodeWithText("1 waiting").assertExists()
                compose.onNodeWithTag("intel-privacy").assertIsDisplayed()
                compose.onNodeWithTag("intel-scan").assertHeightIsAtLeast(Tokens.TouchTarget)

                compose.onNodeWithText("Duas / دعا").performScrollTo().performClick()
                waitForResults("2 results")
                compose.onNodeWithTag("intel-understood").assertExists()
                waitForTag("intel-snippet-rtl")
                compose.onAllNodesWithTag("intel-snippet-rtl", useUnmergedTree = true).onFirst().assertExists()
                compose.onNodeWithText("SYNTH_DUA_CARD.jpg").assertExists()
                compose.onNodeWithTag("intel-search-list").performScrollToKey("count")
                pump(6)
                capture("intelligence-search", variant)
            }
        }
    }

    @Test
    fun smartRulesListTemplatesAndActivityLog() {
        for (variant in Variant.entries) {
            launch(variant) {
                waitForTag("intel-tab-rules")
                compose.onNodeWithTag("intel-tab-rules").performClick()
                waitForText("Upload new 4K videos to Drive")
                compose.onNodeWithText("Choose a source and a destination to switch this rule on.").assertExists()
                compose.onNodeWithTag("intel-rules-list").performScrollToKey("log-header")
                pump(4)
                compose.onNodeWithTag("intel-log").performClick()
                pump(6)
                // Log rows sit below the fold; ids 1 and 2 are the seeded entries.
                compose.onNodeWithTag("intel-rules-list").performScrollToKey("log-1")
                waitForText("SYNTH_CLIP_4K.mp4")
                compose.onNodeWithTag("intel-rules-list").performScrollToIndex(0)
                pump(6)
                compose.onAllNodesWithTag("intel-preview").onFirst().assertHeightIsAtLeast(Tokens.TouchTarget)
                capture("intelligence-rules", variant)

                if (variant == Variant.STANDARD) {
                    compose.onAllNodesWithTag("intel-preview").onFirst().performClick()
                    waitForTag("intel-dry-run")
                    compose.onNodeWithText("Close").performClick()
                    pump(6)
                }
            }
        }
    }

    companion object {
        private const val FIXED_TIME_MS = 1_773_583_500_000L
    }
}
