package com.drivelift.app.feature.desktopdrop.di

import android.app.Application
import com.drivelift.app.Graph
import com.drivelift.app.feature.desktopdrop.controller.DesktopDropController
import com.drivelift.app.feature.desktopdrop.data.AndroidSharedItemSource
import com.drivelift.app.feature.desktopdrop.data.DesktopDropPrefs
import com.drivelift.app.feature.desktopdrop.network.LanNetworkMonitor
import com.drivelift.app.feature.desktopdrop.network.LocalHotspotController

/**
 * Self-contained module wiring Desktop Drop preferences, networking, and controller without
 * modifying core repositories or Room AppDatabase.
 */
class DesktopDropModule(
    app: Application,
    graph: Graph,
) {
    val prefs: DesktopDropPrefs by lazy { DesktopDropPrefs(app) }
    val itemSource: AndroidSharedItemSource by lazy { AndroidSharedItemSource(app) }
    val lanMonitor: LanNetworkMonitor by lazy { LanNetworkMonitor(app) }
    val hotspotController: LocalHotspotController by lazy { LocalHotspotController(app) }
    val controller: DesktopDropController by lazy {
        DesktopDropController(
            app = app,
            prefs = prefs,
            itemSource = itemSource,
            lanMonitor = lanMonitor,
            hotspotController = hotspotController,
            clock = graph.clock,
        )
    }
}
