package com.drivelift.app.feature.storageintelligence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.data.ALL_MIGRATIONS
import com.drivelift.app.data.AppDatabase
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_GRACE_PERIOD_MS
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.scanner.ActiveTransferSnapshot
import com.drivelift.app.feature.storageintelligence.scanner.CloudTrashSummary
import com.drivelift.app.feature.storageintelligence.scanner.DriveMetadataApi
import com.drivelift.app.feature.storageintelligence.scanner.DriveMetadataEntry
import com.drivelift.app.feature.storageintelligence.scanner.DriveMetadataPage
import com.drivelift.app.feature.storageintelligence.scanner.GoogleDriveStorageScannerAdapter
import com.drivelift.app.feature.storageintelligence.scanner.OrphanCacheScanner
import com.drivelift.app.feature.storageintelligence.scanner.OrphanSafetyLock
import com.drivelift.app.feature.storageintelligence.scanner.TransferLockProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OrphanSafetyLockAndScannerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var appDb: AppDatabase
    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        appDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        tempCacheDir = File(context.cacheDir, "si-orphan-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
        appDb.close()
    }

    @Test
    fun safetyLockProtectsActiveTransfersActiveWorkGracePeriodAndCoreDatabases() {
        val lock = OrphanSafetyLock()
        val now = 10_000_000_000L
        val activeSnapshot = ActiveTransferSnapshot(
            activeMediaUris = setOf("content://media/external/images/media/42"),
            activeDriveFileIds = setOf("drive-active-1"),
            activeFileNames = setOf("uploading_video.mp4"),
            activeDestFolderIds = setOf("dest-folder-1"),
            activeTransferWorkRunning = true,
        )

        val lockedByUri = candidate("c1", name = "other.tmp", localUri = "content://media/external/images/media/42", modifiedAt = 0L)
        assertFalse(lock.isSafeToFlag(lockedByUri, activeSnapshot, now))

        val lockedByName = candidate("c2", name = "uploading_video.mp4", modifiedAt = 0L)
        assertFalse(lock.isSafeToFlag(lockedByName, activeSnapshot, now))

        val lockedByDriveId = candidate("c3", name = "stub.jpg", nativeId = "drive-active-1", modifiedAt = 0L)
        assertFalse(lock.isSafeToFlag(lockedByDriveId, activeSnapshot, now))

        val lockedDestFolder = candidate(
            id = "c4",
            name = "BackupFolder",
            nativeId = "dest-folder-1",
            category = OrphanCategory.CLOUD_EMPTY_FOLDER,
            modifiedAt = 0L,
        )
        assertFalse(lock.isSafeToFlag(lockedDestFolder, activeSnapshot, now))

        val lockedTempWhileWorkerActive = candidate(
            id = "c5",
            name = "chunk.part",
            category = OrphanCategory.TRANSFER_ORPHAN,
            modifiedAt = 0L,
        )
        assertFalse(lock.isSafeToFlag(lockedTempWhileWorkerActive, activeSnapshot, now))

        val lockedDatabaseFile = candidate("c6", name = "drivelift.db-wal", modifiedAt = 0L)
        assertFalse(lock.isSafeToFlag(lockedDatabaseFile, ActiveTransferSnapshot(), now))

        val recentInsideGrace = candidate(
            id = "c7",
            name = "fresh_thumb.bin",
            modifiedAt = now - (DEFAULT_GRACE_PERIOD_MS / 2L),
        )
        assertFalse(lock.isSafeToFlag(recentInsideGrace, ActiveTransferSnapshot(), now))

        // Explicitly FAILED or CANCELLED transfer artifacts bypass the 24h grace period.
        val failedSession = candidate(
            id = "c8",
            name = "failed_photo.jpg",
            category = OrphanCategory.TRANSFER_ORPHAN,
            modifiedAt = now - 1_000L,
            transferStatus = TransferStatus.FAILED.name,
        )
        assertTrue(lock.isSafeToFlag(failedSession, activeSnapshot, now))
    }

    @Test
    fun orphanScannerDetectsStaleCachesExpiredSessionsAndCloudOrphansWhileSkippingLockedFiles() = runBlocking {
        val now = 20_000_000_000L
        val staleTime = now - DEFAULT_GRACE_PERIOD_MS - 60_000L

        val staleCache = File(tempCacheDir, "old_preview.bin").apply {
            writeBytes(ByteArray(2048))
            setLastModified(staleTime)
        }
        val stalePart = File(tempCacheDir, "abandoned_upload.part").apply {
            writeBytes(ByteArray(1024))
            setLastModified(staleTime)
        }
        val activePart = File(tempCacheDir, "active_file.jpg.part").apply {
            writeBytes(ByteArray(4096))
            setLastModified(staleTime)
        }
        val freshCache = File(tempCacheDir, "fresh_preview.bin").apply {
            writeBytes(ByteArray(512))
            setLastModified(now - 5_000L)
        }
        assertTrue(staleCache.exists() && stalePart.exists() && activePart.exists() && freshCache.exists())

        appDb.transfers().insertAll(
            listOf(
                TransferEntity(
                    mediaUri = "content://media/external/images/media/100",
                    name = "active_file.jpg.part",
                    mime = "image/jpeg",
                    size = 4096,
                    dateModifiedMs = 1L,
                    dateTakenMs = 1L,
                    bucketName = "Camera",
                    destFolderId = "dest-locked",
                    destLabel = "Backup",
                    status = TransferStatus.UPLOADING.name,
                ),
                TransferEntity(
                    mediaUri = "content://media/external/images/media/200",
                    name = "cancelled_photo.jpg",
                    mime = "image/jpeg",
                    size = 1024,
                    dateModifiedMs = 1L,
                    dateTakenMs = 1L,
                    bucketName = "Camera",
                    destFolderId = "dest-2",
                    destLabel = "Backup",
                    status = TransferStatus.CANCELLED.name,
                    sessionUri = "https://www.googleapis.com/upload/drive/v3/files?upload_id=expired",
                    createdAt = now - 5_000L,
                ),
            ),
        )

        val fakeCloudApi = object : DriveMetadataApi {
            override fun isConnected() = true
            override fun pinnedAccountEmail() = "user@example.com"
            override suspend fun myDriveRootId() = "root-id"
            override suspend fun listActiveFilesPage(pageToken: String?) = DriveMetadataPage(
                entries = listOf(
                    DriveMetadataEntry("stub-1", "zero.bin", "application/octet-stream", 0L, null, null, staleTime, listOf("root-id"), false, null, true),
                    DriveMetadataEntry("doc-1", "Notes", "application/vnd.google-apps.document", 0L, null, null, staleTime, listOf("root-id"), false, null, true),
                    DriveMetadataEntry("empty-1", "EmptyFolder", "application/vnd.google-apps.folder", 0L, null, null, staleTime, listOf("root-id"), false, null, true),
                    DriveMetadataEntry("dest-locked", "LockedDestFolder", "application/vnd.google-apps.folder", 0L, null, null, staleTime, listOf("root-id"), false, null, true),
                ),
                nextPageToken = null,
            )

            override suspend fun queryLargestFiles(minSizeBytes: Long, limit: Int) = emptyList<DriveMetadataEntry>()
            override suspend fun summarizeTrash() = CloudTrashSummary(itemCount = 3, totalBytes = 9_000L)
        }

        val lockProvider = object : TransferLockProvider {
            override suspend fun snapshot() = ActiveTransferSnapshot(
                activeMediaUris = setOf("content://media/external/images/media/100"),
                activeFileNames = setOf("active_file.jpg.part"),
                activeDestFolderIds = setOf("dest-locked"),
                activeTransferWorkRunning = false,
            )
        }

        val scanner = OrphanCacheScanner(
            context = context,
            appDb = appDb,
            safetyLock = OrphanSafetyLock(),
            lockProvider = lockProvider,
            extraCacheRoots = listOf(tempCacheDir),
        )
        val cloudAdapter = GoogleDriveStorageScannerAdapter(fakeCloudApi)

        val output = scanner.scan(
            cloudScanners = listOf(cloudAdapter),
            includeLocal = true,
            gracePeriodMs = DEFAULT_GRACE_PERIOD_MS,
            nowMs = now,
        )

        val names = output.artifacts.map { it.name }.toSet()
        assertTrue("old_preview.bin" in names)
        assertTrue("abandoned_upload.part" in names)
        assertTrue("cancelled_photo.jpg" in names)
        assertTrue("zero.bin" in names)
        assertTrue("EmptyFolder" in names)
        assertTrue("Google Drive trash" in names)

        // Must NOT flag active transfer file, fresh file inside grace period, Google Doc, or locked folder.
        assertFalse("active_file.jpg.part" in names)
        assertFalse("fresh_preview.bin" in names)
        assertFalse("Notes" in names)
        assertFalse("LockedDestFolder" in names)

        val trashSummary = output.artifacts.first { it.category == OrphanCategory.CLOUD_TRASH_SUMMARY }
        assertEquals(9_000L, trashSummary.sizeBytes)
        assertFalse(trashSummary.canClean)
    }

    private fun candidate(
        id: String,
        name: String,
        category: OrphanCategory = OrphanCategory.LOCAL_APP_CACHE,
        nativeId: String? = null,
        localUri: String? = null,
        modifiedAt: Long,
        transferStatus: String? = null,
    ) = OrphanArtifactCandidate(
        artifactId = id,
        sourceId = LOCAL_SOURCE_ID,
        sourceLabel = "This phone",
        sourceType = StorageSourceType.LOCAL,
        category = category,
        name = name,
        path = "/cache/$name",
        sizeBytes = 1_024L,
        modifiedAt = modifiedAt,
        nativeId = nativeId,
        detail = "test",
        localUri = localUri,
        transferStatus = transferStatus,
    )
}
