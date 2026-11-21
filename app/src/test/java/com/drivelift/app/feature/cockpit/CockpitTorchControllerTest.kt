package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.hardware.CockpitTorchController
import com.drivelift.app.feature.cockpit.hardware.TorchHardwareBackend
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

        override fun findFlashCameraId(): String? = cameraId
        override fun getMaxStrengthLevel(cameraId: String): Int = maxLevel
        override fun sdkInt(): Int = sdk
        override fun setTorchMode(cameraId: String, enabled: Boolean) {
            calls.add("setTorchMode($cameraId,$enabled)")
        }

        override fun turnOnTorchWithStrengthLevel(cameraId: String, strengthLevel: Int) {
            calls.add("turnOnTorchWithStrengthLevel($cameraId,$strengthLevel)")
        }

        override fun registerTorchCallback(onTorchChanged: (cameraId: String, enabled: Boolean) -> Unit) = Unit
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
}
