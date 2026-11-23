package com.drivelift.app.feature.cockpit.activities

import java.util.Locale
import kotlin.math.roundToInt

/** Trip distance bookkeeping for one navigation session. All values are kilometres. */
data class TripProgress(
    val startKm: Double,
    val remainingKm: Double,
    val droveKm: Double,
) {
    /** 0 at the start of the route, 1 at the destination. */
    val fraction: Float
        get() {
            val total = droveKm + remainingKm
            return if (total <= 0.0) 0f else (droveKm / total).toFloat().coerceIn(0f, 1f)
        }
}

/**
 * Works out how far along the route the driver is without using GPS. The first remaining distance
 * seen in a session is the start; later readings give `droveKm = (startKm - remainingKm)`, never
 * below zero. The start is taken again when the route changes (a different destination, or a new
 * route that is clearly longer than the old one) and when the session ends.
 */
class NavigationTripTracker(private val routeChangeKm: Double = 0.5) {
    private var startKm: Double? = null
    private var routeKey: String = ""

    /** Null when there is no usable remaining distance. The stored start is kept in that case. */
    fun update(remainingKm: Double?, routeKey: String = ""): TripProgress? {
        if (remainingKm == null || remainingKm.isNaN() || remainingKm.isInfinite() || remainingKm < 0.0) return null
        val start = startKm
        if (start == null || routeKey != this.routeKey || remainingKm > start + routeChangeKm) {
            startKm = remainingKm
            this.routeKey = routeKey
        }
        val resolvedStart = startKm ?: remainingKm
        return TripProgress(
            startKm = resolvedStart,
            remainingKm = remainingKm,
            droveKm = (resolvedStart - remainingKm).coerceAtLeast(0.0),
        )
    }

    /** Navigation ended or was dismissed at the source. The next reading starts a new trip. */
    fun reset() {
        startKm = null
        routeKey = ""
    }

    val currentStartKm: Double? get() = startKm
}

/** Text for the trip footer of the expanded navigation card. */
object TripDistanceFormat {
    private const val KM_PER_MILE = 1.609344

    /** `3.2 km`, `12 km`, `0.4 mi`. One decimal below 100, whole numbers from 100. */
    fun distance(km: Double, miles: Boolean): String {
        val value = if (miles) km / KM_PER_MILE else km
        val unit = if (miles) "mi" else "km"
        val shown = value.coerceAtLeast(0.0)
        return if (shown >= 100.0) {
            "${shown.roundToInt()} $unit"
        } else {
            String.format(Locale.US, "%.1f %s", shown, unit)
        }
    }

    /** `3.2 km drove · 8.4 km left`. */
    fun footer(droveKm: Double, remainingKm: Double, miles: Boolean): String =
        "${distance(droveKm, miles)} drove · ${distance(remainingKm, miles)} left"

    /** Compact HUD metric string (`8.4 km`, `3.2 km`, `1.0 mi`) or `--` when unavailable. */
    fun hudValue(km: Double?, miles: Boolean): String =
        if (km == null || km.isNaN() || km.isInfinite() || km < 0.0) "--" else distance(km, miles)

    /** Live speed readout in km/h (`64`) or `--` when no GPS speed fix is available. */
    fun speedValue(speedKmh: Int?): String =
        if (speedKmh == null || speedKmh < 0) "--" else speedKmh.toString()

    /** Converts GPS speed in m/s to km/h, suppressing stationary sensor drift under 2 km/h. */
    fun metersPerSecondToKmh(speedMetersPerSec: Float): Int {
        if (speedMetersPerSec.isNaN() || speedMetersPerSec.isInfinite() || speedMetersPerSec <= 0f) return 0
        val kmh = (speedMetersPerSec * 3.6f).roundToInt()
        return if (kmh < 2) 0 else kmh
    }
}
