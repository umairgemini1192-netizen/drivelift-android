package com.drivelift.app.feature.cockpit.hardware

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tactile click and tick feedback helper using Android's predefined `EFFECT_CLICK` / `EFFECT_TICK` via
 * [VibratorManager] (API 31+) with safe fallback to [Vibrator] on API 30.
 */
object CockpitHaptics {
    fun performClick(context: Context) {
        perform(context, VibrationEffect.EFFECT_CLICK)
    }

    /** A lighter tick for swiping between live activities. */
    fun performTick(context: Context) {
        perform(context, VibrationEffect.EFFECT_TICK)
    }

    private fun perform(context: Context, effect: Int) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createPredefined(effect))
            }
        }
    }
}
