package com.drivelift.app.feature.cockpit.alerts

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CockpitFlashPattern {
    INCOMING_CALL,
    URGENT_ALERT,
    TRANSFER_DONE,
}

interface TorchHardwareAdapter {
    fun findBackFlashCameraId(): String?
    fun setTorchMode(cameraId: String, enabled: Boolean)
    fun registerTorchCallback(onTorchModeChanged: (cameraId: String, enabled: Boolean) -> Unit)
    fun unregisterTorchCallback()
}

class AndroidTorchHardwareAdapter(context: Context) : TorchHardwareAdapter {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: CameraManager.TorchCallback? = null

    override fun findBackFlashCameraId(): String? {
        val manager = cameraManager ?: return null
        val ids = try {
            manager.cameraIdList
        } catch (_: CameraAccessException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

        var fallbackId: String? = null
        for (id in ids) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (_: CameraAccessException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) continue
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
            if (fallbackId == null) fallbackId = id
        }
        return fallbackId
    }

    override fun setTorchMode(cameraId: String, enabled: Boolean) {
        val manager = cameraManager ?: return
        try {
            manager.setTorchMode(cameraId, enabled)
        } catch (_: CameraAccessException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }

    override fun registerTorchCallback(
        onTorchModeChanged: (cameraId: String, enabled: Boolean) -> Unit,
    ) {
        val manager = cameraManager ?: return
        unregisterTorchCallback()
        val torchCallback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                onTorchModeChanged(cameraId, enabled)
            }
        }
        callback = torchCallback
        try {
            manager.registerTorchCallback(torchCallback, mainHandler)
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }

    override fun unregisterTorchCallback() {
        val existing = callback ?: return
        callback = null
        try {
            cameraManager?.unregisterTorchCallback(existing)
        } catch (_: IllegalArgumentException) {
        }
    }
}

/**
 * Permissionless rear-LED flash alert controller using [CameraManager.setTorchMode].
 * Never overrides or turns off a flashlight that the user manually turned on.
 */
class CockpitLedFlashAlertController(
    private val adapter: TorchHardwareAdapter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val isScreenOn: () -> Boolean = { false },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    private val cameraId: String? = adapter.findBackFlashCameraId()

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var suppressWhenScreenOn: Boolean = true

    @Volatile
    private var manualTorchOn: Boolean = false

    @Volatile
    private var controllerDrivingTorch: Boolean = false

    @Volatile
    private var expectedControllerTorchOn: Boolean = false

    private var activeJob: Job? = null

    init {
        if (cameraId != null) {
            adapter.registerTorchCallback { changedId, isOn ->
                if (changedId == cameraId) {
                    if (controllerDrivingTorch && isOn == expectedControllerTorchOn) {
                        return@registerTorchCallback
                    }
                    manualTorchOn = isOn
                    if (isOn) {
                        activeJob?.cancel()
                    }
                }
            }
        }
    }

    fun trigger(pattern: CockpitFlashPattern) {
        val id = cameraId ?: return
        if (!enabled) return
        if (suppressWhenScreenOn && isScreenOn()) return
        if (manualTorchOn) return

        activeJob?.cancel()
        activeJob = scope.launch {
            runPattern(id, pattern)
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    fun release() {
        cancel()
        adapter.unregisterTorchCallback()
    }

    private suspend fun runPattern(id: String, pattern: CockpitFlashPattern) {
        try {
            when (pattern) {
                CockpitFlashPattern.TRANSFER_DONE -> {
                    pulse(id, onMs = TRANSFER_ON_MS)
                }
                CockpitFlashPattern.URGENT_ALERT -> {
                    pulse(id, onMs = URGENT_ON_MS)
                    delayFn(URGENT_GAP_MS)
                    pulse(id, onMs = URGENT_ON_MS)
                }
                CockpitFlashPattern.INCOMING_CALL -> {
                    while (scope.isActive && !manualTorchOn) {
                        pulse(id, onMs = CALL_ON_MS)
                        delayFn(CALL_SHORT_GAP_MS)
                        if (manualTorchOn) break
                        pulse(id, onMs = CALL_ON_MS)
                        delayFn(CALL_LONG_GAP_MS)
                    }
                }
            }
        } finally {
            if (controllerDrivingTorch && !manualTorchOn) {
                setControllerTorch(id, false)
            }
            controllerDrivingTorch = false
            expectedControllerTorchOn = false
        }
    }

    private suspend fun pulse(id: String, onMs: Long) {
        if (manualTorchOn) return
        setControllerTorch(id, true)
        delayFn(onMs)
        if (!manualTorchOn) {
            setControllerTorch(id, false)
        }
    }

    private fun setControllerTorch(id: String, on: Boolean) {
        controllerDrivingTorch = true
        expectedControllerTorchOn = on
        adapter.setTorchMode(id, on)
        if (!on) {
            controllerDrivingTorch = false
        }
    }

    companion object {
        const val TRANSFER_ON_MS = 110L
        const val URGENT_ON_MS = 120L
        const val URGENT_GAP_MS = 110L
        const val CALL_ON_MS = 180L
        const val CALL_SHORT_GAP_MS = 180L
        const val CALL_LONG_GAP_MS = 700L
    }
}
