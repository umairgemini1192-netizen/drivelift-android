package com.drivelift.app.feature.cockpit.widget

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import com.drivelift.app.Graph
import com.drivelift.app.R
import com.drivelift.app.feature.cockpit.data.PinnedAppInfo
import com.drivelift.app.feature.cockpit.hardware.AlarmCountdownMode
import com.drivelift.app.feature.cockpit.hardware.NextAlarmFormatter
import com.drivelift.app.feature.cockpit.hardware.NextAlarmUiModel
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetrySummary
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.ui.MainActivity
import com.drivelift.app.ui.Tokens

/**
 * All-in-One Home Screen Dark Tactile Glass Master Widget:
 * - Top Row: Native `<TextClock>` (12h/24h synced, zero wakeups) + Next Alarm & Live `<Chronometer>` countdown
 * - Middle Row: Live DriveLift telemetry, free phone storage GB, and current battery %
 * - Bottom Row: 1-tap Desktop Drop, Flashlight, Dynamic Island toggles, and synced Pinned Apps
 */
class CockpitGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT_4X2, SIZE_EXPANDED_4X3),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cockpit = Graph.get(context).cockpit
        val prefs = cockpit.prefsRepo.state.value
        val telemetry = cockpit.telemetryAdapter.summary.value
        val alarm = NextAlarmFormatter.readFromSystem(context)
        val torchOn = cockpit.torchController.state.value.isOn
        val freeStorageLabel = readFreeStorageLabel()
        val batteryLabel = readBatteryLabel(context)
        val pinnedApps = cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages).take(3)

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                val compact = size.height < SIZE_EXPANDED_4X3.height
                MasterWidgetContent(
                    context = context,
                    compact = compact,
                    alarm = alarm,
                    telemetry = telemetry,
                    freeStorageLabel = freeStorageLabel,
                    batteryLabel = batteryLabel,
                    torchOn = torchOn,
                    islandOn = prefs.masterEnabled,
                    pinnedApps = pinnedApps,
                )
            }
        }
    }

    private fun resColorProvider(context: Context, resId: Int): GlanceColorProvider {
        val c = Color(ContextCompat.getColor(context, resId))
        return ColorProvider(day = c, night = c)
    }

    @Composable
    private fun MasterWidgetContent(
        context: Context,
        compact: Boolean,
        alarm: NextAlarmUiModel,
        telemetry: DriveLiftTelemetrySummary,
        freeStorageLabel: String,
        batteryLabel: String,
        torchOn: Boolean,
        islandOn: Boolean,
        pinnedApps: List<PinnedAppInfo>,
    ) {
        val skyBg = resColorProvider(context, R.color.cockpit_sky_top)
        val surfaceBg = resColorProvider(context, R.color.cockpit_surface)
        val containerBg = resColorProvider(context, R.color.cockpit_surface_container)
        val containerHighBg = resColorProvider(context, R.color.cockpit_surface_container_high)
        val primaryText = resColorProvider(context, R.color.cockpit_on_surface)
        val mutedText = resColorProvider(context, R.color.cockpit_on_surface_variant)
        val cyanAccent = resColorProvider(context, R.color.cockpit_tertiary)
        val coralAccent = resColorProvider(context, R.color.cockpit_primary)
        val violetAccent = resColorProvider(context, R.color.cockpit_secondary)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(skyBg)
                .cornerRadius(Tokens.Section)
                .padding(Tokens.Medium),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // Top Row: Native TextClock + Next Alarm & Live Countdown
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<OpenClockAction>()),
                ) {
                    AndroidRemoteViews(
                        remoteViews = RemoteViews(context.packageName, R.layout.cockpit_widget_clock_header),
                    )
                }

                Column(
                    modifier = GlanceModifier
                        .background(containerBg)
                        .cornerRadius(Tokens.Gutter)
                        .clickable(actionRunCallback<OpenNextAlarmAction>())
                        .padding(horizontal = Tokens.Row, vertical = Tokens.Snug),
                    horizontalAlignment = Alignment.Horizontal.End,
                ) {
                    Text(
                        text = if (alarm.hasAlarm) "Alarm ${alarm.formattedTime}" else "No alarm set",
                        style = TextStyle(
                            color = if (alarm.hasAlarm) primaryText else mutedText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    if (alarm.countdownMode == AlarmCountdownMode.CHRONOMETER_UNDER_24H &&
                        alarm.chronometerBaseElapsedMs != null
                    ) {
                        val chronoViews = RemoteViews(
                            context.packageName,
                            R.layout.cockpit_widget_alarm_chronometer,
                        ).apply {
                            setChronometer(
                                R.id.cockpit_alarm_chronometer,
                                alarm.chronometerBaseElapsedMs,
                                "Rings in %s",
                                true,
                            )
                            setChronometerCountDown(R.id.cockpit_alarm_chronometer, true)
                        }
                        AndroidRemoteViews(remoteViews = chronoViews)
                    } else if (alarm.hasAlarm) {
                        Text(
                            text = alarm.countdownText,
                            style = TextStyle(
                                color = cyanAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(GlanceModifier.height(Tokens.Small))

            // Middle Row: Live DriveLift Telemetry, Storage GB, Battery %
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                TelemetryChip(
                    label = telemetry.statusHeadline,
                    bg = surfaceBg,
                    accent = if (telemetry.isDesktopDropRunning) cyanAccent else coralAccent,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<OpenDriveLiftAction>()),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                TelemetryChip(
                    label = freeStorageLabel,
                    bg = surfaceBg,
                    accent = violetAccent,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                TelemetryChip(
                    label = batteryLabel,
                    bg = surfaceBg,
                    accent = primaryText,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }

            Spacer(GlanceModifier.height(Tokens.Small))

            // Bottom Row: Quick Toggles + Pinned Apps Dock
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                QuickActionPill(
                    label = if (telemetry.isDesktopDropRunning) "Drop On" else "Drop",
                    active = telemetry.isDesktopDropRunning,
                    activeColor = cyanAccent,
                    inactiveColor = primaryText,
                    bg = if (telemetry.isDesktopDropRunning) containerHighBg else containerBg,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<ToggleDesktopDropAction>()),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                QuickActionPill(
                    label = if (torchOn) "Torch On" else "Torch",
                    active = torchOn,
                    activeColor = coralAccent,
                    inactiveColor = primaryText,
                    bg = if (torchOn) containerHighBg else containerBg,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<ToggleTorchAction>()),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                QuickActionPill(
                    label = if (islandOn) "Island On" else "Island",
                    active = islandOn,
                    activeColor = violetAccent,
                    inactiveColor = primaryText,
                    bg = if (islandOn) containerHighBg else containerBg,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<ToggleIslandAction>()),
                )
            }

            if (!compact && pinnedApps.isNotEmpty()) {
                Spacer(GlanceModifier.height(Tokens.Small))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    pinnedApps.forEachIndexed { index, app ->
                        if (index > 0) Spacer(GlanceModifier.width(Tokens.Snug))
                        QuickActionPill(
                            label = app.label,
                            active = false,
                            activeColor = cyanAccent,
                            inactiveColor = primaryText,
                            bg = containerBg,
                            modifier = GlanceModifier
                                .defaultWeight()
                                .clickable(
                                    actionRunCallback<LaunchPinnedAppAction>(
                                        actionParametersOf(PARAM_PACKAGE to app.packageName),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TelemetryChip(
        label: String,
        bg: GlanceColorProvider,
        accent: GlanceColorProvider,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        Box(
            modifier = modifier
                .background(bg)
                .cornerRadius(Tokens.Medium)
                .padding(horizontal = Tokens.Small, vertical = Tokens.Snug),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun QuickActionPill(
        label: String,
        active: Boolean,
        activeColor: GlanceColorProvider,
        inactiveColor: GlanceColorProvider,
        bg: GlanceColorProvider,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        val fg = if (active) activeColor else inactiveColor
        Box(
            modifier = modifier
                .background(bg)
                .cornerRadius(Tokens.RowLoose)
                .padding(horizontal = Tokens.Small, vertical = Tokens.Small),
            contentAlignment = Alignment.Center,
        ) {
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

        fun readFreeStorageLabel(): String = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val gb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
            "%.0f GB free".format(gb)
        }.getOrDefault("Storage --")

        fun readBatteryLabel(context: Context): String = runCatching {
            val bm = context.getSystemService(BatteryManager::class.java)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) "$pct% batt" else "Batt --"
        }.getOrDefault("Batt --")
    }
}

class OpenClockAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(showAlarms) }
            .recoverCatching { context.startActivity(fallback) }
    }
}

class OpenNextAlarmAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val showIntent = runCatching {
            context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.showIntent
        }.getOrNull()
        if (showIntent != null && runCatching { showIntent.send() }.isSuccess) return
        val fallback = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }
    }
}

class OpenDriveLiftAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
        runCatching { context.startActivity(intent) }
    }
}

class ToggleDesktopDropAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = Graph.get(context).desktopDrop.controller
        val status = controller.snapshot.value.status
        if (status == ServerStatus.RUNNING || status == ServerStatus.STARTING) {
            controller.stopServer("Stopped from Cockpit widget")
        } else {
            controller.startServer()
        }
        CockpitGlanceWidget().updateAll(context)
    }
}

class ToggleTorchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Graph.get(context).cockpit.torchController.toggleTorch()
        CockpitGlanceWidget().updateAll(context)
    }
}

class ToggleIslandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val cockpit = Graph.get(context).cockpit
        val current = cockpit.prefsRepo.state.value.masterEnabled
        if (!cockpit.setDynamicIslandEnabled(!current)) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
            runCatching { context.startActivity(intent) }
        }
        CockpitGlanceWidget().updateAll(context)
    }
}

class LaunchPinnedAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val pkg = parameters[CockpitGlanceWidget.PARAM_PACKAGE] ?: return
        Graph.get(context).cockpit.pinnedAppsRepo.launchApp(pkg)
    }
}
