package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.overlay.AccessibilityHealth
import com.drivelift.app.feature.cockpit.overlay.CockpitAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A service that Android lists as enabled but has not connected must not read as granted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CockpitAccessibilityHealthTest {

    @Test
    fun connectedMeansTheOverlayCanMount() {
        assertEquals(
            AccessibilityHealth.CONNECTED,
            CockpitAccessibilityService.health(grantedInSettings = true, connected = true),
        )
    }

    @Test
    fun listedInSettingsButNotConnectedNeedsReenable() {
        assertEquals(
            AccessibilityHealth.NEEDS_REENABLE,
            CockpitAccessibilityService.health(grantedInSettings = true, connected = false),
        )
    }

    @Test
    fun notListedIsNotGranted() {
        assertEquals(
            AccessibilityHealth.NOT_GRANTED,
            CockpitAccessibilityService.health(grantedInSettings = false, connected = false),
        )
    }

    @Test
    fun aConnectedServiceCountsAsGrantedEvenBeforeSettingsCatchesUp() {
        assertEquals(
            AccessibilityHealth.CONNECTED,
            CockpitAccessibilityService.health(grantedInSettings = false, connected = true),
        )
    }

    @Test
    fun withNothingEnabledTheLiveCheckReportsNotGranted() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(AccessibilityHealth.NOT_GRANTED, CockpitAccessibilityService.health(context))
    }
}
