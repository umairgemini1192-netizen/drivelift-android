package com.drivelift.app.feature.cockpit.widget

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.drivelift.app.Graph
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.NextAlarmFormatter
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.hardware.TelemetryHudSnapshot
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.desktopdrop.model.ConnectivityMode
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.ui.GlassPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Responsive size bucket for the Home Screen Master Widget (Android 12+ SizeF mapping). */
enum class WidgetSizeVariant(
    val layoutResId: Int,
    val gaugeSizeDp: Float,
    val tileHeightDp: Float,
    val showAppLabels: Boolean,
    val showExtendedTelemetry: Boolean,
    val showTallNetworkRow: Boolean,
) {
    COMPACT(
        layoutResId = R.layout.cockpit_widget_compact,
        gaugeSizeDp = 30f,
        tileHeightDp = 32f,
        showAppLabels = false,
        showExtendedTelemetry = false,
        showTallNetworkRow = false,
    ),
    NORMAL(
        layoutResId = R.layout.cockpit_widget_master,
        gaugeSizeDp = 44f,
        tileHeightDp = 42f,
        showAppLabels = true,
        showExtendedTelemetry = true,
        showTallNetworkRow = false,
    ),
    TALL(
        layoutResId = R.layout.cockpit_widget_tall,
        gaugeSizeDp = 56f,
        tileHeightDp = 48f,
        showAppLabels = true,
        showExtendedTelemetry = true,
        showTallNetworkRow = true,
    ),
}

data class BatteryHudMetric(
    val percent: Int,
    val fraction: Float,
    val valueText: String,
    val label: String = "BATT",
    val isCharging: Boolean = false,
    val isLow: Boolean = false,
    /** Live battery current, for example `-510 mA` while draining or `+2,450 mA` while charging. */
    val current: BatteryCurrentReading = CockpitWidgetModel.unknownCurrent(),
    /** Live battery charge counter in mAh, converted from `BATTERY_PROPERTY_CHARGE_COUNTER` (uAh). */
    val charge: BatteryChargeReading = CockpitWidgetModel.unknownCharge(),
)

data class RamHudMetric(
    val freeGb: Double,
    val usedGb: Double = 0.0,
    val totalGb: Double = 0.0,
    val fraction: Float,
    val valueText: String,
    val label: String = "RAM",
    val usedPercentText: String = "--%",
    val freeText: String = "-- free",
)

/**
 * Explicit widget broadcast actions and unique per-button `PendingIntent` request codes so Android
 * never collapses clicks across interactive buttons or drops rapid taps (Torch, Drop, Island,
 * App Dock pager `<` / `>`, and pinned app shortcuts).
 */
object CockpitWidgetActions {
    const val ACTION_OPEN_CLOCK = "com.drivelift.app.cockpit.widget.OPEN_CLOCK"
    const val ACTION_OPEN_ALARM = "com.drivelift.app.cockpit.widget.OPEN_ALARM"
    const val ACTION_OPEN_DRIVELIFT = "com.drivelift.app.cockpit.widget.OPEN_DRIVELIFT"
    const val ACTION_TOGGLE_DROP = "com.drivelift.app.cockpit.widget.TOGGLE_DROP"
    const val ACTION_TOGGLE_TORCH = "com.drivelift.app.cockpit.widget.TOGGLE_TORCH"
    const val ACTION_TOGGLE_ISLAND = "com.drivelift.app.cockpit.widget.TOGGLE_ISLAND"
    const val ACTION_PINNED_PREV = "com.drivelift.app.cockpit.widget.PINNED_PREV"
    const val ACTION_PINNED_NEXT = "com.drivelift.app.cockpit.widget.PINNED_NEXT"
    const val ACTION_LAUNCH_APP = "com.drivelift.app.cockpit.widget.LAUNCH_APP"

    const val EXTRA_APP_WIDGET_ID = "cockpit_app_widget_id"
    const val EXTRA_PACKAGE_NAME = "cockpit_pinned_package"

    const val SLOT_CLOCK = 1
    const val SLOT_ALARM = 2
    const val SLOT_STATUS = 3
    const val SLOT_DROP = 4
    const val SLOT_TORCH = 5
    const val SLOT_ISLAND = 6
    const val SLOT_PAGER_PREV = 7
    const val SLOT_PAGER_NEXT = 8
    const val SLOT_PINNED_APP_BASE = 10

    private const val REQUEST_CODE_STRIDE = 32

    /** Deterministic, strictly unique request code for every `(appWidgetId, buttonSlot)` pair. */
    fun requestCodeFor(appWidgetId: Int, slot: Int): Int =
        (appWidgetId.coerceAtLeast(0) * REQUEST_CODE_STRIDE) + slot

    fun createBroadcastPendingIntent(
        context: Context,
        appWidgetId: Int,
        slot: Int,
        action: String,
        packageName: String? = null,
    ): PendingIntent {
        val intent = Intent(context, CockpitGlanceWidgetReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("drivelift://cockpit-widget/$appWidgetId/$slot")
            putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
            if (packageName != null) {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(appWidgetId, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * 60-second cache for today's mobile data total so 1-2 s network widget partial patches do not
 * repeatedly query `NetworkStatsManager.querySummaryForDevice` in `system_server`.
 * Invalidated on screen-on and refreshed on full widget rebuilds or after [ttlMs].
 */
internal class TodayMobileDataCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val reader: (Context) -> String = { ctx ->
        CockpitQuickControlsController.readTodayMobileDataLabel(ctx)
    },
) {
    private val lock = Any()
    private var cachedLabel: String? = null
    private var lastReadElapsedMs: Long = Long.MIN_VALUE

    fun get(context: Context, forceRefresh: Boolean = false): String = synchronized(lock) {
        val now = elapsedClock()
        val fresh = !forceRefresh &&
            cachedLabel != null &&
            lastReadElapsedMs != Long.MIN_VALUE &&
            (now - lastReadElapsedMs) in 0L until ttlMs
        if (fresh) {
            return cachedLabel!!
        }
        val value = reader(context)
        cachedLabel = value
        lastReadElapsedMs = now
        value
    }

    fun invalidate() = synchronized(lock) {
        cachedLabel = null
        lastReadElapsedMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
    }
}

/**
 * Home Screen Cyber-Glass Bento HUD Master Widget builder & state synchronizer.
 *
 * Uses direct `RemoteViews` + `AppWidgetManager.updateAppWidget()` with unique `PendingIntent`
 * request codes per interactive button so rapid taps (such as multi-tap Torch and App Dock `<`/`>`
 * paging) never stall in a background worker, and decouples frequent telemetry refreshes onto
 * lightweight `AppWidgetManager.partiallyUpdateAppWidget()` calls so telemetry ticks never wipe
 * out or drop interactive click events.
 */
class CockpitGlanceWidget {

    companion object {
        private const val WIDGET_PREFS = "cockpit_widget_state"
        private const val KEY_PINNED_PAGE_PREFIX = "pinned_page_"
        private const val KEY_DROP_LIVE_OVERRIDE = "drop_live_override"

        internal const val COMPACT_MIN_WIDTH_DP = 180f
        internal const val COMPACT_MIN_HEIGHT_DP = 110f
        internal const val NORMAL_MIN_HEIGHT_DP = 200f
        internal const val TALL_MIN_HEIGHT_DP = 280f

        /** Maps launcher widget bounds in dp to its responsive [WidgetSizeVariant]. */
        fun selectWidgetSizeVariant(widthDp: Float, heightDp: Float): WidgetSizeVariant = when {
            heightDp < NORMAL_MIN_HEIGHT_DP -> WidgetSizeVariant.COMPACT
            heightDp >= TALL_MIN_HEIGHT_DP -> WidgetSizeVariant.TALL
            else -> WidgetSizeVariant.NORMAL
        }

        /** Formats live network connection + throughput for the Tall home screen widget row. */
        fun formatWidgetNetworkSpeed(snapshot: IdleNetworkSnapshot): String = when {
            snapshot.kind == IdleNetworkKind.OFFLINE -> "Offline"
            !snapshot.hasActiveTraffic -> "${snapshot.label} \u00B7 Idle"
            else -> "${snapshot.label} \u00B7 Up ${snapshot.uploadText} \u00B7 Down ${snapshot.downloadText}"
        }

        private fun wrapResponsiveRemoteViews(
            compact: RemoteViews,
            normal: RemoteViews,
            tall: RemoteViews,
        ): RemoteViews = if (Build.VERSION.SDK_INT >= 31) {
            RemoteViews(
                mapOf(
                    SizeF(COMPACT_MIN_WIDTH_DP, COMPACT_MIN_HEIGHT_DP) to compact,
                    SizeF(COMPACT_MIN_WIDTH_DP, NORMAL_MIN_HEIGHT_DP) to normal,
                    SizeF(COMPACT_MIN_WIDTH_DP, TALL_MIN_HEIGHT_DP) to tall,
                ),
            )
        } else {
            normal
        }

        private val APP_CONTAINER_IDS = intArrayOf(
            R.id.cockpit_widget_app_0,
            R.id.cockpit_widget_app_1,
            R.id.cockpit_widget_app_2,
            R.id.cockpit_widget_app_3,
        )
        private val APP_ICON_IDS = intArrayOf(
            R.id.cockpit_widget_app_icon_0,
            R.id.cockpit_widget_app_icon_1,
            R.id.cockpit_widget_app_icon_2,
            R.id.cockpit_widget_app_icon_3,
        )
        private val APP_LABEL_IDS = intArrayOf(
            R.id.cockpit_widget_app_label_0,
            R.id.cockpit_widget_app_label_1,
            R.id.cockpit_widget_app_label_2,
            R.id.cockpit_widget_app_label_3,
        )
        private val PAGER_DOT_IDS = intArrayOf(
            R.id.cockpit_widget_dot_0,
            R.id.cockpit_widget_dot_1,
            R.id.cockpit_widget_dot_2,
            R.id.cockpit_widget_dot_3,
        )

        private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val batteryCurrentAverager = BatteryCurrentRollingAverage(
            windowSize = CockpitWidgetModel.CURRENT_AVERAGE_WINDOW,
        )
        private val todayMobileDataCache = TodayMobileDataCache()

        internal fun resetBatteryCurrentAveragerForTest() {
            batteryCurrentAverager.reset()
        }

        internal fun invalidateTodayMobileDataCache() {
            todayMobileDataCache.invalidate()
        }

        internal fun readCachedTodayMobileDataLabel(
            context: Context,
            forceRefresh: Boolean = false,
        ): String = todayMobileDataCache.get(context, forceRefresh = forceRefresh)

        fun readSavedPage(context: Context, appWidgetId: Int): Int {
            val prefs = context.applicationContext.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            return prefs.getInt("$KEY_PINNED_PAGE_PREFIX$appWidgetId", 0)
        }

        fun savePage(context: Context, appWidgetId: Int, page: Int) {
            val prefs = context.applicationContext.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt("$KEY_PINNED_PAGE_PREFIX$appWidgetId", page).commit()
        }

        fun clearWidgetState(context: Context, appWidgetId: Int) {
            val prefs = context.applicationContext.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove("$KEY_PINNED_PAGE_PREFIX$appWidgetId").apply()
        }

        /**
         * Immediately updates all bound widgets with a full RemoteViews pass (used when user taps
         * Drop, Torch, Island, Alarm changes, or pinned apps change).
         */
        fun updateAllWidgets(context: Context) {
            val appContext = context.applicationContext ?: context
            val manager = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull() ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(appContext, CockpitGlanceWidgetReceiver::class.java))
            }.getOrDefault(IntArray(0))
            if (ids.isEmpty()) return
            for (id in ids) {
                updateWidget(appContext, id, manager = manager)
            }
        }

        /** Immediately updates a single bound widget via `AppWidgetManager.updateAppWidget()`. */
        fun updateWidget(
            context: Context,
            appWidgetId: Int,
            overridePageIndex: Int? = null,
            manager: AppWidgetManager? = null,
        ) {
            val appContext = context.applicationContext ?: context
            val appWidgetManager = manager ?: runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull()
                ?: return
            val views = buildFullRemoteViews(
                context = appContext,
                appWidgetId = appWidgetId,
                overridePageIndex = overridePageIndex,
            )
            runCatching { appWidgetManager.updateAppWidget(appWidgetId, views) }
        }

        fun hasBoundWidgets(context: Context): Boolean {
            val appContext = context.applicationContext ?: context
            val manager = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull() ?: return false
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(appContext, CockpitGlanceWidgetReceiver::class.java))
            }.getOrDefault(IntArray(0))
            return ids.isNotEmpty()
        }

        /**
         * Decoupled lightweight telemetry refresh: patches ONLY the Battery ring, live mA/mAh
         * readouts, and RAM ring + used/free readouts via `partiallyUpdateAppWidget()`.
         * Interactive `PendingIntent`s on Drop, Torch, Island, Pager `<`/`>`, and Pinned Apps are
         * untouched, so frequent telemetry updates never freeze or drop taps.
         */
        suspend fun pushTelemetryUpdate(context: Context) {
            val appContext = context.applicationContext ?: context
            if (!hasBoundWidgets(appContext)) return
            val snapshot = TelemetryHudSnapshot(
                battery = readBatteryMetric(appContext),
                ram = readRamMetric(appContext),
                isLoadingInitial = false,
            )
            val network = runCatching { Graph.get(appContext).cockpit.telemetryMonitor.network.value }.getOrNull()
            pushTelemetryUpdate(appContext, snapshot, batteryCurrentOnly = false, network = network)
        }

        /**
         * Pushes an already-sampled [TelemetryHudSnapshot] without re-sampling the rolling current
         * averager. When [batteryCurrentOnly] is true, patches only the mA and mAh TextViews (plus
         * the Tall network row when provided) via `partiallyUpdateAppWidget()` with zero bitmap
         * redraws. When [networkOnly] is true, patches only the Tall widget network/data row.
         */
        suspend fun pushTelemetryUpdate(
            context: Context,
            snapshot: TelemetryHudSnapshot,
            batteryCurrentOnly: Boolean = false,
            network: IdleNetworkSnapshot? = null,
            networkOnly: Boolean = false,
        ) {
            val appContext = context.applicationContext ?: context
            if (networkOnly && (network == null || Build.VERSION.SDK_INT < 31)) return
            val manager = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull() ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(appContext, CockpitGlanceWidgetReceiver::class.java))
            }.getOrDefault(IntArray(0))
            if (ids.isEmpty()) return

            val partial = when {
                networkOnly && network != null -> buildNetworkPartialRemoteViews(
                    context = appContext,
                    network = network,
                )
                batteryCurrentOnly -> buildBatteryCurrentPartialRemoteViews(
                    context = appContext,
                    battery = snapshot.battery,
                    network = network,
                )
                else -> buildTelemetryPartialRemoteViews(
                    context = appContext,
                    battery = snapshot.battery,
                    ram = snapshot.ram,
                    network = network,
                )
            }
            runCatching { manager.partiallyUpdateAppWidget(ids, partial) }
        }

        /** Pushes a real-time refresh to all bound Cockpit widgets. */
        suspend fun pushRealtimeWidgetUpdate(context: Context) {
            pushTelemetryUpdate(context)
        }

        /**
         * Records the live Drop state and redraws every bound widget at once, so the Drop tile
         * flips to Stop and the status pill to DROP LIVE (and back) the moment the server changes
         * state. Never throws and never blocks the caller.
         */
        fun syncDropState(context: Context, running: Boolean) {
            val appContext = context.applicationContext ?: context
            syncScope.launch {
                runCatching {
                    appContext.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_DROP_LIVE_OVERRIDE, running)
                        .apply()
                    updateAllWidgets(appContext)
                }
            }
        }

        suspend fun registerGeneratedPreviewsIfSupported(context: Context) {
            if (Build.VERSION.SDK_INT >= 35) {
                runCatching {
                    val appContext = context.applicationContext ?: context
                    val manager = AppWidgetManager.getInstance(appContext)
                    val previewViews = buildPreviewRemoteViews(appContext)
                    manager.setWidgetPreview(
                        ComponentName(appContext, CockpitGlanceWidgetReceiver::class.java),
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                        previewViews,
                    )
                }
            }
        }

        fun buildFullRemoteViews(
            context: Context,
            appWidgetId: Int,
            overridePageIndex: Int? = null,
        ): RemoteViews {
            val graph = Graph.get(context)
            val cockpit = graph.cockpit
            val prefs = cockpit.prefsRepo.state.value
            val islandEnabled = cockpit.prefsRepo.isMasterEnabledMirrored()
            val telemetry = cockpit.telemetryAdapter.summary.value
            val alarm = NextAlarmFormatter.readFromSystem(context)
            val torchState = cockpit.torchController.state.value
            val torchOn = torchState.isOn && torchState.available
            val torchAvailable = torchState.available

            val dropSnapshot = graph.desktopDrop.controller.snapshot.value
            val dropLive = dropSnapshot.status == ServerStatus.RUNNING || dropSnapshot.status == ServerStatus.STARTING
            val dropAvailable = isDropToggleAvailable(
                context = context,
                telemetry = telemetry.copy(isDesktopDropRunning = dropLive),
                mode = dropSnapshot.mode,
            )
            val battery = readBatteryMetric(context)
            val ram = readRamMetric(context)
            val network = cockpit.telemetryMonitor.network.value
            val todayDataLabel = readCachedTodayMobileDataLabel(context, forceRefresh = true)

            val allPinned = cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages)
            val pageCount = CockpitWidgetModel.pageCount(allPinned.size)
            val rawPage = overridePageIndex ?: readSavedPage(context, appWidgetId)
            val pinnedPage = CockpitWidgetModel.wrapPage(rawPage, pageCount)
            val pinnedApps = CockpitWidgetModel.pageSlice(allPinned, pinnedPage)

            fun render(variant: WidgetSizeVariant) = renderMasterRemoteViews(
                context = context,
                appWidgetId = appWidgetId,
                alarm = alarm,
                dropLive = dropLive,
                dropAvailable = dropAvailable,
                torchOn = torchOn,
                torchAvailable = torchAvailable,
                islandOn = islandEnabled,
                battery = battery,
                ram = ram,
                pinnedApps = pinnedApps,
                pinnedPageIndex = pinnedPage,
                pinnedPageCount = pageCount,
                attachPendingIntents = true,
                variant = variant,
                networkSpeedLabel = formatWidgetNetworkSpeed(network),
                todayDataLabel = todayDataLabel,
            )

            return if (Build.VERSION.SDK_INT >= 31) {
                wrapResponsiveRemoteViews(
                    compact = render(WidgetSizeVariant.COMPACT),
                    normal = render(WidgetSizeVariant.NORMAL),
                    tall = render(WidgetSizeVariant.TALL),
                )
            } else {
                render(WidgetSizeVariant.NORMAL)
            }
        }

        fun buildPreviewRemoteViews(context: Context): RemoteViews = if (Build.VERSION.SDK_INT >= 31) {
            wrapResponsiveRemoteViews(
                compact = buildPreviewRemoteViewsForVariant(context, WidgetSizeVariant.COMPACT),
                normal = buildPreviewRemoteViewsForVariant(context, WidgetSizeVariant.NORMAL),
                tall = buildPreviewRemoteViewsForVariant(context, WidgetSizeVariant.TALL),
            )
        } else {
            buildPreviewRemoteViewsForVariant(context, WidgetSizeVariant.NORMAL)
        }

        internal fun buildPreviewRemoteViewsForVariant(
            context: Context,
            variant: WidgetSizeVariant,
        ): RemoteViews {
            val nowMs = System.currentTimeMillis()
            val previewAlarm = NextAlarmFormatter.formatNextAlarm(
                triggerAtMillis = nowMs + 24_045_000L,
                nowMillis = nowMs,
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                is24Hour = false,
            )
            val previewBattery = BatteryHudMetric(
                percent = 84,
                fraction = 0.84f,
                valueText = "84%",
                label = "CHRG",
                isCharging = true,
                isLow = false,
                current = CockpitWidgetModel.formatBatteryCurrent(microAmps = 2_450_000L, isCharging = true),
                charge = CockpitWidgetModel.formatBatteryCharge(microAmpHours = 4_210_000L),
            )
            val previewRam = CockpitHudBitmapRenderer.formatRamHudMetric(
                availBytes = (1.8 * 1024 * 1024 * 1024).toLong(),
                totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
            )
            val previewPinnedAll = listOf(
                PinnedAppInfo("com.google.android.apps.maps", "Maps"),
                PinnedAppInfo("com.whatsapp", "WhatsApp"),
                PinnedAppInfo("com.android.chrome", "Chrome"),
                PinnedAppInfo("com.google.android.deskclock", "Clock"),
                PinnedAppInfo("com.google.android.apps.photos", "Photos"),
            )
            val previewNet = IdleNetworkSnapshot(
                kind = IdleNetworkKind.WIFI,
                label = "Wi-Fi",
                uploadBytesPerSec = 12L * 1024L,
                downloadBytesPerSec = (1.4 * 1024 * 1024).toLong(),
            )
            return renderMasterRemoteViews(
                context = context,
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                alarm = previewAlarm,
                dropLive = true,
                dropAvailable = true,
                torchOn = false,
                torchAvailable = true,
                islandOn = true,
                battery = previewBattery,
                ram = previewRam,
                pinnedApps = CockpitWidgetModel.pageSlice(previewPinnedAll, 0),
                pinnedPageIndex = 0,
                pinnedPageCount = CockpitWidgetModel.pageCount(previewPinnedAll.size),
                attachPendingIntents = false,
                variant = variant,
                networkSpeedLabel = formatWidgetNetworkSpeed(previewNet),
                todayDataLabel = "Today 420 MB",
            )
        }

        fun buildTelemetryPartialRemoteViews(
            context: Context,
            battery: BatteryHudMetric,
            ram: RamHudMetric,
            network: IdleNetworkSnapshot? = null,
            todayDataLabel: String? = null,
        ): RemoteViews {
            val density = context.resources.displayMetrics.density.coerceIn(2f, 3f)
            val resolvedToday = if (network != null) {
                todayDataLabel ?: readCachedTodayMobileDataLabel(context)
            } else {
                todayDataLabel
            }
            fun partialFor(variant: WidgetSizeVariant): RemoteViews {
                val views = RemoteViews(context.packageName, variant.layoutResId)
                applyTelemetryToRemoteViews(views, context, battery, ram, density, variant)
                applyTallNetworkRowToRemoteViews(views, network, resolvedToday, variant)
                return views
            }
            return if (Build.VERSION.SDK_INT >= 31) {
                wrapResponsiveRemoteViews(
                    compact = partialFor(WidgetSizeVariant.COMPACT),
                    normal = partialFor(WidgetSizeVariant.NORMAL),
                    tall = partialFor(WidgetSizeVariant.TALL),
                )
            } else {
                partialFor(WidgetSizeVariant.NORMAL)
            }
        }

        /**
         * Ultra-lightweight partial RemoteViews patching only the live mA current and mAh charge
         * TextViews (and the Tall widget network row when provided), skipping arc gauge bitmap
         * rendering on 2-second battery-current-only ticks.
         */
        fun buildBatteryCurrentPartialRemoteViews(
            context: Context,
            battery: BatteryHudMetric,
            network: IdleNetworkSnapshot? = null,
            todayDataLabel: String? = null,
        ): RemoteViews {
            val resolvedToday = if (network != null) {
                todayDataLabel ?: readCachedTodayMobileDataLabel(context)
            } else {
                todayDataLabel
            }
            fun partialFor(variant: WidgetSizeVariant): RemoteViews {
                val views = RemoteViews(context.packageName, variant.layoutResId)
                applyBatteryCurrentToRemoteViews(views, context, battery, variant)
                applyTallNetworkRowToRemoteViews(views, network, resolvedToday, variant)
                return views
            }
            return if (Build.VERSION.SDK_INT >= 31) {
                wrapResponsiveRemoteViews(
                    compact = partialFor(WidgetSizeVariant.COMPACT),
                    normal = partialFor(WidgetSizeVariant.NORMAL),
                    tall = partialFor(WidgetSizeVariant.TALL),
                )
            } else {
                partialFor(WidgetSizeVariant.NORMAL)
            }
        }

        /**
         * Lightweight partial RemoteViews patching ONLY the Tall widget's live network speed and
         * today's mobile data TextViews, performing zero ops on Compact/Normal sizes and zero
         * bitmap redraws.
         */
        fun buildNetworkPartialRemoteViews(
            context: Context,
            network: IdleNetworkSnapshot,
            todayDataLabel: String = readCachedTodayMobileDataLabel(context),
        ): RemoteViews {
            fun partialFor(variant: WidgetSizeVariant): RemoteViews {
                val views = RemoteViews(context.packageName, variant.layoutResId)
                applyTallNetworkRowToRemoteViews(views, network, todayDataLabel, variant)
                return views
            }
            return if (Build.VERSION.SDK_INT >= 31) {
                wrapResponsiveRemoteViews(
                    compact = partialFor(WidgetSizeVariant.COMPACT),
                    normal = partialFor(WidgetSizeVariant.NORMAL),
                    tall = partialFor(WidgetSizeVariant.TALL),
                )
            } else {
                partialFor(WidgetSizeVariant.NORMAL)
            }
        }

        internal fun buildTallNetworkPartialRemoteViewsForTest(
            context: Context,
            network: IdleNetworkSnapshot,
            todayDataLabel: String = readCachedTodayMobileDataLabel(context),
        ): RemoteViews {
            val views = RemoteViews(context.packageName, WidgetSizeVariant.TALL.layoutResId)
            applyTallNetworkRowToRemoteViews(views, network, todayDataLabel, WidgetSizeVariant.TALL)
            return views
        }

        private fun applyTallNetworkRowToRemoteViews(
            views: RemoteViews,
            network: IdleNetworkSnapshot?,
            todayDataLabel: String?,
            variant: WidgetSizeVariant,
        ) {
            if (!variant.showTallNetworkRow) return
            if (network != null) {
                views.setTextViewText(R.id.cockpit_widget_net_speed, formatWidgetNetworkSpeed(network))
            }
            if (todayDataLabel != null) {
                views.setTextViewText(R.id.cockpit_widget_today_data, todayDataLabel)
            }
        }

        private fun applyBatteryCurrentToRemoteViews(
            views: RemoteViews,
            context: Context,
            battery: BatteryHudMetric,
            variant: WidgetSizeVariant = WidgetSizeVariant.NORMAL,
        ) {
            views.setTextViewText(R.id.cockpit_widget_battery_current, battery.current.text)
            val currentColor = when (battery.current.tone) {
                BatteryCurrentTone.CHARGING -> GlassPalette.SuccessGreen.toArgb()
                BatteryCurrentTone.DISCHARGING -> GlassPalette.SecondaryViolet.toArgb()
                BatteryCurrentTone.UNKNOWN -> ContextCompat.getColor(context, R.color.cockpit_on_surface_variant)
            }
            views.setTextColor(R.id.cockpit_widget_battery_current, currentColor)
            views.setContentDescription(R.id.cockpit_widget_battery_current, "Battery current ${battery.current.text}")

            if (variant.showExtendedTelemetry) {
                views.setViewVisibility(R.id.cockpit_widget_battery_charge, View.VISIBLE)
                views.setTextViewText(R.id.cockpit_widget_battery_charge, battery.charge.text)
                views.setContentDescription(R.id.cockpit_widget_battery_charge, "Battery charge ${battery.charge.text}")
            } else {
                views.setViewVisibility(R.id.cockpit_widget_battery_charge, View.GONE)
            }
        }

        private fun applyTelemetryToRemoteViews(
            views: RemoteViews,
            context: Context,
            battery: BatteryHudMetric,
            ram: RamHudMetric,
            density: Float,
            variant: WidgetSizeVariant = WidgetSizeVariant.NORMAL,
        ) {
            val gaugePx = (variant.gaugeSizeDp * density).toInt()
            val batteryBmp = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
                sizePx = gaugePx,
                fraction = battery.fraction,
                valueText = battery.valueText,
                subLabel = battery.label,
                accentColor = CockpitHudBitmapRenderer.batteryArcColor(battery.percent, battery.isCharging),
                density = density,
                isCharging = battery.isCharging,
            )
            val ramBmp = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
                sizePx = gaugePx,
                fraction = ram.fraction,
                valueText = ram.usedPercentText,
                subLabel = ram.label,
                accentColor = GlassPalette.HudElectricCyan,
                density = density,
            )

            views.setImageViewBitmap(R.id.cockpit_widget_battery_gauge, batteryBmp)
            views.setContentDescription(R.id.cockpit_widget_battery_gauge, "Battery ${battery.valueText}")
            applyBatteryCurrentToRemoteViews(views, context, battery, variant)

            views.setImageViewBitmap(R.id.cockpit_widget_ram_gauge, ramBmp)
            views.setContentDescription(R.id.cockpit_widget_ram_gauge, "RAM ${ram.valueText}")
            views.setTextViewText(R.id.cockpit_widget_ram_used, ram.valueText)
            if (variant.showExtendedTelemetry) {
                views.setViewVisibility(R.id.cockpit_widget_ram_free, View.VISIBLE)
                views.setTextViewText(R.id.cockpit_widget_ram_free, ram.freeText)
            } else {
                views.setViewVisibility(R.id.cockpit_widget_ram_free, View.GONE)
            }
        }

        private fun renderMasterRemoteViews(
            context: Context,
            appWidgetId: Int,
            alarm: NextAlarmUiModel,
            dropLive: Boolean,
            dropAvailable: Boolean,
            torchOn: Boolean,
            torchAvailable: Boolean,
            islandOn: Boolean,
            battery: BatteryHudMetric,
            ram: RamHudMetric,
            pinnedApps: List<PinnedAppInfo>,
            pinnedPageIndex: Int,
            pinnedPageCount: Int,
            attachPendingIntents: Boolean,
            variant: WidgetSizeVariant = WidgetSizeVariant.NORMAL,
            networkSpeedLabel: String = "Wi-Fi \u00B7 Idle",
            todayDataLabel: String = "Today --",
        ): RemoteViews {
            val views = RemoteViews(context.packageName, variant.layoutResId)
            val density = context.resources.displayMetrics.density.coerceIn(2f, 3f)

            val shellHeightDp = when (variant) {
                WidgetSizeVariant.COMPACT -> 144f
                WidgetSizeVariant.NORMAL -> 228f
                WidgetSizeVariant.TALL -> 320f
            }
            val shellBmp = CockpitHudBitmapRenderer.renderShellBitmap(
                widthPx = (340f * density).toInt(),
                heightPx = (shellHeightDp * density).toInt(),
                cornerRadiusDp = 26f,
                density = density,
            )
            views.setImageViewBitmap(R.id.cockpit_widget_shell_bg, shellBmp)

            // Alarm pill (Zone 1)
            views.setTextViewText(
                R.id.cockpit_alarm_time_text,
                if (alarm.hasAlarm) "Alarm ${alarm.formattedTime}" else "No alarm set",
            )
            if (variant.showExtendedTelemetry) {
                when {
                    alarm.hasAlarm &&
                        alarm.countdownMode == AlarmCountdownMode.CHRONOMETER_UNDER_24H &&
                        alarm.triggerAtMillis != null -> {
                        val remainingMs = (alarm.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
                        val baseElapsed = SystemClock.elapsedRealtime() + remainingMs
                        views.setViewVisibility(R.id.cockpit_alarm_chronometer, View.VISIBLE)
                        views.setViewVisibility(R.id.cockpit_alarm_static_countdown, View.GONE)
                        views.setChronometerCountDown(R.id.cockpit_alarm_chronometer, true)
                        views.setChronometer(R.id.cockpit_alarm_chronometer, baseElapsed, "Rings in %s", true)
                    }
                    alarm.hasAlarm -> {
                        views.setViewVisibility(R.id.cockpit_alarm_chronometer, View.GONE)
                        views.setViewVisibility(R.id.cockpit_alarm_static_countdown, View.VISIBLE)
                        views.setTextViewText(R.id.cockpit_alarm_static_countdown, alarm.countdownText)
                    }
                    else -> {
                        views.setViewVisibility(R.id.cockpit_alarm_chronometer, View.GONE)
                        views.setViewVisibility(R.id.cockpit_alarm_static_countdown, View.GONE)
                    }
                }
            } else {
                views.setViewVisibility(R.id.cockpit_alarm_chronometer, View.GONE)
                views.setViewVisibility(R.id.cockpit_alarm_static_countdown, View.GONE)
            }

            // Status pill aligned on the clock row
            val statusPill = CockpitWidgetModel.statusPill(dropLive = dropLive, torchOn = torchOn)
            val (statusBg, statusColor) = when (statusPill.tone) {
                WidgetStatusTone.READY -> R.drawable.cockpit_widget_status_ready_bg to GlassPalette.SuccessGreen.toArgb()
                WidgetStatusTone.DROP_LIVE -> R.drawable.cockpit_widget_status_live_bg to GlassPalette.PrimaryCyan.toArgb()
                WidgetStatusTone.TORCH_ON -> R.drawable.cockpit_widget_status_torch_bg to GlassPalette.SecondaryViolet.toArgb()
            }
            views.setInt(R.id.cockpit_widget_status_pill, "setBackgroundResource", statusBg)
            views.setInt(R.id.cockpit_widget_status_dot, "setColorFilter", statusColor)
            views.setTextViewText(R.id.cockpit_widget_status_text, statusPill.text)
            views.setTextColor(R.id.cockpit_widget_status_text, statusColor)

            // Dual telemetry gauges + readouts (Zone 2)
            applyTelemetryToRemoteViews(views, context, battery, ram, density, variant)

            // Tall-only extra row: live network speed + today's mobile data
            if (variant.showTallNetworkRow) {
                views.setTextViewText(R.id.cockpit_widget_net_speed, networkSpeedLabel)
                views.setTextViewText(R.id.cockpit_widget_today_data, todayDataLabel)
            }

            // Sculptural control deck (Zone 3)
            val primaryColor = ContextCompat.getColor(context, R.color.cockpit_on_surface)
            val disabledColor = ContextCompat.getColor(context, R.color.cockpit_on_surface_variant)
            val tileW = (104f * density).toInt()
            val tileH = (variant.tileHeightDp * density).toInt()

            val dropTile = CockpitWidgetModel.dropTile(dropLive = dropLive, available = dropAvailable)
            val dropBmp = CockpitHudBitmapRenderer.renderControlTileBitmap(
                widthPx = tileW,
                heightPx = tileH,
                active = dropTile.active,
                accentColor = GlassPalette.DestructiveRed,
                density = density,
                enabled = dropTile.enabled,
            )
            val dropFg = when {
                !dropTile.enabled -> disabledColor
                dropTile.active -> GlassPalette.DestructiveRed.toArgb()
                else -> primaryColor
            }
            views.setImageViewBitmap(R.id.cockpit_widget_drop_bg, dropBmp)
            views.setImageViewResource(
                R.id.cockpit_widget_drop_icon,
                if (dropLive) R.drawable.ic_cockpit_stop else R.drawable.ic_cockpit_devices,
            )
            views.setInt(R.id.cockpit_widget_drop_icon, "setColorFilter", dropFg)
            views.setTextViewText(R.id.cockpit_widget_drop_label, dropTile.label)
            views.setTextColor(R.id.cockpit_widget_drop_label, dropFg)

            val torchBmp = CockpitHudBitmapRenderer.renderControlTileBitmap(
                widthPx = tileW,
                heightPx = tileH,
                active = torchOn,
                accentColor = GlassPalette.PrimaryCyan,
                density = density,
                enabled = torchAvailable,
            )
            val torchFg = when {
                !torchAvailable -> disabledColor
                torchOn -> GlassPalette.PrimaryCyan.toArgb()
                else -> primaryColor
            }
            val torchLabel = when {
                !torchAvailable -> "Cam Busy"
                torchOn -> "Torch On"
                else -> "Torch"
            }
            views.setImageViewBitmap(R.id.cockpit_widget_torch_bg, torchBmp)
            views.setImageViewResource(
                R.id.cockpit_widget_torch_icon,
                if (torchOn) R.drawable.ic_cockpit_torch_on else R.drawable.ic_cockpit_torch_off,
            )
            views.setInt(R.id.cockpit_widget_torch_icon, "setColorFilter", torchFg)
            views.setTextViewText(R.id.cockpit_widget_torch_label, torchLabel)
            views.setTextColor(R.id.cockpit_widget_torch_label, torchFg)

            val islandBmp = CockpitHudBitmapRenderer.renderControlTileBitmap(
                widthPx = tileW,
                heightPx = tileH,
                active = islandOn,
                accentColor = GlassPalette.SecondaryViolet,
                density = density,
                enabled = true,
            )
            val islandFg = if (islandOn) GlassPalette.SecondaryViolet.toArgb() else primaryColor
            views.setImageViewBitmap(R.id.cockpit_widget_island_bg, islandBmp)
            views.setInt(R.id.cockpit_widget_island_icon, "setColorFilter", islandFg)
            views.setTextViewText(R.id.cockpit_widget_island_label, if (islandOn) "Island On" else "Island")
            views.setTextColor(R.id.cockpit_widget_island_label, islandFg)

            // Recessed App Dock (Zone 4)
            if (pinnedApps.isEmpty()) {
                views.setViewVisibility(R.id.cockpit_widget_dock_container, View.GONE)
            } else {
                views.setViewVisibility(R.id.cockpit_widget_dock_container, View.VISIBLE)
                val trayBmp = CockpitHudBitmapRenderer.renderDockTrayBitmap(
                    widthPx = (312f * density).toInt(),
                    heightPx = (56f * density).toInt(),
                    density = density,
                )
                views.setImageViewBitmap(R.id.cockpit_widget_dock_bg, trayBmp)

                val paged = pinnedPageCount > 1
                views.setViewVisibility(R.id.cockpit_widget_btn_prev, if (paged) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.cockpit_widget_btn_next, if (paged) View.VISIBLE else View.GONE)
                views.setViewVisibility(
                    R.id.cockpit_widget_pager_dots_row,
                    if (paged && variant.showAppLabels) View.VISIBLE else View.GONE,
                )

                if (paged && variant.showAppLabels) {
                    val activeDotColor = GlassPalette.PrimaryCyan.toArgb()
                    for (dotIdx in PAGER_DOT_IDS.indices) {
                        if (dotIdx < pinnedPageCount) {
                            views.setViewVisibility(PAGER_DOT_IDS[dotIdx], View.VISIBLE)
                            views.setInt(
                                PAGER_DOT_IDS[dotIdx],
                                "setColorFilter",
                                if (dotIdx == pinnedPageIndex) activeDotColor else disabledColor,
                            )
                        } else {
                            views.setViewVisibility(PAGER_DOT_IDS[dotIdx], View.GONE)
                        }
                    }
                }

                val haloPx = ((if (variant.showAppLabels) 28f else 24f) * density).toInt()
                for (slotIndex in APP_CONTAINER_IDS.indices) {
                    val app = pinnedApps.getOrNull(slotIndex)
                    if (app == null) {
                        views.setViewVisibility(APP_CONTAINER_IDS[slotIndex], View.GONE)
                    } else {
                        views.setViewVisibility(APP_CONTAINER_IDS[slotIndex], View.VISIBLE)
                        val haloBmp = CockpitHudBitmapRenderer.renderAppHaloBitmap(
                            sizePx = haloPx,
                            appIcon = app.iconBitmap,
                            accentColor = GlassPalette.HudElectricCyan,
                            density = density,
                        )
                        views.setImageViewBitmap(APP_ICON_IDS[slotIndex], haloBmp)
                        views.setTextViewText(APP_LABEL_IDS[slotIndex], app.label)
                        views.setViewVisibility(
                            APP_LABEL_IDS[slotIndex],
                            if (variant.showAppLabels) View.VISIBLE else View.GONE,
                        )
                        views.setContentDescription(APP_CONTAINER_IDS[slotIndex], "Open ${app.label}")
                        if (attachPendingIntents) {
                            views.setOnClickPendingIntent(
                                APP_CONTAINER_IDS[slotIndex],
                                CockpitWidgetActions.createBroadcastPendingIntent(
                                    context = context,
                                    appWidgetId = appWidgetId,
                                    slot = CockpitWidgetActions.SLOT_PINNED_APP_BASE + slotIndex,
                                    action = CockpitWidgetActions.ACTION_LAUNCH_APP,
                                    packageName = app.packageName,
                                ),
                            )
                        }
                    }
                }
            }

            if (attachPendingIntents) {
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_clock_container,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_CLOCK,
                        action = CockpitWidgetActions.ACTION_OPEN_CLOCK,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_alarm_container,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_ALARM,
                        action = CockpitWidgetActions.ACTION_OPEN_ALARM,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_status_pill,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_STATUS,
                        action = CockpitWidgetActions.ACTION_OPEN_DRIVELIFT,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_btn_drop,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_DROP,
                        action = CockpitWidgetActions.ACTION_TOGGLE_DROP,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_btn_torch,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_TORCH,
                        action = CockpitWidgetActions.ACTION_TOGGLE_TORCH,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_btn_island,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_ISLAND,
                        action = CockpitWidgetActions.ACTION_TOGGLE_ISLAND,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_btn_prev,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_PAGER_PREV,
                        action = CockpitWidgetActions.ACTION_PINNED_PREV,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.cockpit_widget_btn_next,
                    CockpitWidgetActions.createBroadcastPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        slot = CockpitWidgetActions.SLOT_PAGER_NEXT,
                        action = CockpitWidgetActions.ACTION_PINNED_NEXT,
                    ),
                )
            }

            return views
        }

        fun readBatteryMetric(context: Context, batteryIntent: Intent? = null): BatteryHudMetric {
            val batteryManager = runCatching {
                context.getSystemService(BatteryManager::class.java)
            }.getOrNull()

            val pct = runCatching {
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            }.getOrDefault(-1)

            val resolvedIntent = batteryIntent ?: runCatching {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.getOrNull()
            val status = resolvedIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val bmCharging = runCatching {
                batteryManager?.isCharging == true
            }.getOrDefault(false)
            val isCharging = bmCharging ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val isLow = !isCharging && pct in 0..20

            val currentNowMicroAmps = runCatching {
                batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    ?: Long.MIN_VALUE
            }.getOrDefault(Long.MIN_VALUE)
            val currentAvgMicroAmps = runCatching {
                batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                    ?: Long.MIN_VALUE
            }.getOrDefault(Long.MIN_VALUE)
            val current = batteryCurrentAverager.record(
                currentNowMicroAmps = currentNowMicroAmps,
                currentAverageMicroAmps = currentAvgMicroAmps,
                isCharging = isCharging,
            )

            val chargeCounterMicroAmpHours = runCatching {
                val intProp = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    ?: Int.MIN_VALUE
                if (intProp != Int.MIN_VALUE && intProp > 0) {
                    intProp.toLong()
                } else {
                    batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                        ?: Long.MIN_VALUE
                }
            }.getOrDefault(Long.MIN_VALUE)
            val charge = CockpitWidgetModel.formatBatteryCharge(chargeCounterMicroAmpHours)

            return if (pct in 0..100) {
                BatteryHudMetric(
                    percent = pct,
                    fraction = (pct / 100f).coerceIn(0.02f, 1f),
                    valueText = "$pct%",
                    label = CockpitHudBitmapRenderer.batterySubLabel(pct, isCharging),
                    isCharging = isCharging,
                    isLow = isLow,
                    current = current,
                    charge = charge,
                )
            } else {
                BatteryHudMetric(
                    percent = 0,
                    fraction = 0f,
                    valueText = "--%",
                    label = if (isCharging) "CHRG" else "BATT",
                    isCharging = isCharging,
                    isLow = false,
                    current = current,
                    charge = charge,
                )
            }
        }

        fun readRamMetric(context: Context): RamHudMetric = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            if (am != null) {
                am.getMemoryInfo(info)
            }
            CockpitHudBitmapRenderer.formatRamHudMetric(info.availMem, info.totalMem)
        }.getOrDefault(RamHudMetric(freeGb = 0.0, usedGb = 0.0, totalGb = 0.0, fraction = 0f, valueText = "--"))

        fun isDropToggleAvailable(
            context: Context,
            telemetry: DriveLiftTelemetrySummary,
            mode: ConnectivityMode,
        ): Boolean {
            if (telemetry.isDesktopDropRunning) return true
            if (mode == ConnectivityMode.DIRECT_HOTSPOT) return true
            return runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val active = cm?.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(true)
        }

        fun launchGoogleClockAlarms(context: Context) {
            val googleClockShow = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .setPackage("com.google.android.deskclock")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val genericShow = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val googleClockLaunch = context.packageManager
                .getLaunchIntentForPackage("com.google.android.deskclock")
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallbackSet = Intent(AlarmClock.ACTION_SET_ALARM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (runCatching { context.startActivity(googleClockShow); true }.getOrDefault(false)) return
            if (googleClockLaunch != null && runCatching { context.startActivity(googleClockLaunch); true }.getOrDefault(false)) return
            if (runCatching { context.startActivity(genericShow); true }.getOrDefault(false)) return
            val showIntent = runCatching {
                context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.showIntent
            }.getOrNull()
            if (showIntent != null && runCatching { showIntent.send(); true }.getOrDefault(false)) return
            runCatching { context.startActivity(fallbackSet) }
        }

        fun showBriefWidgetToast(context: Context, message: String) {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun readFreeStorageLabel(): String = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val gb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
            "%.0f GB free".format(gb)
        }.getOrDefault("Storage --")

        fun readBatteryLabel(context: Context): String {
            val m = readBatteryMetric(context)
            return if (m.valueText == "--%") "Batt --" else "${m.valueText} batt"
        }
    }
}
