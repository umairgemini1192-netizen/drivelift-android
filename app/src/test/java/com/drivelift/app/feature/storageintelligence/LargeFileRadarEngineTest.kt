package com.drivelift.app.feature.storageintelligence

import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.LargeFileCategory
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.model.classifyLargeFileCategory
import com.drivelift.app.feature.storageintelligence.scanner.LargeFileRadarEngine
import com.drivelift.app.feature.storageintelligence.scanner.StorageSourceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeFileRadarEngineTest {

    private val engine = LargeFileRadarEngine()

    private class StubScanner(
        override val sourceId: String,
        override val sourceLabel: String,
        override val sourceType: StorageSourceType,
        private val items: List<UnifiedFileNode> = emptyList(),
        private val failure: Exception? = null,
    ) : StorageSourceScanner {
        override val supportsServerSizeFilter: Boolean = sourceType.isCloud
        override val supportsEmptyTrash: Boolean = false
        override suspend fun isAvailable(): Boolean = true
        override suspend fun scanAllFiles(onProgress: suspend (Int, Long) -> Unit) = items
        override suspend fun queryLargeFiles(
            minSizeBytes: Long,
            limit: Int,
            onProgress: suspend (Int, Long) -> Unit,
        ): List<UnifiedFileNode> {
            failure?.let { throw it }
            return items.filter { it.sizeBytes >= minSizeBytes }.sortedByDescending { it.sizeBytes }.take(limit)
        }
        override suspend fun scanOrphans(gracePeriodMs: Long, nowMs: Long) = emptyList<OrphanArtifactCandidate>()
    }

    private fun file(id: String, sourceId: String, type: StorageSourceType, name: String, mime: String, mb: Long) =
        UnifiedFileNode(
            id = id,
            nativeId = id,
            sourceId = sourceId,
            sourceLabel = if (type == StorageSourceType.LOCAL) "This phone" else "Google Drive",
            sourceType = type,
            name = name,
            path = name,
            sizeBytes = mb * 1024L * 1024L,
            mimeType = mime,
            modifiedAt = 1_000L,
        )

    @Test
    fun categoryClassificationCoversVideoArchivesApksBackupsImagesAudioAndOther() {
        assertEquals(LargeFileCategory.VIDEO, classifyLargeFileCategory("clip.mp4", "video/mp4"))
        assertEquals(LargeFileCategory.ARCHIVES, classifyLargeFileCategory("takeout.zip", "application/zip"))
        assertEquals(LargeFileCategory.APKS, classifyLargeFileCategory("app-release.apk", "application/vnd.android.package-archive"))
        assertEquals(LargeFileCategory.BACKUPS, classifyLargeFileCategory("msgstore.db.crypt14", "application/octet-stream"))
        assertEquals(LargeFileCategory.IMAGES, classifyLargeFileCategory("raw_shot.dng", "image/x-adobe-dng"))
        assertEquals(LargeFileCategory.AUDIO, classifyLargeFileCategory("podcast.flac", "audio/flac"))
        assertEquals(LargeFileCategory.OTHER, classifyLargeFileCategory("manual.pdf", "application/pdf"))
    }

    @Test
    fun scanCombinesLocalAndCloudHandlesPartialAuthFailureAndFiltersByThresholdAndCategory() = runBlocking {
        val localItems = listOf(
            file("l1", LOCAL_SOURCE_ID, StorageSourceType.LOCAL, "4k_video.mp4", "video/mp4", 600),
            file("l2", LOCAL_SOURCE_ID, StorageSourceType.LOCAL, "installer.apk", "application/vnd.android.package-archive", 120),
            file("l3", LOCAL_SOURCE_ID, StorageSourceType.LOCAL, "small.jpg", "image/jpeg", 5),
        )
        val cloudHealthyItems = listOf(
            file("c1", "gdrive:main", StorageSourceType.GOOGLE_DRIVE, "archive.7z", "application/x-7z-compressed", 1_200),
        )

        val scanners = listOf(
            StubScanner(LOCAL_SOURCE_ID, "This phone", StorageSourceType.LOCAL, localItems),
            StubScanner("gdrive:main", "Google Drive", StorageSourceType.GOOGLE_DRIVE, cloudHealthyItems),
            StubScanner("gdrive:expired", "Expired Cloud", StorageSourceType.GOOGLE_DRIVE, failure = AuthRequiredException()),
        )

        val threshold50Mb = 50L * 1024L * 1024L
        val output = engine.scan(scanners, minSizeBytes = threshold50Mb, topN = 10)
        assertEquals(listOf("c1", "l1", "l2"), output.items.map { it.id })
        assertEquals(1, output.errors.size)
        assertTrue(output.errors.single().authExpired)
        assertEquals("gdrive:expired", output.errors.single().sourceId)

        val only500Mb = engine.filter(
            items = output.items,
            minSizeBytes = 500L * 1024L * 1024L,
            sourceFilterId = null,
            categoryFilter = LargeFileCategory.ALL,
            topN = 10,
        )
        assertEquals(listOf("c1", "l1"), only500Mb.map { it.id })

        val onlyLocalApks = engine.filter(
            items = output.items,
            minSizeBytes = threshold50Mb,
            sourceFilterId = LOCAL_SOURCE_ID,
            categoryFilter = LargeFileCategory.APKS,
            topN = 10,
        )
        assertEquals(listOf("l2"), onlyLocalApks.map { it.id })
    }
}
