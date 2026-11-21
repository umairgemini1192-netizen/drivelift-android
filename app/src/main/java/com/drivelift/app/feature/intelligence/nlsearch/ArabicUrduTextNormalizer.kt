package com.drivelift.app.feature.intelligence.nlsearch

import java.text.Normalizer

/**
 * Folds Arabic, Urdu and Latin text into one comparable form. The same function runs when a
 * document is indexed (`normalizedText`) and when a query is typed, so a plain query typed on any
 * keyboard finds vocalized or differently-spelled text:
 *
 * - Compatibility decomposition (NFKD) first, so Arabic presentation forms that PDFs often carry
 *   become base letters, and composed letters (`أ`, `ۂ`, `ئ`) split into a base letter plus a mark.
 * - Arabic diacritics / Tashkeel (`U+064B..U+065F`, `U+0670`, `U+06D6..U+06ED`), Tatweel
 *   (`U+0640`), Latin combining accents and invisible direction / joiner marks are removed.
 * - Alif variants (`أ إ آ ٱ`) become `ا`; Kaf `ك` becomes `ک`; Yeh / Alif Maksura
 *   (`ي ى ی ے`) become `ی`; Heh / Teh Marbuta (`ة ه ہ ھ ە`) become `ه`.
 * - Arabic-Indic and Extended Arabic-Indic (Urdu) digits become ASCII `0-9`.
 * - Letters are lower-cased.
 */
object ArabicUrduTextNormalizer {

    /** Normalized text only. Identical to `normalizeWithMap(input).text`. */
    fun normalize(input: String): String = normalizeWithMap(input).text

    /**
     * Normalized text plus, for each normalized character, the index of the original character it
     * came from. Snippets use the map to highlight a match inside the original, vocalized text.
     */
    fun normalizeWithMap(input: String): NormalizedText {
        val out = StringBuilder(input.length)
        val origin = IntArray(input.length * 2 + 4)
        var count = 0
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val width = Character.charCount(cp)
            val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
            var j = 0
            while (j < decomposed.length) {
                val folded = fold(decomposed[j])
                if (folded != DROP) {
                    if (count == origin.size) return slowPath(input)
                    out.append(folded)
                    origin[count++] = i
                }
                j++
            }
            i += width
        }
        return NormalizedText(out.toString(), origin.copyOf(count))
    }

    /** Rare inputs whose decomposition is longer than twice the input; correct but slower. */
    private fun slowPath(input: String): NormalizedText {
        val out = StringBuilder()
        val origin = ArrayList<Int>()
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
            for (c in decomposed) {
                val folded = fold(c)
                if (folded != DROP) {
                    out.append(folded)
                    origin += i
                }
            }
            i += Character.charCount(cp)
        }
        return NormalizedText(out.toString(), origin.toIntArray())
    }

    /** True when [text] holds at least one Arabic-script letter (Arabic, Urdu, Persian). */
    fun containsArabicScript(text: CharSequence): Boolean {
        for (c in text) if (isArabicScript(c)) return true
        return false
    }

    fun isArabicScript(c: Char): Boolean {
        val code = c.code
        return code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF ||
            code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF
    }

    private const val DROP = '\u0000'

    private fun fold(c: Char): Char {
        val code = c.code
        return when {
            // Tashkeel / Harakat, Quranic annotation marks, superscript Alif, Tatweel.
            code in 0x064B..0x065F || code == 0x0670 || code in 0x06D6..0x06ED || code == 0x0640 -> DROP
            // Latin combining accents left by NFKD (é -> e + U+0301).
            code in 0x0300..0x036F -> DROP
            // Zero-width space / joiners / direction marks, Arabic letter mark, BOM, embeddings.
            code in 0x200B..0x200F || code == 0x061C || code == 0xFEFF ||
                code in 0x202A..0x202E || code in 0x2066..0x2069 -> DROP
            // Alif variants. NFKD already splits أ إ آ; Alif Wasla has no decomposition.
            c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ' || c == 'ٲ' || c == 'ٳ' -> 'ا'
            // Kaf -> Keheh (Urdu/Persian keyboard form).
            c == 'ك' -> 'ک'
            // Yeh, Alif Maksura, Farsi Yeh, Yeh Barree -> Farsi Yeh.
            c == 'ي' || c == 'ى' || c == 'ی' || c == 'ے' -> 'ی'
            // Teh Marbuta, Heh, Heh Goal, Heh Doachashmee, Ae -> Heh.
            c == 'ة' || c == 'ه' || c == 'ہ' || c == 'ھ' || c == 'ە' -> 'ه'
            // Arabic-Indic and Extended Arabic-Indic (Urdu) digits.
            code in 0x0660..0x0669 -> ('0'.code + (code - 0x0660)).toChar()
            code in 0x06F0..0x06F9 -> ('0'.code + (code - 0x06F0)).toChar()
            else -> c.lowercaseChar()
        }
    }
}

/** A normalized string and the original index of every one of its characters. */
class NormalizedText(val text: String, val originIndex: IntArray) {
    /**
     * The range in the original text covered by normalized range [start, endExclusive), or null
     * when the range is empty.
     */
    fun originalRange(start: Int, endExclusive: Int, original: String): IntRange? {
        if (start < 0 || endExclusive > text.length || start >= endExclusive) return null
        val from = originIndex[start]
        var to = originIndex[endExclusive - 1]
        // Carry the marks that follow the last matched letter (its Harakat), so the highlight
        // covers the whole vocalized word fragment.
        val nextOrigin = if (endExclusive < originIndex.size) originIndex[endExclusive] else original.length
        to = maxOf(to, nextOrigin - 1)
        return from..to.coerceAtMost(original.length - 1)
    }
}
