package com.drivelift.app.feature.desktopdrop.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Invisible activity that finishes at once. Android refuses to start a foreground service from the
 * background (for example from a Home Screen widget tap after the process was cached), but allows
 * it from a visible activity. When [DesktopDropService.startService] is refused, the controller
 * launches this activity; it starts the service from the foreground and closes itself.
 */
class DesktopDropTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = DesktopDropService.startService(this)
        if (!started) Log.w(TAG, "Desktop Drop service still could not start from the foreground")
        finish()
    }

    companion object {
        private const val TAG = "DropTrampoline"

        /** Launches the trampoline. Returns false when Android also blocks this activity start. */
        fun launch(context: Context): Boolean = try {
            context.startActivity(
                Intent(context, DesktopDropTrampolineActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Trampoline launch refused: ${e.javaClass.simpleName}")
            false
        }
    }
}
