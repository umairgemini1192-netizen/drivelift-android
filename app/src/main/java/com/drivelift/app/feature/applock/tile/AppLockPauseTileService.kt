package com.drivelift.app.feature.applock.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.drivelift.app.Graph
import com.drivelift.app.feature.applock.AppLockGateMode
import com.drivelift.app.feature.applock.AppLockGateRequest
import com.drivelift.app.feature.applock.AppLockModule
import com.drivelift.app.feature.applock.ui.AppLockGateActivity
import com.drivelift.app.ui.MainActivity

/**
 * Pure presentation snapshot for [AppLockPauseTileService] so tile state logic is unit-testable.
 */
data class AppLockPauseTilePresentation(
    val state: Int,
    val label: String,
    val subtitle: String,
)

/**
 * Authenticated Quick Settings Tile for pausing App Lock for 15 minutes in RAM only.
 *
 * - Tapping while active opens [AppLockGateActivity] in [AppLockGateMode.AUTHORIZE_PAUSE] so
 *   pausing always requires Class 3 biometric or PIN authentication.
 * - Tapping while paused immediately resumes App Lock protection.
 * - Tapping while App Lock is disabled opens the App Lock Manager in [MainActivity].
 */
class AppLockPauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val appLock = Graph.get(applicationContext).appLock
        val config = appLock.prefs.config.value
        when {
            !config.enabled -> openAppLockManager()
            appLock.engine.isPaused() -> {
                appLock.resumeFromPause()
                refreshTileState()
            }
            else -> launchPauseAuthGate()
        }
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val appLock = Graph.get(applicationContext).appLock
        val presentation = computePresentation(
            enabled = appLock.prefs.config.value.enabled,
            isPaused = appLock.engine.isPaused(),
            pauseRemainingMs = appLock.engine.pauseRemainingMs(),
        )
        tile.state = presentation.state
        tile.label = presentation.label
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = presentation.subtitle
        }
        tile.updateTile()
    }

    private fun launchPauseAuthGate() {
        val intent = AppLockGateActivity.createIntent(
            this,
            AppLockGateRequest(
                mode = AppLockGateMode.AUTHORIZE_PAUSE,
                appLabel = "Pause App Lock",
            ),
        )
        launchAndCollapse(intent, requestCode = 41)
    }

    private fun openAppLockManager() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AppLockModule.EXTRA_OPEN_APP_LOCK, true)
        launchAndCollapse(intent, requestCode = 42)
    }

    private fun launchAndCollapse(intent: Intent, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun computePresentation(
            enabled: Boolean,
            isPaused: Boolean,
            pauseRemainingMs: Long,
        ): AppLockPauseTilePresentation = when {
            !enabled -> AppLockPauseTilePresentation(
                state = Tile.STATE_UNAVAILABLE,
                label = "Pause App Lock",
                subtitle = "App Lock off",
            )
            isPaused -> {
                val mins = ((pauseRemainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
                AppLockPauseTilePresentation(
                    state = Tile.STATE_ACTIVE,
                    label = "App Lock Paused",
                    subtitle = "${mins}m left",
                )
            }
            else -> AppLockPauseTilePresentation(
                state = Tile.STATE_INACTIVE,
                label = "Pause App Lock",
                subtitle = "15 min pause",
            )
        }
    }
}
