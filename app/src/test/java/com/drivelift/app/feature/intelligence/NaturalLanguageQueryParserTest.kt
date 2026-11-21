package com.drivelift.app.feature.intelligence

import com.drivelift.app.feature.intelligence.nlsearch.ArabicUrduTextNormalizer
import com.drivelift.app.feature.intelligence.nlsearch.FtsQuerySanitizer
import com.drivelift.app.feature.intelligence.nlsearch.NaturalLanguageQueryParser
import com.drivelift.app.feature.intelligence.nlsearch.SearchQueryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class NaturalLanguageQueryParserTest {

    // 2026-11-21T12:00:00Z
    private val now = LocalDate.of(2026, 11, 21).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * HOUR
    private val parser = NaturalLanguageQueryParser(now = { now }, zone = ZoneOffset.UTC)

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun pdfFromLastWeekOverSize() {
        val q = parser.parse("PDFs from last week over 2MB")
        assertTrue("pdf" in q.extensionFilter)
        assertTrue("application/pdf" in q.mimeFilter)
        assertEquals(2L * MB, q.minSizeBytes)
        assertEquals(now - 7 * DAY, q.dateAfterEpochMs)
        assertTrue(q.ftsTerms.isEmpty())
        assertNull(q.toFtsMatch())
    }

    @Test
    fun relativeDates() {
        val y = parser.parse("yesterday")
        assertEquals(day(2026, 11, 20), y.dateAfterEpochMs)
        assertEquals(day(2026, 11, 21), y.dateBeforeEpochMs)
        assertEquals(day(2026, 11, 21), parser.parse("today").dateAfterEpochMs)
        assertEquals(now - 30 * DAY, parser.parse("last 30 days").dateAfterEpochMs)
        assertEquals(day(2026, 11, 1), parser.parse("this month").dateAfterEpochMs)
        val lm = parser.parse("last month")
        assertEquals(day(2026, 10, 1), lm.dateAfterEpochMs)
        assertEquals(day(2026, 11, 1), lm.dateBeforeEpochMs)
    }

    @Test
    fun monthNamesPickTheMostRecentPastMonth() {
        val oct = parser.parse("receipts october")
        assertEquals(day(2026, 10, 1), oct.dateAfterEpochMs)
        assertEquals(day(2026, 11, 1), oct.dateBeforeEpochMs)
        // December has not happened yet this year, so it means last December.
        assertEquals(day(2025, 12, 1), parser.parse("december").dateAfterEpochMs)
        assertEquals(day(2025, 11, 1), parser.parse("Nov 2025 photos").dateAfterEpochMs)
    }

    @Test
    fun sizes() {
        assertEquals(500L * MB, parser.parse("> 500MB").minSizeBytes)
        assertEquals(1024L * MB, parser.parse("larger than 1GB").minSizeBytes)
        assertEquals(2L * MB, parser.parse("under 2MB").maxSizeBytes)
        assertEquals(50L * MB, parser.parse("videos under ۵۰ mb").maxSizeBytes)
    }

    @Test
    fun fileTypeIntents() {
        assertTrue("image/" in parser.parse("photos").mimeFilter)
        assertTrue("video/" in parser.parse("video").mimeFilter)
        assertTrue("apk" in parser.parse("apk").extensionFilter)
        assertEquals("screenshot", parser.parse("screenshots").folderHint)
        assertTrue("docx" in parser.parse("documents").extensionFilter)
    }

    @Test
    fun multilingualIntentKeywords() {
        val receipts = parser.parse("receipt")
        assertEquals(1, receipts.anyOfGroups.size)
        assertTrue(ArabicUrduTextNormalizer.normalize("رسید") in receipts.anyOfGroups[0])
        assertTrue("invoice" in receipts.anyOfGroups[0])
        assertEquals(receipts.anyOfGroups, parser.parse("رسید").anyOfGroups)
        assertEquals(receipts.anyOfGroups, parser.parse("invoice").anyOfGroups)

        val dua = parser.parse("dua")
        assertTrue("Duas" in dua.understood)
        assertEquals(dua.anyOfGroups, parser.parse("دعا").anyOfGroups)
        assertTrue(dua.toFtsMatch()!!.contains(ArabicUrduTextNormalizer.normalize("ربنا") + "*"))
    }

    @Test
    fun remainingWordsBecomeNormalizedTerms() {
        val q = parser.parse("Electricity BILL from last month")
        assertTrue("electricity" in q.ftsTerms)
        assertEquals(1, q.anyOfGroups.size) // "bill" is the receipt intent
    }

    @Test
    fun ftsSanitizationStripsOperatorsAndPunctuation() {
        val q = parser.parse("\"hello\" OR (world)* -x NEAR:")
        assertEquals("hello* or* world* near*", q.toFtsMatch())
        val match = FtsQuerySanitizer.build(listOf("a\"b'c;DROP TABLE x"), emptyList())!!
        assertFalse(match.contains('"'))
        assertFalse(match.contains('\''))
        assertFalse(match.contains(';'))
        assertNull(FtsQuerySanitizer.build(listOf("\"*()"), emptyList()))
    }

    @Test
    fun arabicTermAlsoMatchesWithDefiniteArticle() {
        assertEquals("کتاب* OR الکتاب*", FtsQuerySanitizer.build(listOf("كتاب"), emptyList()))
        assertEquals("الکتاب*", FtsQuerySanitizer.build(listOf("الكتاب"), emptyList()))
    }

    @Test
    fun searchSqlIsParameterized() {
        val built = SearchQueryBuilder.build(parser.parse("pdf over 2mb"))!!
        assertFalse(built.sql.contains("MATCH"))
        assertTrue(built.sql.contains("d.sizeBytes >= ?"))
        assertTrue("application/pdf%" in built.args)
        assertTrue("%.pdf" in built.args)

        val text = SearchQueryBuilder.build(parser.parse("electricity' OR 1=1 --"))!!
        assertTrue(text.sql.contains("indexed_documents_fts MATCH ?"))
        assertFalse(text.sql.contains("electricity"))
        assertNull(SearchQueryBuilder.build(parser.parse("   ")))
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
