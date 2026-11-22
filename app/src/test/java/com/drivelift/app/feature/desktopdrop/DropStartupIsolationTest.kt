package com.drivelift.app.feature.desktopdrop

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.feature.desktopdrop.network.DropMdnsResponder
import com.drivelift.app.feature.desktopdrop.network.MulticastLockPort
import com.drivelift.app.feature.desktopdrop.service.DesktopDropService
import com.drivelift.app.feature.desktopdrop.service.DesktopDropTrampolineActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A failure while starting Desktop Drop's optional parts must never take the app process down: the
 * same process hosts the Cockpit accessibility service. These cover the isolation points that can
 * run without a phone: the mDNS responder, the foreground service start and its fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DropStartupIsolationTest {

    private class CountingLock : MulticastLockPort {
        var acquired = 0
        var released = 0
        override fun acquire() { acquired++ }
        override fun release() { released++ }
    }

    private class ExplodingLock : MulticastLockPort {
        var releaseCalls = 0
        override fun acquire() = throw IllegalStateException("multicast lock refused")
        override fun release() {
            releaseCalls++
            throw IllegalStateException("release refused")
        }
    }

    @Test
    fun aMulticastLockThatThrowsOnAcquireMakesStartReturnFalseInsteadOfCrashing() {
        val lock = ExplodingLock()
        val responder = DropMdnsResponder(lock, port = 0)

        val started = responder.start("192.168.1.20")

        assertFalse("mDNS failure is reported, not thrown", started)
        assertFalse(responder.isRunning)
        assertEquals("the lock is still released after the failure", 1, lock.releaseCalls)
    }

    @Test
    fun anAddressThatIsNotIpv4LeavesTheLockReleasedAndTheServerFree() {
        val lock = CountingLock()
        val responder = DropMdnsResponder(lock, port = 0)

        assertFalse(responder.start("fe80::1"))
        assertFalse(responder.start("::1"))

        assertEquals(lock.acquired, lock.released)
        assertFalse(responder.isRunning)
    }

    @Test
    fun stopIsSafeWhenTheResponderNeverStartedOrStopsTwice() {
        val responder = DropMdnsResponder(ExplodingLock(), port = 0)
        responder.stop()
        responder.stop()
        assertFalse(responder.isRunning)
    }

    @Test
    fun androidFourteenAndLaterStartAsConnectedDeviceWithDataSyncAsTheFallback() {
        assertEquals(
            listOf<Int?>(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ),
            DesktopDropService.foregroundTypeCandidates(34),
        )
        assertEquals(listOf<Int?>(null), DesktopDropService.foregroundTypeCandidates(33))
    }

    @Test
    fun aRefusedBackgroundServiceStartIsReportedNotThrown() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?) =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException stand-in")
        }

        assertFalse(DesktopDropService.startService(refusing))
    }

    @Test
    fun anAllowedServiceStartIsReportedAsStarted() {
        val app = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(DesktopDropService.startService(app))

        val started = shadowOf(app as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(DesktopDropService::class.java.name, started.component?.className)
    }

    @Test
    fun theTrampolineLaunchesAsANewTaskAndReportsABlockedLaunch() {
        val app = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(DesktopDropTrampolineActivity.launch(app))
        val launched = shadowOf(app as android.app.Application).nextStartedActivity
        assertNotNull(launched)
        assertEquals(DesktopDropTrampolineActivity::class.java.name, launched.component?.className)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val blocked = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) =
                throw IllegalStateException("background activity start blocked stand-in")
        }
        assertFalse(DesktopDropTrampolineActivity.launch(blocked))
    }
}
