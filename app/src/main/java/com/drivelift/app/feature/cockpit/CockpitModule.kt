package com.drivelift.app.feature.cockpit

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.activities.CockpitStateCoordinator
import com.drivelift.app.feature.cockpit.data.CockpitPreferencesRepository
import com.drivelift.app.feature.cockpit.data.PinnedAppsRepository
import com.drivelift.app.feature.cockpit.hardware.AndroidTorchHardwareBackend
import com.drivelift.app.feature.cockpit.hardware.CockpitTorchController
import com.drivelift.app.feature.cockpit.notifications.CockpitNotificationListener
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetryAdapter
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Self-contained Cockpit module wiring preferences, pinned launcher apps, 2-slot live activity
 * coordinator, read-only DriveLift telemetry, hardware flashlight, and overlay service control.
 */
class CockpitModule(
    private val app: Application,
    graph: Graph,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefsRepo: CockpitPreferencesRepository by lazy {
        CockpitPreferencesRepository(app)
    }

    val pinnedAppsRepo: PinnedAppsRepository by lazy {
        PinnedAppsRepository(app)
    }

    val coordinator: CockpitStateCoordinator by lazy {
        CockpitStateCoordinator().also { coord ->
            scope.launch {
                prefsRepo.state.collect { prefs ->
                    coord.applyPrefs(prefs)
                }
            }
        }
    }

    val torchController: CockpitTorchController by lazy {
        CockpitTorchController(AndroidTorchHardwareBackend(app))
    }

    val telemetryAdapter: DriveLiftTelemetryAdapter by lazy {
        DriveLiftTelemetryAdapter(
            desktopDropController = graph.desktopDrop.controller,
            transferDao = graph.db.transfers(),
            coordinator = coordinator,
            clock = graph.clock,
        )
    }

    private val _openHubPending = MutableStateFlow(false)
    val openHubPending: StateFlow<Boolean> = _openHubPending.asStateFlow()

    fun requestOpenHub() {
        _openHubPending.value = true
    }

    fun consumeOpenHubRequest() {
        _openHubPending.value = false
    }

    fun setDynamicIslandEnabled(enabled: Boolean): Boolean {
        if (enabled && !hasOverlayPermission()) {
            prefsRepo.setMasterEnabledAsync(false)
            return false
        }
        prefsRepo.setMasterEnabledAsync(enabled)
        syncOverlayService(enabled)
        return true
    }

    fun syncOverlayService(enabled: Boolean = prefsRepo.state.value.masterEnabled) {
        val intent = Intent(app, CockpitOverlayService::class.java)
        if (enabled && hasOverlayPermission()) {
            intent.action = CockpitOverlayService.ACTION_START
            if (Build.VERSION.SDK_INT >= 26) {
                runCatching { app.startForegroundService(intent) }
            } else {
                runCatching { app.startService(intent) }
            }
        } else {
            intent.action = CockpitOverlayService.ACTION_STOP
            runCatching { app.stopService(intent) }
        }
    }

    fun hasOverlayPermission(): Boolean = runCatching {
        Settings.canDrawOverlays(app)
    }.getOrDefault(false)

    fun hasNotificationAccess(): Boolean =
        CockpitNotificationListener.isPermissionGranted(app)

    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
    }.getOrDefault(false)
}
