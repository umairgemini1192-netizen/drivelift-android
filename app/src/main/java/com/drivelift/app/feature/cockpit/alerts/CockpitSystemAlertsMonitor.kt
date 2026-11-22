package com.drivelift.app.feature.cockpit.alerts

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Standalone lifecycle-aware monitor for Phase 2 system alerts.
 * Ready to be wired into CockpitStateCoordinator after PR A merges.
 */
class CockpitSystemAlertsMonitor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val reducer: CockpitSystemAlertReducer = CockpitSystemAlertReducer(),
    private val flashController: CockpitLedFlashAlertController? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _activeAlert = MutableStateFlow<CockpitSystemAlert?>(null)
    val activeAlert: StateFlow<CockpitSystemAlert?> = _activeAlert.asStateFlow()

    private val _events = MutableSharedFlow<CockpitSystemAlert>(extraBufferCapacity = 16)
    val events: SharedFlow<CockpitSystemAlert> = _events.asSharedFlow()

    private val lock = Any()
    private var started = false
    private var expiryJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            handleIntent(intent)
        }
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(Intent.ACTION_USER_PRESENT)
                if (hasBluetoothConnectPermission()) {
                    addAction(CockpitEarbudsBatteryParser.ACTION_BATTERY_LEVEL_CHANGED)
                    addAction(CockpitEarbudsBatteryParser.ACTION_HEADSET_CONNECTION_STATE_CHANGED)
                    addAction(CockpitEarbudsBatteryParser.ACTION_A2DP_CONNECTION_STATE_CHANGED)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            started = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            expiryJob?.cancel()
            expiryJob = null
            runCatching { context.unregisterReceiver(receiver) }
            _activeAlert.value = null
        }
    }

    fun emitBiometric(stage: CockpitBiometricStage) {
        dispatchEvent(CockpitSystemAlertEvent.BiometricFeedback(stage = stage))
        if (stage == CockpitBiometricStage.ERROR) {
            flashController?.trigger(CockpitFlashPattern.URGENT_ALERT)
        }
    }

    fun emitTransfer(fileName: String, progressPercent: Int, isCompleted: Boolean) {
        dispatchEvent(
            CockpitSystemAlertEvent.DriveLiftTransfer(
                fileName = fileName,
                progressPercent = progressPercent,
                isCompleted = isCompleted,
            ),
        )
        if (isCompleted) {
            flashController?.trigger(CockpitFlashPattern.TRANSFER_DONE)
        }
    }

    fun emitRadarConnected(peerName: String) {
        dispatchEvent(CockpitSystemAlertEvent.DriveLiftRadarConnected(peerName = peerName))
    }

    fun dismissCurrent() {
        synchronized(lock) {
            val next = reducer.dismissCurrent()
            _activeAlert.value = next
            scheduleExpiryLocked(next)
        }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (level < 0) return
                val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged != 0
                val isFull = status == BatteryManager.BATTERY_STATUS_FULL
                val fastCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC
                dispatchEvent(
                    CockpitSystemAlertEvent.BatteryChanged(
                        percent = percent,
                        isCharging = isCharging,
                        isFastCharging = fastCharging,
                        isStatusFull = isFull,
                    ),
                )
            }

            Intent.ACTION_POWER_CONNECTED -> {
                val batteryInfo = queryStickyBatteryPercent()
                dispatchEvent(
                    CockpitSystemAlertEvent.BatteryChanged(
                        percent = batteryInfo.first,
                        isCharging = true,
                        isFastCharging = batteryInfo.second,
                    ),
                )
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                val batteryInfo = queryStickyBatteryPercent()
                dispatchEvent(
                    CockpitSystemAlertEvent.BatteryChanged(
                        percent = batteryInfo.first,
                        isCharging = false,
                    ),
                )
            }

            Intent.ACTION_BATTERY_LOW -> {
                val batteryInfo = queryStickyBatteryPercent()
                dispatchEvent(
                    CockpitSystemAlertEvent.BatteryChanged(
                        percent = batteryInfo.first.coerceAtMost(CockpitSystemAlertReducer.BATTERY_LOW_THRESHOLD),
                        isCharging = false,
                    ),
                )
            }

            AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                val modeInt = intent.getIntExtra(
                    AudioManager.EXTRA_RINGER_MODE,
                    AudioManager.RINGER_MODE_NORMAL,
                )
                val mode = when (modeInt) {
                    AudioManager.RINGER_MODE_SILENT -> CockpitRingerMode.SILENT
                    AudioManager.RINGER_MODE_VIBRATE -> CockpitRingerMode.VIBRATE
                    else -> CockpitRingerMode.NORMAL
                }
                dispatchEvent(CockpitSystemAlertEvent.RingerModeChanged(mode))
            }

            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val enabled = intent.getBooleanExtra(
                    "state",
                    Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON,
                        0,
                    ) != 0,
                )
                dispatchEvent(CockpitSystemAlertEvent.AirplaneModeChanged(enabled))
            }

            Intent.ACTION_USER_PRESENT -> {
                emitBiometric(CockpitBiometricStage.DEVICE_UNLOCKED)
            }

            CockpitEarbudsBatteryParser.ACTION_BATTERY_LEVEL_CHANGED,
            CockpitEarbudsBatteryParser.ACTION_HEADSET_CONNECTION_STATE_CHANGED,
            CockpitEarbudsBatteryParser.ACTION_A2DP_CONNECTION_STATE_CHANGED -> {
                if (!hasBluetoothConnectPermission()) return
                val parsed = try {
                    CockpitEarbudsBatteryParser.fromIntent(intent)
                } catch (_: SecurityException) {
                    null
                } ?: return

                when (parsed) {
                    is ParsedBluetoothEvent.ConnectionState -> {
                        dispatchEvent(
                            CockpitSystemAlertEvent.EarbudsConnectionChanged(
                                deviceKey = parsed.deviceKey,
                                deviceName = parsed.deviceName,
                                isConnected = parsed.isConnected,
                            ),
                        )
                    }
                    is ParsedBluetoothEvent.BatteryLevel -> {
                        val percent = parsed.batteryPercent ?: return
                        dispatchEvent(
                            CockpitSystemAlertEvent.EarbudsBatteryChanged(
                                deviceKey = parsed.deviceKey,
                                deviceName = parsed.deviceName,
                                batteryPercent = percent,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun queryStickyBatteryPercent(): Pair<Int, Boolean> {
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50
        val scale = (sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        return percent to (plugged == BatteryManager.BATTERY_PLUGGED_AC)
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun dispatchEvent(event: CockpitSystemAlertEvent) {
        val result = synchronized(lock) {
            val reduced = reducer.reduce(event)
            _activeAlert.value = reduced.activeAlert
            scheduleExpiryLocked(reduced.activeAlert)
            reduced
        }
        result.emittedAlert?.let { _events.tryEmit(it) }
    }

    private fun scheduleExpiryLocked(alert: CockpitSystemAlert?) {
        expiryJob?.cancel()
        if (alert == null) {
            expiryJob = null
            return
        }
        val waitMs = (alert.expiresAtMs - clockMs()).coerceAtLeast(0L)
        expiryJob = scope.launch {
            delay(waitMs)
            synchronized(lock) {
                val next = reducer.expireIfNeeded()
                _activeAlert.value = next
                scheduleExpiryLocked(next)
            }
        }
    }
}
