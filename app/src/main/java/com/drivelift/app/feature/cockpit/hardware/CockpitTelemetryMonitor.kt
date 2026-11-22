package com.drivelift.app.feature.cockpit.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.drivelift.app.feature.cockpit.ui.CockpitTokens
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.cockpit.widget.RamHudMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TelemetryHudSnapshot(
    val battery: BatteryHudMetric = BatteryHudMetric(percent = 0, fraction = 0f, valueText = "--%"),
    val ram: RamHudMetric = RamHudMetric(freeGb = 0.0, usedGb = 0.0, totalGb = 0.0, fraction = 0f, valueText = "--"),
    val isLoadingInitial: Boolean = true,
)

/**
 * Real-Time Battery & RAM telemetry monitor wired into Cockpit background services:
 * - Dynamically registers `ACTION_BATTERY_CHANGED`, `ACTION_POWER_CONNECTED`,
 *   `ACTION_POWER_DISCONNECTED`, `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`, and `ACTION_USER_PRESENT`
 *   for instant battery %, charging, and wake updates with zero polling.
 * - Runs a screen-on-only coroutine ticker every 20 seconds (`PowerManager.isInteractive == true`)
 *   that updates `CockpitGlanceWidget` ONLY when battery %, charging state, or RAM (`>= 0.1 GB`) changed.
 */
class CockpitTelemetryMonitor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val isScreenInteractive: () -> Boolean = {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
    },
    private val snapshotReader: (Intent?) -> TelemetryHudSnapshot = { batteryIntent ->
        TelemetryHudSnapshot(
            battery = CockpitGlanceWidget.readBatteryMetric(context, batteryIntent),
            ram = CockpitGlanceWidget.readRamMetric(context),
            isLoadingInitial = false,
        )
    },
    private val widgetPusher: suspend () -> Unit = {
        runCatching { CockpitGlanceWidget().updateAll(context) }
    },
) {
    private val appContext = context.applicationContext
    private val _snapshot = MutableStateFlow(TelemetryHudSnapshot())
    val snapshot: StateFlow<TelemetryHudSnapshot> = _snapshot.asStateFlow()

    private var activeClients = 0
    private var receiver: BroadcastReceiver? = null
    private var tickerJob: Job? = null
    private var lastPushedSnapshot: TelemetryHudSnapshot? = null

    @Synchronized
    fun acquire() {
        activeClients++
        if (activeClients > 1) return
        registerReceivers()
        evaluateNow(batteryIntent = null, pushWidget = true)
        if (isScreenInteractive()) {
            startScreenOnTicker()
        }
    }

    @Synchronized
    fun release() {
        if (activeClients <= 0) return
        activeClients--
        if (activeClients > 0) return
        stopScreenOnTicker()
        receiver?.let { r ->
            runCatching { appContext.unregisterReceiver(r) }
        }
        receiver = null
    }

    fun onBatteryOrScreenEvent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> stopScreenOnTicker()
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
            -> {
                evaluateNow(batteryIntent = null, pushWidget = true)
                startScreenOnTicker()
            }
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            -> evaluateNow(batteryIntent = intent, pushWidget = true)
        }
    }

    fun evaluateNow(batteryIntent: Intent? = null, pushWidget: Boolean = true): Boolean {
        val current = snapshotReader(batteryIntent).copy(isLoadingInitial = false)
        _snapshot.value = current
        val changed = shouldPushWidgetUpdate(lastPushedSnapshot, current)
        if (changed && pushWidget) {
            lastPushedSnapshot = current
            scope.launch { widgetPusher() }
        }
        return changed
    }

    private fun registerReceivers() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val monitorReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onBatteryOrScreenEvent(intent)
            }
        }
        receiver = monitorReceiver
        ContextCompat.registerReceiver(
            appContext,
            monitorReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun startScreenOnTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(CockpitTokens.TELEMETRY_TICK_INTERVAL_MS)
                if (!isScreenInteractive()) {
                    stopScreenOnTicker()
                    break
                }
                evaluateNow(batteryIntent = null, pushWidget = true)
            }
        }
    }

    private fun stopScreenOnTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    companion object {
        fun shouldPushWidgetUpdate(
            previous: TelemetryHudSnapshot?,
            current: TelemetryHudSnapshot,
        ): Boolean {
            if (previous == null) return true
            if (previous.battery.percent != current.battery.percent) return true
            if (previous.battery.isCharging != current.battery.isCharging) return true
            if (abs(previous.ram.usedGb - current.ram.usedGb) >= CockpitTokens.RAM_DELTA_THRESHOLD_GB) return true
            return false
        }

        fun extractBatteryChargingFromIntent(intent: Intent?): Boolean? {
            if (intent == null) return null
            if (intent.action == Intent.ACTION_POWER_CONNECTED) return true
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED) return false
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }
}
