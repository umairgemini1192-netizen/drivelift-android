package com.drivelift.app.feature.cockpit.activities

import java.util.Locale

/** Live call and recording clock text: `MM:SS`, or `H:MM:SS` from one hour on. Never negative. */
object CockpitDurationFormat {
    fun clock(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }
}
