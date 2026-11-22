package com.drivelift.app.feature.cockpit.alerts

import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import com.drivelift.app.feature.desktopdrop.model.DropTransferProgress
import com.drivelift.app.feature.desktopdrop.model.DropTransferStatus
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.model.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitDriveLiftAlertBridgeTest {

    private class RecordingSink : CockpitAlertSink {
        val events = mutableListOf<String>()

        override fun emitBiometric(stage: CockpitBiometricStage) {
            events += "biometric:$stage"
        }

        override fun emitTransfer(fileName: String, progressPercent: Int, isCompleted: Boolean) {
            events += "transfer:$fileName:$progressPercent:$isCompleted"
        }

        override fun emitRadarConnected(peerName: String) {
            events += "radar:$peerName"
        }
    }

    private fun peer(id: String, status: PeerApprovalStatus, label: String = "Work laptop") = PeerSession(
        sessionId = id,
        peerIp = "192.168.1.20",
        userAgent = "Chrome",
        deviceLabel = label,
        connectedAtMs = 1_000L,
        lastSeenMs = 2_000L,
        status = status,
        matchCode = "74-29",
    )

    private fun transfer(id: String, name: String, status: DropTransferStatus) = DropTransferProgress(
        transferId = id,
        filename = name,
        relativePath = "",
        direction = TransferDirection.PC_TO_PHONE,
        totalBytes = 1_000L,
        transferredBytes = if (status == DropTransferStatus.COMPLETED) 1_000L else 400L,
        speedBytesPerSec = 0L,
        etaSeconds = 0L,
        status = status,
    )

    private fun snapshot(
        peers: List<PeerSession> = emptyList(),
        pending: PeerSession? = null,
        transfers: List<DropTransferProgress> = emptyList(),
    ) = DesktopDropSnapshot(peers = peers, pendingApproval = pending, transfers = transfers)

    @Test
    fun theFirstSnapshotOnlySetsTheBaseline() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)

        bridge.onDropSnapshot(
            snapshot(
                peers = listOf(peer("a", PeerApprovalStatus.APPROVED)),
                transfers = listOf(transfer("t1", "old.jpg", DropTransferStatus.COMPLETED)),
            ),
        )

        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun aKnockThenApprovalRaisesScanningSuccessAndRadarInOrder() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)
        bridge.onDropSnapshot(snapshot())

        val waiting = peer("a", PeerApprovalStatus.PENDING)
        bridge.onDropSnapshot(snapshot(peers = listOf(waiting), pending = waiting))
        assertEquals(listOf("biometric:SCANNING"), sink.events)

        bridge.onDropSnapshot(snapshot(peers = listOf(peer("a", PeerApprovalStatus.APPROVED))))
        assertEquals(
            listOf("biometric:SCANNING", "biometric:SUCCESS", "radar:Work laptop"),
            sink.events,
        )
    }

    @Test
    fun aDeniedKnockRaisesAnError() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)
        bridge.onDropSnapshot(snapshot())
        val waiting = peer("a", PeerApprovalStatus.PENDING)
        bridge.onDropSnapshot(snapshot(peers = listOf(waiting), pending = waiting))

        bridge.onDropSnapshot(snapshot(peers = listOf(peer("a", PeerApprovalStatus.REVOKED))))

        assertEquals(listOf("biometric:SCANNING", "biometric:ERROR"), sink.events)
    }

    @Test
    fun aKnockThatStaysPendingRaisesScanningOnlyOnce() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)
        bridge.onDropSnapshot(snapshot())
        val waiting = peer("a", PeerApprovalStatus.PENDING)

        repeat(3) { bridge.onDropSnapshot(snapshot(peers = listOf(waiting), pending = waiting)) }

        assertEquals(listOf("biometric:SCANNING"), sink.events)
    }

    @Test
    fun aTrustedBrowserThatReconnectsRaisesRadarWithoutABiometricAlert() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)
        bridge.onDropSnapshot(snapshot())

        bridge.onDropSnapshot(snapshot(peers = listOf(peer("t", PeerApprovalStatus.APPROVED, "Home PC"))))

        assertEquals(listOf("radar:Home PC"), sink.events)
    }

    @Test
    fun aCompletedDropTransferRaisesOneCompletedTransferAlert() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)
        bridge.onDropSnapshot(snapshot(transfers = listOf(transfer("t1", "IMG_0412.jpg", DropTransferStatus.ACTIVE))))

        val done = snapshot(transfers = listOf(transfer("t1", "IMG_0412.jpg", DropTransferStatus.COMPLETED)))
        bridge.onDropSnapshot(done)
        bridge.onDropSnapshot(done)

        assertEquals(listOf("transfer:IMG_0412.jpg:100:true"), sink.events)
    }

    @Test
    fun aVerifiedDriveCopyRaisesACompletedTransferAlertWithTheLastFileName() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)
        bridge.onTelemetry(DriveLiftTelemetrySummary(verifiedCount = 3))
        assertTrue(sink.events.isEmpty())

        bridge.onTelemetry(DriveLiftTelemetrySummary(uploadFileName = "VID_0099.mp4", verifiedCount = 3))
        assertTrue(sink.events.isEmpty())

        bridge.onTelemetry(DriveLiftTelemetrySummary(verifiedCount = 4))
        assertEquals(listOf("transfer:VID_0099.mp4:100:true"), sink.events)
    }

    @Test
    fun fewerVerifiedFilesAfterARemovalRaiseNothing() {
        val sink = RecordingSink()
        val bridge = CockpitDriveLiftAlertBridge(sink)
        bridge.onTelemetry(DriveLiftTelemetrySummary(verifiedCount = 5))

        bridge.onTelemetry(DriveLiftTelemetrySummary(verifiedCount = 2))

        assertTrue(sink.events.isEmpty())
    }
}
