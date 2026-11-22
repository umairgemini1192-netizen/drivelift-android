package com.drivelift.app.feature.cockpit.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitEarbudsBatteryParserTest {

    @Test
    fun parsesValidBatteryRangeZeroToHundred() {
        val zero = CockpitEarbudsBatteryParser.parse(
            action = CockpitEarbudsBatteryParser.ACTION_BATTERY_LEVEL_CHANGED,
            deviceName = "AirPods",
            deviceAddress = "11:22:33:44",
            batteryLevelExtra = 0,
            connectionStateExtra = -1,
        ) as ParsedBluetoothEvent.BatteryLevel
        assertEquals(0, zero.batteryPercent)
        assertTrue(zero.isLowBattery)
        assertEquals("AirPods", zero.deviceName)

        val hundred = CockpitEarbudsBatteryParser.parse(
            action = CockpitEarbudsBatteryParser.ACTION_BATTERY_LEVEL_CHANGED,
            deviceName = "AirPods",
            deviceAddress = "11:22:33:44",
            batteryLevelExtra = 100,
            connectionStateExtra = -1,
        ) as ParsedBluetoothEvent.BatteryLevel
        assertEquals(100, hundred.batteryPercent)
        assertFalse(hundred.isLowBattery)
    }

    @Test
    fun filtersUnknownAndOutOfRangeBatteryValuesToNull() {
        for (invalid in listOf(-1, -42, 101, 255)) {
            val event = CockpitEarbudsBatteryParser.parse(
                action = CockpitEarbudsBatteryParser.ACTION_BATTERY_LEVEL_CHANGED,
                deviceName = "Buds",
                deviceAddress = "AA:BB",
                batteryLevelExtra = invalid,
                connectionStateExtra = -1,
            ) as ParsedBluetoothEvent.BatteryLevel
            assertNull("Expected null for $invalid", event.batteryPercent)
            assertFalse(event.isLowBattery)
        }
    }

    @Test
    fun fallsBackToBluetoothAudioWhenDeviceNameMissingOrBlank() {
        val nullName = CockpitEarbudsBatteryParser.parse(
            action = CockpitEarbudsBatteryParser.ACTION_HEADSET_CONNECTION_STATE_CHANGED,
            deviceName = null,
            deviceAddress = "AA:BB:CC",
            batteryLevelExtra = -1,
            connectionStateExtra = CockpitEarbudsBatteryParser.STATE_CONNECTED,
        ) as ParsedBluetoothEvent.ConnectionState
        assertEquals(CockpitEarbudsBatteryParser.FALLBACK_DEVICE_NAME, nullName.deviceName)
        assertTrue(nullName.isConnected)

        val blankName = CockpitEarbudsBatteryParser.parse(
            action = CockpitEarbudsBatteryParser.ACTION_A2DP_CONNECTION_STATE_CHANGED,
            deviceName = "   ",
            deviceAddress = "",
            batteryLevelExtra = -1,
            connectionStateExtra = CockpitEarbudsBatteryParser.STATE_DISCONNECTED,
        ) as ParsedBluetoothEvent.ConnectionState
        assertEquals(CockpitEarbudsBatteryParser.FALLBACK_DEVICE_NAME, blankName.deviceName)
        assertEquals(CockpitEarbudsBatteryParser.FALLBACK_DEVICE_NAME, blankName.deviceKey)
        assertFalse(blankName.isConnected)
    }

    @Test
    fun classifiesLowBatteryThresholdAtTwentyPercent() {
        assertTrue(CockpitEarbudsBatteryParser.isLowBattery(20))
        assertTrue(CockpitEarbudsBatteryParser.isLowBattery(1))
        assertFalse(CockpitEarbudsBatteryParser.isLowBattery(21))
        assertFalse(CockpitEarbudsBatteryParser.isLowBattery(null))
    }

    @Test
    fun ignoresConnectingAndDisconnectingIntermediateStates() {
        assertNull(
            CockpitEarbudsBatteryParser.parse(
                action = CockpitEarbudsBatteryParser.ACTION_A2DP_CONNECTION_STATE_CHANGED,
                deviceName = "Sony WH-1000XM5",
                deviceAddress = "00:11:22",
                batteryLevelExtra = -1,
                connectionStateExtra = 1,
            ),
        )
        assertNull(
            CockpitEarbudsBatteryParser.parse(
                action = "com.example.UNKNOWN_ACTION",
                deviceName = "Sony WH-1000XM5",
                deviceAddress = "00:11:22",
                batteryLevelExtra = 50,
                connectionStateExtra = 2,
            ),
        )
    }
}
