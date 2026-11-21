package com.drivelift.app.feature.storageintelligence.worker

import com.drivelift.app.feature.storageintelligence.model.ScanPhase
import com.drivelift.app.feature.storageintelligence.model.ScanProgress
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local live telemetry bus shared between [StorageIntelligenceWorker] and
 * [StorageIntelligenceViewModel] so scan progress updates without high-frequency Room writes.
 */
class StorageIntelligenceScanBus {
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    val cancelRequested = AtomicBoolean(false)

    fun start(totalEstimatedCount: Int = 0) {
        cancelRequested.set(false)
        _progress.value = ScanProgress(
            phase = ScanPhase.SCANNING_SOURCES,
            totalEstimatedCount = totalEstimatedCount,
            isRunning = true,
            isCancellable = true,
        )
    }

    fun update(
        phase: ScanPhase? = null,
        activeSourceId: String? = _progress.value.activeSourceId,
        activeSourceLabel: String? = _progress.value.activeSourceLabel,
        scannedCount: Int = _progress.value.scannedCount,
        bytesAnalyzed: Long = _progress.value.bytesAnalyzed,
        totalEstimatedCount: Int = _progress.value.totalEstimatedCount,
        errors: List<SourceScanError> = _progress.value.errors,
        sessionId: Long? = _progress.value.sessionId,
        isRunning: Boolean = _progress.value.isRunning,
        isCancellable: Boolean = _progress.value.isCancellable,
    ) {
        _progress.value = _progress.value.copy(
            sessionId = sessionId,
            phase = phase ?: _progress.value.phase,
            activeSourceId = activeSourceId,
            activeSourceLabel = activeSourceLabel,
            scannedCount = scannedCount,
            bytesAnalyzed = bytesAnalyzed,
            totalEstimatedCount = totalEstimatedCount,
            errors = errors,
            isRunning = isRunning,
            isCancellable = isCancellable,
        )
    }

    fun requestCancel() {
        cancelRequested.set(true)
        if (_progress.value.isRunning) {
            _progress.value = _progress.value.copy(isCancellable = false)
        }
    }

    fun complete(
        sessionId: Long,
        scannedCount: Int,
        bytesAnalyzed: Long,
        errors: List<SourceScanError>,
    ) {
        cancelRequested.set(false)
        _progress.value = ScanProgress(
            sessionId = sessionId,
            phase = ScanPhase.COMPLETED,
            activeSourceId = null,
            activeSourceLabel = null,
            scannedCount = scannedCount,
            bytesAnalyzed = bytesAnalyzed,
            totalEstimatedCount = scannedCount,
            errors = errors,
            isRunning = false,
            isCancellable = false,
        )
    }

    fun cancelled() {
        cancelRequested.set(false)
        _progress.value = _progress.value.copy(
            phase = ScanPhase.CANCELLED,
            isRunning = false,
            isCancellable = false,
        )
    }

    fun failed(error: SourceScanError) {
        cancelRequested.set(false)
        _progress.value = _progress.value.copy(
            phase = ScanPhase.FAILED,
            errors = (_progress.value.errors + error).distinctBy { it.sourceId to it.message },
            isRunning = false,
            isCancellable = false,
        )
    }
}
