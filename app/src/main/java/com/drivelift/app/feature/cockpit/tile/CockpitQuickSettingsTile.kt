package com.drivelift.app.feature.cockpit.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.ui.MainActivity

/**
 * Pixel Quick Settings Tile for 1-tap DriveLift Cockpit Dynamic Island control.
 * Long-pressing opens Cockpit Hub via `QS_TILE_PREFERENCES`.
 */
class CockpitQuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val cockpit = Graph.get(applicationContext).cockpit
        val targetEnabled = !cockpit.prefsRepo.state.value.masterEnabled
        if (cockpit.setDynamicIslandEnabled(targetEnabled)) {
            refreshTileState()
        } else {
            openCockpitSetup()
        }
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val cockpit = Graph.get(applicationContext).cockpit
        val enabled = cockpit.prefsRepo.state.value.masterEnabled && cockpit.hasOverlayPermission()
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "DriveLift Cockpit"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (enabled) "Island active" else "Tap to enable"
        }
        tile.updateTile()
    }

    private fun openCockpitSetup() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                32,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
