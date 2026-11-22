package com.drivelift.app.feature.cockpit.alerts

/**
 * Priority tier for transient Dynamic Island system alerts.
 * Higher [rank] preempts lower [rank].
 */
enum class CockpitAlertPriority(val rank: Int) {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    CRITICAL(4),
}

/**
 * Semantic accent role mapped by the UI layer to theme tokens.
 */
enum class CockpitAlertAccent {
    PRIMARY,
    INFO,
    POSITIVE,
    WARNING,
    ERROR,
}

enum class CockpitRingerMode {
    SILENT,
    VIBRATE,
    NORMAL,
}

enum class CockpitBiometricStage {
    SCANNING,
    SUCCESS,
    ERROR,
    DEVICE_UNLOCKED,
}

/**
 * Immutable models for Phase 2 transient Dynamic Island system alerts.
 * Default auto-dismiss durations are bounded to 2500ms..4000ms.
 */
sealed interface CockpitSystemAlert {
    val id: String
    val priority: CockpitAlertPriority
    val accent: CockpitAlertAccent
    val defaultDurationMs: Long
    val createdAtMs: Long
    val expiresAtMs: Long

    fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): CockpitSystemAlert

    data class BatteryCharging(
        val percent: Int,
        val isFastCharging: Boolean,
        val estimatedFullMinutes: Long?,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.NORMAL
        override val accent: CockpitAlertAccent = CockpitAlertAccent.POSITIVE
        override val defaultDurationMs: Long = 3000L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): BatteryCharging =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_battery_charging"
        }
    }

    data class BatteryFull(
        val percent: Int,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.NORMAL
        override val accent: CockpitAlertAccent = CockpitAlertAccent.POSITIVE
        override val defaultDurationMs: Long = 3000L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): BatteryFull =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_battery_full"
        }
    }

    data class BatteryLow(
        val percent: Int,
        val isCritical: Boolean,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority =
            if (isCritical) CockpitAlertPriority.CRITICAL else CockpitAlertPriority.HIGH
        override val accent: CockpitAlertAccent =
            if (isCritical) CockpitAlertAccent.ERROR else CockpitAlertAccent.WARNING
        override val defaultDurationMs: Long = if (isCritical) 4000L else 3500L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): BatteryLow =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_battery_low"
        }
    }

    data class EarbudsConnected(
        val deviceName: String,
        val batteryPercent: Int?,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.NORMAL
        override val accent: CockpitAlertAccent = CockpitAlertAccent.INFO
        override val defaultDurationMs: Long = 3000L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): EarbudsConnected =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_earbuds_connected"
        }
    }

    data class EarbudsLowBattery(
        val deviceName: String,
        val batteryPercent: Int,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.HIGH
        override val accent: CockpitAlertAccent = CockpitAlertAccent.WARNING
        override val defaultDurationMs: Long = 3500L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): EarbudsLowBattery =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_earbuds_low_battery"
        }
    }

    data class RingerModeChanged(
        val mode: CockpitRingerMode,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.LOW
        override val accent: CockpitAlertAccent = CockpitAlertAccent.INFO
        override val defaultDurationMs: Long = 2500L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): RingerModeChanged =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_ringer_mode"
        }
    }

    data class AirplaneModeChanged(
        val enabled: Boolean,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.LOW
        override val accent: CockpitAlertAccent =
            if (enabled) CockpitAlertAccent.WARNING else CockpitAlertAccent.INFO
        override val defaultDurationMs: Long = 2500L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): AirplaneModeChanged =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_airplane_mode"
        }
    }

    data class BiometricFeedback(
        val stage: CockpitBiometricStage,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority =
            if (stage == CockpitBiometricStage.ERROR) CockpitAlertPriority.CRITICAL
            else CockpitAlertPriority.HIGH
        override val accent: CockpitAlertAccent = when (stage) {
            CockpitBiometricStage.SCANNING -> CockpitAlertAccent.INFO
            CockpitBiometricStage.SUCCESS,
            CockpitBiometricStage.DEVICE_UNLOCKED -> CockpitAlertAccent.POSITIVE
            CockpitBiometricStage.ERROR -> CockpitAlertAccent.ERROR
        }
        override val defaultDurationMs: Long = when (stage) {
            CockpitBiometricStage.SCANNING -> 4000L
            CockpitBiometricStage.SUCCESS,
            CockpitBiometricStage.DEVICE_UNLOCKED -> 2500L
            CockpitBiometricStage.ERROR -> 3500L
        }

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): BiometricFeedback =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "system_biometric_feedback"
        }
    }

    data class DriveLiftTransfer(
        val fileName: String,
        val progressPercent: Int,
        val isCompleted: Boolean,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.HIGH
        override val accent: CockpitAlertAccent =
            if (isCompleted) CockpitAlertAccent.POSITIVE else CockpitAlertAccent.PRIMARY
        override val defaultDurationMs: Long = if (isCompleted) 3000L else 3500L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): DriveLiftTransfer =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "drivelift_transfer_alert"
        }
    }

    data class DriveLiftRadarConnected(
        val peerName: String,
        override val createdAtMs: Long = 0L,
        override val expiresAtMs: Long = 0L,
    ) : CockpitSystemAlert {
        override val id: String = ID
        override val priority: CockpitAlertPriority = CockpitAlertPriority.NORMAL
        override val accent: CockpitAlertAccent = CockpitAlertAccent.PRIMARY
        override val defaultDurationMs: Long = 3000L

        override fun withTimestamps(createdAtMs: Long, expiresAtMs: Long): DriveLiftRadarConnected =
            copy(createdAtMs = createdAtMs, expiresAtMs = expiresAtMs)

        companion object {
            const val ID = "drivelift_radar_connected"
        }
    }
}
