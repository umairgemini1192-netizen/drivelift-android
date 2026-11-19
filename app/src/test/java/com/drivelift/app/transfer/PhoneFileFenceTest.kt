package com.drivelift.app.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #42 review: the duplicate cleanup's reservation against queuing, removal and other trash. */
class PhoneFileFenceTest {
    private val a = "content://media/external/images/media/1"
    private val b = "content://media/external/images/media/2"
    private val c = "content://media/external/images/media/3"
    private val listed = mapOf(a to 500L, b to 500L, c to 500L)

    private suspend fun PhoneFileFence.cleanup(
        keep: String = a,
        trash: List<String> = listOf(b),
        held: Set<String> = emptySet(),
        present: Map<String, Long> = listed,
    ) = beginCleanup(listOf(PhoneFileFence.Group(keep, trash, 500)), { held }, { present })

    @Test
    fun aFileBeingTrashedCannotBeQueuedUntilAndroidAnswered() = runBlocking {
        val fence = PhoneFileFence()
        val reservation = fence.cleanup()
        assertEquals(listOf(b), reservation.trash)
        assertEquals(setOf(b), fence.whileEnqueueing { it })
        fence.endCleanup(reservation)
        assertTrue(fence.whileEnqueueing { it }.isEmpty())
    }

    @Test
    fun aFileATransferHoldsAtTheHandoffIsLeftOut() = runBlocking {
        val fence = PhoneFileFence()
        val reservation = fence.cleanup(trash = listOf(b, c), held = setOf(b))
        assertEquals(listOf(c), reservation.trash)
        assertEquals(listOf(a), reservation.keep)
    }

    @Test
    fun aKeptCopyThatIsGoneOrChangedRefusesTheWholeGroup() = runBlocking {
        val fence = PhoneFileFence()
        assertTrue(fence.cleanup(present = listed - a).isEmpty)
        assertTrue(fence.cleanup(present = listed + (a to 600L)).isEmpty)
        // Nothing was reserved by the refused attempts.
        assertTrue(fence.whileEnqueueing { it }.isEmpty())
    }

    @Test
    fun aKeptCopyInAnOpenRemovalRequestRefusesTheGroup() = runBlocking {
        val fence = PhoneFileFence()
        assertEquals(listOf(a), fence.beginRemoval(listOf(a)))
        assertTrue(fence.cleanup().isEmpty)
        fence.endRemoval()
        assertEquals(listOf(b), fence.cleanup().trash)
    }

    @Test
    fun aKeptCopyIsLeftOutOfRemovalAndOtherTrashRequests() = runBlocking {
        val fence = PhoneFileFence()
        val reservation = fence.cleanup()
        assertEquals(listOf(c), fence.beginRemoval(listOf(a, b, c)))
        fence.endRemoval()
        assertEquals(listOf(c), fence.beginTrash(listOf(a, c)))
        fence.endTrash(listOf(c))
        fence.endCleanup(reservation)
        assertEquals(listOf(a, b), fence.beginRemoval(listOf(a, b)))
    }

    @Test
    fun aHeldKeptCopyStillStays() = runBlocking {
        val fence = PhoneFileFence()
        assertEquals(listOf(b), fence.cleanup(held = setOf(a)).trash)
    }

    @Test
    fun queuingWaitsForACleanupCheckInProgress() = runBlocking {
        val fence = PhoneFileFence()
        val inCheck = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanup = async {
            fence.beginCleanup(listOf(PhoneFileFence.Group(a, listOf(b), 500)), {
                inCheck.complete(Unit)
                release.await()
                emptySet()
            }, { listed })
        }
        inCheck.await()
        // Queuing started while the cleanup is checking: it runs after the reservation and sees it.
        val queued = async { fence.whileEnqueueing { it } }
        release.complete(Unit)
        assertEquals(listOf(b), cleanup.await().trash)
        assertEquals(setOf(b), queued.await())
    }
}
