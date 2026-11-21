package com.drivelift.app.feature.cockpit.hardware

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TorchUiState(
    val available: Boolean = false,
    val isOn: Boolean = false,
    val brightnessLevel: Int = 1,
    val maxLevel: Int = 1,
    val supportsBrightnessSlider: Boolean = false,
    val sosActive: Boolean = false,
)

interface TorchHardwareBackend {
    fun findFlashCameraId(): String?
    fun getMaxStrengthLevel(cameraId: String): Int
    fun sdkInt(): Int
    fun setTorchMode(cameraId: String, enabled: Boolean)
    fun turnOnTorchWithStrengthLevel(cameraId: String, strengthLevel: Int)
    fun registerTorchCallback(onTorchChanged: (cameraId: String, enabled: Boolean) -> Unit)
}

class AndroidTorchHardwareBackend(context: Context) : TorchHardwareBackend {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun findFlashCameraId(): String? {
        val manager = cameraManager ?: return null
        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: return null
        var fallbackId: String? = null
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallbackId == null) fallbackId = id
        }
        return fallbackId
    }

    override fun getMaxStrengthLevel(cameraId: String): Int {
        if (Build.VERSION.SDK_INT < 33) return 1
        val manager = cameraManager ?: return 1
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return 1
        return (chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1).coerceAtLeast(1)
    }

    override fun sdkInt(): Int = Build.VERSION.SDK_INT

    override fun setTorchMode(cameraId: String, enabled: Boolean) {
        runCatching {
            cameraManager?.setTorchMode(cameraId, enabled)
        }
    }

    override fun turnOnTorchWithStrengthLevel(cameraId: String, strengthLevel: Int) {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                cameraManager?.turnOnTorchWithStrengthLevel(cameraId, strengthLevel)
            }
        } else {
            setTorchMode(cameraId, true)
        }
    }

    override fun registerTorchCallback(onTorchChanged: (cameraId: String, enabled: Boolean) -> Unit) {
        val manager = cameraManager ?: return
        runCatching {
            manager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        onTorchChanged(cameraId, enabled)
                    }
                },
                mainHandler,
            )
        }
    }
}

/**
 * Hardware Flashlight controller using CameraManager (`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` +
 * `turnOnTorchWithStrengthLevel` on API 33+, requiring no camera permission) with graceful
 * fallback to `setTorchMode` when `maxLevel <= 1` or API < 33, plus an optional SOS strobe loop.
 */
class CockpitTorchController(
    private val backend: TorchHardwareBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val cameraId: String? = backend.findFlashCameraId()
    private val maxLevel: Int = cameraId?.let { backend.getMaxStrengthLevel(it) }?.coerceAtLeast(1) ?: 1
    private val supportsStrength: Boolean =
        cameraId != null && backend.sdkInt() >= 33 && maxLevel > 1

    private val _state = MutableStateFlow(
        TorchUiState(
            available = cameraId != null,
            isOn = false,
            brightnessLevel = maxLevel.coerceAtLeast(1),
            maxLevel = maxLevel,
            supportsBrightnessSlider = supportsStrength,
            sosActive = false,
        ),
    )
    val state: StateFlow<TorchUiState> = _state.asStateFlow()

    private var sosJob: Job? = null

    init {
        if (cameraId != null) {
            backend.registerTorchCallback { changedId, enabled ->
                if (changedId == cameraId && !_state.value.sosActive) {
                    _state.value = _state.value.copy(isOn = enabled)
                }
            }
        }
    }

    fun toggleTorch() {
        setTorch(!_state.value.isOn)
    }

    fun setTorch(enabled: Boolean) {
        val id = cameraId ?: return
        stopSosInternal()
        if (enabled) {
            val level = _state.value.brightnessLevel.coerceIn(1, maxLevel)
            applyHardwareOn(id, level)
            _state.value = _state.value.copy(isOn = true, brightnessLevel = level, sosActive = false)
        } else {
            backend.setTorchMode(id, false)
            _state.value = _state.value.copy(isOn = false, sosActive = false)
        }
    }

    fun setBrightnessLevel(requestedLevel: Int) {
        val id = cameraId ?: return
        val clamped = requestedLevel.coerceIn(1, maxLevel)
        stopSosInternal()
        applyHardwareOn(id, clamped)
        _state.value = _state.value.copy(isOn = true, brightnessLevel = clamped, sosActive = false)
    }

    fun toggleSos() {
        setSosEnabled(!_state.value.sosActive)
    }

    fun setSosEnabled(enabled: Boolean) {
        val id = cameraId ?: return
        stopSosInternal()
        if (!enabled) {
            backend.setTorchMode(id, false)
            _state.value = _state.value.copy(isOn = false, sosActive = false)
            return
        }
        _state.value = _state.value.copy(isOn = true, sosActive = true)
        sosJob = scope.launch {
            // Standard Morse SOS cadence: 3 short, 3 long, 3 short.
            val patternMs = longArrayOf(
                180L, 180L, 180L, 180L, 180L, 420L,
                480L, 180L, 480L, 180L, 480L, 420L,
                180L, 180L, 180L, 180L, 180L, 900L,
            )
            var index = 0
            while (isActive) {
                val onPhase = index % 2 == 0
                if (onPhase) applyHardwareOn(id, _state.value.brightnessLevel.coerceIn(1, maxLevel))
                else backend.setTorchMode(id, false)
                delay(patternMs[index])
                index = (index + 1) % patternMs.size
            }
        }
    }

    fun seedForTest(state: TorchUiState) {
        _state.value = state
    }

    private fun applyHardwareOn(id: String, level: Int) {
        if (supportsStrength) {
            backend.turnOnTorchWithStrengthLevel(id, level)
        } else {
            backend.setTorchMode(id, true)
        }
    }

    private fun stopSosInternal() {
        sosJob?.cancel()
        sosJob = null
    }
}
