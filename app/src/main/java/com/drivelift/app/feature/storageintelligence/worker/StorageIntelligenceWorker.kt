package com.drivelift.app.feature.storageintelligence.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.drivelift.app.Graph
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_GRACE_PERIOD_MS
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_LARGE_THRESHOLD_BYTES
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_LARGE_TOP_N
import com.drivelift.app.feature.storageintelligence.model.ScanPhase
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException

/**
 * Background WorkManager worker running unified local + cloud Storage Intelligence scans with
 * cooperative cancellation and live [ScanProgress] emission via [StorageIntelligenceScanBus].
 */
class StorageIntelligenceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = Graph.get(applicationContext)
        val repository = graph.storageIntelligence.repository
        val scopeName = inputData.getString(KEY_SCOPE)
        val scope = ScanScope.entries.firstOrNull { it.name == scopeName } ?: ScanScope.CROSS_SOURCE
        val singleCloudSourceId = inputData.getString(KEY_CLOUD_SOURCE_ID)
        val thresholdBytes = inputData.getLong(KEY_THRESHOLD_BYTES, DEFAULT_LARGE_THRESHOLD_BYTES)
        val topN = inputData.getInt(KEY_TOP_N, DEFAULT_LARGE_TOP_N)
        val gracePeriodMs = inputData.getLong(KEY_GRACE_PERIOD_MS, DEFAULT_GRACE_PERIOD_MS)

        return try {
            val session = repository.executeScan(
                scope = scope,
                singleCloudSourceId = singleCloudSourceId,
                thresholdBytes = thresholdBytes,
                topN = topN,
                gracePeriodMs = gracePeriodMs,
            )
            if (session.status == ScanPhase.CANCELLED.name) {
                Result.failure()
            } else {
                Result.success(Data.Builder().putLong(KEY_OUTPUT_SESSION_ID, session.sessionId).build())
            }
        } catch (e: CancellationException) {
            repository.scanProgress.value // keep bus consistent
            repository.markScanCancelled()
            throw e
        } catch (e: Exception) {
            repository.markScanFailed(
                SourceScanError(
                    sourceId = "worker",
                    sourceLabel = "Storage Intelligence",
                    message = describeError(e),
                    authExpired = false,
                ),
            )
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "drivelift-storage-intelligence-scan"
        const val KEY_SCOPE = "scope"
        const val KEY_CLOUD_SOURCE_ID = "cloud_source_id"
        const val KEY_THRESHOLD_BYTES = "threshold_bytes"
        const val KEY_TOP_N = "top_n"
        const val KEY_GRACE_PERIOD_MS = "grace_period_ms"
        const val KEY_OUTPUT_SESSION_ID = "session_id"

        fun inputData(
            scope: ScanScope,
            singleCloudSourceId: String? = null,
            thresholdBytes: Long = DEFAULT_LARGE_THRESHOLD_BYTES,
            topN: Int = DEFAULT_LARGE_TOP_N,
            gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
        ): Data = Data.Builder()
            .putString(KEY_SCOPE, scope.name)
            .apply {
                if (singleCloudSourceId != null) putString(KEY_CLOUD_SOURCE_ID, singleCloudSourceId)
            }
            .putLong(KEY_THRESHOLD_BYTES, thresholdBytes)
            .putInt(KEY_TOP_N, topN)
            .putLong(KEY_GRACE_PERIOD_MS, gracePeriodMs)
            .build()
    }
}
