package com.drivelift.app.feature.desktopdrop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.drivelift.app.feature.desktopdrop.server.SessionAuthManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Resolves the active local Wi-Fi or SoftAP IPv4 address (excluding VPN tunnels and cellular
 * interfaces) and optionally advertises the server over mDNS (_http._tcp.).
 */
class LanNetworkMonitor(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun resolveLanIpv4(preferHotspotAp: Boolean = false): String? {
        if (!preferHotspotAp) {
            connectivityWifiIpv4()?.let { return it }
        }
        return interfaceIpv4(preferHotspotAp)
    }

    private fun connectivityWifiIpv4(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                if (n != active) add(n)
            }
        }
        for (network in candidates) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val linkProps = cm.getLinkProperties(network) ?: continue
            for (linkAddr in linkProps.linkAddresses) {
                val host = (linkAddr.address as? Inet4Address)?.hostAddress ?: continue
                if (SessionAuthManager.isPrivateLanIp(host, allowLoopback = false)) return host
            }
        }
        return null
    }

    private fun interfaceIpv4(preferHotspotAp: Boolean): String? {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrElse { return null }
        val scored = ArrayList<Pair<Int, String>>()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            val name = iface.name.lowercase()
            if (isExcludedInterface(name)) continue
            for (addr in Collections.list(iface.inetAddresses)) {
                val v4 = addr as? Inet4Address ?: continue
                val host = v4.hostAddress ?: continue
                if (!SessionAuthManager.isPrivateLanIp(host, allowLoopback = false)) continue
                scored += scoreInterface(name, preferHotspotAp) to host
            }
        }
        return scored.maxByOrNull { it.first }?.second
    }

    fun registerMdns(port: Int) {
        unregisterMdns()
        runCatching {
            val manager = context.getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "DriveLift Drop"
                serviceType = "_http._tcp."
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS registered: ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            nsdManager = manager
            registrationListener = listener
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun unregisterMdns() {
        val manager = nsdManager
        val listener = registrationListener
        nsdManager = null
        registrationListener = null
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
    }

    companion object {
        private const val TAG = "LanNetworkMonitor"

        fun isExcludedInterface(name: String): Boolean {
            val lower = name.lowercase()
            return EXCLUDED_PREFIXES.any { lower.startsWith(it) }
        }

        private fun scoreInterface(name: String, preferHotspotAp: Boolean): Int {
            val lower = name.lowercase()
            return if (preferHotspotAp) {
                when {
                    "ap" in lower || "swlan" in lower || "softap" in lower -> 100
                    lower.startsWith("wlan") && lower != "wlan0" -> 90
                    lower.startsWith("wlan") -> 50
                    else -> 20
                }
            } else {
                when {
                    lower == "wlan0" -> 100
                    lower.startsWith("wlan") -> 80
                    lower.startsWith("eth") -> 60
                    else -> 20
                }
            }
        }

        private val EXCLUDED_PREFIXES = listOf("tun", "tap", "ppp", "wg", "rmnet", "ccmni", "v4-", "dummy", "lo")
    }
}
