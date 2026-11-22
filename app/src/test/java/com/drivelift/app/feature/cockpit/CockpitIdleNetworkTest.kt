package com.drivelift.app.feature.cockpit

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.feature.cockpit.hardware.CockpitNetworkFormat
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.feature.cockpit.hardware.TelemetryHudSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Idle pill: how connection and throughput are named, measured and paused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CockpitIdleNetworkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- formatting (pure) ----

    @Test
    fun ratesUseKilobytesBelowAMegabyteAndMegabytesAbove() {
        assertEquals("0K", CockpitNetworkFormat.formatRate(0L))
        assertEquals("0K", CockpitNetworkFormat.formatRate(900L))
        assertEquals("12K", CockpitNetworkFormat.formatRate(12L * 1024L))
        assertEquals("999K", CockpitNetworkFormat.formatRate(999L * 1024L))
        assertEquals("1.4M", CockpitNetworkFormat.formatRate((1.4 * 1024 * 1024).toLong()))
        assertEquals("18M", CockpitNetworkFormat.formatRate(18L * 1024L * 1024L))
        assertEquals("0K", CockpitNetworkFormat.formatRate(-5L))
    }

    @Test
    fun spokenRatesSpellOutTheUnit() {
        assertEquals("12 kilobytes per second", CockpitNetworkFormat.spokenRate(12L * 1024L))
        assertEquals("1.4 megabytes per second", CockpitNetworkFormat.spokenRate((1.4 * 1024 * 1024).toLong()))
    }

    @Test
    fun aRateIsTheByteDeltaOverTheElapsedTime() {
        assertEquals(20_480L, CockpitNetworkFormat.rate(1_000L, 21_480L, 1_000L))
        assertEquals(10_240L, CockpitNetworkFormat.rate(0L, 20_480L, 2_000L))
    }

    @Test
    fun unsupportedCountersResetsAndZeroIntervalsGiveZero() {
        assertEquals(0L, CockpitNetworkFormat.rate(-1L, 500L, 1_000L))
        assertEquals(0L, CockpitNetworkFormat.rate(500L, -1L, 1_000L))
        assertEquals(0L, CockpitNetworkFormat.rate(5_000L, 100L, 1_000L))
        assertEquals(0L, CockpitNetworkFormat.rate(100L, 5_000L, 0L))
    }

    @Test
    fun connectionLabelsNameWifiTheRadioGenerationOrOffline() {
        assertEquals("Wi-Fi", CockpitNetworkFormat.label(IdleNetworkKind.WIFI))
        assertEquals("5G", CockpitNetworkFormat.label(IdleNetworkKind.CELLULAR, telephonyNetworkType = 20))
        assertEquals("4G", CockpitNetworkFormat.label(IdleNetworkKind.CELLULAR, telephonyNetworkType = 13))
        assertEquals("Data", CockpitNetworkFormat.label(IdleNetworkKind.CELLULAR, telephonyNetworkType = null))
        assertEquals("Data", CockpitNetworkFormat.label(IdleNetworkKind.CELLULAR, telephonyNetworkType = 3))
        assertEquals("Offline", CockpitNetworkFormat.label(IdleNetworkKind.OFFLINE))
    }

    @Test
    fun theSnapshotTextMatchesTheFormatter() {
        val snapshot = IdleNetworkSnapshot(
            kind = IdleNetworkKind.WIFI,
            label = "Wi-Fi",
            uploadBytesPerSec = 12L * 1024L,
            downloadBytesPerSec = (1.4 * 1024 * 1024).toLong(),
        )
        assertEquals("12K", snapshot.uploadText)
        assertEquals("1.4M", snapshot.downloadText)
        assertEquals("Offline", IdleNetworkSnapshot().contentDescription)
    }

    // ---- sampling, pausing ----

    private class Counters(var tx: Long = 0L, var rx: Long = 0L, var nowMs: Long = 1_000L)

    private fun monitor(counters: Counters, interactive: () -> Boolean = { true }): CockpitTelemetryMonitor =
        CockpitTelemetryMonitor(
            context = ApplicationProvider.getApplicationContext<Context>(),
            scope = scope,
            isScreenInteractive = interactive,
            snapshotReader = { TelemetryHudSnapshot(isLoadingInitial = false) },
            widgetPusher = {},
            trafficReader = { counters.tx to counters.rx },
            networkReader = { IdleNetworkKind.WIFI to null },
            elapsedClock = { counters.nowMs },
        )

    @Test
    fun throughputIsMeasuredBetweenSamplesAndTheFirstSampleOnlySetsTheBaseline() {
        val counters = Counters()
        val monitor = monitor(counters)

        monitor.sampleSpeed()
        assertEquals(0L, monitor.network.value.uploadBytesPerSec)

        counters.nowMs += 1_000L
        counters.tx += 12L * 1024L
        counters.rx += (1.4 * 1024 * 1024).toLong()
        monitor.sampleSpeed()

        assertEquals("12K", monitor.network.value.uploadText)
        assertEquals("1.4M", monitor.network.value.downloadText)
    }

    @Test
    fun screenOffPausesSamplingAndTrafficWhileOffNeverShowsAsABurst() {
        val counters = Counters()
        val monitor = monitor(counters)
        monitor.acquireIdleNetwork()
        monitor.sampleSpeed()
        counters.nowMs += 1_000L
        counters.rx += 5L * 1024L * 1024L
        monitor.sampleSpeed()
        assertEquals("5.0M", monitor.network.value.downloadText)

        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_OFF))
        // A gigabyte moves while the screen is off. The first reading after wake is a baseline.
        counters.nowMs += 600_000L
        counters.rx += 1024L * 1024L * 1024L
        monitor.onBatteryOrScreenEvent(Intent(Intent.ACTION_SCREEN_ON))
        monitor.sampleSpeed()

        assertEquals("0K", monitor.network.value.downloadText)
        monitor.releaseIdleNetwork()
    }

    @Test
    fun theConnectionIsReadWhenAnIdlePillAppearsAndRatesClearWhenItLeaves() {
        val counters = Counters()
        val monitor = monitor(counters)
        assertEquals(IdleNetworkKind.OFFLINE, monitor.network.value.kind)

        monitor.acquireIdleNetwork()
        assertEquals(IdleNetworkKind.WIFI, monitor.network.value.kind)
        assertEquals("Wi-Fi", monitor.network.value.label)

        monitor.sampleSpeed()
        counters.nowMs += 1_000L
        counters.tx += 64L * 1024L
        monitor.sampleSpeed()
        assertEquals("64K", monitor.network.value.uploadText)

        monitor.releaseIdleNetwork()
        assertEquals("0K", monitor.network.value.uploadText)
    }
}
