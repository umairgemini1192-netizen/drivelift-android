package com.drivelift.app.feature.storageintelligence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.Graph
import com.drivelift.app.feature.storageintelligence.db.DuplicateGroupEntity
import com.drivelift.app.feature.storageintelligence.db.DuplicateItemEntity
import com.drivelift.app.feature.storageintelligence.db.LargeFileEntity
import com.drivelift.app.feature.storageintelligence.db.OrphanArtifactEntity
import com.drivelift.app.feature.storageintelligence.db.StorageIntelligenceDatabase
import com.drivelift.app.feature.storageintelligence.db.StorageScanSessionEntity
import com.drivelift.app.feature.storageintelligence.model.DuplicateMatchKind
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.LargeFileCategory
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.ScanPhase
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.worker.StorageIntelligenceWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageIntelligenceDatabaseAndWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StorageIntelligenceDatabase

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StorageIntelligenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun persistScanResultAndPruneCleanedNodesKeepTotalsConsistent() = runBlocking {
        val dao = db.dao()
        val sessionId = dao.persistScanResult(
            session = StorageScanSessionEntity(
                scope = ScanScope.CROSS_SOURCE.name,
                startedAtMs = 100L,
                completedAtMs = 200L,
                status = ScanPhase.COMPLETED.name,
                scannedFilesCount = 4,
                bytesAnalyzed = 10_000L,
                duplicateReclaimableBytes = 2_000L,
                largeFilesTotalBytes = 5_000L,
                orphanReclaimableBytes = 500L,
                totalReclaimableBytes = 2_500L,
                partialErrorsJson = "[]",
                thresholdBytes = 1_000L,
            ),
            groups = listOf(
                DuplicateGroupEntity(
                    groupKey = "g1",
                    sessionId = 0L,
                    matchKind = DuplicateMatchKind.EXACT_HASH.name,
                    hashAlgorithm = HashAlgorithm.SHA256.name,
                    hashValue = "deadbeef",
                    normalizedName = null,
                    sizeBytes = 2_000L,
                    itemCount = 2,
                    reclaimableBytes = 2_000L,
                    isCrossSource = false,
                ),
            ),
            duplicateItems = listOf(
                DuplicateItemEntity(
                    sessionId = 0L,
                    groupKey = "g1",
                    nodeId = "local:1",
                    nativeId = "1",
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    name = "IMG_1.jpg",
                    path = "DCIM/IMG_1.jpg",
                    sizeBytes = 2_000L,
                    mimeType = "image/jpeg",
                    modifiedAt = 50L,
                    hashAlgorithm = HashAlgorithm.SHA256.name,
                    hashValue = "deadbeef",
                    thumbnailRef = null,
                    localUri = "content://media/1",
                    bucketId = 1L,
                    parentFolderId = null,
                    isTrashed = false,
                ),
                DuplicateItemEntity(
                    sessionId = 0L,
                    groupKey = "g1",
                    nodeId = "local:2",
                    nativeId = "2",
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    name = "IMG_2.jpg",
                    path = "DCIM/IMG_2.jpg",
                    sizeBytes = 2_000L,
                    mimeType = "image/jpeg",
                    modifiedAt = 60L,
                    hashAlgorithm = HashAlgorithm.SHA256.name,
                    hashValue = "deadbeef",
                    thumbnailRef = null,
                    localUri = "content://media/2",
                    bucketId = 1L,
                    parentFolderId = null,
                    isTrashed = false,
                ),
            ),
            largeFiles = listOf(
                LargeFileEntity(
                    sessionId = 0L,
                    nodeId = "local:1",
                    nativeId = "1",
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    name = "BIG.mp4",
                    path = "Movies/BIG.mp4",
                    sizeBytes = 5_000L,
                    mimeType = "video/mp4",
                    category = LargeFileCategory.VIDEO.name,
                    modifiedAt = 70L,
                    thumbnailRef = null,
                    localUri = "content://media/1",
                    bucketId = 2L,
                    parentFolderId = null,
                ),
            ),
            orphans = listOf(
                OrphanArtifactEntity(
                    artifactId = "o1",
                    sessionId = 0L,
                    sourceId = LOCAL_SOURCE_ID,
                    sourceLabel = "This phone",
                    sourceType = StorageSourceType.LOCAL.name,
                    category = OrphanCategory.LOCAL_APP_CACHE.name,
                    name = "thumb.cache",
                    path = "/cache/thumb.cache",
                    sizeBytes = 500L,
                    modifiedAt = 10L,
                    itemCount = 1,
                    nativeId = null,
                    detail = "cache",
                    localUri = null,
                    transferStatus = null,
                    isLocked = false,
                    canClean = true,
                ),
            ),
        )

        assertEquals(1, dao.duplicateGroupsForSession(sessionId).size)

        // Removing 1 duplicate item leaves <2 copies in group g1, so the group is pruned and totals update.
        dao.pruneCleanedNodes(sessionId, listOf("local:1"))
        assertTrue(dao.duplicateGroupsForSession(sessionId).isEmpty())
        assertTrue(dao.largeFilesForSession(sessionId).isEmpty())

        val updated = dao.observeLatestSession().first()!!
        assertEquals(0L, updated.duplicateReclaimableBytes)
        assertEquals(500L, updated.orphanReclaimableBytes)
        assertEquals(500L, updated.totalReclaimableBytes)
    }

    @Test
    fun storageIntelligenceWorkerExecutesLocalScanAndEmitsCompletedProgress() = runBlocking {
        val worker = TestListenableWorkerBuilder<StorageIntelligenceWorker>(context)
            .setInputData(StorageIntelligenceWorker.inputData(scope = ScanScope.LOCAL_ONLY))
            .build()

        val result = worker.doWork()
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)

        val module = Graph.get(context).storageIntelligence
        assertEquals(ScanPhase.COMPLETED, module.repository.scanProgress.value.phase)
        val latest = module.repository.latestSession.first()
        assertNotNull(latest)
        assertEquals(ScanScope.LOCAL_ONLY.name, latest!!.scope)
    }
}
