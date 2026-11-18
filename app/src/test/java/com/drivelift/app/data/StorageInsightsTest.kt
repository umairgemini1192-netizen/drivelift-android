package com.drivelift.app.data

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/** Issue #42: storage breakdown, duplicate grouping and the duplicate cleanup rules. */
@RunWith(RobolectricTestRunner::class)
class StorageInsightsTest {
    private fun item(
        id: Long, size: Long, name: String = "IMG_$id.jpg", video: Boolean = false,
        bucket: Long = 1, bucketName: String = "Camera",
    ) = MediaItem(
        id = id, uri = Uri.parse("content://media/external/${if (video) "video" else "images"}/media/$id"),
        name = name, size = size, mime = if (video) "video/mp4" else "image/jpeg", dateTakenMs = id,
        dateModifiedMs = id, bucketId = bucket, bucketName = bucketName, relativePath = "DCIM/$bucketName/",
        isVideo = video, durationMs = 0,
    )

    private fun row(item: MediaItem, status: TransferStatus) = TransferEntity(
        id = item.id, mediaUri = item.uri.toString(), name = item.name, mime = item.mime, size = item.size,
        dateModifiedMs = 0, dateTakenMs = 0, bucketName = item.bucketName, destFolderId = null,
        destLabel = "Drive", status = status.name, driveFileId = null, attempts = 0, lastError = null,
    )

    @Test
    fun breakdownSplitsPhotosAndVideosAndRanksFoldersByBytes() {
        val items = listOf(
            item(1, 100), item(2, 200), item(3, 5_000, video = true),
            item(4, 50, bucket = 2, bucketName = "Screenshots"), item(5, 60, bucket = 2, bucketName = "Screenshots"),
        )
        val b = storageBreakdown(items)
        assertEquals(4, b.photoCount)
        assertEquals(410L, b.photoBytes)
        assertEquals(1, b.videoCount)
        assertEquals(5_000L, b.videoBytes)
        assertEquals(5_410L, b.totalBytes)
        assertEquals(listOf("Camera", "Screenshots"), b.folders.map { it.name })
        assertEquals(2, b.folders[0].photoCount)
        assertEquals(1, b.folders[0].videoCount)
        assertEquals(5_300L, b.folders[0].bytes)
        assertEquals(110L, b.folders[1].bytes)
    }

    @Test
    fun largestItemsAreLargestFirstAndLimited() {
        val items = listOf(item(1, 10), item(2, 30), item(3, 20), item(4, 30, name = "A.jpg"))
        assertEquals(listOf(4L, 2L, 3L), largestItems(items, 3).map { it.id })
    }

    @Test
    fun candidatesAreSameSizeOnlyAndNeverByName() {
        val items = listOf(
            item(1, 100, name = "same.jpg"), item(2, 200, name = "same.jpg"), // same name, other size
            item(3, 300, name = "a.jpg"), item(4, 300, name = "b.jpg"), // different names, same size
            item(5, 0), item(6, 0), // empty files are skipped
        )
        val groups = sizeCandidates(items)
        assertEquals(listOf(listOf(3L, 4L)), groups.map { g -> g.map { it.id } })
    }

    @Test
    fun sameSizeButDifferentContentIsSplitByChecksum() = runBlocking {
        val items = listOf(item(1, 500), item(2, 500), item(3, 500), item(4, 500), item(5, 900), item(6, 900))
        val digests = mapOf(1L to "aa", 2L to "bb", 3L to "aa", 4L to "cc", 5L to "dd", 6L to "ee")
        val result = findDuplicates(sizeCandidates(items)) { digests[it.id] }
        assertEquals(1, result.groups.size)
        assertEquals(listOf(1L, 3L), result.groups[0].items.map { it.id })
        assertEquals("aa", result.groups[0].sha256)
        assertEquals(500L, result.groups[0].reclaimableBytes)
        assertEquals(6, result.filesRead)
    }

    @Test
    fun unreadableFilesAreNeverCountedAsMatches() = runBlocking {
        val items = listOf(item(1, 500), item(2, 500), item(3, 500))
        // Two files cannot be read: unknown content must not form a group of "null" matches.
        val result = findDuplicates(sizeCandidates(items)) { if (it.id == 1L) "aa" else null }
        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(2L, 3L), result.unreadable.map { it.id })
        assertEquals(1, result.filesRead)
    }

    @Test
    fun theChecksumIsSha256OfTheBytes() = runBlocking {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Of(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test
    fun theDuplicateCheckStopsWhenCancelled() = runBlocking {
        val items = (1L..20L).map { item(it, 500) }
        var read = 0
        val job = async {
            findDuplicates(sizeCandidates(items)) {
                read++
                if (read == 3) throw CancellationException("stopped")
                yield()
                "aa"
            }
        }
        val failed = runCatching { job.await() }.exceptionOrNull()
        assertTrue(failed is CancellationException)
        assertEquals(3, read)
    }

    @Test
    fun aGroupLosesFilesThatLeftTheListingOrChangedSize() {
        val group = DuplicateGroup(500, "aa", listOf(item(1, 500), item(2, 500), item(3, 500)))
        assertEquals(listOf(1L, 3L), pruneGroups(listOf(group), listOf(item(1, 500), item(3, 500))).single().items.map { it.id })
        // One left and one changed size: fewer than two remain, so the group disappears.
        assertTrue(pruneGroups(listOf(group), listOf(item(1, 500), item(2, 600))).isEmpty())
    }

    @Test
    fun theLastUnselectedCopyCannotBeSelected() {
        val group = DuplicateGroup(500, "aa", listOf(item(1, 500), item(2, 500), item(3, 500)))
        assertTrue(canSelect(group, emptySet(), 1))
        assertTrue(canSelect(group, setOf(1), 2))
        assertFalse(canSelect(group, setOf(1, 2), 3))
        // An already selected file can always be cleared.
        assertTrue(canSelect(group, setOf(1, 2), 1))
    }

    private val sameContent: suspend (MediaItem) -> String? = { "aa" }

    @Test
    fun cleanupTrashesTheSelectedCopiesAndNamesTheOneThatStays() = runBlocking {
        val files = listOf(item(1, 500), item(2, 500), item(3, 500))
        val group = DuplicateGroup(500, "aa", files)
        val plan = planDuplicateCleanup(listOf(group), setOf(2, 3), files, emptyList(), sameContent)
        assertEquals(listOf(2L, 3L), plan.eligible.map { it.id })
        assertEquals(listOf(1L), plan.kept.map { it.id })
        assertEquals(1_000L, plan.totalBytes)
        assertTrue(plan.leftOut.isEmpty())
    }

    @Test
    fun selectingEveryCopyStillKeepsOne() = runBlocking {
        val files = listOf(item(1, 500), item(2, 500))
        val plan = planDuplicateCleanup(listOf(DuplicateGroup(500, "aa", files)), setOf(1, 2), files, emptyList(), sameContent)
        assertEquals(listOf(2L), plan.eligible.map { it.id })
        assertEquals(listOf(1L), plan.kept.map { it.id })
        assertEquals(listOf(1L), plan.leftOut(LeftOutReason.LAST_COPY).map { it.id })
    }

    @Test
    fun aRetainedCopyThatChangedMeansNothingInItsGroupIsTrashed() = runBlocking {
        val files = listOf(item(1, 500), item(2, 500))
        // The copy meant to stay was edited since the check: its bytes no longer match.
        val plan = planDuplicateCleanup(listOf(DuplicateGroup(500, "aa", files)), setOf(2), files, emptyList()) {
            if (it.id == 1L) "changed" else "aa"
        }
        assertTrue(plan.eligible.isEmpty())
        assertEquals(listOf(2L), plan.leftOut(LeftOutReason.LAST_COPY).map { it.id })
    }

    @Test
    fun aRetainedCopyThatLeftThePhoneMeansNothingInItsGroupIsTrashed() = runBlocking {
        val files = listOf(item(1, 500), item(2, 500))
        val plan = planDuplicateCleanup(listOf(DuplicateGroup(500, "aa", files)), setOf(2), listOf(files[1]), emptyList(), sameContent)
        assertTrue(plan.eligible.isEmpty())
    }

    @Test
    fun aSelectedCopyWhoseContentChangedIsLeftOut() = runBlocking {
        val files = listOf(item(1, 500), item(2, 500), item(3, 500))
        val plan = planDuplicateCleanup(listOf(DuplicateGroup(500, "aa", files)), setOf(2, 3), files, emptyList()) {
            when (it.id) {
                2L -> "different"
                3L -> null // could not be read now
                else -> "aa"
            }
        }
        assertTrue(plan.eligible.isEmpty())
        assertEquals(listOf(2L, 3L), plan.leftOut(LeftOutReason.CONTENT_CHANGED).map { it.id })
    }

    @Test
    fun filesATransferHoldsAreProtected() = runBlocking {
        val files = listOf(item(1, 500), item(2, 500), item(3, 500), item(4, 500), item(5, 500), item(6, 500))
        val rows = listOf(
            row(files[1], TransferStatus.QUEUED), row(files[2], TransferStatus.UPLOADING),
            row(files[3], TransferStatus.UPLOADED), row(files[4], TransferStatus.VERIFIED),
            row(files[5], TransferStatus.FAILED),
        )
        val plan = planDuplicateCleanup(listOf(DuplicateGroup(500, "aa", files)), setOf(2, 3, 4, 5, 6), files, rows, sameContent)
        assertEquals(listOf(6L), plan.eligible.map { it.id })
        assertEquals(listOf(2L, 3L, 4L, 5L), plan.leftOut(LeftOutReason.IN_TRANSFER).map { it.id })
        assertEquals(listOf(1L), plan.kept.map { it.id })
    }

    @Test
    fun aHeldCopyCanBeTheOneThatStays() = runBlocking {
        val files = listOf(item(1, 500), item(2, 500))
        // File 1 is queued for Drive and stays on the phone; file 2 may go.
        val plan = planDuplicateCleanup(
            listOf(DuplicateGroup(500, "aa", files)), setOf(2), files, listOf(row(files[0], TransferStatus.QUEUED)), sameContent,
        )
        assertEquals(listOf(2L), plan.eligible.map { it.id })
        assertEquals(listOf(1L), plan.kept.map { it.id })
    }

    @Test
    fun unselectedGroupsAreUntouchedAndUnread() = runBlocking {
        val a = listOf(item(1, 500), item(2, 500))
        val b = listOf(item(3, 700), item(4, 700))
        val read = mutableListOf<Long>()
        val plan = planDuplicateCleanup(
            listOf(DuplicateGroup(500, "aa", a), DuplicateGroup(700, "bb", b)), setOf(2), a + b, emptyList(),
        ) { read += it.id; if (it.size == 500L) "aa" else "bb" }
        assertEquals(listOf(2L), plan.eligible.map { it.id })
        assertEquals(listOf(1L, 2L), read)
    }
}
