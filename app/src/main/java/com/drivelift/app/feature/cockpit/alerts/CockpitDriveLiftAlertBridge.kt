package com.drivelift.app.feature.cockpit.alerts

import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import com.drivelift.app.feature.desktopdrop.model.DropTransferStatus
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus

/**
 * Turns read-only DriveLift state changes into transient island alerts: a Desktop Drop knock is
 * the biometric prompt (scanning, then success or error), an approved browser is a radar
 * connection, and a finished Drive copy or Drop transfer is a completed transfer.
 *
 * The first state it sees only sets the baseline, so a restart with a browser already connected or
 * files already verified raises nothing. It never writes to the sources it watches.
 */
class CockpitDriveLiftAlertBridge(private val sink: CockpitAlertSink) {
    private var dropBaselineSet = false
    private var pendingKnockId: String? = null
    private val approvedIds = HashSet<String>()
    private val completedDropIds = HashSet<String>()

    private var telemetryBaselineSet = false
    private var lastVerifiedCount = 0
    private var lastUploadFileName = ""

    fun onDropSnapshot(snapshot: DesktopDropSnapshot) {
        val nowApproved = snapshot.peers
            .filter { it.status == PeerApprovalStatus.APPROVED }
            .associateBy { it.sessionId }
        val nowPendingId = snapshot.pendingApproval?.sessionId
        val completedNow = snapshot.transfers
            .filter { it.status == DropTransferStatus.COMPLETED }
            .map { it.transferId to it.filename }

        if (!dropBaselineSet) {
            dropBaselineSet = true
            approvedIds += nowApproved.keys
            completedDropIds += completedNow.map { it.first }
            pendingKnockId = nowPendingId
            return
        }

        val previousPending = pendingKnockId
        if (nowPendingId != null && nowPendingId != previousPending) {
            sink.emitBiometric(CockpitBiometricStage.SCANNING)
        }
        if (previousPending != null && previousPending != nowPendingId) {
            val resolved = snapshot.peers.firstOrNull { it.sessionId == previousPending }
            when (resolved?.status) {
                PeerApprovalStatus.APPROVED -> sink.emitBiometric(CockpitBiometricStage.SUCCESS)
                PeerApprovalStatus.REVOKED,
                PeerApprovalStatus.EXPIRED -> sink.emitBiometric(CockpitBiometricStage.ERROR)
                else -> Unit
            }
        }
        pendingKnockId = nowPendingId

        for ((id, peer) in nowApproved) {
            if (approvedIds.add(id)) sink.emitRadarConnected(peer.deviceLabel.ifBlank { "Laptop" })
        }
        approvedIds.retainAll(nowApproved.keys)

        for ((id, name) in completedNow) {
            if (completedDropIds.add(id)) {
                sink.emitTransfer(fileName = name, progressPercent = 100, isCompleted = true)
            }
        }
    }

    fun onTelemetry(summary: DriveLiftTelemetrySummary) {
        if (summary.uploadFileName.isNotBlank()) lastUploadFileName = summary.uploadFileName
        if (!telemetryBaselineSet) {
            telemetryBaselineSet = true
            lastVerifiedCount = summary.verifiedCount
            return
        }
        if (summary.verifiedCount > lastVerifiedCount) {
            sink.emitTransfer(
                fileName = lastUploadFileName.ifBlank { "Drive copy" },
                progressPercent = 100,
                isCompleted = true,
            )
        }
        lastVerifiedCount = summary.verifiedCount
    }
}
