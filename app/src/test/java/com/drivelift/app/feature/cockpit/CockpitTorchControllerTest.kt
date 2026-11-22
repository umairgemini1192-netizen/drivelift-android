package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.hardware.CockpitTorchController
import com.drivelift.app.feature.cockpit.hardware.TorchHardwareBackend
import com.drivelift.app.feature.cockpit.hardware.TorchUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitTorchControllerTest {

    private class FakeTorchBackend(
        private val cameraId: String? = "0",
        private val maxLevel: Int = 5,
        private val sdk: Int = 34,
    ) : TorchHardwareBackend {
        val calls = mutableListOf<String>()
        var onChangedCallback: ((String, Boolean) -> Unit)? = null
        var onUnavailableCallback: ((String) -> Unit)? = null

        override fun findFlashCameraId(): String? = cameraId
        override fun getMaxStrengthLevel(cameraId: String): Int = maxLevel
        override fun sdkInt(): Int = sdk
        override fun setTorchMode(cameraId: String, enabled: Boolean) {
            calls.add("setTorchMode($cameraId,$enabled)")
        }

        override fun turnOnTorchWithStrengthLevel(cameraId: String, strengthLevel: Int) {
            calls.add("turnOnTorchWithStrengthLevel($cameraId,$strengthLevel)")
        }

        override fun registerTorchCallback(
            onTorchChanged: (cameraId: String, enabled: Boolean) -> Unit,
            onTorchUnavailable: (cameraId: String) -> Unit,
        ) {
            onChangedCallback = onTorchChanged
            onUnavailableCallback = onTorchUnavailable
        }
    }

    @Test
    fun clampsBrightnessLevelAndUsesStrengthApiOnApi33Plus() {
        val backend = FakeTorchBackend(maxLevel = 5, sdk = 34)
        val controller = CockpitTorchController(backend, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertTrue(controller.state.value.available)
        assertTrue(controller.state.value.supportsBrightnessSlider)

        controller.setBrightnessLevel(0)
        assertEquals(1, controller.state.value.brightnessLevel)
        assertTrue(controller.state.value.isOn)
        assertEquals("turnOnTorchWithStrengthLevel(0,1)", backend.calls.last())

        controller.setBrightnessLevel(99)
        assertEquals(5, controller.state.value.brightnessLevel)
        assertEquals("turnOnTorchWithStrengthLevel(0,5)", backend.calls.last())

        controller.setTorch(false)
        assertFalse(controller.state.value.isOn)
        assertEquals("setTorchMode(0,false)", backend.calls.last())
    }

    @Test
    fun fallsBackToStandardTorchModeWhenMaxLevelIsOneOrApiBelow33() {
        val singleLevel = FakeTorchBackend(maxLevel = 1, sdk = 34)
        val controllerSingle = CockpitTorchController(singleLevel, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertFalse(controllerSingle.state.value.supportsBrightnessSlider)
        controllerSingle.setTorch(true)
        assertEquals("setTorchMode(0,true)", singleLevel.calls.last())

        val legacyApi = FakeTorchBackend(maxLevel = 5, sdk = 32)
        val controllerLegacy = CockpitTorchController(legacyApi, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertFalse(controllerLegacy.state.value.supportsBrightnessSlider)
        controllerLegacy.setBrightnessLevel(3)
        assertEquals("setTorchMode(0,true)", legacyApi.calls.last())
    }

    @Test
    fun handlesMissingCameraFlashGracefully() {
        val noFlash = FakeTorchBackend(cameraId = null)
        val controller = CockpitTorchController(noFlash, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertFalse(controller.state.value.available)
        controller.toggleTorch()
        assertFalse(controller.state.value.isOn)
        assertTrue(noFlash.calls.isEmpty())
    }

    @Test
    fun unavailableCameraCallbackDisablesTorchUntilCameraReleasesFlash() {
        val backend = FakeTorchBackend(maxLevel = 5, sdk = 34)
        val controller = CockpitTorchController(backend, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        controller.setTorch(true)
        assertTrue(controller.state.value.isOn)
        assertTrue(controller.state.value.available)
        assertFalse(controller.state.value.cameraInUse)

        // Camera opens and claims flash -> torch unavailable & off, toggles ignored.
        backend.onUnavailableCallback?.invoke("0")
        assertFalse(controller.state.value.available)
        assertFalse(controller.state.value.isOn)
        assertTrue(controller.state.value.cameraInUse)

        val callCountBefore = backend.calls.size
        controller.toggleTorch()
        assertEquals(callCountBefore, backend.calls.size)
        assertFalse(controller.state.value.isOn)

        // Camera closes and releases flash -> torch becomes available again.
        backend.onChangedCallback?.invoke("0", false)
        assertTrue(controller.state.value.available)
        assertFalse(controller.state.value.cameraInUse)
        assertEquals(80, TorchUiState(available = true, isOn = true, brightnessLevel = 4, maxLevel = 5).brightnessPercent)
    }
}
