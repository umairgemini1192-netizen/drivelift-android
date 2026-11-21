package com.drivelift.app.feature.cockpit.widget

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.View
import android.widget.RemoteViews
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.size
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
import com.drivelift.app.feature.cockpit.ui.CockpitTokens
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.ui.MainActivity
import com.drivelift.app.ui.Tokens

/**
 * All-in-One Home Screen Dark Tactile Glass Master Widget:
 * - Top Row: Native `<TextClock>` (`hh:mm a` uppercase) + native `<Chronometer>` alarm badge
 * - Middle Row: Live DriveLift telemetry, free phone storage GB, and current battery % with glass pills & icons
 * - Controls Row: 1-tap Desktop Drop, Flashlight, Dynamic Island toggles with 1dp glass strokes & icons
 * - Pinned Row: Dedicated row of synced Pinned Apps showing cached launcher app icons
 */
class CockpitGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT_4X2, SIZE_EXPANDED_4X3),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cockpit = Graph.get(context).cockpit
        val prefs = cockpit.prefsRepo.state.value
        val islandEnabled = cockpit.prefsRepo.readCurrentMasterEnabled()
        val telemetry = cockpit.telemetryAdapter.summary.value
        val alarm = NextAlarmFormatter.readFromSystem(context)
        val torchOn = cockpit.torchController.state.value.isOn
        val freeStorageLabel = readFreeStorageLabel()
        val batteryLabel = readBatteryLabel(context)
        val pinnedApps = cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages).take(4)

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                val maxPinned = if (size.height < SIZE_EXPANDED_4X3.height) 3 else 4
                MasterWidgetContent(
                    context = context,
                    maxPinned = maxPinned,
                    alarm = alarm,
                    telemetry = telemetry,
                    freeStorageLabel = freeStorageLabel,
                    batteryLabel = batteryLabel,
                    torchOn = torchOn,
                    islandOn = islandEnabled,
                    pinnedApps = pinnedApps,
                )
            }
        }
    }

    private fun resColorProvider(context: Context, resId: Int): GlanceColorProvider {
        val c = Color(ContextCompat.getColor(context, resId))
        return ColorProvider(day = c, night = c)
    }

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
        freeStorageLabel: String,
        batteryLabel: String,
        torchOn: Boolean,
        islandOn: Boolean,
        pinnedApps: List<PinnedAppInfo>,
    ) {
        val primaryText = resColorProvider(context, R.color.cockpit_on_surface)
        val cyanAccent = resColorProvider(context, R.color.cockpit_tertiary)
        val coralAccent = resColorProvider(context, R.color.cockpit_primary)
        val violetAccent = resColorProvider(context, R.color.cockpit_secondary)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.cockpit_widget_card_bg))
                .padding(Tokens.Medium),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // Top Row: Native TextClock (uppercase hh:mm a) + Self-Contained Native Alarm Chronometer
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

                Box(
                    modifier = GlanceModifier
                        .clickable(actionRunCallback<OpenNextAlarmAction>()),
                ) {
                    AndroidRemoteViews(remoteViews = buildAlarmRemoteViews(context, alarm))
                }
            }

            Spacer(GlanceModifier.height(Tokens.Small))

            // Middle Row: Live DriveLift Telemetry, Storage GB, Battery %
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                TelemetryChip(
                    iconRes = R.drawable.ic_cockpit_devices,
                    label = telemetry.statusHeadline,
                    accent = if (telemetry.isDesktopDropRunning) cyanAccent else coralAccent,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<OpenDriveLiftAction>()),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                TelemetryChip(
                    iconRes = R.drawable.ic_cockpit_storage,
                    label = freeStorageLabel,
                    accent = violetAccent,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                TelemetryChip(
                    iconRes = R.drawable.ic_cockpit_battery,
                    label = batteryLabel,
                    accent = primaryText,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }

            Spacer(GlanceModifier.height(Tokens.Small))

            // Row 3: Fixed Quick Controls Row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                QuickActionPill(
                    iconRes = R.drawable.ic_cockpit_devices,
                    label = if (telemetry.isDesktopDropRunning) "Drop On" else "Drop",
                    active = telemetry.isDesktopDropRunning,
                    activeColor = cyanAccent,
                    inactiveColor = primaryText,
                    bgDrawableRes = if (telemetry.isDesktopDropRunning) {
                        R.drawable.cockpit_widget_pill_active_cyan_bg
                    } else {
                        R.drawable.cockpit_widget_pill_inactive_bg
                    },
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<ToggleDesktopDropAction>()),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                QuickActionPill(
                    iconRes = if (torchOn) R.drawable.ic_cockpit_torch_on else R.drawable.ic_cockpit_torch_off,
                    label = if (torchOn) "Torch On" else "Torch",
                    active = torchOn,
                    activeColor = coralAccent,
                    inactiveColor = primaryText,
                    bgDrawableRes = if (torchOn) {
                        R.drawable.cockpit_widget_pill_active_coral_bg
                    } else {
                        R.drawable.cockpit_widget_pill_inactive_bg
                    },
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<ToggleTorchAction>()),
                )
                Spacer(GlanceModifier.width(Tokens.Snug))
                QuickActionPill(
                    iconRes = R.drawable.ic_cockpit_tile,
                    label = if (islandOn) "Island On" else "Island",
                    active = islandOn,
                    activeColor = violetAccent,
                    inactiveColor = primaryText,
                    bgDrawableRes = if (islandOn) {
                        R.drawable.cockpit_widget_pill_active_violet_bg
                    } else {
                        R.drawable.cockpit_widget_pill_inactive_bg
                    },
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<ToggleIslandAction>()),
                )
            }

            // Row 4: Dedicated Pinned Apps Dock Row (always separated from Quick Controls)
            if (pinnedApps.isNotEmpty()) {
                Spacer(GlanceModifier.height(Tokens.Snug))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    pinnedApps.take(maxPinned).forEachIndexed { index, app ->
                        if (index > 0) Spacer(GlanceModifier.width(Tokens.Snug))
                        PinnedAppGlancePill(
                            app = app,
                            labelColor = primaryText,
                            fallbackTint = cyanAccent,
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
        iconRes: Int,
        label: String,
        accent: GlanceColorProvider,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        Row(
            modifier = modifier
                .background(ImageProvider(R.drawable.cockpit_widget_well_bg))
                .padding(horizontal = Tokens.Small, vertical = Tokens.Snug),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = null,
                modifier = GlanceModifier.size(CockpitTokens.WidgetBadgeIconSize),
                colorFilter = ColorFilter.tint(accent),
            )
            Spacer(GlanceModifier.width(Tokens.Tiny))
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
        iconRes: Int,
        label: String,
        active: Boolean,
        activeColor: GlanceColorProvider,
        inactiveColor: GlanceColorProvider,
        bgDrawableRes: Int,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        val fg = if (active) activeColor else inactiveColor
        Row(
            modifier = modifier
                .background(ImageProvider(bgDrawableRes))
                .padding(horizontal = Tokens.Small, vertical = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = null,
                modifier = GlanceModifier.size(CockpitTokens.WidgetBadgeIconSize),
                colorFilter = ColorFilter.tint(fg),
            )
            Spacer(GlanceModifier.width(Tokens.Tiny))
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

    @Composable
    private fun PinnedAppGlancePill(
        app: PinnedAppInfo,
        labelColor: GlanceColorProvider,
        fallbackTint: GlanceColorProvider,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        val iconBitmap = app.iconBitmap?.takeIf { !it.isRecycled }
        Row(
            modifier = modifier
                .background(ImageProvider(R.drawable.cockpit_widget_pill_inactive_bg))
                .padding(horizontal = Tokens.Small, vertical = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (iconBitmap != null) {
                Image(
                    provider = ImageProvider(iconBitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.size(CockpitTokens.WidgetAppIconSize),
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_cockpit_app_default),
                    contentDescription = null,
                    modifier = GlanceModifier.size(CockpitTokens.WidgetAppIconSize),
                    colorFilter = ColorFilter.tint(fallbackTint),
                )
            }
            Spacer(GlanceModifier.width(Tokens.Tiny))
            Text(
                text = app.label,
                style = TextStyle(
                    color = labelColor,
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
        val current = cockpit.prefsRepo.readCurrentMasterEnabled()
        if (!cockpit.setDynamicIslandEnabledAndSync(!current)) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
            runCatching { context.startActivity(intent) }
        }
    }
}

class LaunchPinnedAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val pkg = parameters[CockpitGlanceWidget.PARAM_PACKAGE] ?: return
        Graph.get(context).cockpit.pinnedAppsRepo.launchApp(pkg)
    }
}
