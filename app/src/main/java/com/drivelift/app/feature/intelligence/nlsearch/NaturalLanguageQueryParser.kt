package com.drivelift.app.feature.intelligence.nlsearch

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * What a plain-language query asks for. Every field is optional; an empty query has no terms and
 * no filters.
 *
 * - [ftsTerms]: normalized words that must all appear (AND).
 * - [anyOfGroups]: each group needs at least one of its words (an intent such as "receipt").
 * - [mimeFilter] / [extensionFilter]: a document matches when its MIME type starts with any of
 *   the prefixes or its name ends with any of the extensions (one OR group).
 */
data class ParsedSearchQuery(
    val ftsTerms: List<String> = emptyList(),
    val anyOfGroups: List<List<String>> = emptyList(),
    val mimeFilter: List<String> = emptyList(),
    val extensionFilter: List<String> = emptyList(),
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val dateAfterEpochMs: Long? = null,
    val dateBeforeEpochMs: Long? = null,
    val folderHint: String? = null,
    /** Short, user-facing names of what was understood ("PDF", "Last 7 days", "Over 50 MB"). */
    val understood: List<String> = emptyList(),
) {
    val hasTextMatch: Boolean get() = ftsTerms.isNotEmpty() || anyOfGroups.isNotEmpty()
    val hasFilters: Boolean
        get() = mimeFilter.isNotEmpty() || extensionFilter.isNotEmpty() || minSizeBytes != null ||
            maxSizeBytes != null || dateAfterEpochMs != null || dateBeforeEpochMs != null || folderHint != null
    val isEmpty: Boolean get() = !hasTextMatch && !hasFilters

    /** The FTS4 MATCH expression, or null when there is nothing to match on. */
    fun toFtsMatch(): String? = FtsQuerySanitizer.build(ftsTerms, anyOfGroups)
}

/**
 * Deterministic, fully offline parser from a plain query ("pdfs from last week over 2mb",
 * "دعا", "receipts october") to a [ParsedSearchQuery]. No model, no network: a fixed vocabulary
 * of file type, date and size phrases, in English, Arabic and Urdu. Whatever is left becomes
 * normalized search words.
 */
class NaturalLanguageQueryParser(
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun parse(raw: String): ParsedSearchQuery {
        // Digits first, so "۵۰ MB" and "٥٠MB" read as sizes; then lower-case for the vocabulary.
        var text = " " + ArabicUrduTextNormalizer.normalize(raw).replace(Regex("\\s+"), " ") + " "
        val understood = ArrayList<String>()

        var minSize: Long? = null
        var maxSize: Long? = null
        SIZE_PATTERN.findAll(text).toList().forEach { m ->
            val op = m.groupValues[1].trim()
            val bytes = toBytes(m.groupValues[2], m.groupValues[3])
            if (bytes != null) {
                val label = "${m.groupValues[2]} ${m.groupValues[3].uppercase(Locale.ROOT)}"
                if (op in MAX_OPS) {
                    maxSize = bytes
                    understood += "Under $label"
                } else {
                    minSize = bytes
                    understood += "Over $label"
                }
            }
        }
        text = SIZE_PATTERN.replace(text, " ")

        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        var after: Long? = null
        var before: Long? = null
        fun startOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

        for ((pattern, kind) in RELATIVE_DATES) {
            val m = pattern.find(text) ?: continue
            when (kind) {
                DateKind.TODAY -> { after = startOf(today); understood += "Today" }
                DateKind.YESTERDAY -> {
                    after = startOf(today.minusDays(1)); before = startOf(today); understood += "Yesterday"
                }
                DateKind.LAST_7 -> { after = now() - 7 * DAY_MS; understood += "Last 7 days" }
                DateKind.LAST_30 -> { after = now() - 30 * DAY_MS; understood += "Last 30 days" }
                DateKind.THIS_MONTH -> { after = startOf(today.withDayOfMonth(1)); understood += "This month" }
                DateKind.LAST_MONTH -> {
                    val month = YearMonth.from(today).minusMonths(1)
                    after = startOf(month.atDay(1)); before = startOf(month.plusMonths(1).atDay(1))
                    understood += "Last month"
                }
                DateKind.THIS_YEAR -> { after = startOf(today.withDayOfYear(1)); understood += "This year" }
            }
            text = text.replaceRange(m.range, " ")
            break
        }

        if (after == null && before == null) {
            val m = MONTH_PATTERN.find(text)
            if (m != null) {
                val month = MONTHS.getValue(m.groupValues[1])
                val explicitYear = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                val year = explicitYear ?: if (month > today.monthValue) today.year - 1 else today.year
                val ym = YearMonth.of(year, month)
                after = startOf(ym.atDay(1))
                before = startOf(ym.plusMonths(1).atDay(1))
                understood += ym.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) +
                    (if (explicitYear != null) " $year" else "")
                text = text.replaceRange(m.range, " ")
            }
        }

        val mime = LinkedHashSet<String>()
        val ext = LinkedHashSet<String>()
        val groups = ArrayList<List<String>>()
        var folderHint: String? = null
        val words = ArrayList<String>()
        for (word in text.split(' ').filter { it.isNotBlank() }) {
            val intent = INTENTS[word]
            when {
                intent != null -> {
                    mime += intent.mime
                    ext += intent.extensions
                    if (intent.anyOf.isNotEmpty() && intent.anyOf !in groups) groups += intent.anyOf
                    if (intent.folderHint != null) folderHint = intent.folderHint
                    if (intent.label !in understood) understood += intent.label
                }
                word in STOP_WORDS -> Unit
                else -> words += word
            }
        }

        return ParsedSearchQuery(
            ftsTerms = FtsQuerySanitizer.tokens(words.joinToString(" ")),
            anyOfGroups = groups,
            mimeFilter = mime.toList(),
            extensionFilter = ext.toList(),
            minSizeBytes = minSize,
            maxSizeBytes = maxSize,
            dateAfterEpochMs = after,
            dateBeforeEpochMs = before,
            folderHint = folderHint,
            understood = understood,
        )
    }

    private fun toBytes(number: String, unit: String): Long? {
        val value = number.toDoubleOrNull() ?: return null
        val mult = when (unit.lowercase(Locale.ROOT)) {
            "b" -> 1L
            "kb" -> KB
            "mb" -> KB * KB
            "gb" -> KB * KB * KB
            else -> return null
        }
        return (value * mult).toLong()
    }

    private enum class DateKind { TODAY, YESTERDAY, LAST_7, LAST_30, THIS_MONTH, LAST_MONTH, THIS_YEAR }

    private data class Intent(
        val label: String,
        val mime: List<String> = emptyList(),
        val extensions: List<String> = emptyList(),
        val anyOf: List<String> = emptyList(),
        val folderHint: String? = null,
    )

    companion object {
        private const val KB = 1024L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private val MAX_OPS = setOf("under", "below", "less than", "smaller than", "<", "<=")

        private val SIZE_PATTERN = Regex(
            "(over|above|more than|larger than|bigger than|greater than|under|below|less than|smaller than|>=|<=|>|<)" +
                "\\s*(\\d+(?:\\.\\d+)?)\\s*(kb|mb|gb|b)\\b",
        )

        private val RELATIVE_DATES: List<Pair<Regex, DateKind>> = listOf(
            Regex(" (today|${ArabicUrduTextNormalizer.normalize("آج")}|${ArabicUrduTextNormalizer.normalize("اليوم")}) ") to DateKind.TODAY,
            Regex(" yesterday ") to DateKind.YESTERDAY,
            Regex(" (last|past) (7 days|week|seven days) ") to DateKind.LAST_7,
            Regex(" this week ") to DateKind.LAST_7,
            Regex(" (last|past) (30 days|thirty days) ") to DateKind.LAST_30,
            Regex(" this month ") to DateKind.THIS_MONTH,
            Regex(" last month ") to DateKind.LAST_MONTH,
            Regex(" this year ") to DateKind.THIS_YEAR,
        )

        private val MONTHS: Map<String, Int> = mapOf(
            "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
            "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
            "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9, "october" to 10,
            "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
        )

        private val MONTH_PATTERN = Regex(
            " (${MONTHS.keys.sortedByDescending { it.length }.joinToString("|")})(?: (\\d{4}))? ",
        )

        private val DUA = listOf("dua", "duas", "دعا", "ادعیه", "ربنا", "اللهم")
        private val RECEIPT = listOf("receipt", "invoice", "bill", "total", "رسید", "فاتوره", "بل")

        private val INTENTS: Map<String, Intent> = buildMap {
            val pdf = Intent("PDF", mime = listOf("application/pdf"), extensions = listOf("pdf"))
            listOf("pdf", "pdfs").forEach { put(it, pdf) }
            val receipt = Intent("Receipts", anyOf = RECEIPT.map { ArabicUrduTextNormalizer.normalize(it) })
            listOf("receipt", "receipts", "invoice", "invoices", "bill", "bills", "رسید", "رسیدیں", "فاتوره")
                .forEach { put(ArabicUrduTextNormalizer.normalize(it), receipt) }
            val dua = Intent("Duas", anyOf = DUA.map { ArabicUrduTextNormalizer.normalize(it) })
            listOf("dua", "duas", "دعا", "دعاء", "دعائیں", "ادعیه").forEach { put(ArabicUrduTextNormalizer.normalize(it), dua) }
            val screenshot = Intent("Screenshots", mime = listOf("image/"), folderHint = "screenshot")
            listOf("screenshot", "screenshots").forEach { put(it, screenshot) }
            val photo = Intent("Photos", mime = listOf("image/"))
            listOf("photo", "photos", "image", "images", "picture", "pictures", "pic", "pics", "تصویر", "صوره")
                .forEach { put(ArabicUrduTextNormalizer.normalize(it), photo) }
            val video = Intent("Videos", mime = listOf("video/"))
            listOf("video", "videos", "ویڈیو", "فیدیو").forEach { put(ArabicUrduTextNormalizer.normalize(it), video) }
            val apk = Intent("APK", mime = listOf("application/vnd.android.package-archive"), extensions = listOf("apk"))
            listOf("apk", "apks").forEach { put(it, apk) }
            val doc = Intent(
                "Documents",
                mime = listOf("application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument", "text/"),
                extensions = listOf("pdf", "doc", "docx", "txt", "odt", "rtf"),
            )
            listOf("document", "documents", "doc", "docs", "دستاویز").forEach { put(ArabicUrduTextNormalizer.normalize(it), doc) }
        }

        private val STOP_WORDS = setOf(
            "from", "in", "on", "the", "of", "with", "my", "all", "files", "file", "show", "find",
            "me", "and", "a", "an", "for", "at", "to", "that", "taken", "dated", "search",
        )
    }
}

/**
 * Builds a safe FTS4 MATCH string from user words. Only letters and digits survive, so quotes,
 * `*`, `-`, `:`, parentheses and `NEAR` / `OR` operators typed by the user can never change the
 * query's meaning or make SQLite throw. Every word is a prefix match.
 */
object FtsQuerySanitizer {

    private const val MAX_TERMS = 12

    /** Normalizes [text] and splits it into letter/digit words, at most [MAX_TERMS]. */
    fun tokens(text: String): List<String> {
        val normalized = ArabicUrduTextNormalizer.normalize(text)
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                val word = current.toString()
                if (word.length >= 2 || word.all { it.isDigit() }) out += word
                current.setLength(0)
            }
        }
        for (c in normalized) {
            if (Character.isLetterOrDigit(c)) current.append(c) else flush()
        }
        flush()
        return out.distinct().take(MAX_TERMS)
    }

    /**
     * `group1a* OR group1b* term1* term2*`. In FTS4 OR binds tighter than the implicit AND, so
     * every group and every term must match. An Arabic word also matches with the definite
     * article (`کتاب*` finds `الکتاب`).
     */
    fun build(terms: List<String>, anyOfGroups: List<List<String>>): String? {
        val parts = ArrayList<String>()
        for (group in anyOfGroups) {
            val alternatives = group.flatMap { tokens(it) }.distinct().flatMap { variants(it) }
            if (alternatives.isNotEmpty()) parts += alternatives.joinToString(" OR ")
        }
        for (term in terms.flatMap { tokens(it) }.distinct()) {
            parts += variants(term).joinToString(" OR ")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun variants(word: String): List<String> {
        val base = "$word*"
        val arabic = ArabicUrduTextNormalizer.isArabicScript(word.first())
        return if (arabic && !word.startsWith("ال") && word.length >= 2) listOf(base, "ال$word*") else listOf(base)
    }
}
