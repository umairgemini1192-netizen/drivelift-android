package com.drivelift.app.feature.desktopdrop.model

/** How the Android host exposes the local HTTP drop server. */
enum class ConnectivityMode(val label: String) {
    SAME_WIFI("Same Wi-Fi"),
    DIRECT_HOTSPOT("Direct Hotspot"),
}

enum class ServerStatus(val label: String) {
    STOPPED("Stopped"),
    STARTING("Starting"),
    RUNNING("Running"),
    ERROR("Error"),
}

enum class PeerApprovalStatus {
    PENDING,
    APPROVED,
    REVOKED,
}

data class PeerSession(
    val sessionId: String,
    val peerIp: String,
    val userAgent: String,
    val deviceLabel: String,
    val connectedAtMs: Long,
    val lastSeenMs: Long,
    val status: PeerApprovalStatus,
)

data class SharedDropItem(
    val id: String,
    val name: String,
    val relativePath: String = "",
    val sizeBytes: Long,
    val mimeType: String,
    val sourceUri: String? = null,
    val localFilePath: String? = null,
    val addedAtMs: Long = 0L,
)

enum class TransferDirection(val label: String) {
    PC_TO_PHONE("PC to Phone"),
    PHONE_TO_PC("Phone to PC"),
}

enum class DropTransferStatus(val label: String) {
    ACTIVE("Transferring"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

data class DropTransferProgress(
    val transferId: String,
    val filename: String,
    val relativePath: String,
    val direction: TransferDirection,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val status: DropTransferStatus,
    val errorMessage: String? = null,
    val updatedAtMs: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class HotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val wifiQrPayload: String,
)

data class DesktopDropPrefsState(
    val preferredPort: Int = 8080,
    val requireApproval: Boolean = true,
    val idleTimeoutMinutes: Int = 15,
    val destinationTreeUri: String? = null,
    val destinationLabel: String = "Downloads/DriveLift Drop",
)

data class DesktopDropSnapshot(
    val status: ServerStatus = ServerStatus.STOPPED,
    val mode: ConnectivityMode = ConnectivityMode.SAME_WIFI,
    val bindIp: String? = null,
    val port: Int? = null,
    val sessionToken: String = "",
    val dropUrl: String? = null,
    val hotspotCredentials: HotspotCredentials? = null,
    val peers: List<PeerSession> = emptyList(),
    val pendingApproval: PeerSession? = null,
    val sharedItems: List<SharedDropItem> = emptyList(),
    val transfers: List<DropTransferProgress> = emptyList(),
    val aggregateSpeedBytesPerSec: Long = 0L,
    val activeTransferCount: Int = 0,
    val prefs: DesktopDropPrefsState = DesktopDropPrefsState(),
    val errorMessage: String? = null,
    val statusNote: String? = null,
)
