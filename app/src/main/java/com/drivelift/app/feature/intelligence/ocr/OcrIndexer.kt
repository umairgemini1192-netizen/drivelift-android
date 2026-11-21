package com.drivelift.app.feature.intelligence.ocr

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.drivelift.app.Graph
import com.drivelift.app.feature.intelligence.data.ExtractionStatus
import com.drivelift.app.feature.intelligence.data.IndexedDocumentEntity
import com.drivelift.app.feature.intelligence.data.IndexedDocumentKey
import com.drivelift.app.feature.intelligence.data.IntelligenceDao
import com.drivelift.app.feature.intelligence.data.IntelligencePrefs
import com.drivelift.app.feature.intelligence.nlsearch.ArabicUrduTextNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Live state of the background indexer, for the status banner. */
data class IndexingTelemetry(
    val running: Boolean = false,
    val indexedCount: Int = 0,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val currentFile: String? = null,
    val lastRunAt: Long? = null,
)

/** Shared, in-memory telemetry. The database holds the durable counts. */
class IndexingBus {
    private val _telemetry = MutableStateFlow(IndexingTelemetry())
    val telemetry: StateFlow<IndexingTelemetry> = _telemetry.asStateFlow()

    fun update(transform: (IndexingTelemetry) -> IndexingTelemetry) = _telemetry.update(transform)
}

/**
 * Which documents need reading. A document whose uri, modified time and size are unchanged is
 * left alone (including one that failed before, so a broken file is not retried on every run);
 * one that is new, changed or still pending is read; an indexed document no longer on the phone
 * is removed from the index.
 */
object OcrIndexDelta {
    data class Plan(
        val toIndex: List<DocumentCandidate>,
        val unchangedCount: Int,
        val removedUris: List<String>,
    )

    fun compute(existing: List<IndexedDocumentKey>, candidates: List<DocumentCandidate>): Plan {
        val known = existing.associateBy { it.uri }
        val present = HashSet<String>(candidates.size * 2)
        val toIndex = ArrayList<DocumentCandidate>()
        var unchanged = 0
        for (c in candidates) {
            present += c.uri
            val row = known[c.uri]
            if (row != null && row.modifiedAt == c.modifiedAt && row.sizeBytes == c.sizeBytes &&
                row.extractionStatus != ExtractionStatus.PENDING.name
            ) {
                unchanged++
            } else {
                toIndex += c
            }
        }
        val removed = existing.map { it.uri }.filter { it !in present }
        return Plan(toIndex, unchanged, removed)
    }
}

data class IndexRunSummary(val indexed: Int, val failed: Int, val skipped: Int, val unchanged: Int, val removed: Int)

/**
 * Incremental indexer: lists documents, applies [OcrIndexDelta], removes deleted files, records
 * new or changed ones as PENDING (so the banner can count them) and then reads them one at a
 * time, newest first. Stops cleanly when paused or cancelled; the next run resumes the pending
 * rows.
 */
class OcrIndexer(
    private val dao: IntelligenceDao,
    private val source: DocumentSource,
    private val extractor: DocumentTextExtractor,
    private val bus: IndexingBus,
    private val clock: () -> Long,
    private val isPaused: () -> Boolean,
) {
    suspend fun run(): IndexRunSummary {
        bus.update { it.copy(running = true, currentFile = null) }
        try {
            val plan = OcrIndexDelta.compute(dao.documentKeys(), source.candidates())
            if (plan.removedUris.isNotEmpty()) plan.removedUris.chunked(500).forEach { dao.deleteDocuments(it) }
            for (c in plan.toIndex) {
                dao.upsertDocument(
                    IndexedDocumentEntity(
                        uri = c.uri, displayName = c.displayName, folderPath = c.folderPath,
                        mimeType = c.mimeType, sizeBytes = c.sizeBytes, modifiedAt = c.modifiedAt,
                        extractionStatus = ExtractionStatus.PENDING.name,
                        normalizedText = nameText(c.displayName, c.folderPath),
                    ),
                )
            }
            refreshCounts()
            var indexed = 0
            var failed = 0
            var skipped = 0
            while (true) {
                val batch = dao.pendingDocuments(BATCH)
                if (batch.isEmpty()) break
                for (doc in batch) {
                    coroutineContext.ensureActive()
                    if (isPaused()) return IndexRunSummary(indexed, failed, skipped, plan.unchangedCount, plan.removedUris.size)
                    bus.update { it.copy(currentFile = doc.displayName) }
                    val candidate = DocumentCandidate(doc.uri, doc.displayName, doc.folderPath, doc.mimeType, doc.sizeBytes, doc.modifiedAt)
                    val result = try {
                        extractor.extract(candidate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ExtractionResult(ExtractionStatus.FAILED)
                    }
                    when (result.status) {
                        ExtractionStatus.INDEXED -> indexed++
                        ExtractionStatus.FAILED -> failed++
                        else -> skipped++
                    }
                    val raw = result.text.take(MAX_TEXT_CHARS)
                    dao.upsertDocument(
                        doc.copy(
                            indexedAt = clock(),
                            extractionStatus = if (result.status == ExtractionStatus.PENDING) ExtractionStatus.FAILED.name else result.status.name,
                            pageCount = result.pageCount,
                            extractedText = raw,
                            normalizedText = ArabicUrduTextNormalizer.normalize(raw) + "\n" + nameText(doc.displayName, doc.folderPath),
                        ),
                    )
                    refreshCounts()
                }
            }
            return IndexRunSummary(indexed, failed, skipped, plan.unchangedCount, plan.removedUris.size)
        } finally {
            refreshCounts()
            bus.update { it.copy(running = false, currentFile = null, lastRunAt = clock()) }
        }
    }

    private suspend fun refreshCounts() {
        val counts = dao.statusCounts().associate { it.extractionStatus to it.count }
        bus.update {
            it.copy(
                indexedCount = (counts[ExtractionStatus.INDEXED.name] ?: 0) + (counts[ExtractionStatus.SKIPPED.name] ?: 0),
                pendingCount = counts[ExtractionStatus.PENDING.name] ?: 0,
                failedCount = counts[ExtractionStatus.FAILED.name] ?: 0,
            )
        }
    }

    private fun nameText(name: String, folder: String): String =
        ArabicUrduTextNormalizer.normalize("$name $folder")

    companion object {
        private const val BATCH = 25
        const val MAX_TEXT_CHARS = 100_000
    }
}

/** WorkManager entry for the indexer. Runs only while the battery is not low (and charging if asked). */
class OcrIndexingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val module = Graph.get(applicationContext).intelligence
        if (module.prefs.paused.value) return Result.success()
        return try {
            module.indexer.run()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** Schedules indexing: a periodic delta run plus "Scan now". */
class IndexScheduler(private val context: Context, private val prefs: IntelligencePrefs) {
    private fun constraints() = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(prefs.chargingOnly.value)
        .build()

    fun scanNow() {
        if (prefs.paused.value) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OcrIndexingWorker>().setConstraints(constraints()).build(),
        )
    }

    /** Keeps the index fresh twice a day. [replace] applies changed constraints. */
    fun schedulePeriodic(replace: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OcrIndexingWorker>(12, TimeUnit.HOURS).setConstraints(constraints()).build(),
        )
    }

    fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
    }

    companion object {
        const val WORK_NOW = "drivelift-intel-ocr-index"
        const val WORK_PERIODIC = "drivelift-intel-ocr-periodic"
    }
}
