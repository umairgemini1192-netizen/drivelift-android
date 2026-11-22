package com.drivelift.app.feature.cockpit.alerts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitLedFlashAlertControllerTest {

    private class FakeTorchHardwareAdapter(
        private val cameraId: String? = "0",
    ) : TorchHardwareAdapter {
        val events = mutableListOf<String>()
        var callback: ((String, Boolean) -> Unit)? = null
            private set

        override fun findBackFlashCameraId(): String? = cameraId

        override fun setTorchMode(cameraId: String, enabled: Boolean) {
            events.add("torch($cameraId,$enabled)")
            callback?.invoke(cameraId, enabled)
        }

        override fun registerTorchCallback(onTorchModeChanged: (cameraId: String, enabled: Boolean) -> Unit) {
            callback = onTorchModeChanged
        }

        override fun unregisterTorchCallback() {
            callback = null
        }

        fun simulateExternalTorch(enabled: Boolean) {
            val id = cameraId ?: return
            callback?.invoke(id, enabled)
        }
    }

    @Test
    fun urgentAlertExecutesDoubleBlinkTimingSequence() = runBlocking {
        val adapter = FakeTorchHardwareAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CockpitLedFlashAlertController(
            adapter = adapter,
            scope = scope,
            delayFn = { ms -> adapter.events.add("delay($ms)") },
        )
        controller.enabled = true

        controller.trigger(CockpitFlashPattern.URGENT_ALERT)

        assertEquals(
            listOf(
                "torch(0,true)",
                "delay(${CockpitLedFlashAlertController.URGENT_ON_MS})",
                "torch(0,false)",
                "delay(${CockpitLedFlashAlertController.URGENT_GAP_MS})",
                "torch(0,true)",
                "delay(${CockpitLedFlashAlertController.URGENT_ON_MS})",
                "torch(0,false)",
            ),
            adapter.events,
        )
    }

    @Test
    fun transferDoneExecutesSingleSubtleBlink() = runBlocking {
        val adapter = FakeTorchHardwareAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CockpitLedFlashAlertController(
            adapter = adapter,
            scope = scope,
            delayFn = { ms -> adapter.events.add("delay($ms)") },
        )
        controller.enabled = true

        controller.trigger(CockpitFlashPattern.TRANSFER_DONE)

        assertEquals(
            listOf(
                "torch(0,true)",
                "delay(${CockpitLedFlashAlertController.TRANSFER_ON_MS})",
                "torch(0,false)",
            ),
            adapter.events,
        )
    }

    @Test
    fun respectsOptOutAndScreenOnSuppression() {
        val adapter = FakeTorchHardwareAdapter()
        var screenOn = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CockpitLedFlashAlertController(
            adapter = adapter,
            scope = scope,
            isScreenOn = { screenOn },
            delayFn = { ms -> adapter.events.add("delay($ms)") },
        )

        // Opt-out disabled -> no-op
        controller.enabled = false
        controller.trigger(CockpitFlashPattern.URGENT_ALERT)
        assertTrue(adapter.events.isEmpty())

        // Enabled, but screen is on with suppressWhenScreenOn = true -> no-op
        controller.enabled = true
        controller.suppressWhenScreenOn = true
        controller.trigger(CockpitFlashPattern.URGENT_ALERT)
        assertTrue(adapter.events.isEmpty())

        // Screen off -> flashes
        screenOn = false
        controller.trigger(CockpitFlashPattern.TRANSFER_DONE)
        assertFalse(adapter.events.isEmpty())
    }

    @Test
    fun manualTorchOnGuardNeverClobbersUserFlashlight() {
        val adapter = FakeTorchHardwareAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CockpitLedFlashAlertController(
            adapter = adapter,
            scope = scope,
            delayFn = { ms -> adapter.events.add("delay($ms)") },
        )
        controller.enabled = true

        // User turns on flashlight manually
        adapter.simulateExternalTorch(true)
        controller.trigger(CockpitFlashPattern.URGENT_ALERT)
        assertTrue(adapter.events.isEmpty())

        // User turns off flashlight manually -> alerts work again
        adapter.simulateExternalTorch(false)
        controller.trigger(CockpitFlashPattern.TRANSFER_DONE)
        assertEquals(3, adapter.events.size)
    }

    @Test
    fun incomingCallPulseTurnsOffTorchOnCancellation() = runBlocking {
        val adapter = FakeTorchHardwareAdapter()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CockpitLedFlashAlertController(
            adapter = adapter,
            scope = scope,
            delayFn = { ms ->
                adapter.events.add("delay($ms)")
                gate.await()
            },
        )
        controller.enabled = true

        controller.trigger(CockpitFlashPattern.INCOMING_CALL)
        // First pulse turned torch ON and is now suspended inside delay
        assertEquals(listOf("torch(0,true)", "delay(${CockpitLedFlashAlertController.CALL_ON_MS})"), adapter.events)

        controller.cancel()
        // Cleanup in finally guarantees torch is turned OFF
        assertEquals("torch(0,false)", adapter.events.last())

        // Also ensure release unregisters callback
        controller.release()
        assertEquals(null, adapter.callback)
        if (!gate.isCompleted) {
            gate.completeExceptionally(CancellationException("done"))
        }
    }
}
