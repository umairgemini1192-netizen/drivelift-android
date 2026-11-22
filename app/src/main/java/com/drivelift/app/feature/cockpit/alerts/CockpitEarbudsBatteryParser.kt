package com.drivelift.app.feature.cockpit.alerts

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build

sealed interface ParsedBluetoothEvent {
    val deviceKey: String
    val deviceName: String

    data class BatteryLevel(
        override val deviceKey: String,
        override val deviceName: String,
        val batteryPercent: Int?,
        val isLowBattery: Boolean,
    ) : ParsedBluetoothEvent

    data class ConnectionState(
        override val deviceKey: String,
        override val deviceName: String,
        val isConnected: Boolean,
    ) : ParsedBluetoothEvent
}

/**
 * Pure parser for Bluetooth device battery and profile connection broadcasts.
 */
object CockpitEarbudsBatteryParser {
    const val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BATTERY_LEVEL =
        "android.bluetooth.device.extra.BATTERY_LEVEL"

    const val ACTION_HEADSET_CONNECTION_STATE_CHANGED =
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
    const val ACTION_A2DP_CONNECTION_STATE_CHANGED =
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
    const val EXTRA_STATE =
        "android.bluetooth.profile.extra.STATE"

    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTED = 2

    const val FALLBACK_DEVICE_NAME = "Bluetooth Audio"
    const val LOW_BATTERY_THRESHOLD_PERCENT = 20

    fun sanitizeDeviceName(rawName: String?): String =
        rawName?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_DEVICE_NAME

    fun sanitizeBatteryPercent(rawLevel: Int?): Int? =
        rawLevel?.takeIf { it in 0..100 }

    fun isLowBattery(batteryPercent: Int?): Boolean =
        batteryPercent != null && batteryPercent in 0..LOW_BATTERY_THRESHOLD_PERCENT

    fun parse(
        action: String?,
        deviceName: String?,
        deviceAddress: String?,
        batteryLevelExtra: Int,
        connectionStateExtra: Int,
    ): ParsedBluetoothEvent? {
        val cleanName = sanitizeDeviceName(deviceName)
        val key = deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: cleanName

        return when (action) {
            ACTION_BATTERY_LEVEL_CHANGED -> {
                val battery = sanitizeBatteryPercent(batteryLevelExtra)
                ParsedBluetoothEvent.BatteryLevel(
                    deviceKey = key,
                    deviceName = cleanName,
                    batteryPercent = battery,
                    isLowBattery = isLowBattery(battery),
                )
            }
            ACTION_HEADSET_CONNECTION_STATE_CHANGED,
            ACTION_A2DP_CONNECTION_STATE_CHANGED -> {
                when (connectionStateExtra) {
                    STATE_CONNECTED -> ParsedBluetoothEvent.ConnectionState(
                        deviceKey = key,
                        deviceName = cleanName,
                        isConnected = true,
                    )
                    STATE_DISCONNECTED -> ParsedBluetoothEvent.ConnectionState(
                        deviceKey = key,
                        deviceName = cleanName,
                        isConnected = false,
                    )
                    else -> null
                }
            }
            else -> null
        }
    }

    fun fromIntent(intent: Intent): ParsedBluetoothEvent? {
        val device = extractBluetoothDevice(intent)
        val rawName = runCatching { device?.name }.getOrNull()
        val rawAddress = runCatching { device?.address }.getOrNull()
        val batteryExtra = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        val stateExtra = intent.getIntExtra(EXTRA_STATE, -1)
        return parse(
            action = intent.action,
            deviceName = rawName,
            deviceAddress = rawAddress,
            batteryLevelExtra = batteryExtra,
            connectionStateExtra = stateExtra,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
