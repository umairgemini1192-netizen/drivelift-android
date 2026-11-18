package com.drivelift.app.data

import android.content.Context
import com.drivelift.app.drive.AccountPinStore
import com.drivelift.app.ui.GridDensity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Prefs(context: Context) : AccountPinStore {
    private val sp = context.getSharedPreferences("drivelift", Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(sp.getBoolean(KEY_WIFI, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    private val _autoRemove = MutableStateFlow(sp.getBoolean(KEY_AUTO_REMOVE, true))
    val autoRemove: StateFlow<Boolean> = _autoRemove

    private val _paused = MutableStateFlow(sp.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused

    private val _gridDensity = MutableStateFlow(GridDensity.fromStored(sp.getString(KEY_GRID_DENSITY, null)))
    val gridDensity: StateFlow<GridDensity> = _gridDensity

    // Sort choice of Phone folders and of Drive folders, each kept across restarts (issue #36).
    private val _phoneSort = MutableStateFlow(FileSort.fromStored(sp.getString(KEY_PHONE_SORT, null), FileSort.PHONE_DEFAULT))
    val phoneSort: StateFlow<FileSort> = _phoneSort
    private val _driveSort = MutableStateFlow(FileSort.fromStored(sp.getString(KEY_DRIVE_SORT, null), FileSort.DRIVE_DEFAULT))
    val driveSort: StateFlow<FileSort> = _driveSort

    fun setPhoneSort(value: FileSort) {
        sp.edit().putString(KEY_PHONE_SORT, value.name).apply()
        _phoneSort.value = value
    }

    fun setDriveSort(value: FileSort) {
        sp.edit().putString(KEY_DRIVE_SORT, value.name).apply()
        _driveSort.value = value
    }

    /** Email of the connected Drive account. Only used to pin Drive token requests. */
    override var accountEmail: String?
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

    fun setGridDensity(value: GridDensity) {
        sp.edit().putString(KEY_GRID_DENSITY, value.name).apply()
        _gridDensity.value = value
    }

    private companion object {
        const val KEY_WIFI = "wifi_only"
        const val KEY_AUTO_REMOVE = "auto_remove"
        const val KEY_PAUSED = "paused"
        const val KEY_ACCOUNT = "account_email"
        const val KEY_GRID_DENSITY = "grid_density"
        const val KEY_PHONE_SORT = "phone_sort"
        const val KEY_DRIVE_SORT = "drive_sort"
    }
}
