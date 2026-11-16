package com.drivelift.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("drivelift", Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(sp.getBoolean(KEY_WIFI, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    private val _autoRemove = MutableStateFlow(sp.getBoolean(KEY_AUTO_REMOVE, true))
    val autoRemove: StateFlow<Boolean> = _autoRemove

    private val _paused = MutableStateFlow(sp.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused

    /** Email of the connected Drive account. Only used to pin background token requests. */
    var accountEmail: String?
        get() = sp.getString(KEY_ACCOUNT, null)
        set(value) = sp.edit().putString(KEY_ACCOUNT, value).apply()

    fun setWifiOnly(value: Boolean) {
        sp.edit().putBoolean(KEY_WIFI, value).apply()
        _wifiOnly.value = value
    }

    fun setAutoRemove(value: Boolean) {
        sp.edit().putBoolean(KEY_AUTO_REMOVE, value).apply()
        _autoRemove.value = value
    }

    fun setPaused(value: Boolean) {
        sp.edit().putBoolean(KEY_PAUSED, value).apply()
        _paused.value = value
    }

    private companion object {
        const val KEY_WIFI = "wifi_only"
        const val KEY_AUTO_REMOVE = "auto_remove"
        const val KEY_PAUSED = "paused"
        const val KEY_ACCOUNT = "account_email"
    }
}
