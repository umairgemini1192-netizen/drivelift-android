package com.drivelift.app.feature.intelligence.nlsearch

import androidx.sqlite.db.SimpleSQLiteQuery
import com.drivelift.app.feature.intelligence.data.IndexedDocumentEntity
import com.drivelift.app.feature.intelligence.data.IntelligenceDao
import java.util.Locale

/** SQL text plus its bound arguments. User text only ever travels in [args]. */
data class BuiltSearchSql(val sql: String, val args: List<Any>)

/** Turns a [ParsedSearchQuery] into one parameterized query over the document index. */
object SearchQueryBuilder {
    const val DEFAULT_LIMIT = 200

    fun build(query: ParsedSearchQuery, limit: Int = DEFAULT_LIMIT): BuiltSearchSql? {
        if (query.isEmpty) return null
        val args = ArrayList<Any>()
        val sql = StringBuilder("SELECT d.* FROM indexed_documents d")
        val where = ArrayList<String>()
        val match = query.toFtsMatch()
        if (match != null) {
            sql.append(" JOIN indexed_documents_fts ON indexed_documents_fts.rowid = d.id")
            where += "indexed_documents_fts MATCH ?"
            args += match
        } else if (query.hasTextMatch) {
            // Words that sanitize to nothing (only punctuation) cannot match anything.
            return null
        }
        query.minSizeBytes?.let { where += "d.sizeBytes >= ?"; args += it }
        query.maxSizeBytes?.let { where += "d.sizeBytes <= ?"; args += it }
        query.dateAfterEpochMs?.let { where += "d.modifiedAt >= ?"; args += it }
        query.dateBeforeEpochMs?.let { where += "d.modifiedAt < ?"; args += it }
        val typeOr = ArrayList<String>()
        for (prefix in query.mimeFilter) {
            typeOr += "d.mimeType LIKE ?"
            args += prefix.lowercase(Locale.ROOT) + "%"
        }
        for (ext in query.extensionFilter) {
            typeOr += "LOWER(d.displayName) LIKE ?"
            args += "%." + ext.lowercase(Locale.ROOT)
        }
        if (typeOr.isNotEmpty()) where += typeOr.joinToString(" OR ", "(", ")")
        query.folderHint?.let { hint ->
            where += "(LOWER(d.folderPath) LIKE ? OR LOWER(d.displayName) LIKE ?)"
            val like = "%" + hint.lowercase(Locale.ROOT) + "%"
            args += like
            args += like
        }
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where.joinToString(" AND "))
        sql.append(" ORDER BY d.modifiedAt DESC LIMIT ").append(limit.coerceIn(1, 1000))
        return BuiltSearchSql(sql.toString(), args)
    }
}

/** A highlighted preview from a document's original text. Ranges index into [text]. */
data class SearchSnippet(
    val text: String,
    val highlights: List<IntRange>,
    val rightToLeft: Boolean,
)

/**
 * Cuts a short preview out of the original (vocalized) text around the first match, and marks
 * every matched word fragment. Matching runs on normalized text; the normalizer's index map
 * carries the ranges back into the original, so Harakat and Tashkeel stay in the preview.
 */
object SnippetBuilder {
    private const val RADIUS = 70

    fun build(original: String, terms: List<String>): SearchSnippet? {
        val source = original.trim()
        if (source.isEmpty()) return null
        val normalized = ArabicUrduTextNormalizer.normalizeWithMap(source)
        val hits = ArrayList<IntRange>()
        val needles = terms.map { ArabicUrduTextNormalizer.normalize(it) }.filter { it.isNotEmpty() }.distinct()
        for (needle in needles) {
            var from = 0
            while (true) {
                val at = normalized.text.indexOf(needle, from)
                if (at < 0) break
                normalized.originalRange(at, at + needle.length, source)?.let { hits += it }
                from = at + needle.length
                if (hits.size > 64) break
            }
        }
        val sorted = hits.sortedBy { it.first }
        val center = sorted.firstOrNull()?.first ?: 0
        var start = (center - RADIUS).coerceAtLeast(0)
        var end = (center + RADIUS * 2).coerceAtMost(source.length)
        // Do not cut through a word.
        while (start > 0 && !source[start - 1].isWhitespace()) start--
        while (end < source.length && !source[end].isWhitespace()) end++
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < source.length) "…" else ""
        val body = source.substring(start, end).replace('\n', ' ')
        val shift = prefix.length - start
        val inWindow = sorted
            .filter { it.first >= start && it.last < end }
            .map { (it.first + shift)..(it.last + shift) }
        val text = prefix + body + suffix
        return SearchSnippet(text, inWindow, ArabicUrduTextNormalizer.containsArabicScript(body))
    }
}

/** A search hit ready for the result list. */
data class SearchResult(
    val document: IndexedDocumentEntity,
    val snippet: SearchSnippet?,
)

/** Runs a parsed query against the offline FTS4 index. Nothing leaves the phone. */
class OfflineSearchRepository(
    private val dao: IntelligenceDao,
    private val parser: NaturalLanguageQueryParser,
) {
    fun parse(raw: String): ParsedSearchQuery = parser.parse(raw)

    suspend fun search(query: ParsedSearchQuery): List<SearchResult> {
        val built = SearchQueryBuilder.build(query) ?: return emptyList()
        val rows = dao.searchDocuments(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
        val highlightTerms = query.ftsTerms + query.anyOfGroups.flatten()
        return rows.map { doc -> SearchResult(doc, SnippetBuilder.build(doc.extractedText, highlightTerms)) }
    }
}
