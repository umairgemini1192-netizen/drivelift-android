package com.drivelift.app.feature.cockpit.alerts

sealed interface CockpitSystemAlertEvent {
    data class BatteryChanged(
        val percent: Int,
        val isCharging: Boolean,
        val isFastCharging: Boolean = false,
        val isStatusFull: Boolean = false,
        val estimatedFullMinutes: Long? = null,
    ) : CockpitSystemAlertEvent

    data class EarbudsConnectionChanged(
        val deviceKey: String,
        val deviceName: String,
        val isConnected: Boolean,
        val batteryPercent: Int? = null,
    ) : CockpitSystemAlertEvent

    data class EarbudsBatteryChanged(
        val deviceKey: String,
        val deviceName: String,
        val batteryPercent: Int,
    ) : CockpitSystemAlertEvent

    data class RingerModeChanged(val mode: CockpitRingerMode) : CockpitSystemAlertEvent

    data class AirplaneModeChanged(val enabled: Boolean) : CockpitSystemAlertEvent

    data class BiometricFeedback(val stage: CockpitBiometricStage) : CockpitSystemAlertEvent

    data class DriveLiftTransfer(
        val fileName: String,
        val progressPercent: Int,
        val isCompleted: Boolean,
    ) : CockpitSystemAlertEvent

    data class DriveLiftRadarConnected(val peerName: String) : CockpitSystemAlertEvent
}

data class CockpitAlertReduceResult(
    val activeAlert: CockpitSystemAlert?,
    val emittedAlert: CockpitSystemAlert?,
)

/**
 * Pure, deterministic state machine for Phase 2 transient system alerts.
 * Enforces battery and earbuds hysteresis, sticky-broadcast suppression on boot,
 * priority preemption, and clock-driven expiration.
 */
class CockpitSystemAlertReducer(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class EarbudsTracker(
        var isConnected: Boolean = false,
        var lowBatteryFired: Boolean = false,
    )

    private var wasCharging: Boolean? = null
    private var lowBatteryFired: Boolean = false
    private var criticalBatteryFired: Boolean = false
    private var fullBatteryFired: Boolean = false

    private var lastRingerMode: CockpitRingerMode? = null
    private var lastAirplaneMode: Boolean? = null

    private val earbudsState = mutableMapOf<String, EarbudsTracker>()

    private var currentAlert: CockpitSystemAlert? = null
    private val pendingQueue = mutableListOf<CockpitSystemAlert>()

    fun activeAlert(): CockpitSystemAlert? {
        expireIfNeeded()
        return currentAlert
    }

    fun expireIfNeeded(): CockpitSystemAlert? {
        val now = clockMs()
        val active = currentAlert ?: return null
        if (now < active.expiresAtMs) return active

        currentAlert = promoteNext(now)
        return currentAlert
    }

    fun dismissCurrent(): CockpitSystemAlert? {
        val now = clockMs()
        currentAlert = promoteNext(now)
        return currentAlert
    }

    fun reduce(event: CockpitSystemAlertEvent): CockpitAlertReduceResult {
        val now = clockMs()
        expireIfNeeded()

        val candidate = when (event) {
            is CockpitSystemAlertEvent.BatteryChanged -> handleBatteryChanged(event)
            is CockpitSystemAlertEvent.EarbudsConnectionChanged -> handleEarbudsConnection(event)
            is CockpitSystemAlertEvent.EarbudsBatteryChanged -> handleEarbudsBattery(event)
            is CockpitSystemAlertEvent.RingerModeChanged -> handleRingerMode(event)
            is CockpitSystemAlertEvent.AirplaneModeChanged -> handleAirplaneMode(event)
            is CockpitSystemAlertEvent.BiometricFeedback ->
                CockpitSystemAlert.BiometricFeedback(stage = event.stage)
            is CockpitSystemAlertEvent.DriveLiftTransfer ->
                CockpitSystemAlert.DriveLiftTransfer(
                    fileName = event.fileName,
                    progressPercent = event.progressPercent.coerceIn(0, 100),
                    isCompleted = event.isCompleted,
                )
            is CockpitSystemAlertEvent.DriveLiftRadarConnected ->
                CockpitSystemAlert.DriveLiftRadarConnected(peerName = event.peerName)
        } ?: return CockpitAlertReduceResult(activeAlert = currentAlert, emittedAlert = null)

        val stamped = candidate.withTimestamps(
            createdAtMs = now,
            expiresAtMs = now + candidate.defaultDurationMs,
        )
        enqueueOrPreempt(stamped, now)
        return CockpitAlertReduceResult(activeAlert = currentAlert, emittedAlert = stamped)
    }

    private fun handleBatteryChanged(
        event: CockpitSystemAlertEvent.BatteryChanged,
    ): CockpitSystemAlert? {
        val percent = event.percent.coerceIn(0, 100)
        val previousCharging = wasCharging
        wasCharging = event.isCharging

        if (event.isCharging) {
            lowBatteryFired = false
            criticalBatteryFired = false
        } else if (percent >= BATTERY_LOW_RESET_PERCENT) {
            lowBatteryFired = false
            criticalBatteryFired = false
        }

        if (!event.isCharging || percent < BATTERY_FULL_RESET_PERCENT) {
            fullBatteryFired = false
        }

        // Plug-in transition (ignore initial sticky charging state on service startup).
        if (previousCharging == false && event.isCharging) {
            return CockpitSystemAlert.BatteryCharging(
                percent = percent,
                isFastCharging = event.isFastCharging,
                estimatedFullMinutes = event.estimatedFullMinutes,
            )
        }

        // Full charge while plugged in.
        if (event.isCharging && (percent >= 100 || event.isStatusFull) && !fullBatteryFired) {
            fullBatteryFired = true
            return CockpitSystemAlert.BatteryFull(percent = percent)
        }

        // Low and critical battery thresholds while discharging.
        if (!event.isCharging) {
            if (percent <= BATTERY_CRITICAL_THRESHOLD && !criticalBatteryFired) {
                criticalBatteryFired = true
                lowBatteryFired = true
                return CockpitSystemAlert.BatteryLow(percent = percent, isCritical = true)
            }
            if (percent <= BATTERY_LOW_THRESHOLD && !lowBatteryFired) {
                lowBatteryFired = true
                return CockpitSystemAlert.BatteryLow(percent = percent, isCritical = false)
            }
        }

        return null
    }

    private fun handleEarbudsConnection(
        event: CockpitSystemAlertEvent.EarbudsConnectionChanged,
    ): CockpitSystemAlert? {
        val key = event.deviceKey.trim().ifEmpty { event.deviceName }
        val tracker = earbudsState.getOrPut(key) { EarbudsTracker() }
        if (!event.isConnected) {
            tracker.isConnected = false
            tracker.lowBatteryFired = false
            return null
        }

        if (tracker.isConnected) {
            return null
        }

        tracker.isConnected = true
        val validBattery = event.batteryPercent?.takeIf { it in 0..100 }
        return CockpitSystemAlert.EarbudsConnected(
            deviceName = event.deviceName,
            batteryPercent = validBattery,
        )
    }

    private fun handleEarbudsBattery(
        event: CockpitSystemAlertEvent.EarbudsBatteryChanged,
    ): CockpitSystemAlert? {
        val key = event.deviceKey.trim().ifEmpty { event.deviceName }
        val tracker = earbudsState[key] ?: return null
        if (!tracker.isConnected) return null

        val percent = event.batteryPercent.takeIf { it in 0..100 } ?: return null
        if (percent >= EARBUDS_LOW_RESET_PERCENT) {
            tracker.lowBatteryFired = false
            return null
        }

        if (percent <= EARBUDS_LOW_THRESHOLD && !tracker.lowBatteryFired) {
            tracker.lowBatteryFired = true
            return CockpitSystemAlert.EarbudsLowBattery(
                deviceName = event.deviceName,
                batteryPercent = percent,
            )
        }

        return null
    }

    private fun handleRingerMode(
        event: CockpitSystemAlertEvent.RingerModeChanged,
    ): CockpitSystemAlert? {
        val previous = lastRingerMode
        lastRingerMode = event.mode
        if (previous == null || previous == event.mode) return null
        return CockpitSystemAlert.RingerModeChanged(mode = event.mode)
    }

    private fun handleAirplaneMode(
        event: CockpitSystemAlertEvent.AirplaneModeChanged,
    ): CockpitSystemAlert? {
        val previous = lastAirplaneMode
        lastAirplaneMode = event.enabled
        if (previous == null || previous == event.enabled) return null
        return CockpitSystemAlert.AirplaneModeChanged(enabled = event.enabled)
    }

    private fun enqueueOrPreempt(stamped: CockpitSystemAlert, now: Long) {
        val active = currentAlert
        if (active == null) {
            currentAlert = stamped
            return
        }

        // Same-source updates (e.g. transfer progress or biometric stage) replace in-place.
        if (active.id == stamped.id) {
            currentAlert = stamped
            return
        }

        if (stamped.priority.rank >= active.priority.rank) {
            // Re-queue preempted lower-priority alert if it has remaining lifetime.
            if (stamped.priority.rank > active.priority.rank && active.expiresAtMs > now) {
                enqueuePending(active)
            }
            currentAlert = stamped
        } else {
            enqueuePending(stamped)
        }
    }

    private fun enqueuePending(alert: CockpitSystemAlert) {
        pendingQueue.removeAll { it.id == alert.id }
        pendingQueue.add(alert)
        if (pendingQueue.size > MAX_PENDING_ALERTS) {
            pendingQueue.sortWith(
                compareByDescending<CockpitSystemAlert> { it.priority.rank }
                    .thenBy { it.createdAtMs },
            )
            while (pendingQueue.size > MAX_PENDING_ALERTS) {
                pendingQueue.removeAt(pendingQueue.lastIndex)
            }
        }
    }

    private fun promoteNext(now: Long): CockpitSystemAlert? {
        if (pendingQueue.isEmpty()) return null
        pendingQueue.sortWith(
            compareByDescending<CockpitSystemAlert> { it.priority.rank }
                .thenBy { it.createdAtMs },
        )
        val next = pendingQueue.removeAt(0)
        return next.withTimestamps(
            createdAtMs = now,
            expiresAtMs = now + next.defaultDurationMs,
        )
    }

    companion object {
        const val BATTERY_LOW_THRESHOLD = 20
        const val BATTERY_CRITICAL_THRESHOLD = 15
        const val BATTERY_LOW_RESET_PERCENT = 23
        const val BATTERY_FULL_RESET_PERCENT = 98

        const val EARBUDS_LOW_THRESHOLD = 20
        const val EARBUDS_LOW_RESET_PERCENT = 25

        private const val MAX_PENDING_ALERTS = 4
    }
}
