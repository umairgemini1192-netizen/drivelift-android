package com.drivelift.app.feature.applock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPackageMonitorContractTest {

    @Test
    fun ignoresSystemUiLauncherBlankAndSelfLockActivityToPreventLoops() {
        val filter = ForegroundPackageChangeFilter()

        val blank = filter.onForegroundEvent(ForegroundEvent("   ", timestampMs = 1_000L))
        assertEquals(ForegroundFilterResult.Ignored("", IgnoreReason.BLANK_PACKAGE), blank)

        val systemUi = filter.onForegroundEvent(
            ForegroundEvent("com.android.systemui", timestampMs = 1_500L),
        )
        assertEquals(
            ForegroundFilterResult.Ignored("com.android.systemui", IgnoreReason.SYSTEM_UI_OR_OVERLAY),
            systemUi,
        )

        val launcher = filter.onForegroundEvent(
            ForegroundEvent("com.google.android.apps.nexuslauncher", timestampMs = 2_000L),
        )
        assertEquals(
            ForegroundFilterResult.Ignored("com.google.android.apps.nexuslauncher", IgnoreReason.LAUNCHER),
            launcher,
        )

        val selfLock = filter.onForegroundEvent(
            ForegroundEvent(
                packageName = "com.drivelift.app",
                className = "com.drivelift.app.feature.applock.ui.AppLockActivity",
                timestampMs = 2_500L,
            ),
        )
        assertEquals(
            ForegroundFilterResult.Ignored("com.drivelift.app", IgnoreReason.SELF_LOCK_ACTIVITY),
            selfLock,
        )

        // DriveLift's main activity itself is still accepted if DriveLift is monitored.
        val selfMain = filter.onForegroundEvent(
            ForegroundEvent(
                packageName = "com.drivelift.app",
                className = "com.drivelift.app.ui.MainActivity",
                timestampMs = 3_000L,
            ),
        )
        assertEquals(
            ForegroundFilterResult.Accepted("com.drivelift.app", 3_000L),
            selfMain,
        )
    }

    @Test
    fun debouncesRapidTransitionsAndSuppressesDuplicates() {
        val filter = ForegroundPackageChangeFilter(
            MonitorFilterConfig(debounceWindowMs = 250L),
        )

        val first = filter.onForegroundEvent(ForegroundEvent("com.example.a", timestampMs = 1_000L))
        assertTrue(first is ForegroundFilterResult.Accepted)

        val duplicate = filter.onForegroundEvent(ForegroundEvent("com.example.a", timestampMs = 1_500L))
        assertEquals(
            ForegroundFilterResult.Ignored("com.example.a", IgnoreReason.DUPLICATE_PACKAGE),
            duplicate,
        )

        val debounced = filter.onForegroundEvent(ForegroundEvent("com.example.b", timestampMs = 1_249L))
        assertEquals(
            ForegroundFilterResult.Ignored("com.example.b", IgnoreReason.DEBOUNCED),
            debounced,
        )

        val acceptedAfterWindow = filter.onForegroundEvent(
            ForegroundEvent("com.example.b", timestampMs = 1_250L),
        )
        assertEquals(
            ForegroundFilterResult.Accepted("com.example.b", 1_250L),
            acceptedAfterWindow,
        )
    }

    @Test
    fun reset_allowsReEvaluatingSamePackageAfterScreenOff() {
        val filter = ForegroundPackageChangeFilter()

        assertTrue(
            filter.onForegroundEvent(ForegroundEvent("com.example.a", timestampMs = 1_000L))
                is ForegroundFilterResult.Accepted,
        )
        assertEquals(
            ForegroundFilterResult.Ignored("com.example.a", IgnoreReason.DUPLICATE_PACKAGE),
            filter.onForegroundEvent(ForegroundEvent("com.example.a", timestampMs = 2_000L)),
        )

        filter.reset()
        assertEquals(
            ForegroundFilterResult.Accepted("com.example.a", 2_500L),
            filter.onForegroundEvent(ForegroundEvent("com.example.a", timestampMs = 2_500L)),
        )
    }
}
