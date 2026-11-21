package com.drivelift.app.feature.cockpit.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.drivelift.app.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Privacy-strict AccessibilityService whose sole purpose is mounting the Dynamic Island overlay
 * via `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` so touches clear Pixel SystemUI's
 * top status-bar touch dead zone. Subscribes to zero accessibility events and reads zero window content.
 */
class CockpitAccessibilityService :
    AccessibilityService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: CockpitOverlayWindowController? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller = CockpitOverlayWindowController(
            serviceContext = this,
            lifecycleOwner = this,
            savedStateRegistryOwner = this,
            viewModelStoreOwner = this,
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val cockpit = Graph.get(this).cockpit
        cockpit.syncOverlayService()
        serviceScope.launch {
            cockpit.prefsRepo.state.collect { prefs ->
                applyEnabledState(prefs.masterEnabled)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally a no-op: accessibilityEventTypes is empty and canRetrieveWindowContent is false.
    }

    override fun onInterrupt() {
        // No speech or feedback stream owned by this overlay service.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.updateMetrics()
        controller?.applyVisibility()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardownActiveOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownActiveOverlay()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun applyEnabledState(enabled: Boolean) {
        val windowController = controller ?: return
        val cockpit = Graph.get(this).cockpit
        if (enabled && cockpit.prefsRepo.isMasterEnabledMirrored()) {
            stopService(Intent(this, CockpitOverlayService::class.java))
            windowController.attachIfNeeded(serviceScope)
        } else {
            cockpit.coordinator.setExpanded(false)
            windowController.detach()
        }
    }

    private fun teardownActiveOverlay() {
        if (activeInstance === this) activeInstance = null
        controller?.detach()
        Graph.get(applicationContext).cockpit.let { cockpit ->
            if (cockpit.prefsRepo.isMasterEnabledMirrored() && cockpit.hasOverlayPermission()) {
                cockpit.syncOverlayService(true)
            }
        }
    }

    companion object {
        @Volatile
        private var activeInstance: CockpitAccessibilityService? = null

        fun isRunning(): Boolean = activeInstance != null

        fun isServiceEnabled(context: Context): Boolean {
            if (activeInstance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val expected = ComponentName(context, CockpitAccessibilityService::class.java).flattenToString()
            val shortName = ComponentName(context, CockpitAccessibilityService::class.java).flattenToShortString()
            return enabled.split(':').any { entry ->
                entry.equals(expected, ignoreCase = true) || entry.equals(shortName, ignoreCase = true)
            }
        }

        fun syncActiveOverlay(enabled: Boolean) {
            activeInstance?.let { instance ->
                instance.serviceScope.launch {
                    instance.applyEnabledState(enabled)
                }
            }
        }
    }
}
