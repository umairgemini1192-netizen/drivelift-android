package com.drivelift.app.feature.desktopdrop.data

import android.content.Context
import android.content.SharedPreferences
import com.drivelift.app.feature.desktopdrop.model.BiometricGateMode
import com.drivelift.app.feature.desktopdrop.model.DesktopDropPrefsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Isolated preferences file for Desktop Drop settings so the core AppDatabase and Prefs store
 * remain untouched.
 */
class DesktopDropPrefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<DesktopDropPrefsState> = _state.asStateFlow()

    val current: DesktopDropPrefsState get() = _state.value

    private fun readState(): DesktopDropPrefsState = DesktopDropPrefsState(
        preferredPort = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535),
        gateMode = readGateMode(),
        idleTimeoutMinutes = prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES).coerceIn(1, 240),
        destinationTreeUri = prefs.getString(KEY_DEST_URI, null),
        destinationLabel = prefs.getString(KEY_DEST_LABEL, DEFAULT_DEST_LABEL) ?: DEFAULT_DEST_LABEL,
    )

    fun setPreferredPort(port: Int) {
        val clamped = port.coerceIn(1024, 65535)
        prefs.edit().putInt(KEY_PORT, clamped).apply()
        _state.value = _state.value.copy(preferredPort = clamped)
    }

    private fun readGateMode(): BiometricGateMode =
        BiometricGateMode.entries.firstOrNull { it.name == prefs.getString(KEY_GATE_MODE, null) }
            ?: BiometricGateMode.APPROVE_EVERY_START

    fun setGateMode(mode: BiometricGateMode) {
        prefs.edit().putString(KEY_GATE_MODE, mode.name).apply()
        _state.value = _state.value.copy(gateMode = mode)
    }

    fun setIdleTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 240)
        prefs.edit().putInt(KEY_IDLE_MINUTES, clamped).apply()
        _state.value = _state.value.copy(idleTimeoutMinutes = clamped)
    }

    fun setDestination(treeUri: String?, label: String) {
        val cleanLabel = label.ifBlank { DEFAULT_DEST_LABEL }
        prefs.edit()
            .putString(KEY_DEST_URI, treeUri)
            .putString(KEY_DEST_LABEL, cleanLabel)
            .apply()
        _state.value = _state.value.copy(destinationTreeUri = treeUri, destinationLabel = cleanLabel)
    }

    fun resetDestination() {
        prefs.edit()
            .remove(KEY_DEST_URI)
            .putString(KEY_DEST_LABEL, DEFAULT_DEST_LABEL)
            .apply()
        _state.value = _state.value.copy(destinationTreeUri = null, destinationLabel = DEFAULT_DEST_LABEL)
    }

    companion object {
        private const val PREFS_NAME = "drivelift_desktop_drop_prefs"
        private const val KEY_PORT = "preferred_port"
        private const val KEY_GATE_MODE = "biometric_gate_mode"
        private const val KEY_IDLE_MINUTES = "idle_timeout_minutes"
        private const val KEY_DEST_URI = "destination_tree_uri"
        private const val KEY_DEST_LABEL = "destination_label"

        const val DEFAULT_PORT = 8080
        const val DEFAULT_IDLE_MINUTES = 15
        const val DEFAULT_DEST_LABEL = "Downloads/DriveLift Drop"
    }
}
