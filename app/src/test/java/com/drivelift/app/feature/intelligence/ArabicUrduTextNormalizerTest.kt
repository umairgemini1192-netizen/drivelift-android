package com.drivelift.app.feature.intelligence

import com.drivelift.app.feature.intelligence.nlsearch.ArabicUrduTextNormalizer
import com.drivelift.app.feature.intelligence.nlsearch.NaturalLanguageQueryParser
import com.drivelift.app.feature.intelligence.nlsearch.SnippetBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ArabicUrduTextNormalizerTest {

    private fun n(s: String) = ArabicUrduTextNormalizer.normalize(s)

    @Test
    fun plainArabicQueryMatchesVocalizedDua() {
        val dua = "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً"
        assertEquals("ربنا اتنا", n("رَبَّنَا آتِنَا"))
        assertEquals(n("ربنا اتنا"), n("رَبَّنَا آتِنَا"))
        assertTrue(n(dua).contains(n("ربنا اتنا")))
        assertTrue(n(dua).contains(n("الدنيا")))
    }

    @Test
    fun alifVariantsAndTatweelFold() {
        assertEquals(n("احمد"), n("أحمد"))
        assertEquals(n("اسلام"), n("إسلام"))
        assertEquals(n("امنوا"), n("آمنوا"))
        assertEquals(n("الله"), n("ٱلله"))
        assertEquals(n("الله"), n("اللـــه"))
    }

    @Test
    fun crossKeyboardArabicAndUrduLettersUnify() {
        // Kaf: Arabic keyboard vs Urdu keyboard.
        assertEquals(n("کتاب"), n("كتاب"))
        assertEquals("کتاب", n("كتاب"))
        // Yeh, Alif Maksura, Farsi Yeh, Yeh Barree.
        assertEquals("ی", n("ي"))
        assertEquals("ی", n("ى"))
        assertEquals("ی", n("ی"))
        assertEquals("ی", n("ے"))
        assertEquals(n("علي"), n("علی"))
        // Heh, Heh Goal, Teh Marbuta, Heh Doachashmee.
        assertEquals(n("رحمه"), n("رحمة"))
        assertEquals(n("رحمه"), n("رحمہ"))
        assertEquals("ه", n("ھ"))
    }

    @Test
    fun arabicIndicAndUrduDigitsBecomeAscii() {
        assertEquals("2026", n("٢٠٢٦"))
        assertEquals("1234567890", n("۱۲۳۴۵۶۷۸۹۰"))
        assertEquals("50mb", n("٥٠MB"))
    }

    @Test
    fun urduNumeralsWorkForSizeAndDateParsing() {
        val parser = NaturalLanguageQueryParser(now = { 1_795_262_400_000L }, zone = ZoneOffset.UTC)
        val size = parser.parse("over ۵۰ MB")
        assertEquals(50L * 1024 * 1024, size.minSizeBytes)
        val date = parser.parse("nov ٢٠٢٥")
        assertEquals(java.time.LocalDate.of(2025, 11, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), date.dateAfterEpochMs)
    }

    @Test
    fun presentationFormsLatinAccentsAndInvisibleMarksFold() {
        assertEquals("لا", n("ﻻ"))
        assertEquals("cafe", n("Café"))
        assertEquals("invoice", n("INVOICE"))
        assertEquals("میں", n("می‌ں"))
    }

    @Test
    fun normalizationIsIdempotent() {
        val text = "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً وَفِي الْآخِرَةِ ۱۲ Receipt"
        assertEquals(n(text), n(n(text)))
    }

    @Test
    fun snippetHighlightsInsideOriginalVocalizedText() {
        val original = "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً"
        val snippet = SnippetBuilder.build(original, listOf("اتنا"))!!
        assertTrue(snippet.rightToLeft)
        assertEquals(1, snippet.highlights.size)
        assertEquals("آتِنَا", snippet.text.substring(snippet.highlights[0]))
    }

    @Test
    fun arabicScriptDetection() {
        assertTrue(ArabicUrduTextNormalizer.containsArabicScript("Invoice رسید 12"))
        assertFalse(ArabicUrduTextNormalizer.containsArabicScript("Invoice 12"))
    }
}
