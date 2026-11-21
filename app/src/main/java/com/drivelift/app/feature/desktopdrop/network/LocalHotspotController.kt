package com.drivelift.app.feature.desktopdrop.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.drivelift.app.feature.desktopdrop.model.HotspotCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages Android's LocalOnlyHotspot lifecycle for direct PC-to-Pixel transfers without a router
 * or on restricted corporate Wi-Fi networks.
 */
class LocalHotspotController(private val context: Context) {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missingPermissions(): List<String> = requiredPermissions().filter { perm ->
        ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun startHotspot(): HotspotCredentials {
        stopHotspot()
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            throw SecurityException("Permission needed for Direct Hotspot: ${missing.joinToString()}")
        }
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: throw IllegalStateException("Wi-Fi hardware is unavailable on this device")

        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        val (ssid, pass) = extractSsidAndPassphrase(res)
                        val payload = buildWifiQrPayload(ssid, pass)
                        if (cont.isActive) {
                            cont.resume(HotspotCredentials(ssid = ssid, passphrase = pass, wifiQrPayload = payload))
                        } else {
                            res.close()
                        }
                    }

                    override fun onStopped() {
                        reservation = null
                    }

                    override fun onFailed(reason: Int) {
                        reservation = null
                        if (cont.isActive) {
                            val msg = when (reason) {
                                ERROR_NO_CHANNEL -> "No free Wi-Fi hotspot channel right now"
                                ERROR_TETHERING_DISALLOWED -> "Hotspot is restricted by device policy"
                                ERROR_INCOMPATIBLE_MODE -> "Turn off other tethering or hotspot before starting Direct Hotspot"
                                else -> "Could not start Direct Hotspot (code $reason)"
                            }
                            cont.resumeWithException(IllegalStateException(msg))
                        }
                    }
                },
                handler,
            )
            cont.invokeOnCancellation { stopHotspot() }
        }
    }

    fun stopHotspot() {
        val current = reservation
        reservation = null
        runCatching { current?.close() }
    }

    private fun extractSsidAndPassphrase(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            val cfg = res.softApConfiguration
            val ssid = cfg.ssid.orEmpty().trim('"')
            val pass = cfg.passphrase.orEmpty().trim('"')
            ssid to pass
        } else {
            @Suppress("DEPRECATION")
            val cfg = res.wifiConfiguration
            val ssid = cfg?.SSID.orEmpty().trim('"')
            val pass = cfg?.preSharedKey.orEmpty().trim('"')
            ssid to pass
        }
    }

    companion object {
        fun escapeWifiField(value: String): String = buildString(value.length + 4) {
            for (ch in value) {
                if (ch == '\\' || ch == ';' || ch == ',' || ch == '"' || ch == ':') append('\\')
                append(ch)
            }
        }

        fun buildWifiQrPayload(ssid: String, passphrase: String): String {
            val cleanSsid = escapeWifiField(ssid)
            val cleanPass = escapeWifiField(passphrase)
            return if (cleanPass.isEmpty()) {
                "WIFI:T:nopass;S:$cleanSsid;;"
            } else {
                "WIFI:T:WPA;S:$cleanSsid;P:$cleanPass;;"
            }
        }
    }
}
