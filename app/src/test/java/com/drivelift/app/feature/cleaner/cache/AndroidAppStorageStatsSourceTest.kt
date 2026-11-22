package com.drivelift.app.feature.cleaner.cache

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.feature.hibernation.engine.StandbyBucket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAppStorageStatsSourceTest {

    @Test
    fun mapsAllStandbyBucketsCorrectly() {
        assertEquals(
            StandbyBucket.ACTIVE,
            AndroidAppStorageStatsSource.mapStandbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE),
        )
        assertEquals(
            StandbyBucket.WORKING_SET,
            AndroidAppStorageStatsSource.mapStandbyBucket(UsageStatsManager.STANDBY_BUCKET_WORKING_SET),
        )
        assertEquals(
            StandbyBucket.FREQUENT,
            AndroidAppStorageStatsSource.mapStandbyBucket(UsageStatsManager.STANDBY_BUCKET_FREQUENT),
        )
        assertEquals(
            StandbyBucket.RARE,
            AndroidAppStorageStatsSource.mapStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE),
        )
        assertEquals(
            StandbyBucket.RESTRICTED,
            AndroidAppStorageStatsSource.mapStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED),
        )
        assertEquals(
            StandbyBucket.NEVER,
            AndroidAppStorageStatsSource.mapStandbyBucket(50),
        )
        assertEquals(
            StandbyBucket.UNKNOWN,
            AndroidAppStorageStatsSource.mapStandbyBucket(-1),
        )
    }

    @Test
    fun gracefullyDegradesWhenUsageAccessNotGranted() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DriveLiftApp>()
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName,
            AppOpsManager.MODE_ERRORED,
        )
        val source = AndroidAppStorageStatsSource(app, nowMs = { 1_760_000_000_000L })

        assertFalse(source.hasUsageStatsAccess())
        assertTrue(source.queryInstalledAppStats().isEmpty())
        assertTrue(source.queryAppUsageSnapshots().isEmpty())

        val intent = source.createUsageAccessSettingsIntent()
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intent.action)
        assertTrue(intent.data.toString().contains(app.packageName))

        val summary = source.queryDeviceStorageSummary()
        assertTrue(summary.totalBytes >= 0L)
        assertTrue(summary.freeBytes >= 0L)
    }
}
