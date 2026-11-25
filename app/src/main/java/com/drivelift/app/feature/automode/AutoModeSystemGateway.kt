package com.drivelift.app.feature.automode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Abstraction over Android system settings, Wi-Fi state, and permission checks required by
 * Built-in Auto Mode so all orchestration logic stays unit-testable on the JVM.
 */
interface AutoModeSystemGateway {
    fun isDeveloperOptionsEnabled(): Boolean
    fun isOnWifi(): Boolean
    fun hasWriteSecureSettings(): Boolean
    fun isAdbWifiEnabled(): Boolean
    fun setAdbWifiEnabled(enabled: Boolean): Boolean
    fun getAdbAllowedConnectionTime(): Long?
    fun setAdbAllowedConnectionTime(value: Long): Boolean
    fun applicationId(): String
    fun notificationAccessState(): NotificationAccessState = NotificationAccessState.ALLOWED
}

/**
 * Production Android gateway reading/writing Settings.Global and ConnectivityManager safely.
 */
class AndroidAutoModeSystemGateway(
    private val context: Context,
) : AutoModeSystemGateway {

    override fun isDeveloperOptionsEnabled(): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1
    }.getOrDefault(false)

    override fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    override fun hasWriteSecureSettings(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun isAdbWifiEnabled(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING_ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    override fun setAdbWifiEnabled(enabled: Boolean): Boolean = runCatching {
        if (!hasWriteSecureSettings()) return false
        Settings.Global.putInt(
            context.contentResolver,
            SETTING_ADB_WIFI_ENABLED,
            if (enabled) 1 else 0,
        )
    }.getOrDefault(false)

    override fun getAdbAllowedConnectionTime(): Long? = runCatching {
        Settings.Global.getLong(context.contentResolver, SETTING_ADB_ALLOWED_CONNECTION_TIME)
    }.getOrNull()

    override fun setAdbAllowedConnectionTime(value: Long): Boolean = runCatching {
        if (!hasWriteSecureSettings()) return false
        Settings.Global.putLong(
            context.contentResolver,
            SETTING_ADB_ALLOWED_CONNECTION_TIME,
            value,
        )
    }.getOrDefault(false)

    override fun applicationId(): String = context.packageName

    override fun notificationAccessState(): NotificationAccessState =
        AdbPairingNotifier.checkNotificationAccess(context)

    companion object {
        const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val SETTING_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

        fun createAboutPhoneIntent(): Intent =
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun createDeveloperOptionsIntent(): Intent =
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun createWifiSettingsIntent(): Intent =
            Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

/**
 * Observes `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` while the Auto Mode setup wizard is
 * visible so Step 1 advances automatically the moment the "Use developer options" master switch
 * is turned on.
 */
class DeveloperOptionsObserver(
    private val context: Context,
    private val onChanged: () -> Unit,
    handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var registered = false

    internal val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onChanged()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onChanged()
        }
    }

    fun register() {
        if (registered) return
        val uri = Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        runCatching {
            context.contentResolver.registerContentObserver(uri, false, contentObserver)
            registered = true
        }
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching {
            context.contentResolver.unregisterContentObserver(contentObserver)
        }
    }
}
