package com.drivelift.app.feature.cockpit.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.NetworkStatsManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToLong

enum class CockpitRingerMode {
    NORMAL,
    VIBRATE,
    SILENT,
}

data class WifiTileState(
    val isEnabled: Boolean = false,
    val isConnected: Boolean = false,
    val ssid: String = "",
    val hasLocationPermission: Boolean = false,
    val label: String = "Off",
)

data class MobileDataTileState(
    val isEnabled: Boolean = true,
    val hasUsageAccess: Boolean = false,
    val isLoadingInitial: Boolean = false,
    val monthlyBytes: Long = 0L,
    val label: String = "Grant access",
)

data class BluetoothTileState(
    val isEnabled: Boolean = false,
    val hasPermission: Boolean = false,
    val connectedCount: Int = 0,
    val label: String = "Off",
)

data class RingerTileState(
    val mode: CockpitRingerMode = CockpitRingerMode.NORMAL,
    val hasDndAccess: Boolean = false,
    val label: String = "Ringer",
)

data class CockpitQuickControlsState(
    val wifi: WifiTileState = WifiTileState(),
    val mobileData: MobileDataTileState = MobileDataTileState(),
    val bluetooth: BluetoothTileState = BluetoothTileState(),
    val ringer: RingerTileState = RingerTileState(),
    val statusConfirmation: String? = null,
)

/**
 * Observes and controls the 5-Control Tactile Glass Quick Deck inside the expanded Dynamic Island:
 * Wi-Fi SSID (via `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)`), Monthly Mobile Data
 * (`NetworkStatsManager`), Bluetooth connected count, Ringer mode (`AudioManager` + DND check),
 * and deep-links to system settings panels with graceful degradation when permissions are ungranted.
 */
class CockpitQuickControlsController(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val networkStatsManager = appContext.getSystemService(NetworkStatsManager::class.java)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    private val _state = MutableStateFlow(CockpitQuickControlsState())
    val state: StateFlow<CockpitQuickControlsState> = _state.asStateFlow()

    private val connectedProfileDevices = LinkedHashMap<Int, Set<String>>()
    private val aclConnectedAddresses = LinkedHashSet<String>()
    private val activeProxies = LinkedHashMap<Int, BluetoothProfile>()

    private var started = false
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var refreshJob: Job? = null
    private var statusClearJob: Job? = null
    private var hasLoadedUsageOnce = false
    private var cachedUsageBytes: Long = 0L

    @Volatile
    private var latestWifiInfoSsid: String? = null

    @Volatile
    private var latestWifiConnected: Boolean = false

    fun start() {
        if (started) {
            refreshAll()
            return
        }
        started = true
        registerWifiCallback()
        registerSystemReceivers()
        bindBluetoothProfiles()
        refreshAll()
        refreshJob = scope.launch {
            while (isActive) {
                delay(USAGE_REFRESH_INTERVAL_MS)
                refreshMonthlyMobileData()
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        refreshJob?.cancel()
        refreshJob = null
        statusClearJob?.cancel()
        statusClearJob = null
        wifiCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        wifiCallback = null
        receiver?.let { r ->
            runCatching { appContext.unregisterReceiver(r) }
        }
        receiver = null
        unbindBluetoothProfiles()
    }

    fun refreshAll() {
        refreshWifiState()
        refreshBluetoothState()
        refreshRingerState()
        refreshMonthlyMobileData()
    }

    fun openInternetPanel() {
        CockpitHaptics.performClick(appContext)
        val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val wifiFallback = Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { appContext.startActivity(panelIntent); true }
            .getOrElse { runCatching { appContext.startActivity(wifiFallback); true }.getOrDefault(false) }
        if (launched) postStatusConfirmation("Internet panel opened")
    }

    fun openWifiSettings() {
        CockpitHaptics.performClick(appContext)
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { appContext.startActivity(intent); true }.getOrDefault(false)) {
            postStatusConfirmation("Wi-Fi settings opened")
        }
    }

    fun onMobileDataTap() {
        CockpitHaptics.performClick(appContext)
        if (!hasUsageStatsPermission(appContext)) {
            openUsageAccessSettings()
            postStatusConfirmation("Grant Usage Access for monthly data")
        } else {
            openInternetPanel()
        }
    }

    fun openDataUsageSettings() {
        CockpitHaptics.performClick(appContext)
        val dataIntent = Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val wirelessFallback = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { appContext.startActivity(dataIntent); true }
            .getOrElse { runCatching { appContext.startActivity(wirelessFallback); true }.getOrDefault(false) }
        if (launched) postStatusConfirmation("Data usage settings opened")
    }

    fun openBluetoothSettings() {
        CockpitHaptics.performClick(appContext)
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { appContext.startActivity(intent); true }.getOrDefault(false)) {
            postStatusConfirmation("Bluetooth settings opened")
        }
    }

    fun cycleRingerMode() {
        CockpitHaptics.performClick(appContext)
        val current = readRingerMode()
        val hasDnd = hasDndPolicyAccess(appContext)
        val target = nextRingerMode(current)
        if ((target == CockpitRingerMode.SILENT || current == CockpitRingerMode.SILENT) && !hasDnd) {
            openDndPolicySettings()
            postStatusConfirmation("Grant Do Not Disturb access for Silent mode")
            return
        }
        val applied = runCatching {
            audioManager?.ringerMode = toAndroidRingerMode(target)
            true
        }.getOrDefault(false)
        refreshRingerState()
        if (applied) {
            postStatusConfirmation("Ringer: ${ringerLabel(readRingerMode())}")
        }
    }

    fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    fun openUsageAccessSettings() {
        val pkgIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(android.net.Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(pkgIntent) }
            .recoverCatching { appContext.startActivity(fallbackIntent) }
    }

    fun openDndPolicySettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    fun seedForTest(state: CockpitQuickControlsState) {
        _state.value = state
    }

    private fun registerWifiCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val info = networkCapabilities.transportInfo as? WifiInfo
                    latestWifiInfoSsid = info?.ssid
                    latestWifiConnected = true
                    refreshWifiState()
                }

                override fun onAvailable(network: Network) {
                    latestWifiConnected = true
                    refreshWifiState()
                }

                override fun onLost(network: Network) {
                    latestWifiConnected = false
                    latestWifiInfoSsid = null
                    refreshWifiState()
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    latestWifiConnected = true
                    refreshWifiState()
                }

                override fun onLost(network: Network) {
                    latestWifiConnected = false
                    latestWifiInfoSsid = null
                    refreshWifiState()
                }
            }
        }
        wifiCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun registerSystemReceivers() {
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        }
        val sysReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> refreshWifiState()
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothAdapter.STATE_OFF
                        ) {
                            aclConnectedAddresses.clear()
                            connectedProfileDevices.clear()
                        }
                        refreshBluetoothState()
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        @Suppress("DEPRECATION")
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        dev?.address?.let { aclConnectedAddresses.add(it) }
                        refreshBluetoothState()
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        @Suppress("DEPRECATION")
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        dev?.address?.let { aclConnectedAddresses.remove(it) }
                        refreshBluetoothState()
                    }
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                    -> {
                        refreshProfileProxies()
                        refreshBluetoothState()
                    }
                    AudioManager.RINGER_MODE_CHANGED_ACTION,
                    NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED,
                    -> refreshRingerState()
                }
            }
        }
        receiver = sysReceiver
        ContextCompat.registerReceiver(appContext, sysReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun refreshWifiState() {
        val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
        val activeConnected = runCatching {
            val active = connectivityManager?.activeNetwork
            val caps = active?.let { connectivityManager?.getNetworkCapabilities(it) }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        val connected = latestWifiConnected || activeConnected
        val hasFineLocation = hasLocationPermission(appContext)
        val locationEnabled = runCatching { locationManager?.isLocationEnabled == true }.getOrDefault(false)
        val hasFullLocationAccess = hasFineLocation && locationEnabled

        @Suppress("DEPRECATION")
        val rawSsid = latestWifiInfoSsid ?: if (hasFullLocationAccess) {
            runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        } else {
            null
        }
        val displayLabel = sanitizeSsid(
            rawSsid = rawSsid,
            wifiEnabled = wifiEnabled,
            wifiConnected = connected,
            hasLocationAccess = hasFullLocationAccess,
        )
        val cleanSsid = if (displayLabel != "Off" && displayLabel != "Wi-Fi On") displayLabel else ""
        _state.value = _state.value.copy(
            wifi = WifiTileState(
                isEnabled = wifiEnabled,
                isConnected = connected,
                ssid = cleanSsid,
                hasLocationPermission = hasFineLocation,
                label = displayLabel,
            ),
        )
    }

    private fun refreshMonthlyMobileData() {
        val mobileEnabled = runCatching {
            Settings.Global.getInt(appContext.contentResolver, "mobile_data", 1) == 1
        }.getOrDefault(true)
        val granted = hasUsageStatsPermission(appContext)
        if (!granted) {
            _state.value = _state.value.copy(
                mobileData = MobileDataTileState(
                    isEnabled = mobileEnabled,
                    hasUsageAccess = false,
                    isLoadingInitial = false,
                    monthlyBytes = 0L,
                    label = "Grant access",
                ),
            )
            return
        }
        if (!hasLoadedUsageOnce) {
            _state.value = _state.value.copy(
                mobileData = _state.value.mobileData.copy(
                    isEnabled = mobileEnabled,
                    hasUsageAccess = true,
                    isLoadingInitial = true,
                    label = formatMonthlyDataUsage(cachedUsageBytes),
                ),
            )
        }
        scope.launch(Dispatchers.IO) {
            val nowMs = clock()
            val monthStartMs = startOfMonthMillis(nowMs)
            val totalBytes = queryMonthlyCellularBytes(monthStartMs, nowMs)
            hasLoadedUsageOnce = true
            cachedUsageBytes = totalBytes
            _state.value = _state.value.copy(
                mobileData = MobileDataTileState(
                    isEnabled = mobileEnabled,
                    hasUsageAccess = true,
                    isLoadingInitial = false,
                    monthlyBytes = totalBytes,
                    label = formatMonthlyDataUsage(totalBytes),
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun queryMonthlyCellularBytes(startMs: Long, endMs: Long): Long {
        val statsManager = networkStatsManager ?: return cachedUsageBytes
        return runCatching {
            val bucket = statsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startMs,
                endMs,
            )
            (bucket.rxBytes + bucket.txBytes).coerceAtLeast(0L)
        }.getOrDefault(cachedUsageBytes)
    }

    @SuppressLint("MissingPermission")
    private fun bindBluetoothProfiles() {
        val adapter = bluetoothManager?.adapter ?: return
        if (!hasBluetoothConnectPermission(appContext)) return
        val profiles = listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
        ) + if (Build.VERSION.SDK_INT >= 33) listOf(BluetoothProfile.LE_AUDIO) else emptyList()

        for (profile in profiles) {
            runCatching {
                adapter.getProfileProxy(
                    appContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            activeProxies[p] = proxy
                            connectedProfileDevices[p] = runCatching {
                                proxy.connectedDevices.mapNotNull { it.address }.toSet()
                            }.getOrDefault(emptySet())
                            refreshBluetoothState()
                        }

                        override fun onServiceDisconnected(p: Int) {
                            activeProxies.remove(p)
                            connectedProfileDevices.remove(p)
                            refreshBluetoothState()
                        }
                    },
                    profile,
                )
            }
        }
    }

    private fun unbindBluetoothProfiles() {
        val adapter = bluetoothManager?.adapter ?: return
        activeProxies.forEach { (profile, proxy) ->
            runCatching { adapter.closeProfileProxy(profile, proxy) }
        }
        activeProxies.clear()
        connectedProfileDevices.clear()
        aclConnectedAddresses.clear()
    }

    @SuppressLint("MissingPermission")
    private fun refreshProfileProxies() {
        if (!hasBluetoothConnectPermission(appContext)) return
        activeProxies.forEach { (profile, proxy) ->
            connectedProfileDevices[profile] = runCatching {
                proxy.connectedDevices.mapNotNull { it.address }.toSet()
            }.getOrDefault(emptySet())
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetoothState() {
        val hasPermission = hasBluetoothConnectPermission(appContext)
        val adapter = bluetoothManager?.adapter
        val enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        if (!hasPermission || !enabled) {
            _state.value = _state.value.copy(
                bluetooth = BluetoothTileState(
                    isEnabled = enabled,
                    hasPermission = hasPermission,
                    connectedCount = 0,
                    label = formatBluetoothStatus(enabled = enabled, hasPermission = hasPermission, connectedCount = 0),
                ),
            )
            return
        }

        val addresses = LinkedHashSet<String>()
        runCatching {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                ?.mapNotNullTo(addresses) { it.address }
        }
        connectedProfileDevices.values.forEach { addresses.addAll(it) }
        addresses.addAll(aclConnectedAddresses)
        val count = addresses.size

        _state.value = _state.value.copy(
            bluetooth = BluetoothTileState(
                isEnabled = true,
                hasPermission = true,
                connectedCount = count,
                label = formatBluetoothStatus(enabled = true, hasPermission = true, connectedCount = count),
            ),
        )
    }

    private fun readRingerMode(): CockpitRingerMode {
        val raw = runCatching { audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL }
            .getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        return fromAndroidRingerMode(raw)
    }

    private fun refreshRingerState() {
        val mode = readRingerMode()
        val hasDnd = hasDndPolicyAccess(appContext)
        _state.value = _state.value.copy(
            ringer = RingerTileState(
                mode = mode,
                hasDndAccess = hasDnd,
                label = ringerLabel(mode),
            ),
        )
    }

    private fun postStatusConfirmation(message: String) {
        statusClearJob?.cancel()
        _state.value = _state.value.copy(statusConfirmation = message)
        statusClearJob = scope.launch {
            delay(STATUS_CONFIRMATION_MS)
            _state.value = _state.value.copy(statusConfirmation = null)
        }
    }

    companion object {
        private const val USAGE_REFRESH_INTERVAL_MS = 60_000L
        private const val STATUS_CONFIRMATION_MS = 2_400L
        private const val BYTES_PER_MB = 1024.0 * 1024.0
        private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

        fun sanitizeSsid(
            rawSsid: String?,
            wifiEnabled: Boolean,
            wifiConnected: Boolean,
            hasLocationAccess: Boolean,
        ): String {
            if (!wifiEnabled) return "Off"
            if (!wifiConnected || !hasLocationAccess) return "Wi-Fi On"
            val cleaned = rawSsid?.trim()?.removeSurrounding("\"").orEmpty()
            return if (cleaned.isBlank() ||
                cleaned.equals("<unknown ssid>", ignoreCase = true) ||
                cleaned.equals("0x", ignoreCase = true)
            ) {
                "Wi-Fi On"
            } else {
                cleaned
            }
        }

        fun formatMonthlyDataUsage(bytes: Long): String {
            if (bytes < 0L) return "-- / mo"
            val gb = bytes / BYTES_PER_GB
            return if (gb >= 1.0) {
                "%.1f GB / mo".format(Locale.US, gb)
            } else {
                val mb = (bytes / BYTES_PER_MB).roundToLong().coerceAtLeast(0L)
                "$mb MB / mo"
            }
        }

        fun formatBluetoothStatus(
            enabled: Boolean,
            hasPermission: Boolean,
            connectedCount: Int,
        ): String = when {
            !hasPermission -> "Grant access"
            !enabled -> "Off"
            connectedCount > 0 -> "$connectedCount connected"
            else -> "On"
        }

        fun nextRingerMode(current: CockpitRingerMode): CockpitRingerMode = when (current) {
            CockpitRingerMode.NORMAL -> CockpitRingerMode.VIBRATE
            CockpitRingerMode.VIBRATE -> CockpitRingerMode.SILENT
            CockpitRingerMode.SILENT -> CockpitRingerMode.NORMAL
        }

        fun ringerLabel(mode: CockpitRingerMode): String = when (mode) {
            CockpitRingerMode.NORMAL -> "Ringer"
            CockpitRingerMode.VIBRATE -> "Vibrate"
            CockpitRingerMode.SILENT -> "Silent"
        }

        fun fromAndroidRingerMode(mode: Int): CockpitRingerMode = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> CockpitRingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> CockpitRingerMode.VIBRATE
            else -> CockpitRingerMode.NORMAL
        }

        fun toAndroidRingerMode(mode: CockpitRingerMode): Int = when (mode) {
            CockpitRingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            CockpitRingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            CockpitRingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }

        fun startOfMonthMillis(
            nowMillis: Long,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): Long {
            val nowZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
            return nowZdt.withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun hasUsageStatsPermission(context: Context): Boolean = runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        fun hasBluetoothConnectPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 31) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun hasDndPolicyAccess(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        }.getOrDefault(false)
    }
}
