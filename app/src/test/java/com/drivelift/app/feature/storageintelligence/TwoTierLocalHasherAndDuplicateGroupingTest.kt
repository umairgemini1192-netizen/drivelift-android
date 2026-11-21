package com.drivelift.app.feature.storageintelligence

import com.drivelift.app.feature.storageintelligence.model.DuplicateMatchKind
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.scanner.CrossSourceDuplicateEngine
import com.drivelift.app.feature.storageintelligence.scanner.DualDigest
import com.drivelift.app.feature.storageintelligence.scanner.LocalByteSource
import com.drivelift.app.feature.storageintelligence.scanner.dualDigestOf
import com.drivelift.app.feature.storageintelligence.scanner.sha256PartialFromStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TwoTierLocalHasherAndDuplicateGroupingTest {

    private class FakeByteSource(
        private val bytesByNodeId: Map<String, ByteArray>,
        private val windowBytes: Int = 64,
    ) : LocalByteSource {
        val partialCalls = ArrayList<String>()
        val fullCalls = ArrayList<String>()

        override suspend fun readPartialHash(node: UnifiedFileNode, windowBytes: Int): String? {
            partialCalls += node.id
            val bytes = bytesByNodeId[node.id] ?: return null
            return sha256PartialFromStream(ByteArrayInputStream(bytes), bytes.size.toLong(), this.windowBytes)
        }

        override suspend fun computeFullDualDigest(node: UnifiedFileNode, onRead: (Int) -> Unit): DualDigest? {
            fullCalls += node.id
            val bytes = bytesByNodeId[node.id] ?: return null
            return dualDigestOf(ByteArrayInputStream(bytes), onRead)
        }
    }

    private fun localNode(
        id: String,
        name: String,
        size: Long,
        modifiedAt: Long = 1_000L,
    ) = UnifiedFileNode(
        id = id,
        nativeId = id.substringAfter(':'),
        sourceId = LOCAL_SOURCE_ID,
        sourceLabel = "This phone",
        sourceType = StorageSourceType.LOCAL,
        name = name,
        path = "DCIM/$name",
        sizeBytes = size,
        mimeType = "image/jpeg",
        modifiedAt = modifiedAt,
        localUri = "content://media/external/images/media/${id.substringAfter(':')}",
    )

    private fun cloudNode(
        id: String,
        sourceId: String = "gdrive:acct1",
        sourceType: StorageSourceType = StorageSourceType.GOOGLE_DRIVE,
        name: String,
        size: Long,
        alg: HashAlgorithm = HashAlgorithm.NONE,
        hash: String? = null,
        modifiedAt: Long = 2_000L,
    ) = UnifiedFileNode(
        id = id,
        nativeId = id.substringAfter(':'),
        sourceId = sourceId,
        sourceLabel = if (sourceType == StorageSourceType.GOOGLE_DRIVE) "Google Drive" else "Cloud Drive",
        sourceType = sourceType,
        name = name,
        path = "Backup/$name",
        sizeBytes = size,
        mimeType = "image/jpeg",
        modifiedAt = modifiedAt,
        hashAlgorithm = alg,
        hashValue = hash,
    )

    @Test
    fun partialHashSkipsFullHashWhenHeadOrTailDiffers() = runBlocking {
        val size = 512
        val a = ByteArray(size) { 1 }
        val b = ByteArray(size) { 2 }
        val unique = ByteArray(size + 1) { 3 }
        val empty = ByteArray(0)

        val byteSource = FakeByteSource(
            mapOf(
                "local:1" to a,
                "local:2" to b,
                "local:3" to unique,
                "local:4" to empty,
            ),
            windowBytes = 64,
        )
        val engine = CrossSourceDuplicateEngine(byteSource, partialWindowBytes = 64)
        val nodes = listOf(
            localNode("local:1", "A.jpg", size.toLong()),
            localNode("local:2", "B.jpg", size.toLong()),
            localNode("local:3", "U.jpg", (size + 1).toLong()),
            localNode("local:4", "Zero.jpg", 0L),
        )

        val result = engine.detect(nodes, ScanScope.LOCAL_ONLY)
        assertTrue(result.groups.isEmpty())
        assertEquals(listOf("local:1", "local:2"), byteSource.partialCalls)
        assertTrue("Expected zero full hashes when partial hashes differ", byteSource.fullCalls.isEmpty())
    }

    @Test
    fun fullHashDistinguishesSameHeadAndTailWithDifferentMiddle() = runBlocking {
        val size = 512
        val copy1 = ByteArray(size) { 7 }
        val copy2 = ByteArray(size) { 7 }
        val diffMiddle = ByteArray(size) { 7 }.also { it[256] = 99 }

        val byteSource = FakeByteSource(
            mapOf(
                "local:1" to copy1,
                "local:2" to copy2,
                "local:3" to diffMiddle,
            ),
            windowBytes = 64,
        )
        val engine = CrossSourceDuplicateEngine(byteSource, partialWindowBytes = 64)
        val nodes = listOf(
            localNode("local:1", "IMG_1.jpg", size.toLong()),
            localNode("local:2", "IMG_1_copy.jpg", size.toLong()),
            localNode("local:3", "IMG_other.jpg", size.toLong()),
        )

        val result = engine.detect(nodes, ScanScope.LOCAL_ONLY)
        assertEquals(3, byteSource.partialCalls.size)
        assertEquals(3, byteSource.fullCalls.size)
        assertEquals(1, result.groups.size)
        val group = result.groups.single()
        assertEquals(DuplicateMatchKind.EXACT_HASH, group.matchKind)
        assertEquals(setOf("local:1", "local:2"), group.items.map { it.id }.toSet())
        assertEquals(size.toLong(), group.reclaimableBytes)
    }

    @Test
    fun crossSourceExactHashMatchesLocalToDriveUsingZeroDownloadMd5Metadata() = runBlocking {
        val payload = "synthetic cross source payload bytes".repeat(20).toByteArray()
        val digest = dualDigestOf(ByteArrayInputStream(payload))
        val byteSource = FakeByteSource(mapOf("local:10" to payload))
        val engine = CrossSourceDuplicateEngine(byteSource)

        val nodes = listOf(
            localNode("local:10", "DCIM_20260101.jpg", payload.size.toLong()),
            cloudNode(
                id = "gdrive:d10",
                name = "Renamed_Archive_Photo.jpg",
                size = payload.size.toLong(),
                alg = HashAlgorithm.MD5,
                hash = digest.md5,
            ),
        )

        val result = engine.detect(nodes, ScanScope.CROSS_SOURCE)
        assertEquals(1, result.groups.size)
        val group = result.groups.single()
        assertEquals(DuplicateMatchKind.EXACT_HASH, group.matchKind)
        assertTrue(group.isCrossSource)
        assertEquals(setOf("local:10", "gdrive:d10"), group.items.map { it.id }.toSet())
    }

    @Test
    fun incompatibleCloudHashAlgorithmsWithSameSizeAndNormalizedNameYieldProbableMatch() = runBlocking {
        val engine = CrossSourceDuplicateEngine(FakeByteSource(emptyMap()))
        val nodes = listOf(
            cloudNode(
                id = "gdrive:1",
                sourceId = "gdrive:a",
                name = "Vacation  Clip.MP4",
                size = 20_000L,
                alg = HashAlgorithm.MD5,
                hash = "aaaa",
            ),
            cloudNode(
                id = "other:2",
                sourceId = "cloud:b",
                sourceType = StorageSourceType.CLOUD_GENERIC,
                name = "  vacation clip.mp4 ",
                size = 20_000L,
                alg = HashAlgorithm.QUICK_XOR,
                hash = "bbbb",
            ),
        )

        val result = engine.detect(nodes, ScanScope.ALL_CLOUDS)
        assertEquals(1, result.groups.size)
        val group = result.groups.single()
        assertEquals(DuplicateMatchKind.PROBABLE_MATCH, group.matchKind)
        assertTrue(group.isCrossSource)
        assertEquals("vacation clip.mp4", group.normalizedName)
    }

    @Test
    fun sameStrongAlgorithmWithDifferentHashesNeverProducesProbableMatch() = runBlocking {
        val engine = CrossSourceDuplicateEngine(FakeByteSource(emptyMap()))
        val nodes = listOf(
            cloudNode(
                id = "gdrive:1",
                sourceId = "gdrive:a",
                name = "Report.pdf",
                size = 50_000L,
                alg = HashAlgorithm.SHA256,
                hash = "1111111111111111",
            ),
            cloudNode(
                id = "gdrive:2",
                sourceId = "gdrive:b",
                name = "report.pdf",
                size = 50_000L,
                alg = HashAlgorithm.SHA256,
                hash = "2222222222222222",
            ),
        )

        val result = engine.detect(nodes, ScanScope.ALL_CLOUDS)
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun singleCloudAndAllCloudsScopesRespectSourceFilters() = runBlocking {
        val engine = CrossSourceDuplicateEngine(FakeByteSource(emptyMap()))
        val nodes = listOf(
            localNode("local:1", "A.jpg", 10_000L),
            cloudNode("gdrive:1", sourceId = "gdrive:one", name = "A.jpg", size = 10_000L, alg = HashAlgorithm.MD5, hash = "abc"),
            cloudNode("gdrive:2", sourceId = "gdrive:one", name = "A_copy.jpg", size = 10_000L, alg = HashAlgorithm.MD5, hash = "abc"),
            cloudNode("cloud2:3", sourceId = "cloud:two", sourceType = StorageSourceType.CLOUD_GENERIC, name = "A.jpg", size = 10_000L, alg = HashAlgorithm.MD5, hash = "abc"),
        )

        val single = engine.detect(nodes, ScanScope.SINGLE_CLOUD, singleCloudSourceId = "gdrive:one")
        assertEquals(1, single.groups.size)
        assertEquals(setOf("gdrive:1", "gdrive:2"), single.groups.single().items.map { it.id }.toSet())

        val allClouds = engine.detect(nodes, ScanScope.ALL_CLOUDS)
        assertEquals(1, allClouds.groups.size)
        assertEquals(setOf("gdrive:1", "gdrive:2", "cloud2:3"), allClouds.groups.single().items.map { it.id }.toSet())
        assertNotEquals(single.groups.single().items.size, allClouds.groups.single().items.size)
    }
}
