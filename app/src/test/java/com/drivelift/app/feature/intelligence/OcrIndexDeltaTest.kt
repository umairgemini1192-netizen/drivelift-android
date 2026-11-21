package com.drivelift.app.feature.intelligence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.feature.intelligence.data.ExtractionStatus
import com.drivelift.app.feature.intelligence.data.IndexedDocumentKey
import com.drivelift.app.feature.intelligence.data.IntelligenceAutomationDatabase
import com.drivelift.app.feature.intelligence.data.OcrLanguageMode
import com.drivelift.app.feature.intelligence.nlsearch.NaturalLanguageQueryParser
import com.drivelift.app.feature.intelligence.nlsearch.OfflineSearchRepository
import com.drivelift.app.feature.intelligence.ocr.DocumentCandidate
import com.drivelift.app.feature.intelligence.ocr.DocumentSource
import com.drivelift.app.feature.intelligence.ocr.DocumentTextExtractor
import com.drivelift.app.feature.intelligence.ocr.ExtractionResult
import com.drivelift.app.feature.intelligence.ocr.IndexingBus
import com.drivelift.app.feature.intelligence.ocr.MultiScriptOcrRouter
import com.drivelift.app.feature.intelligence.ocr.OcrIndexDelta
import com.drivelift.app.feature.intelligence.ocr.OcrIndexer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class OcrIndexDeltaTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: IntelligenceAutomationDatabase

    private class FakeSource(var docs: List<DocumentCandidate>) : DocumentSource {
        override suspend fun candidates() = docs
    }

    private class FakeExtractor(val texts: Map<String, String>) : DocumentTextExtractor {
        val calls = ArrayList<String>()
        override suspend fun extract(candidate: DocumentCandidate): ExtractionResult {
            calls += candidate.uri
            val text = texts[candidate.uri] ?: return ExtractionResult(ExtractionStatus.FAILED)
            return ExtractionResult(ExtractionStatus.INDEXED, text, 1)
        }
    }

    private fun doc(id: String, modified: Long = 1_000L, size: Long = 10L, name: String = "$id.jpg", mime: String = "image/jpeg") =
        DocumentCandidate("content://media/$id", name, "Pictures/", mime, size, modified)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, IntelligenceAutomationDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deltaPlanSkipsUnchangedAndListsRemoved() {
        val existing = listOf(
            IndexedDocumentKey("content://media/a", 1_000L, 10L, ExtractionStatus.INDEXED.name),
            IndexedDocumentKey("content://media/b", 1_000L, 10L, ExtractionStatus.INDEXED.name),
            IndexedDocumentKey("content://media/gone", 1_000L, 10L, ExtractionStatus.INDEXED.name),
            IndexedDocumentKey("content://media/p", 1_000L, 10L, ExtractionStatus.PENDING.name),
            IndexedDocumentKey("content://media/f", 1_000L, 10L, ExtractionStatus.FAILED.name),
        )
        val plan = OcrIndexDelta.compute(existing, listOf(doc("a"), doc("b", modified = 2_000L), doc("p"), doc("f"), doc("new")))
        assertEquals(listOf("content://media/b", "content://media/p", "content://media/new"), plan.toIndex.map { it.uri })
        assertEquals(2, plan.unchangedCount) // a, and f (a broken file is not retried until it changes)
        assertEquals(listOf("content://media/gone"), plan.removedUris)
    }

    @Test
    fun incrementalReindexSkipsUnchangedFilesAndRemovesDeletedEntries() = runBlocking {
        val dua = "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً"
        val source = FakeSource(listOf(doc("a"), doc("b"), doc("c")))
        val extractor = FakeExtractor(
            mapOf(
                "content://media/a" to dua,
                "content://media/b" to "Electricity invoice total 4,250",
                "content://media/c" to "كتاب الأدعية",
            ),
        )
        val indexer = OcrIndexer(db.dao(), source, extractor, IndexingBus(), clock = { 5_000L }, isPaused = { false })

        val first = indexer.run()
        assertEquals(3, first.indexed)
        assertEquals(3, extractor.calls.size)

        extractor.calls.clear()
        val second = indexer.run()
        assertEquals(0, second.indexed)
        assertEquals(3, second.unchanged)
        assertTrue(extractor.calls.isEmpty())

        source.docs = listOf(doc("a"), doc("b", modified = 9_000L))
        val third = indexer.run()
        assertEquals(listOf("content://media/b"), extractor.calls)
        assertEquals(1, third.removed)
        val keys = db.dao().documentKeys().map { it.uri }.toSet()
        assertEquals(setOf("content://media/a", "content://media/b"), keys)

        val stored = db.dao().documentByUri("content://media/a")!!
        assertEquals(dua, stored.extractedText)
        assertEquals(ExtractionStatus.INDEXED.name, stored.extractionStatus)
        assertFalse(stored.normalizedText.contains('َ'))
    }

    @Test
    fun ftsSearchFindsVocalizedArabicFromPlainQueryAndCrossKeyboardLetters() = runBlocking {
        val source = FakeSource(listOf(doc("dua"), doc("bill", name = "bill.pdf", mime = "application/pdf"), doc("urdu")))
        val extractor = FakeExtractor(
            mapOf(
                "content://media/dua" to "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً",
                "content://media/bill" to "Electricity invoice total 4,250",
                "content://media/urdu" to "یہ کتاب میری ہے",
            ),
        )
        OcrIndexer(db.dao(), source, extractor, IndexingBus(), clock = { 5_000L }, isPaused = { false }).run()
        val search = OfflineSearchRepository(db.dao(), NaturalLanguageQueryParser(now = { 10_000L }, zone = ZoneOffset.UTC))

        val dua = search.search(search.parse("ربنا اتنا"))
        assertEquals(listOf("content://media/dua"), dua.map { it.document.uri })
        val snippet = dua.single().snippet!!
        assertTrue(snippet.rightToLeft)
        assertTrue(snippet.text.contains("رَبَّنَا"))

        // Arabic keyboard Kaf finds Urdu text.
        assertEquals(listOf("content://media/urdu"), search.search(search.parse("كتاب")).map { it.document.uri })
        // Receipt intent plus a PDF filter.
        assertEquals(listOf("content://media/bill"), search.search(search.parse("invoice pdf")).map { it.document.uri })
        // Name search.
        assertEquals(listOf("content://media/bill"), search.search(search.parse("bill.pdf")).map { it.document.uri })
    }

    @Test
    fun ocrRoutingAndMerge() {
        assertTrue(MultiScriptOcrRouter.shouldRunArabicUrdu(OcrLanguageMode.ALL_SCRIPTS, "lots of english text here and more", true))
        assertFalse(MultiScriptOcrRouter.shouldRunArabicUrdu(OcrLanguageMode.ALL_SCRIPTS, "", false))
        assertFalse(MultiScriptOcrRouter.shouldRunArabicUrdu(OcrLanguageMode.ENGLISH_ONLY, "", true))
        assertTrue(MultiScriptOcrRouter.shouldRunArabicUrdu(OcrLanguageMode.SMART, "Total", true))
        assertFalse(MultiScriptOcrRouter.shouldRunArabicUrdu(OcrLanguageMode.SMART, "a".repeat(60), true))
        val merged = MultiScriptOcrRouter.mergeOcrText("Invoice 12\nTotal 40", "ربنا آتنا\nlnvoice l2\nTotal 40")
        assertEquals("Invoice 12\nTotal 40\nربنا آتنا", merged)
    }
}
