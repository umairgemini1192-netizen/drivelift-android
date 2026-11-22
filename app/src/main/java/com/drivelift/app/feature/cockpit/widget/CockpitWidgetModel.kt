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

/** What the Drop control tile on the widget and its in-app preview shows. */
data class WidgetDropTile(val label: String, val active: Boolean, val enabled: Boolean)

/**
 * Pure rules shared by the Home Screen `CockpitGlanceWidget` and the in-app preview
 * (`CockpitMasterWidgetCard`), so both always say the same thing. Nothing here touches Android.
 */
object CockpitWidgetModel {
    const val PINNED_PER_PAGE = 4
    private const val UNSUPPORTED_CURRENT = Long.MIN_VALUE

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

    /**
     * Battery current from `BATTERY_PROPERTY_CURRENT_NOW` (microamps). The sign is taken from the
     * charging state rather than the raw value, because phones disagree about which way is
     * positive: charging reads `+2,450 mA`, discharging reads `-510 mA`.
     */
    fun formatBatteryCurrent(microAmps: Long, isCharging: Boolean): BatteryCurrentReading {
        if (microAmps == UNSUPPORTED_CURRENT) return unknownCurrent()
        val magnitudeMa = abs(microAmps) / 1000L
        val signed = if (isCharging) magnitudeMa else -magnitudeMa
        val asInt = signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        val digits = String.format(Locale.US, "%,d", magnitudeMa)
        val text = when {
            magnitudeMa == 0L -> "0 mA"
            isCharging -> "+$digits mA"
            else -> "-$digits mA"
        }
        return BatteryCurrentReading(
            milliAmps = asInt,
            text = text,
            tone = if (isCharging) BatteryCurrentTone.CHARGING else BatteryCurrentTone.DISCHARGING,
        )
    }

    fun unknownCurrent(): BatteryCurrentReading =
        BatteryCurrentReading(milliAmps = null, text = "-- mA", tone = BatteryCurrentTone.UNKNOWN)

    /** True when a change in current is big enough to redraw the widget. */
    fun currentChangedEnough(previous: Int?, current: Int?, thresholdMa: Int = 50): Boolean {
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
