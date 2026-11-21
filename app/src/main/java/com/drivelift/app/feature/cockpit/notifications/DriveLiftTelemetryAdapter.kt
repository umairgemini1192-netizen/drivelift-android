package com.drivelift.app.feature.cockpit.notifications

import com.drivelift.app.data.TransferDao
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitStateCoordinator
import com.drivelift.app.feature.desktopdrop.controller.DesktopDropController
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.transfer.LiveProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class DriveLiftTelemetrySummary(
    val isDesktopDropRunning: Boolean = false,
    val connectedPeerName: String? = null,
    val desktopDropSpeedBytesPerSec: Long = 0L,
    val desktopDropActiveCount: Int = 0,
    val uploadPhase: LiveProgress.Phase = LiveProgress.Phase.IDLE,
    val uploadFileName: String = "",
    val uploadFraction: Float = 0f,
    val uploadSpeedBytesPerSec: Long = 0L,
    val queuedCount: Int = 0,
    val verifiedCount: Int = 0,
    val statusHeadline: String = "Ready",
)

/**
 * Strictly read-only telemetry adapter observing Desktop Drop, Drive Upload [LiveProgress],
 * and Room transfer counts without modifying any underlying engine.
 */
class DriveLiftTelemetryAdapter(
    private val desktopDropController: DesktopDropController,
    private val transferDao: TransferDao,
    private val coordinator: CockpitStateCoordinator,
    private val clock: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _summary = MutableStateFlow(DriveLiftTelemetrySummary())
    val summary: StateFlow<DriveLiftTelemetrySummary> = _summary.asStateFlow()

    init {
        scope.launch {
            combine(
                desktopDropController.snapshot,
                LiveProgress.state,
                transferDao.observeCounts(),
            ) { dropSnap, liveState, counts ->
                val countsByStatus = counts.associateBy { it.status }
                val queued = (countsByStatus[TransferStatus.QUEUED.name]?.count ?: 0) +
                    (countsByStatus[TransferStatus.UPLOADING.name]?.count ?: 0)
                val verified = countsByStatus[TransferStatus.VERIFIED.name]?.count ?: 0
                buildSummary(dropSnap, liveState, queued, verified)
            }.collect { next ->
                _summary.value = next
                publishLiveActivities(next)
            }
        }
    }

    fun stopDesktopDrop() {
        desktopDropController.stopServer("Stopped from Cockpit")
    }

    fun seedSummaryForTest(summary: DriveLiftTelemetrySummary) {
        _summary.value = summary
    }

    private fun buildSummary(
        dropSnap: DesktopDropSnapshot,
        live: LiveProgress.State,
        queuedCount: Int,
        verifiedCount: Int,
    ): DriveLiftTelemetrySummary {
        val dropRunning = dropSnap.status == ServerStatus.RUNNING || dropSnap.status == ServerStatus.STARTING
        val peerName = dropSnap.peers
            .firstOrNull { it.status == PeerApprovalStatus.APPROVED }
            ?.deviceLabel
            ?: dropSnap.pendingApproval?.deviceLabel
        val uploadFraction = if (live.total > 0L) {
            (live.sent.toFloat() / live.total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val headline = when {
            dropRunning && dropSnap.activeTransferCount > 0 ->
                "Drop ${formatCompactRate(dropSnap.aggregateSpeedBytesPerSec)}"
            dropRunning ->
                peerName?.let { "Drop: $it" } ?: "Drop listening"
            live.phase == LiveProgress.Phase.UPLOADING ->
                "Uploading ${(uploadFraction * 100).toInt()}%"
            live.phase == LiveProgress.Phase.VERIFYING ->
                "Verifying copy"
            live.phase == LiveProgress.Phase.RETRY_WAIT ->
                "Retry waiting"
            queuedCount > 0 ->
                "$queuedCount queued"
            else ->
                "Ready"
        }
        return DriveLiftTelemetrySummary(
            isDesktopDropRunning = dropRunning,
            connectedPeerName = peerName,
            desktopDropSpeedBytesPerSec = dropSnap.aggregateSpeedBytesPerSec,
            desktopDropActiveCount = dropSnap.activeTransferCount,
            uploadPhase = live.phase,
            uploadFileName = live.name,
            uploadFraction = uploadFraction,
            uploadSpeedBytesPerSec = live.bytesPerSec,
            queuedCount = queuedCount,
            verifiedCount = verifiedCount,
            statusHeadline = headline,
        )
    }

    private fun publishLiveActivities(summary: DriveLiftTelemetrySummary) {
        val now = clock()
        if (summary.isDesktopDropRunning) {
            val activeLabel = if (summary.desktopDropActiveCount > 0) {
                "${summary.desktopDropActiveCount} active"
            } else {
                "Waiting for transfer"
            }
            coordinator.upsertActivity(
                CockpitLiveActivity.TransferActivity(
                    id = CockpitLiveActivity.ID_DESKTOP_DROP,
                    title = summary.connectedPeerName ?: "Desktop Drop",
                    subtitle = activeLabel,
                    speedText = if (summary.desktopDropSpeedBytesPerSec > 0L) {
                        formatCompactRate(summary.desktopDropSpeedBytesPerSec)
                    } else {
                        "LAN ready"
                    },
                    progressFraction = null,
                    isDesktopDrop = true,
                    canStop = true,
                    activeCount = summary.desktopDropActiveCount.coerceAtLeast(1),
                    updatedAtMs = now,
                ),
            )
        } else {
            coordinator.removeActivity(CockpitLiveActivity.ID_DESKTOP_DROP)
        }

        if (summary.uploadPhase != LiveProgress.Phase.IDLE) {
            val subtitle = when (summary.uploadPhase) {
                LiveProgress.Phase.UPLOADING -> "${(summary.uploadFraction * 100).toInt()}% to Drive"
                LiveProgress.Phase.VERIFYING -> "Checking size and MD5"
                LiveProgress.Phase.RETRY_WAIT -> "Waiting to retry"
                LiveProgress.Phase.IDLE -> ""
            }
            coordinator.upsertActivity(
                CockpitLiveActivity.TransferActivity(
                    id = CockpitLiveActivity.ID_TRANSFER,
                    title = summary.uploadFileName.ifBlank { "Drive Upload" },
                    subtitle = subtitle,
                    speedText = if (summary.uploadSpeedBytesPerSec > 0L) {
                        formatCompactRate(summary.uploadSpeedBytesPerSec)
                    } else {
                        "${(summary.uploadFraction * 100).toInt()}%"
                    },
                    progressFraction = summary.uploadFraction,
                    isDesktopDrop = false,
                    canStop = false,
                    activeCount = summary.queuedCount.coerceAtLeast(1),
                    updatedAtMs = now,
                ),
            )
        } else {
            coordinator.removeActivity(CockpitLiveActivity.ID_TRANSFER)
        }
    }

    companion object {
        fun formatCompactRate(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "0 KB/s"
            val mb = bytesPerSec / (1024.0 * 1024.0)
            return if (mb >= 1.0) "%.1f MB/s".format(mb)
            else "${(bytesPerSec / 1024L).coerceAtLeast(1L)} KB/s"
        }
    }
}
