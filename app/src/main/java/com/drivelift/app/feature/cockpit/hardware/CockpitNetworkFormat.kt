package com.drivelift.app.feature.cockpit.hardware

import java.util.Locale

/** The active connection, as the idle Dynamic Island pill names it. */
enum class IdleNetworkKind { WIFI, CELLULAR, OFFLINE }

/**
 * What the collapsed idle pill shows beside the camera cutout: the connection on the left and the
 * live upload and download rates on the right.
 */
data class IdleNetworkSnapshot(
    val kind: IdleNetworkKind = IdleNetworkKind.OFFLINE,
    /** `Wi-Fi`, `5G`, `4G`, `Data` or `Offline`. */
    val label: String = "Offline",
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
) {
    val uploadText: String get() = CockpitNetworkFormat.formatRate(uploadBytesPerSec)
    val downloadText: String get() = CockpitNetworkFormat.formatRate(downloadBytesPerSec)

    /** Spoken form for the pill, since the arrows and units are visual shorthand. */
    val contentDescription: String
        get() = if (kind == IdleNetworkKind.OFFLINE) {
            "Offline"
        } else {
            "$label, uploading ${CockpitNetworkFormat.spokenRate(uploadBytesPerSec)}, " +
                "downloading ${CockpitNetworkFormat.spokenRate(downloadBytesPerSec)}"
        }
}

/** Pure formatting and arithmetic for the idle network readout. Nothing here touches Android. */
object CockpitNetworkFormat {
    private const val KIB = 1024L
    private const val MIB = 1024L * 1024L
    private const val TELEPHONY_NR = 20
    private const val TELEPHONY_LTE = 13

    /**
     * Compact tabular rate: `0K`, `12K`, `999K`, `1.4M`, `35M`. Bytes per second, 1024 based,
     * so the text never grows past four characters.
     */
    fun formatRate(bytesPerSec: Long): String {
        val bytes = bytesPerSec.coerceAtLeast(0L)
        val kib = bytes / KIB
        if (kib < 1000L) return "${kib}K"
        val mib = bytes.toDouble() / MIB
        return if (mib < 10.0) String.format(Locale.US, "%.1fM", mib) else "${mib.toLong()}M"
    }

    /** `12 kilobytes per second` style text for screen readers. */
    fun spokenRate(bytesPerSec: Long): String {
        val bytes = bytesPerSec.coerceAtLeast(0L)
        val kib = bytes / KIB
        if (kib < 1000L) return "$kib kilobytes per second"
        val mib = bytes.toDouble() / MIB
        val text = if (mib < 10.0) String.format(Locale.US, "%.1f", mib) else "${mib.toLong()}"
        return "$text megabytes per second"
    }

    /**
     * Bytes per second from two readings of `TrafficStats` totals. A reading below zero
     * (`TrafficStats.UNSUPPORTED`), a counter that went backwards (interface reset) or a
     * non-positive interval gives 0.
     */
    fun rate(previousBytes: Long, currentBytes: Long, elapsedMs: Long): Long {
        if (previousBytes < 0L || currentBytes < 0L || elapsedMs <= 0L) return 0L
        val delta = currentBytes - previousBytes
        if (delta <= 0L) return 0L
        return delta * 1000L / elapsedMs
    }

    /** `5G` or `4G` when the radio technology is known, otherwise plain `Data`. */
    fun cellularLabel(telephonyNetworkType: Int?): String = when (telephonyNetworkType) {
        TELEPHONY_NR -> "5G"
        TELEPHONY_LTE -> "4G"
        else -> "Data"
    }

    fun label(kind: IdleNetworkKind, telephonyNetworkType: Int? = null): String = when (kind) {
        IdleNetworkKind.WIFI -> "Wi-Fi"
        IdleNetworkKind.CELLULAR -> cellularLabel(telephonyNetworkType)
        IdleNetworkKind.OFFLINE -> "Offline"
    }
}
