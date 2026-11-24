package com.drivelift.app.feature.cockpit.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.drivelift.app.ui.Tokens
import com.drivelift.app.feature.cockpit.widget.BatteryHudMetric
import com.drivelift.app.feature.cockpit.widget.CockpitWidgetModel
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
import kotlinx.coroutines.flow.update
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
 *   that updates `CockpitGlanceWidget` ONLY when battery %, charging state, battery current (`>= 50 mA`
 *   change) or RAM (`>= 0.1 GB`) changed.
 * - Feeds the idle Dynamic Island pill: the active connection (event driven, no polling) and a 1 Hz
 *   `TrafficStats` upload and download rate. The rate ticker runs only while at least one idle pill
 *   is on screen AND the screen is on; `ACTION_SCREEN_OFF` stops it at once, so standby costs nothing.
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
        CockpitGlanceWidget.pushTelemetryUpdate(context)
    },
    private val trafficReader: () -> Pair<Long, Long> = {
        TrafficStats.getTotalTxBytes() to TrafficStats.getTotalRxBytes()
    },
    private val networkReader: () -> Pair<IdleNetworkKind, Int?> = { readNetworkKind(context) },
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
    private val _snapshot = MutableStateFlow(TelemetryHudSnapshot())
    val snapshot: StateFlow<TelemetryHudSnapshot> = _snapshot.asStateFlow()

    private val _network = MutableStateFlow(IdleNetworkSnapshot())

    /** Connection and live throughput for the idle pill. Moves only while [acquireIdleNetwork] is held. */
    val network: StateFlow<IdleNetworkSnapshot> = _network.asStateFlow()

    private val _isScreenOn = MutableStateFlow(runCatching { isScreenInteractive() }.getOrDefault(true))

    /** Live interactive screen state driven by ACTION_SCREEN_OFF / ACTION_SCREEN_ON broadcasts. */
    val isScreenOn: StateFlow<Boolean> = _isScreenOn.asStateFlow()

    private var activeClients = 0
    private var idleConsumers = 0
    private var screenPaused = false
    private var receiver: BroadcastReceiver? = null
    private var tickerJob: Job? = null
    private var speedJob: Job? = null
    private var widgetPushJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastPushedSnapshot: TelemetryHudSnapshot? = null
    private var lastWidgetPushElapsedMs: Long = Long.MIN_VALUE
    private val speedLock = Any()
    private var lastTxBytes = -1L
    private var lastRxBytes = -1L
    private var lastSampleMs = 0L

    /** True while the widget battery/RAM telemetry polling loop is actively running. */
    val isTelemetryTickerActive: Boolean
        get() = tickerJob?.isActive == true

    /** True while the 1 Hz network speed polling loop is actively running. */
    val isSpeedTickerActive: Boolean
        get() = speedJob?.isActive == true

    @Synchronized
    fun acquire() {
        activeClients++
        if (activeClients > 1) return
        registerReceivers()
        val interactive = !screenPaused && isScreenInteractive()
        _isScreenOn.value = interactive
        evaluateNow(batteryIntent = null, pushWidget = interactive)
        if (interactive) {
            startScreenOnTicker()
        }
    }

    @Synchronized
    fun release() {
        if (activeClients <= 0) return
        activeClients--
        if (activeClients > 0) return
        stopScreenOnTicker()
        unregisterReceiversIfUnused()
    }

    /** The idle pill calls this when it appears on screen and [releaseIdleNetwork] when it leaves. */
    @Synchronized
    fun acquireIdleNetwork() {
        idleConsumers++
        if (idleConsumers > 1) return
        registerReceivers()
        registerNetworkCallback()
        refreshNetworkKind()
        val interactive = !screenPaused && isScreenInteractive()
        _isScreenOn.value = interactive
        if (interactive) startSpeedTicker()
    }

    @Synchronized
    fun releaseIdleNetwork() {
        if (idleConsumers <= 0) return
        idleConsumers--
        if (idleConsumers > 0) return
        stopSpeedTicker()
        unregisterNetworkCallback()
        _network.update { it.copy(uploadBytesPerSec = 0L, downloadBytesPerSec = 0L) }
        unregisterReceiversIfUnused()
    }

    private fun unregisterReceiversIfUnused() {
        if (activeClients > 0 || idleConsumers > 0) return
        receiver?.let { r ->
            runCatching { appContext.unregisterReceiver(r) }
        }
        receiver = null
    }

    @Synchronized
    fun onBatteryOrScreenEvent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                screenPaused = true
                _isScreenOn.value = false
                stopScreenOnTicker()
                stopSpeedTicker()
                widgetPushJob?.cancel()
                widgetPushJob = null
                _network.update { it.copy(uploadBytesPerSec = 0L, downloadBytesPerSec = 0L) }
            }
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
            -> {
                screenPaused = false
                _isScreenOn.value = true
                if (activeClients > 0) {
                    evaluateNow(batteryIntent = null, pushWidget = true)
                    startScreenOnTicker()
                }
                if (idleConsumers > 0) {
                    refreshNetworkKind()
                    startSpeedTicker()
                }
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                // Suppress background widget RemoteViews/bitmap pushes while screen is off.
                val interactive = !screenPaused && isScreenInteractive()
                evaluateNow(batteryIntent = intent, pushWidget = interactive)
            }
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            -> evaluateNow(batteryIntent = intent, pushWidget = true)
        }
    }

    fun evaluateNow(batteryIntent: Intent? = null, pushWidget: Boolean = true): Boolean {
        val current = snapshotReader(batteryIntent).copy(isLoadingInitial = false)
        _snapshot.value = current
        val previous = lastPushedSnapshot
        val changed = shouldPushWidgetUpdate(previous, current)
        if (changed && pushWidget) {
            val nowMs = elapsedClock()
            val urgent = isUrgentWidgetChange(previous, current)
            val withinDebounce = lastWidgetPushElapsedMs != Long.MIN_VALUE &&
                (nowMs - lastWidgetPushElapsedMs) in 0L until Tokens.Cockpit.WIDGET_PUSH_DEBOUNCE_MS
            if (!urgent && withinDebounce) {
                return false
            }
            lastPushedSnapshot = current
            lastWidgetPushElapsedMs = nowMs
            widgetPushJob?.cancel()
            widgetPushJob = scope.launch { widgetPusher() }
        }
        return changed
    }

    private fun startSpeedTicker() {
        if (idleConsumers <= 0 || screenPaused || !isScreenInteractive() || speedJob?.isActive == true) return
        resetSpeedBaseline()
        speedJob = scope.launch {
            while (isActive) {
                delay(SPEED_TICK_INTERVAL_MS)
                if (screenPaused || !isScreenInteractive()) {
                    stopSpeedTicker()
                    break
                }
                sampleSpeed()
            }
        }
    }

    private fun stopSpeedTicker() {
        speedJob?.cancel()
        speedJob = null
        resetSpeedBaseline()
    }

    private fun resetSpeedBaseline() {
        synchronized(speedLock) {
            lastTxBytes = -1L
            lastRxBytes = -1L
            lastSampleMs = 0L
        }
    }

    /**
     * One throughput sample. The first sample after a start only sets the baseline, so traffic that
     * happened while the screen was off never shows up as a burst.
     */
    internal fun sampleSpeed() {
        val (tx, rx) = runCatching { trafficReader() }.getOrDefault(-1L to -1L)
        val now = elapsedClock()
        val up: Long
        val down: Long
        synchronized(speedLock) {
            val elapsed = if (lastSampleMs > 0L) now - lastSampleMs else 0L
            up = CockpitNetworkFormat.rate(lastTxBytes, tx, elapsed)
            down = CockpitNetworkFormat.rate(lastRxBytes, rx, elapsed)
            lastTxBytes = tx
            lastRxBytes = rx
            lastSampleMs = now
        }
        _network.update { current ->
            if (current.uploadBytesPerSec == up && current.downloadBytesPerSec == down) {
                current
            } else {
                current.copy(uploadBytesPerSec = up, downloadBytesPerSec = down)
            }
        }
    }

    internal fun refreshNetworkKind() {
        val (kind, telephonyType) = runCatching { networkReader() }
            .getOrDefault(IdleNetworkKind.OFFLINE to null)
        applyNetworkKind(kind, telephonyType)
    }

    /**
     * Updates the idle Dynamic Island pill directly from a `registerDefaultNetworkCallback`
     * [NetworkCapabilities] event so switching off Wi-Fi or losing `TRANSPORT_WIFI` immediately
     * flips the pill to Mobile Data (or Offline) with zero stale cache lag.
     */
    fun onDefaultNetworkCapabilitiesChanged(capabilities: NetworkCapabilities?) {
        val (kind, telephonyType) = readNetworkKindFromCapabilities(appContext, capabilities)
        applyNetworkKind(kind, telephonyType)
    }

    private fun applyNetworkKind(kind: IdleNetworkKind, telephonyType: Int?) {
        val label = CockpitNetworkFormat.label(kind, telephonyType)
        _network.update { current ->
            if (current.kind == kind && current.label == label) current else current.copy(kind = kind, label = label)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                onDefaultNetworkCapabilitiesChanged(caps)
            }

            override fun onLost(network: Network) {
                val active = runCatching { cm.activeNetwork }.getOrNull()
                val caps = if (active != null && active != network) {
                    runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
                } else {
                    null
                }
                onDefaultNetworkCapabilitiesChanged(caps)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                onDefaultNetworkCapabilitiesChanged(networkCapabilities)
            }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess) {
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
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
        if (activeClients <= 0 || screenPaused || !isScreenInteractive() || tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(Tokens.Cockpit.TELEMETRY_TICK_INTERVAL_MS)
                if (screenPaused || !isScreenInteractive()) {
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
        private const val SPEED_TICK_INTERVAL_MS = 1_000L

        /**
         * The active connection. `Data` is shown when the radio technology cannot be read: the
         * 5G and 4G names need READ_PHONE_STATE, which Cockpit deliberately does not request.
         */
        fun readNetworkKind(context: Context): Pair<IdleNetworkKind, Int?> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return IdleNetworkKind.OFFLINE to null
            val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
            return readNetworkKindFromCapabilities(context, caps)
        }

        fun readNetworkKindFromCapabilities(
            context: Context,
            caps: NetworkCapabilities?,
        ): Pair<IdleNetworkKind, Int?> {
            if (caps == null) return IdleNetworkKind.OFFLINE to null
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val telephonyType = if (hasCellular && !hasWifi && !hasEthernet) {
                runCatching {
                    context.getSystemService(TelephonyManager::class.java)?.dataNetworkType
                }.getOrNull()
            } else {
                null
            }
            return classifyNetworkCapabilities(
                hasInternet = hasInternet,
                hasWifi = hasWifi,
                hasEthernet = hasEthernet,
                hasCellular = hasCellular,
                telephonyType = telephonyType,
            )
        }

        fun classifyNetworkCapabilities(
            hasInternet: Boolean,
            hasWifi: Boolean,
            hasEthernet: Boolean,
            hasCellular: Boolean,
            telephonyType: Int? = null,
        ): Pair<IdleNetworkKind, Int?> {
            if (!hasInternet) return IdleNetworkKind.OFFLINE to null
            return when {
                hasWifi || hasEthernet -> IdleNetworkKind.WIFI to null
                hasCellular -> IdleNetworkKind.CELLULAR to telephonyType
                else -> IdleNetworkKind.OFFLINE to null
            }
        }

        fun shouldPushWidgetUpdate(
            previous: TelemetryHudSnapshot?,
            current: TelemetryHudSnapshot,
        ): Boolean {
            if (previous == null) return true
            if (previous.battery.percent != current.battery.percent) return true
            if (previous.battery.isCharging != current.battery.isCharging) return true
            if (CockpitWidgetModel.currentChangedEnough(
                    previous.battery.current.milliAmps,
                    current.battery.current.milliAmps,
                )
            ) {
                return true
            }
            if (CockpitWidgetModel.chargeChangedEnough(
                    previous.battery.charge.milliAmpHours,
                    current.battery.charge.milliAmpHours,
                )
            ) {
                return true
            }
            if (abs(previous.ram.usedGb - current.ram.usedGb) >= Tokens.Cockpit.RAM_DELTA_THRESHOLD_GB) return true
            return false
        }

        private fun isUrgentWidgetChange(
            previous: TelemetryHudSnapshot?,
            current: TelemetryHudSnapshot,
        ): Boolean {
            if (previous == null) return true
            if (previous.battery.percent != current.battery.percent) return true
            if (previous.battery.isCharging != current.battery.isCharging) return true
            if (abs(previous.ram.usedGb - current.ram.usedGb) >= Tokens.Cockpit.RAM_DELTA_THRESHOLD_GB) return true
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
