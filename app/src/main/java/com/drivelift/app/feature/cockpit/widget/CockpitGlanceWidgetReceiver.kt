package com.drivelift.app.feature.cockpit.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.hardware.CockpitHaptics
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.ui.MainActivity

/**
 * Direct `AppWidgetProvider` for the DriveLift Cockpit Master Widget (retaining the manifest
 * receiver class name so existing home-screen widgets upgrade cleanly without re-pinning).
 *
 * Handles every interactive button via explicit unique-request-code `PendingIntent` broadcasts and
 * triggers immediate `AppWidgetManager.updateAppWidget()` redraws so rapid taps (Torch, Drop,
 * Island, App Dock `<` / `>` paging) never freeze behind a background Glance session worker.
 */
class CockpitGlanceWidgetReceiver : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        runCatching { Graph.get(context).cockpit.telemetryMonitor.setWidgetBound(true) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        runCatching { Graph.get(context).cockpit.telemetryMonitor.setWidgetBound(false) }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isNotEmpty()) {
            runCatching { Graph.get(context).cockpit.telemetryMonitor.setWidgetBound(true) }
        }
        for (appWidgetId in appWidgetIds) {
            CockpitGlanceWidget.updateWidget(
                context = context,
                appWidgetId = appWidgetId,
                manager = appWidgetManager,
            )
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        CockpitGlanceWidget.updateWidget(
            context = context,
            appWidgetId = appWidgetId,
            manager = appWidgetManager,
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            CockpitGlanceWidget.clearWidgetState(context, appWidgetId)
        }
        if (!CockpitGlanceWidget.hasBoundWidgets(context)) {
            runCatching { Graph.get(context).cockpit.telemetryMonitor.setWidgetBound(false) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val appWidgetId = intent.getIntExtra(
            CockpitWidgetActions.EXTRA_APP_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        when (action) {
            CockpitWidgetActions.ACTION_OPEN_CLOCK,
            CockpitWidgetActions.ACTION_OPEN_ALARM,
            -> {
                CockpitHaptics.performClick(context)
                CockpitGlanceWidget.launchGoogleClockAlarms(context)
            }

            CockpitWidgetActions.ACTION_OPEN_DRIVELIFT -> {
                CockpitHaptics.performClick(context)
                openCockpitHub(context)
            }

            CockpitWidgetActions.ACTION_TOGGLE_DROP -> {
                CockpitHaptics.performClick(context)
                handleToggleDrop(context)
                refreshTarget(context, appWidgetId)
            }

            CockpitWidgetActions.ACTION_TOGGLE_TORCH -> {
                CockpitHaptics.performClick(context)
                handleToggleTorch(context)
                refreshTarget(context, appWidgetId)
            }

            CockpitWidgetActions.ACTION_TOGGLE_ISLAND -> {
                CockpitHaptics.performClick(context)
                handleToggleIsland(context)
                refreshTarget(context, appWidgetId)
            }

            CockpitWidgetActions.ACTION_PINNED_PREV -> {
                CockpitHaptics.performClick(context)
                handlePageStep(context, appWidgetId, delta = -1)
            }

            CockpitWidgetActions.ACTION_PINNED_NEXT -> {
                CockpitHaptics.performClick(context)
                handlePageStep(context, appWidgetId, delta = +1)
            }

            CockpitWidgetActions.ACTION_LAUNCH_APP -> {
                CockpitHaptics.performClick(context)
                val pkg = intent.getStringExtra(CockpitWidgetActions.EXTRA_PACKAGE_NAME)
                if (!pkg.isNullOrBlank()) {
                    Graph.get(context).cockpit.pinnedAppsRepo.launchApp(pkg)
                }
            }

            else -> super.onReceive(context, intent)
        }
    }

    private fun handleToggleDrop(context: Context) {
        val graph = Graph.get(context)
        val controller = graph.desktopDrop.controller
        val snap = controller.snapshot.value
        val status = snap.status
        if (status == ServerStatus.RUNNING || status == ServerStatus.STARTING) {
            controller.stopServer("Stopped from Cockpit widget")
            CockpitGlanceWidget.showBriefWidgetToast(context, "Desktop Drop stopped")
        } else if (!CockpitGlanceWidget.isDropToggleAvailable(
                context = context,
                telemetry = graph.cockpit.telemetryAdapter.summary.value,
                mode = snap.mode,
            )
        ) {
            CockpitGlanceWidget.showBriefWidgetToast(context, "Connect to Wi-Fi or use Hotspot in Drop")
        } else {
            controller.startServer()
            CockpitGlanceWidget.showBriefWidgetToast(context, "Desktop Drop starting")
        }
    }

    private fun handleToggleTorch(context: Context) {
        val torchController = Graph.get(context).cockpit.torchController
        val torchState = torchController.state.value
        if (!torchState.available) {
            CockpitGlanceWidget.showBriefWidgetToast(
                context,
                if (torchState.cameraInUse) "Torch unavailable while camera is in use" else "No flashlight detected",
            )
        } else {
            torchController.toggleTorch()
            val nextOn = torchController.state.value.isOn
            CockpitGlanceWidget.showBriefWidgetToast(context, if (nextOn) "Torch on" else "Torch off")
        }
    }

    private fun handleToggleIsland(context: Context) {
        val cockpit = Graph.get(context).cockpit
        val current = cockpit.prefsRepo.isMasterEnabledMirrored()
        if (!cockpit.setDynamicIslandEnabled(!current)) {
            CockpitGlanceWidget.showBriefWidgetToast(context, "Grant overlay permission in Cockpit Hub")
            openCockpitHub(context)
        } else {
            CockpitGlanceWidget.showBriefWidgetToast(
                context,
                if (!current) "Dynamic Island on" else "Dynamic Island off",
            )
        }
    }

    private fun handlePageStep(context: Context, appWidgetId: Int, delta: Int) {
        val cockpit = Graph.get(context).cockpit
        val total = cockpit.pinnedAppsRepo.resolvePinnedApps(cockpit.prefsRepo.state.value.pinnedPackages).size
        val count = CockpitWidgetModel.pageCount(total)
        val current = CockpitGlanceWidget.readSavedPage(context, appWidgetId)
        val nextPage = CockpitWidgetModel.wrapPage(current + delta, count)
        CockpitGlanceWidget.savePage(context, appWidgetId, nextPage)
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            CockpitGlanceWidget.updateWidget(
                context = context,
                appWidgetId = appWidgetId,
                overridePageIndex = nextPage,
            )
        } else {
            CockpitGlanceWidget.updateAllWidgets(context)
        }
    }

    private fun refreshTarget(context: Context, appWidgetId: Int) {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            CockpitGlanceWidget.updateWidget(context = context, appWidgetId = appWidgetId)
        }
        CockpitGlanceWidget.updateAllWidgets(context)
    }

    private fun openCockpitHub(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
        runCatching { context.startActivity(intent) }
    }
}
