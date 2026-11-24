package com.drivelift.app.feature.cockpit.widget

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** What the status pill at the top right of the Master Widget says and which colour it uses. */
enum class WidgetStatusTone { READY, DROP_LIVE, TORCH_ON }

data class WidgetStatusPill(val text: String, val tone: WidgetStatusTone)

/** Battery current as shown beside the battery gauge. */
enum class BatteryCurrentTone { DISCHARGING, CHARGING, UNKNOWN }

data class BatteryCurrentReading(
    /** Signed milliamps, negative while the battery drains. Null when the phone does not report it. */
    val milliAmps: Int?,
    val text: String,
    val tone: BatteryCurrentTone,
)

/** Remaining battery charge from `BATTERY_PROPERTY_CHARGE_COUNTER` (converted from uAh to mAh). */
data class BatteryChargeReading(
    /** Remaining charge in mAh, or null when the device does not report a valid counter. */
    val milliAmpHours: Int?,
    val text: String,
)

/** What the Drop control tile on the widget and its in-app preview shows. */
data class WidgetDropTile(val label: String, val active: Boolean, val enabled: Boolean)

/**
 * Pure rules shared by the Home Screen `CockpitGlanceWidget` and the in-app preview
 * (`CockpitMasterWidgetCard`), so both always say the same thing. Nothing here touches Android.
 */
object CockpitWidgetModel {
    const val PINNED_PER_PAGE = 4
    const val CURRENT_AVERAGE_WINDOW = 3
    const val CURRENT_DELTA_THRESHOLD_MA = 10
    const val CHARGE_DELTA_THRESHOLD_MAH = 1
    private const val UNSUPPORTED_CURRENT = Long.MIN_VALUE
    private const val UNSUPPORTED_CURRENT_INT = Int.MIN_VALUE.toLong()

    /** `• DROP LIVE` beats `• TORCH ON` beats `• READY`. The dot is drawn beside the text, not part of it. */
    fun statusPill(dropLive: Boolean, torchOn: Boolean): WidgetStatusPill = when {
        dropLive -> WidgetStatusPill("DROP LIVE", WidgetStatusTone.DROP_LIVE)
        torchOn -> WidgetStatusPill("TORCH ON", WidgetStatusTone.TORCH_ON)
        else -> WidgetStatusPill("READY", WidgetStatusTone.READY)
    }

    /** While Drop is live the tile becomes the way to stop it. */
    fun dropTile(dropLive: Boolean, available: Boolean): WidgetDropTile = when {
        dropLive -> WidgetDropTile(label = "Stop", active = true, enabled = true)
        !available -> WidgetDropTile(label = "No Wi-Fi", active = false, enabled = false)
        else -> WidgetDropTile(label = "Drop", active = false, enabled = true)
    }

    /** True when a `BatteryManager` current property value represents a valid hardware reading. */
    fun isValidCurrentSample(microAmps: Long): Boolean =
        microAmps != UNSUPPORTED_CURRENT && microAmps != UNSUPPORTED_CURRENT_INT

    /**
     * Converts a raw `BatteryManager` microamp reading to a non-negative milliamp magnitude using
     * half-up rounding to match Ampere's mA readout.
     */
    fun normalizeMicroAmpsToMilliAmps(microAmps: Long): Long? {
        if (!isValidCurrentSample(microAmps)) return null
        val absUa = abs(microAmps)
        if (absUa == 0L) return 0L
        return (absUa + 500L) / 1000L
    }

    /**
     * Selects the best available raw current sample in microamps: prefers `CURRENT_NOW` when valid
     * and non-zero (or when `CURRENT_AVERAGE` is also zero/invalid), and falls back to
     * `BATTERY_PROPERTY_CURRENT_AVERAGE` whenever `CURRENT_NOW` is unsupported or transiently zero.
     */
    fun selectPreferredCurrentMicroAmps(currentNowMicroAmps: Long, currentAverageMicroAmps: Long): Long {
        val nowValid = isValidCurrentSample(currentNowMicroAmps)
        val avgValid = isValidCurrentSample(currentAverageMicroAmps)
        return when {
            nowValid && currentNowMicroAmps != 0L -> currentNowMicroAmps
            avgValid && currentAverageMicroAmps != 0L -> currentAverageMicroAmps
            nowValid -> currentNowMicroAmps
            avgValid -> currentAverageMicroAmps
            else -> UNSUPPORTED_CURRENT
        }
    }

    /**
     * Formats an already-normalized milliamp magnitude with sign driven by charging state so
     * charging reads `+2,450 mA` and discharging reads `-510 mA`.
     */
    fun formatBatteryCurrentMilliAmps(magnitudeMa: Long, isCharging: Boolean): BatteryCurrentReading {
        val safeMagnitude = magnitudeMa.coerceAtLeast(0L)
        val signed = if (isCharging) safeMagnitude else -safeMagnitude
        val asInt = signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        val digits = String.format(Locale.US, "%,d", safeMagnitude)
        val text = when {
            safeMagnitude == 0L -> "0 mA"
            isCharging -> "+$digits mA"
            else -> "-$digits mA"
        }
        return BatteryCurrentReading(
            milliAmps = asInt,
            text = text,
            tone = if (isCharging) BatteryCurrentTone.CHARGING else BatteryCurrentTone.DISCHARGING,
        )
    }

    /**
     * Battery current from `BATTERY_PROPERTY_CURRENT_NOW` (microamps). The sign is taken from the
     * charging state rather than the raw value, because phones disagree about which way is
     * positive: charging reads `+2,450 mA`, discharging reads `-510 mA`.
     */
    fun formatBatteryCurrent(microAmps: Long, isCharging: Boolean): BatteryCurrentReading {
        val magnitudeMa = normalizeMicroAmpsToMilliAmps(microAmps) ?: return unknownCurrent()
        return formatBatteryCurrentMilliAmps(magnitudeMa, isCharging)
    }

    fun unknownCurrent(): BatteryCurrentReading =
        BatteryCurrentReading(milliAmps = null, text = "-- mA", tone = BatteryCurrentTone.UNKNOWN)

    /**
     * Remaining battery charge from `BATTERY_PROPERTY_CHARGE_COUNTER` (microamp-hours -> mAh).
     * Values <= 0 or [Long.MIN_VALUE] / [Int.MIN_VALUE] indicate unsupported hardware.
     */
    fun formatBatteryCharge(microAmpHours: Long): BatteryChargeReading {
        if (microAmpHours == UNSUPPORTED_CURRENT ||
            microAmpHours == Int.MIN_VALUE.toLong() ||
            microAmpHours <= 0L
        ) {
            return unknownCharge()
        }
        val mah = (microAmpHours / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (mah <= 0) return unknownCharge()
        val digits = String.format(Locale.US, "%,d", mah)
        return BatteryChargeReading(milliAmpHours = mah, text = "$digits mAh")
    }

    fun unknownCharge(): BatteryChargeReading =
        BatteryChargeReading(milliAmpHours = null, text = "-- mAh")

    /** True when a change in charge counter (mAh) is big enough to redraw the widget. */
    fun chargeChangedEnough(
        previous: Int?,
        current: Int?,
        thresholdMah: Int = CHARGE_DELTA_THRESHOLD_MAH,
    ): Boolean {
        if (previous == null && current == null) return false
        if (previous == null || current == null) return true
        return abs(previous - current) >= thresholdMah
    }

    /** True when a change in current is big enough to redraw the widget. */
    fun currentChangedEnough(
        previous: Int?,
        current: Int?,
        thresholdMa: Int = CURRENT_DELTA_THRESHOLD_MA,
    ): Boolean {
        if (previous == null && current == null) return false
        if (previous == null || current == null) return true
        if ((previous >= 0) != (current >= 0)) return true
        return abs(previous - current) >= thresholdMa
    }

    fun pageCount(totalApps: Int, perPage: Int = PINNED_PER_PAGE): Int =
        if (totalApps <= 0) 1 else ceil(totalApps / perPage.toDouble()).toInt()

    /** Wraps around: past the last page is the first, before the first is the last. */
    fun wrapPage(page: Int, pageCount: Int): Int {
        if (pageCount <= 1) return 0
        return Math.floorMod(page, pageCount)
    }

    fun <T> pageSlice(apps: List<T>, page: Int, perPage: Int = PINNED_PER_PAGE): List<T> {
        if (apps.isEmpty()) return emptyList()
        val safePage = wrapPage(page, pageCount(apps.size, perPage))
        return apps.drop(safePage * perPage).take(perPage)
    }
}

/**
 * Rolling N-sample (default 3, ~6 s at 2 s sampling) battery current averager matching Ampere's
 * responsive mA output. Normalizes microamps to milliamps, prefers `BATTERY_PROPERTY_CURRENT_NOW`
 * with fallback to `BATTERY_PROPERTY_CURRENT_AVERAGE` when missing or transiently zero, and
 * immediately clears the window whenever the charging state flips so plug/unplug transitions
 * respond without lag.
 */
class BatteryCurrentRollingAverage(
    private val windowSize: Int = CockpitWidgetModel.CURRENT_AVERAGE_WINDOW,
) {
    private val lock = Any()
    private val samples = ArrayDeque<Long>()
    private var lastCharging: Boolean? = null

    val sampleCount: Int
        get() = synchronized(lock) { samples.size }

    fun reset() {
        synchronized(lock) {
            samples.clear()
            lastCharging = null
        }
    }

    fun record(
        currentNowMicroAmps: Long,
        currentAverageMicroAmps: Long = Long.MIN_VALUE,
        isCharging: Boolean,
    ): BatteryCurrentReading = synchronized(lock) {
        if (lastCharging != null && lastCharging != isCharging) {
            samples.clear()
        }
        lastCharging = isCharging

        val chosenMicroAmps = CockpitWidgetModel.selectPreferredCurrentMicroAmps(
            currentNowMicroAmps = currentNowMicroAmps,
            currentAverageMicroAmps = currentAverageMicroAmps,
        )
        val normalizedMa = CockpitWidgetModel.normalizeMicroAmpsToMilliAmps(chosenMicroAmps)
        if (normalizedMa != null) {
            if (samples.size >= windowSize.coerceAtLeast(1)) {
                samples.removeFirst()
            }
            samples.addLast(normalizedMa)
        }
        if (samples.isEmpty()) {
            return@synchronized CockpitWidgetModel.unknownCurrent()
        }
        val sum = samples.sum()
        val count = samples.size.toLong()
        val meanMa = (sum + count / 2L) / count
        CockpitWidgetModel.formatBatteryCurrentMilliAmps(meanMa, isCharging)
    }
}
