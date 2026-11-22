package com.drivelift.app.feature.cockpit.widget

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.setWidgetPreviews
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentWidth
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import com.drivelift.app.Graph
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.CockpitHaptics
import com.drivelift.app.feature.cockpit.hardware.NextAlarmFormatter
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.feature.cockpit.ui.CockpitTokens
import com.drivelift.app.feature.desktopdrop.model.ConnectivityMode
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.MainActivity
import com.drivelift.app.ui.Tokens

data class BatteryHudMetric(
    val percent: Int,
    val fraction: Float,
    val valueText: String,
    val label: String = "BATT",
    val isCharging: Boolean = false,
    val isLow: Boolean = false,
)

data class RamHudMetric(
    val freeGb: Double,
    val usedGb: Double = 0.0,
    val totalGb: Double = 0.0,
    val fraction: Float,
    val valueText: String,
    val label: String = "RAM",
)

private data class HudRenderBitmaps(
    val shell: Bitmap,
    val statusPill: Bitmap,
    val batteryGauge: Bitmap,
    val ramGauge: Bitmap,
    val dropTile: Bitmap,
    val torchTile: Bitmap,
    val islandTile: Bitmap,
    val dockTray: Bitmap,
    val appHalos: Map<String, Bitmap>,
)

/**
 * Futuristic Cyber-Glass 4-Zone Spatial Bento HUD Master Widget:
 * - Zone 1 (Top-Left): Bold tabular `<TextClock>` + uppercase AM/PM badge + date over illuminated `<Chronometer>` alarm pill (tapping opens Google Clock)
 * - Zone 2 (Top-Right): High-contrast wrapContentWidth status pill (`READY` / `DROP LIVE`) over dual 270-degree Battery (charging lightning halo / `<= 20%` red LOW) & Used/Total RAM gauges
 * - Zone 3 (Middle Control Deck): Isolated 3-column stateful ON/OFF/disabled tiles (`Drop`, `Torch`, `Island`) with 2dp stroke icons, >= 48dp touch height, and haptic feedback
 * - Zone 4 (Bottom Recessed App Dock): Frosted obsidian tray holding pinned apps inside glass halo badges
 */
class CockpitGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT_4X2, SIZE_EXPANDED_4X3),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = Graph.get(context)
        val cockpit = graph.cockpit
        val prefs = cockpit.prefsRepo.state.value
        val islandEnabled = cockpit.prefsRepo.readCurrentMasterEnabled()
        val telemetry = cockpit.telemetryAdapter.summary.value
        val alarm = NextAlarmFormatter.readFromSystem(context)
        val torchState = cockpit.torchController.state.value
        val torchOn = torchState.isOn && torchState.available
        val torchAvailable = torchState.available
        val dropAvailable = isDropToggleAvailable(context, telemetry, graph.desktopDrop.controller.snapshot.value.mode)
        val battery = readBatteryMetric(context)
        val ram = readRamMetric(context)
        val pinnedApps = cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages).take(4)

        val density = context.resources.displayMetrics.density.coerceAtLeast(2f)
        val bitmaps = renderHudBitmaps(
            density = density,
            isDropActive = telemetry.isDesktopDropRunning,
            isDropAvailable = dropAvailable,
            isTorchOn = torchOn,
            isTorchAvailable = torchAvailable,
            isIslandOn = islandEnabled,
            battery = battery,
            ram = ram,
            pinnedApps = pinnedApps,
        )

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                val maxPinned = if (size.height < SIZE_EXPANDED_4X3.height) 3 else 4
                MasterWidgetContent(
                    context = context,
                    maxPinned = maxPinned,
                    alarm = alarm,
                    telemetry = telemetry,
                    battery = battery,
                    dropAvailable = dropAvailable,
                    torchOn = torchOn,
                    torchAvailable = torchAvailable,
                    islandOn = islandEnabled,
                    pinnedApps = pinnedApps,
                    bitmaps = bitmaps,
                )
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val nowMs = System.currentTimeMillis()
        val previewAlarm = NextAlarmFormatter.formatNextAlarm(
            triggerAtMillis = nowMs + 24_045_000L,
            nowMillis = nowMs,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            is24Hour = false,
        )
        val previewTelemetry = DriveLiftTelemetrySummary(
            isDesktopDropRunning = true,
            isDropAvailable = true,
            connectedPeerName = "Workstation",
            desktopDropSpeedBytesPerSec = 58_000_000L,
            statusHeadline = "Drop 55.3 MB/s",
        )
        val previewBattery = BatteryHudMetric(
            percent = 84,
            fraction = 0.84f,
            valueText = "84%",
            label = "CHRG",
            isCharging = true,
            isLow = false,
        )
        val previewRam = CockpitHudBitmapRenderer.formatRamHudMetric(
            availBytes = (1.5 * 1024 * 1024 * 1024).toLong(),
            totalBytes = (12.0 * 1024 * 1024 * 1024).toLong(),
        )
        val previewPinned = listOf(
            PinnedAppInfo("com.google.android.apps.maps", "Maps"),
            PinnedAppInfo("com.whatsapp", "WhatsApp"),
            PinnedAppInfo("com.android.chrome", "Chrome"),
        )
        val density = context.resources.displayMetrics.density.coerceAtLeast(2f)
        val bitmaps = renderHudBitmaps(
            density = density,
            isDropActive = true,
            isDropAvailable = true,
            isTorchOn = false,
            isTorchAvailable = true,
            isIslandOn = true,
            battery = previewBattery,
            ram = previewRam,
            pinnedApps = previewPinned,
        )
        provideContent {
            GlanceTheme {
                MasterWidgetContent(
                    context = context,
                    maxPinned = 3,
                    alarm = previewAlarm,
                    telemetry = previewTelemetry,
                    battery = previewBattery,
                    dropAvailable = true,
                    torchOn = false,
                    torchAvailable = true,
                    islandOn = true,
                    pinnedApps = previewPinned,
                    bitmaps = bitmaps,
                )
            }
        }
    }

    private fun renderHudBitmaps(
        density: Float,
        isDropActive: Boolean,
        isDropAvailable: Boolean,
        isTorchOn: Boolean,
        isTorchAvailable: Boolean,
        isIslandOn: Boolean,
        battery: BatteryHudMetric,
        ram: RamHudMetric,
        pinnedApps: List<PinnedAppInfo>,
    ): HudRenderBitmaps {
        val shellW = (340f * density).toInt()
        val shellH = (220f * density).toInt()
        val gaugePx = (58f * density).toInt()
        val tileW = (104f * density).toInt()
        val tileH = (50f * density).toInt()
        val trayW = (312f * density).toInt()
        val trayH = (44f * density).toInt()
        val haloPx = (28f * density).toInt()
        val pillW = (132f * density).toInt()
        val pillH = (28f * density).toInt()

        return HudRenderBitmaps(
            shell = CockpitHudBitmapRenderer.renderShellBitmap(
                widthPx = shellW,
                heightPx = shellH,
                cornerRadiusDp = 26f,
                density = density,
            ),
            statusPill = CockpitHudBitmapRenderer.renderStatusPillBitmap(
                widthPx = pillW,
                heightPx = pillH,
                active = isDropActive,
                accentColor = if (isDropActive) GlassPalette.HudElectricCyan else GlassPalette.HudEmerald,
                density = density,
            ),
            batteryGauge = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
                sizePx = gaugePx,
                fraction = battery.fraction,
                valueText = battery.valueText,
                subLabel = battery.label,
                accentColor = CockpitHudBitmapRenderer.batteryArcColor(battery.percent, battery.isCharging),
                density = density,
                isCharging = battery.isCharging,
            ),
            ramGauge = CockpitHudBitmapRenderer.renderArcGaugeBitmap(
                sizePx = gaugePx,
                fraction = ram.fraction,
                valueText = ram.valueText,
                subLabel = ram.label,
                accentColor = GlassPalette.HudElectricCyan,
                density = density,
            ),
            dropTile = CockpitHudBitmapRenderer.renderControlTileBitmap(
                widthPx = tileW,
                heightPx = tileH,
                active = isDropActive,
                accentColor = GlassPalette.HudElectricCyan,
                density = density,
                enabled = isDropAvailable,
            ),
            torchTile = CockpitHudBitmapRenderer.renderControlTileBitmap(
                widthPx = tileW,
                heightPx = tileH,
                active = isTorchOn,
                accentColor = GlassPalette.HudAmber,
                density = density,
                enabled = isTorchAvailable,
            ),
            islandTile = CockpitHudBitmapRenderer.renderControlTileBitmap(
                widthPx = tileW,
                heightPx = tileH,
                active = isIslandOn,
                accentColor = GlassPalette.HudVioletBlue,
                density = density,
                enabled = true,
            ),
            dockTray = CockpitHudBitmapRenderer.renderDockTrayBitmap(
                widthPx = trayW,
                heightPx = trayH,
                density = density,
            ),
            appHalos = pinnedApps.associate { app ->
                app.packageName to CockpitHudBitmapRenderer.renderAppHaloBitmap(
                    sizePx = haloPx,
                    appIcon = app.iconBitmap,
                    accentColor = GlassPalette.HudElectricCyan,
                    density = density,
                )
            },
        )
    }

    private fun resColorProvider(context: Context, resId: Int): GlanceColorProvider {
        val c = Color(ContextCompat.getColor(context, resId))
        return ColorProvider(day = c, night = c)
    }

    private fun directColorProvider(color: Color): GlanceColorProvider =
        ColorProvider(day = color, night = color)

    private fun buildAlarmRemoteViews(context: Context, alarm: NextAlarmUiModel): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.cockpit_widget_alarm_chronometer)
        views.setTextViewText(
            R.id.cockpit_alarm_time_text,
            if (alarm.hasAlarm) "Alarm ${alarm.formattedTime}" else "No alarm set",
        )
        when {
            alarm.hasAlarm &&
                alarm.countdownMode == AlarmCountdownMode.CHRONOMETER_UNDER_24H &&
                alarm.triggerAtMillis != null -> {
                val remainingMs = (alarm.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
                val baseElapsed = SystemClock.elapsedRealtime() + remainingMs
                views.setViewVisibility(R.id.cockpit_alarm_chronometer, View.VISIBLE)
                views.setViewVisibility(R.id.cockpit_alarm_static_countdown, View.GONE)
                views.setChronometerCountDown(R.id.cockpit_alarm_chronometer, true)
                views.setChronometer(
                    R.id.cockpit_alarm_chronometer,
                    baseElapsed,
                    "Rings in %s",
                    true,
                )
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
        return views
    }

    @Composable
    private fun MasterWidgetContent(
        context: Context,
        maxPinned: Int,
        alarm: NextAlarmUiModel,
        telemetry: DriveLiftTelemetrySummary,
        battery: BatteryHudMetric,
        dropAvailable: Boolean,
        torchOn: Boolean,
        torchAvailable: Boolean,
        islandOn: Boolean,
        pinnedApps: List<PinnedAppInfo>,
        bitmaps: HudRenderBitmaps,
    ) {
        val primaryText = resColorProvider(context, R.color.cockpit_on_surface)
        val disabledText = resColorProvider(context, R.color.cockpit_on_surface_variant)
        val dropAccent = directColorProvider(GlassPalette.HudElectricCyan)
        val torchAccent = directColorProvider(GlassPalette.HudAmber)
        val islandAccent = directColorProvider(GlassPalette.HudVioletBlue)
        val readyAccent = directColorProvider(GlassPalette.HudEmerald)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(bitmaps.shell))
                .padding(Tokens.Medium),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // Top Bento Split: Zone 1 (Clock + Alarm Pill) | Zone 2 (Status Pill + Dual Neon Arc Gauges)
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.Top,
            ) {
                // Zone 1: Top-Left
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.Horizontal.Start,
                ) {
                    Box(modifier = GlanceModifier.clickable(actionRunCallback<OpenClockAction>())) {
                        AndroidRemoteViews(
                            remoteViews = RemoteViews(context.packageName, R.layout.cockpit_widget_clock_header),
                        )
                    }
                    Spacer(GlanceModifier.height(Tokens.Snug))
                    Box(modifier = GlanceModifier.clickable(actionRunCallback<OpenNextAlarmAction>())) {
                        AndroidRemoteViews(remoteViews = buildAlarmRemoteViews(context, alarm))
                    }
                }

                Spacer(GlanceModifier.width(Tokens.Small))

                // Zone 2: Top-Right
                Column(
                    horizontalAlignment = Alignment.Horizontal.End,
                ) {
                    Row(
                        modifier = GlanceModifier
                            .wrapContentWidth()
                            .background(ImageProvider(bitmaps.statusPill))
                            .clickable(actionRunCallback<OpenDriveLiftAction>())
                            .padding(horizontal = Tokens.Small, vertical = Tokens.Tiny),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Text(
                            text = if (telemetry.isDesktopDropRunning) {
                                "DROP LIVE \u00B7 ${telemetry.statusHeadline}"
                            } else {
                                "READY"
                            },
                            style = TextStyle(
                                color = if (telemetry.isDesktopDropRunning) dropAccent else readyAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    }

                    Spacer(GlanceModifier.height(Tokens.Snug))

                    Row(
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(bitmaps.batteryGauge),
                            contentDescription = "Battery ${battery.valueText}",
                            modifier = GlanceModifier.size(CockpitTokens.HudGaugeSize),
                        )
                        Spacer(GlanceModifier.width(CockpitTokens.HudPillSpacing))
                        Image(
                            provider = ImageProvider(bitmaps.ramGauge),
                            contentDescription = "RAM usage",
                            modifier = GlanceModifier.size(CockpitTokens.HudGaugeSize),
                        )
                    }
                }
            }

            Spacer(GlanceModifier.height(Tokens.Small))

            // Zone 3: Middle Control Deck (Strictly isolated 3-column stateful sculptural plates)
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                SculpturalControlTile(
                    iconRes = R.drawable.ic_cockpit_devices,
                    label = when {
                        telemetry.isDesktopDropRunning -> "Drop On"
                        !dropAvailable -> "No Wi-Fi"
                        else -> "Drop"
                    },
                    active = telemetry.isDesktopDropRunning,
                    enabled = dropAvailable,
                    activeColor = dropAccent,
                    inactiveColor = primaryText,
                    disabledColor = disabledText,
                    plateBitmap = bitmaps.dropTile,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .height(CockpitTokens.HudControlHeight)
                        .clickable(actionRunCallback<ToggleDesktopDropAction>()),
                )
                Spacer(GlanceModifier.width(CockpitTokens.HudPillSpacing))
                SculpturalControlTile(
                    iconRes = if (torchOn) R.drawable.ic_cockpit_torch_on else R.drawable.ic_cockpit_torch_off,
                    label = when {
                        !torchAvailable -> "Cam Busy"
                        torchOn -> "Torch On"
                        else -> "Torch"
                    },
                    active = torchOn,
                    enabled = torchAvailable,
                    activeColor = torchAccent,
                    inactiveColor = primaryText,
                    disabledColor = disabledText,
                    plateBitmap = bitmaps.torchTile,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .height(CockpitTokens.HudControlHeight)
                        .clickable(actionRunCallback<ToggleTorchAction>()),
                )
                Spacer(GlanceModifier.width(CockpitTokens.HudPillSpacing))
                SculpturalControlTile(
                    iconRes = R.drawable.ic_cockpit_tile,
                    label = if (islandOn) "Island On" else "Island",
                    active = islandOn,
                    enabled = true,
                    activeColor = islandAccent,
                    inactiveColor = primaryText,
                    disabledColor = disabledText,
                    plateBitmap = bitmaps.islandTile,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .height(CockpitTokens.HudControlHeight)
                        .clickable(actionRunCallback<ToggleIslandAction>()),
                )
            }

            // Zone 4: Bottom Recessed App Dock
            if (pinnedApps.isNotEmpty()) {
                Spacer(GlanceModifier.height(Tokens.Snug))
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(ImageProvider(bitmaps.dockTray))
                        .padding(horizontal = Tokens.Small, vertical = Tokens.Tiny),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    pinnedApps.take(maxPinned).forEachIndexed { index, app ->
                        if (index > 0) Spacer(GlanceModifier.width(CockpitTokens.HudPillSpacing))
                        val halo = bitmaps.appHalos[app.packageName]
                        Row(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .clickable(
                                    actionRunCallback<LaunchPinnedAppAction>(
                                        actionParametersOf(PARAM_PACKAGE to app.packageName),
                                    ),
                                )
                                .padding(vertical = Tokens.Tiny),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (halo != null) {
                                Image(
                                    provider = ImageProvider(halo),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(CockpitTokens.HudDockIconHaloSize),
                                )
                            } else {
                                Image(
                                    provider = ImageProvider(R.drawable.ic_cockpit_app_default),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(CockpitTokens.WidgetAppIconSize),
                                    colorFilter = ColorFilter.tint(dropAccent),
                                )
                            }
                            Spacer(GlanceModifier.width(Tokens.Tiny))
                            Text(
                                text = app.label,
                                style = TextStyle(
                                    color = primaryText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SculpturalControlTile(
        iconRes: Int,
        label: String,
        active: Boolean,
        enabled: Boolean,
        activeColor: GlanceColorProvider,
        inactiveColor: GlanceColorProvider,
        disabledColor: GlanceColorProvider,
        plateBitmap: Bitmap,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        val fg = when {
            !enabled -> disabledColor
            active -> activeColor
            else -> inactiveColor
        }
        Column(
            modifier = modifier
                .background(ImageProvider(plateBitmap))
                .padding(horizontal = Tokens.Tiny, vertical = Tokens.Tiny),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = null,
                modifier = GlanceModifier.size(CockpitTokens.WidgetBadgeIconSize),
                colorFilter = ColorFilter.tint(fg),
            )
            Spacer(GlanceModifier.height(Tokens.Stroke))
            Text(
                text = label,
                style = TextStyle(
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }

    companion object {
        private val SIZE_COMPACT_4X2 = DpSize(250.dp, 110.dp)
        private val SIZE_EXPANDED_4X3 = DpSize(250.dp, 180.dp)
        val PARAM_PACKAGE = ActionParameters.Key<String>("pinned_package")

        suspend fun registerGeneratedPreviewsIfSupported(context: Context) {
            if (Build.VERSION.SDK_INT >= 35) {
                runCatching {
                    GlanceAppWidgetManager(context).setWidgetPreviews<CockpitGlanceWidgetReceiver>()
                }
            }
        }

        fun readBatteryMetric(context: Context, batteryIntent: Intent? = null): BatteryHudMetric {
            val pct = runCatching {
                context.getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?: -1
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
                context.getSystemService(BatteryManager::class.java)?.isCharging == true
            }.getOrDefault(false)
            val isCharging = bmCharging ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val isLow = !isCharging && pct in 0..20

            return if (pct in 0..100) {
                BatteryHudMetric(
                    percent = pct,
                    fraction = (pct / 100f).coerceIn(0.02f, 1f),
                    valueText = "$pct%",
                    label = CockpitHudBitmapRenderer.batterySubLabel(pct, isCharging),
                    isCharging = isCharging,
                    isLow = isLow,
                )
            } else {
                BatteryHudMetric(
                    percent = 0,
                    fraction = 0f,
                    valueText = "--%",
                    label = if (isCharging) "CHRG" else "BATT",
                    isCharging = isCharging,
                    isLow = false,
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

class OpenClockAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CockpitHaptics.performClick(context)
        CockpitGlanceWidget.launchGoogleClockAlarms(context)
    }
}

class OpenNextAlarmAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CockpitHaptics.performClick(context)
        CockpitGlanceWidget.launchGoogleClockAlarms(context)
    }
}

class OpenDriveLiftAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CockpitHaptics.performClick(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
        runCatching { context.startActivity(intent) }
    }
}

class ToggleDesktopDropAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CockpitHaptics.performClick(context)
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
        CockpitGlanceWidget().updateAll(context)
    }
}

class ToggleTorchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CockpitHaptics.performClick(context)
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
        CockpitGlanceWidget().updateAll(context)
    }
}

class ToggleIslandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CockpitHaptics.performClick(context)
        val cockpit = Graph.get(context).cockpit
        val current = cockpit.prefsRepo.readCurrentMasterEnabled()
        if (!cockpit.setDynamicIslandEnabledAndSync(!current)) {
            CockpitGlanceWidget.showBriefWidgetToast(context, "Grant overlay permission in Cockpit Hub")
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
            runCatching { context.startActivity(intent) }
        } else {
            CockpitGlanceWidget.showBriefWidgetToast(
                context,
                if (!current) "Dynamic Island on" else "Dynamic Island off",
            )
        }
    }
}

class LaunchPinnedAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CockpitHaptics.performClick(context)
        val pkg = parameters[CockpitGlanceWidget.PARAM_PACKAGE] ?: return
        Graph.get(context).cockpit.pinnedAppsRepo.launchApp(pkg)
    }
}
