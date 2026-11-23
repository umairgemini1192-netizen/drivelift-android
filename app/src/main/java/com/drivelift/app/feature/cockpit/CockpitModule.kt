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
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.CockpitTorchController
import com.drivelift.app.feature.cockpit.notifications.CockpitCallActionsController
import com.drivelift.app.feature.cockpit.notifications.CockpitNotificationListener
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetryAdapter
import com.drivelift.app.feature.cockpit.overlay.CockpitAccessibilityService
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Self-contained Cockpit module wiring preferences, pinned launcher apps, 2-slot live activity
 * coordinator, read-only DriveLift telemetry, hardware flashlight, overlay services, and widget sync.
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
        CockpitStateCoordinator(scope = scope).also { coord ->
            scope.launch {
                prefsRepo.state.collect { prefs ->
                    coord.applyPrefs(prefs)
                }
            }
        }
    }

    val callActionsController: CockpitCallActionsController by lazy {
        CockpitCallActionsController(app)
    }

    val torchController: CockpitTorchController by lazy {
        CockpitTorchController(AndroidTorchHardwareBackend(app))
    }

    val quickControlsController: CockpitQuickControlsController by lazy {
        CockpitQuickControlsController(
            context = app,
            clock = graph.clock,
            onDefaultNetworkChanged = { caps ->
                telemetryMonitor.onDefaultNetworkCapabilitiesChanged(caps)
            },
        )
    }

    val telemetryMonitor: CockpitTelemetryMonitor by lazy {
        CockpitTelemetryMonitor(context = app)
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
        if (enabled && !hasAnyOverlayPermission()) {
            prefsRepo.setMasterEnabledAsync(false)
            coordinator.setExpanded(false)
            syncOverlayService(false)
            refreshWidgetsAsync()
            return false
        }
        prefsRepo.setMasterEnabledAsync(enabled)
        if (!enabled) coordinator.setExpanded(false)
        syncOverlayService(enabled)
        refreshWidgetsAsync()
        return true
    }

    suspend fun setDynamicIslandEnabledAndSync(enabled: Boolean): Boolean {
        if (enabled && !hasAnyOverlayPermission()) {
            prefsRepo.setMasterEnabled(false)
            coordinator.setExpanded(false)
            syncOverlayService(false)
            runCatching { CockpitGlanceWidget.updateAllWidgets(app) }
            return false
        }
        prefsRepo.setMasterEnabled(enabled)
        if (!enabled) coordinator.setExpanded(false)
        syncOverlayService(enabled)
        runCatching { CockpitGlanceWidget.updateAllWidgets(app) }
        return true
    }

    fun syncOverlayService(enabled: Boolean = prefsRepo.isMasterEnabledMirrored()) {
        val effectiveEnabled = enabled && prefsRepo.isMasterEnabledMirrored()
        CockpitAccessibilityService.syncActiveOverlay(effectiveEnabled)

        val shouldFallback = CockpitOverlayService.shouldMountFallbackOverlay(
            masterEnabled = effectiveEnabled,
            hasOverlayPermission = hasOverlayPermission(),
            accessibilityOverlayActive = CockpitAccessibilityService.isConnected,
        )
        val intent = Intent(app, CockpitOverlayService::class.java)
        if (shouldFallback) {
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

    private fun refreshWidgetsAsync() {
        scope.launch(Dispatchers.Default) {
            runCatching { CockpitGlanceWidget.updateAllWidgets(app) }
        }
    }

    fun hasAccessibilityOverlay(): Boolean = CockpitAccessibilityService.isServiceEnabled(app)

    fun hasOverlayPermission(): Boolean = runCatching {
        Settings.canDrawOverlays(app)
    }.getOrDefault(false)

    fun hasAnyOverlayPermission(): Boolean = hasAccessibilityOverlay() || hasOverlayPermission()

    fun hasNotificationAccess(): Boolean =
        CockpitNotificationListener.isPermissionGranted(app)

    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
    }.getOrDefault(false)
}
