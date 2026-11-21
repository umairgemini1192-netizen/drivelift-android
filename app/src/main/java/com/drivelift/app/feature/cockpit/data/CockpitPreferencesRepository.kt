package com.drivelift.app.feature.cockpit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

enum class CockpitActivityCategory {
    TRANSFERS_AND_DROP,
    TIMERS_AND_RECORDING,
    MAPS_NAVIGATION,
    WHATSAPP_ALERTS,
}

data class CockpitPrefsState(
    val masterEnabled: Boolean = false,
    val transfersEnabled: Boolean = true,
    val timersEnabled: Boolean = true,
    val mapsEnabled: Boolean = true,
    val whatsappEnabled: Boolean = true,
    val cutoutOffsetX: Int = 0,
    val cutoutOffsetY: Int = 0,
    val cutoutWidthDelta: Int = 0,
    val pinnedPackages: List<String> = emptyList(),
) {
    fun isCategoryEnabled(category: CockpitActivityCategory): Boolean = when (category) {
        CockpitActivityCategory.TRANSFERS_AND_DROP -> transfersEnabled
        CockpitActivityCategory.TIMERS_AND_RECORDING -> timersEnabled
        CockpitActivityCategory.MAPS_NAVIGATION -> mapsEnabled
        CockpitActivityCategory.WHATSAPP_ALERTS -> whatsappEnabled
    }
}

/**
 * Isolated Preferences DataStore (`cockpit_prefs`) for all Cockpit settings, activity toggles,
 * punch-hole cutout calibration offsets, and ordered pinned launcher package names.
 * Never touches Room `AppDatabase`.
 */
class CockpitPreferencesRepository(
    context: Context,
    produceFile: () -> File = { context.applicationContext.preferencesDataStoreFile(STORE_NAME) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = produceFile,
        scope = scope,
    )

    private val _state = MutableStateFlow(CockpitPrefsState())
    val state: StateFlow<CockpitPrefsState> = _state.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { error ->
                    if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
                    else throw error
                }
                .map(::fromPreferences)
                .collect { loaded ->
                    _state.value = loaded
                }
        }
    }

    suspend fun setMasterEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(masterEnabled = enabled)
        dataStore.edit { prefs -> prefs[KEY_MASTER_ENABLED] = enabled }
    }

    fun setMasterEnabledAsync(enabled: Boolean) {
        scope.launch { setMasterEnabled(enabled) }
    }

    suspend fun setActivityEnabled(category: CockpitActivityCategory, enabled: Boolean) {
        val updated = when (category) {
            CockpitActivityCategory.TRANSFERS_AND_DROP -> _state.value.copy(transfersEnabled = enabled)
            CockpitActivityCategory.TIMERS_AND_RECORDING -> _state.value.copy(timersEnabled = enabled)
            CockpitActivityCategory.MAPS_NAVIGATION -> _state.value.copy(mapsEnabled = enabled)
            CockpitActivityCategory.WHATSAPP_ALERTS -> _state.value.copy(whatsappEnabled = enabled)
        }
        _state.value = updated
        dataStore.edit { prefs ->
            when (category) {
                CockpitActivityCategory.TRANSFERS_AND_DROP -> prefs[KEY_TRANSFERS_ENABLED] = enabled
                CockpitActivityCategory.TIMERS_AND_RECORDING -> prefs[KEY_TIMERS_ENABLED] = enabled
                CockpitActivityCategory.MAPS_NAVIGATION -> prefs[KEY_MAPS_ENABLED] = enabled
                CockpitActivityCategory.WHATSAPP_ALERTS -> prefs[KEY_WHATSAPP_ENABLED] = enabled
            }
        }
    }

    fun setActivityEnabledAsync(category: CockpitActivityCategory, enabled: Boolean) {
        scope.launch { setActivityEnabled(category, enabled) }
    }

    suspend fun setCutoutCalibration(offsetX: Int, offsetY: Int, widthDelta: Int) {
        val clampedX = offsetX.coerceIn(MIN_OFFSET_X, MAX_OFFSET_X)
        val clampedY = offsetY.coerceIn(MIN_OFFSET_Y, MAX_OFFSET_Y)
        val clampedWidth = widthDelta.coerceIn(MIN_WIDTH_DELTA, MAX_WIDTH_DELTA)
        _state.value = _state.value.copy(
            cutoutOffsetX = clampedX,
            cutoutOffsetY = clampedY,
            cutoutWidthDelta = clampedWidth,
        )
        dataStore.edit { prefs ->
            prefs[KEY_CUTOUT_OFFSET_X] = clampedX
            prefs[KEY_CUTOUT_OFFSET_Y] = clampedY
            prefs[KEY_CUTOUT_WIDTH_DELTA] = clampedWidth
        }
    }

    fun setCutoutCalibrationAsync(offsetX: Int, offsetY: Int, widthDelta: Int) {
        scope.launch { setCutoutCalibration(offsetX, offsetY, widthDelta) }
    }

    suspend fun togglePinnedPackage(packageName: String) {
        val clean = packageName.trim()
        if (clean.isEmpty()) return
        val current = _state.value.pinnedPackages
        val next = if (clean in current) current.filterNot { it == clean }
        else (current + clean).take(MAX_PINNED_APPS)
        setPinnedPackages(next)
    }

    fun togglePinnedPackageAsync(packageName: String) {
        scope.launch { togglePinnedPackage(packageName) }
    }

    suspend fun setPinnedPackages(packages: List<String>) {
        val normalized = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PINNED_APPS)
        _state.value = _state.value.copy(pinnedPackages = normalized)
        dataStore.edit { prefs ->
            prefs[KEY_PINNED_PACKAGES] = normalized.joinToString(PINNED_DELIMITER)
        }
    }

    fun seedForTest(state: CockpitPrefsState) {
        _state.value = state
    }

    private fun fromPreferences(prefs: Preferences): CockpitPrefsState {
        val rawPinned = prefs[KEY_PINNED_PACKAGES].orEmpty()
        val pinned = rawPinned
            .split(PINNED_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_PINNED_APPS)
        return CockpitPrefsState(
            masterEnabled = prefs[KEY_MASTER_ENABLED] ?: false,
            transfersEnabled = prefs[KEY_TRANSFERS_ENABLED] ?: true,
            timersEnabled = prefs[KEY_TIMERS_ENABLED] ?: true,
            mapsEnabled = prefs[KEY_MAPS_ENABLED] ?: true,
            whatsappEnabled = prefs[KEY_WHATSAPP_ENABLED] ?: true,
            cutoutOffsetX = (prefs[KEY_CUTOUT_OFFSET_X] ?: 0).coerceIn(MIN_OFFSET_X, MAX_OFFSET_X),
            cutoutOffsetY = (prefs[KEY_CUTOUT_OFFSET_Y] ?: 0).coerceIn(MIN_OFFSET_Y, MAX_OFFSET_Y),
            cutoutWidthDelta = (prefs[KEY_CUTOUT_WIDTH_DELTA] ?: 0).coerceIn(MIN_WIDTH_DELTA, MAX_WIDTH_DELTA),
            pinnedPackages = pinned,
        )
    }

    companion object {
        const val STORE_NAME = "cockpit_prefs"
        const val MIN_OFFSET_X = -40
        const val MAX_OFFSET_X = 40
        const val MIN_OFFSET_Y = -24
        const val MAX_OFFSET_Y = 40
        const val MIN_WIDTH_DELTA = -32
        const val MAX_WIDTH_DELTA = 64
        const val MAX_PINNED_APPS = 8

        private const val PINNED_DELIMITER = "\n"

        private val KEY_MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        private val KEY_TRANSFERS_ENABLED = booleanPreferencesKey("transfers_enabled")
        private val KEY_TIMERS_ENABLED = booleanPreferencesKey("timers_enabled")
        private val KEY_MAPS_ENABLED = booleanPreferencesKey("maps_enabled")
        private val KEY_WHATSAPP_ENABLED = booleanPreferencesKey("whatsapp_enabled")
        private val KEY_CUTOUT_OFFSET_X = intPreferencesKey("cutout_offset_x")
        private val KEY_CUTOUT_OFFSET_Y = intPreferencesKey("cutout_offset_y")
        private val KEY_CUTOUT_WIDTH_DELTA = intPreferencesKey("cutout_width_delta")
        private val KEY_PINNED_PACKAGES = stringPreferencesKey("pinned_packages_ordered")
    }
}
