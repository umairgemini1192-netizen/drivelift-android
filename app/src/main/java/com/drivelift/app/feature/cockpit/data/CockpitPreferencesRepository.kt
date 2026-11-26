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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Uses an app-private synchronous mirror for `master_enabled` and `pinned_packages_ordered` so
 * cold-started services and widget callbacks never observe stale empty defaults before DataStore
 * finishes loading on IO.
 * Never touches Room `AppDatabase`.
 */
class CockpitPreferencesRepository(
    context: Context,
    produceFile: () -> File = { context.applicationContext.preferencesDataStoreFile(STORE_NAME) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onPinnedPackagesSynced: ((List<String>) -> Unit)? = null,
) {
    private val mirrorPrefs = context.applicationContext.getSharedPreferences(
        SYNC_MIRROR_PREFS,
        Context.MODE_PRIVATE,
    )

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = produceFile,
        scope = scope,
    )

    @Volatile
    private var explicitMasterWrite: Boolean? = null

    @Volatile
    private var explicitPinnedWrite: List<String>? = null

    private val _state = MutableStateFlow(
        CockpitPrefsState(
            masterEnabled = mirrorPrefs.getBoolean(KEY_MIRROR_MASTER_ENABLED, false),
            pinnedPackages = readMirroredPinnedPackages(),
        ),
    )
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
                    val overrideMaster = explicitMasterWrite
                    val resolvedMaster = overrideMaster ?: loaded.masterEnabled
                    val overridePinned = explicitPinnedWrite
                    val resolvedPinned = overridePinned ?: loaded.pinnedPackages
                    val previousPinned = _state.value.pinnedPackages
                    mirrorPrefs.edit()
                        .putBoolean(KEY_MIRROR_MASTER_ENABLED, resolvedMaster)
                        .putString(KEY_MIRROR_PINNED_PACKAGES, resolvedPinned.joinToString(PINNED_DELIMITER))
                        .apply()
                    _state.value = loaded.copy(
                        masterEnabled = resolvedMaster,
                        pinnedPackages = resolvedPinned,
                    )
                    if (resolvedPinned != previousPinned) {
                        onPinnedPackagesSynced?.invoke(resolvedPinned)
                    }
                }
        }
    }

    /**
     * Reads the persisted `masterEnabled` flag synchronously or from DataStore so widget actions
     * on cold start always toggle against the true stored state.
     */
    suspend fun readCurrentMasterEnabled(): Boolean {
        explicitMasterWrite?.let { return it }
        if (mirrorPrefs.contains(KEY_MIRROR_MASTER_ENABLED)) {
            return mirrorPrefs.getBoolean(KEY_MIRROR_MASTER_ENABLED, false)
        }
        val persisted = runCatching {
            dataStore.data.first()[KEY_MASTER_ENABLED] ?: false
        }.getOrDefault(false)
        mirrorPrefs.edit().putBoolean(KEY_MIRROR_MASTER_ENABLED, persisted).apply()
        return persisted
    }

    fun isMasterEnabledMirrored(): Boolean =
        explicitMasterWrite ?: mirrorPrefs.getBoolean(KEY_MIRROR_MASTER_ENABLED, _state.value.masterEnabled)

    suspend fun setMasterEnabled(enabled: Boolean) {
        explicitMasterWrite = enabled
        mirrorPrefs.edit().putBoolean(KEY_MIRROR_MASTER_ENABLED, enabled).commit()
        _state.value = _state.value.copy(masterEnabled = enabled)
        dataStore.edit { prefs -> prefs[KEY_MASTER_ENABLED] = enabled }
    }

    fun setMasterEnabledAsync(enabled: Boolean) {
        explicitMasterWrite = enabled
        mirrorPrefs.edit().putBoolean(KEY_MIRROR_MASTER_ENABLED, enabled).apply()
        _state.value = _state.value.copy(masterEnabled = enabled)
        scope.launch {
            dataStore.edit { prefs -> prefs[KEY_MASTER_ENABLED] = enabled }
        }
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

    fun readPinnedPackagesForWidget(timeoutMs: Long = 250L): List<String> {
        explicitPinnedWrite?.let { return it }
        val current = _state.value.pinnedPackages
        if (current.isNotEmpty()) return current
        if (mirrorPrefs.contains(KEY_MIRROR_PINNED_PACKAGES)) {
            return readMirroredPinnedPackages()
        }
        val loaded = runCatching {
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    withContext(Dispatchers.IO) {
                        parsePinnedPackages(dataStore.data.first()[KEY_PINNED_PACKAGES])
                    }
                }
            }
        }.getOrNull() ?: return emptyList()
        mirrorPrefs.edit()
            .putString(KEY_MIRROR_PINNED_PACKAGES, loaded.joinToString(PINNED_DELIMITER))
            .apply()
        if (explicitPinnedWrite == null && _state.value.pinnedPackages != loaded) {
            _state.value = _state.value.copy(pinnedPackages = loaded)
        }
        return loaded
    }

    suspend fun setPinnedPackages(packages: List<String>) {
        val normalized = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PINNED_APPS)
        explicitPinnedWrite = normalized
        mirrorPrefs.edit()
            .putString(KEY_MIRROR_PINNED_PACKAGES, normalized.joinToString(PINNED_DELIMITER))
            .commit()
        _state.value = _state.value.copy(pinnedPackages = normalized)
        dataStore.edit { prefs ->
            prefs[KEY_PINNED_PACKAGES] = normalized.joinToString(PINNED_DELIMITER)
        }
    }

    fun seedForTest(state: CockpitPrefsState) {
        explicitMasterWrite = state.masterEnabled
        explicitPinnedWrite = state.pinnedPackages
        mirrorPrefs.edit()
            .putBoolean(KEY_MIRROR_MASTER_ENABLED, state.masterEnabled)
            .putString(KEY_MIRROR_PINNED_PACKAGES, state.pinnedPackages.joinToString(PINNED_DELIMITER))
            .commit()
        _state.value = state
    }

    private fun readMirroredPinnedPackages(): List<String> =
        parsePinnedPackages(mirrorPrefs.getString(KEY_MIRROR_PINNED_PACKAGES, null))

    private fun parsePinnedPackages(raw: String?): List<String> = raw
        .orEmpty()
        .split(PINNED_DELIMITER)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_PINNED_APPS)

    private fun fromPreferences(prefs: Preferences): CockpitPrefsState {
        val pinned = if (prefs.contains(KEY_PINNED_PACKAGES)) {
            parsePinnedPackages(prefs[KEY_PINNED_PACKAGES])
        } else {
            readMirroredPinnedPackages()
        }
        val storedMaster = if (prefs.contains(KEY_MASTER_ENABLED)) {
            prefs[KEY_MASTER_ENABLED] ?: false
        } else {
            mirrorPrefs.getBoolean(KEY_MIRROR_MASTER_ENABLED, false)
        }
        return CockpitPrefsState(
            masterEnabled = storedMaster,
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
        private const val SYNC_MIRROR_PREFS = "cockpit_prefs_sync"
        private const val KEY_MIRROR_MASTER_ENABLED = "master_enabled"
        private const val KEY_MIRROR_PINNED_PACKAGES = "pinned_packages_ordered"
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
