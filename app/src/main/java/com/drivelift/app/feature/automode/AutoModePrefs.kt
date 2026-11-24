package com.drivelift.app.feature.automode

import android.content.Context
import com.drivelift.app.feature.hibernation.engine.HibernationMode

/**
 * Persistence contract for Auto Mode setup flags and stored [HibernationMode] migration.
 * Never persists pairing codes.
 */
interface AutoModePreferences {
    var isEnabled: Boolean
    var isPaired: Boolean
    var isBootstrapComplete: Boolean
    var storedHibernationMode: HibernationMode
    fun clearAutoModeState()
}

/**
 * App-private SharedPreferences store for Built-in Auto Mode.
 * Migrates any legacy stored "SHIZUKU_SILENT" value transparently to [HibernationMode.AUTO_SILENT].
 */
class SharedPrefsAutoModePreferences(
    context: Context,
) : AutoModePreferences {

    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyHibernationMode()
    }

    override var isEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) {
            sp.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    override var isPaired: Boolean
        get() = sp.getBoolean(KEY_PAIRED, false)
        set(value) {
            sp.edit().putBoolean(KEY_PAIRED, value).apply()
        }

    override var isBootstrapComplete: Boolean
        get() = sp.getBoolean(KEY_BOOTSTRAP_COMPLETE, false)
        set(value) {
            sp.edit().putBoolean(KEY_BOOTSTRAP_COMPLETE, value).apply()
        }

    override var storedHibernationMode: HibernationMode
        get() = HibernationMode.fromStored(sp.getString(KEY_HIBERNATION_MODE, null))
        set(value) {
            sp.edit().putString(KEY_HIBERNATION_MODE, value.name).apply()
        }

    override fun clearAutoModeState() {
        sp.edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_PAIRED, false)
            .putBoolean(KEY_BOOTSTRAP_COMPLETE, false)
            .putString(KEY_HIBERNATION_MODE, HibernationMode.GUIDED_PILL.name)
            .apply()
    }

    private fun migrateLegacyHibernationMode() {
        val raw = sp.getString(KEY_HIBERNATION_MODE, null) ?: return
        if (raw == LEGACY_SHIZUKU_MODE) {
            sp.edit().putString(KEY_HIBERNATION_MODE, HibernationMode.AUTO_SILENT.name).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "drivelift_auto_mode"
        const val KEY_ENABLED = "auto_mode_enabled"
        const val KEY_PAIRED = "auto_mode_paired"
        const val KEY_BOOTSTRAP_COMPLETE = "auto_mode_bootstrap_complete"
        const val KEY_HIBERNATION_MODE = "hibernation_mode"
        const val LEGACY_SHIZUKU_MODE = "SHIZUKU_SILENT"
    }
}
