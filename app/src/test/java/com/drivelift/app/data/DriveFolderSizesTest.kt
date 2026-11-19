package com.drivelift.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Storage folder details (issue #42): counting rules for My Drive folders, on synthetic entries. */
class DriveFolderSizesTest {
    private val root = "ROOT"

    private fun folder(id: String, vararg parents: String) = DriveEntry(id, "Folder $id", FOLDER_MIME_TYPE, null, parents.toList())
    private fun file(id: String, size: Long?, vararg parents: String, mine: Boolean = true) =
        DriveEntry(id, "", "image/jpeg", size, parents.toList(), ownedByMe = mine)
    private fun doc(id: String, vararg parents: String) =
        DriveEntry(id, "", "application/vnd.google-apps.document", null, parents.toList())
    private fun shortcut(id: String, vararg parents: String) = DriveEntry(id, "", SHORTCUT_MIME_TYPE, null, parents.toList())

    private fun totals(vararg entries: DriveEntry) = driveFolderTotals(root, "My Drive", entries.toList())

    @Test
    fun totalsIncludeEverySubfolderAndDirectFilesStayDirect() {
        val tree = totals(
            folder("A", root), folder("B", "A"), folder("C", "B"),
            file("f1", 100, root), file("f2", 10, "A"), file("f3", 20, "B"), file("f4", 30, "C"),
        )
        val a = tree.folders.getValue("A")
        assertEquals(3, a.fileCount)
        assertEquals(60, a.bytes)
        assertEquals(2, a.folderCount)
        assertEquals(1, a.directFileCount)
        assertEquals(10, a.directBytes)
        assertEquals(listOf("B"), a.children)
        assertEquals(4, tree.root.fileCount)
        assertEquals(160, tree.root.bytes)
        assertEquals(3, tree.root.folderCount)
        assertEquals(100, tree.root.directBytes)
    }

    @Test
    fun childrenAreLargestFirstAndTiesGoByName() {
        val tree = totals(
            folder("small", root), folder("big", root), folder("tieB", root), folder("tieA", root),
            file("1", 5, "small"), file("2", 500, "big"), file("3", 50, "tieB"), file("4", 50, "tieA"),
        )
        assertEquals(listOf("big", "tieA", "tieB", "small"), tree.root.children)
    }

    @Test
    fun aFileWithTwoParentsIsCountedOnceInEachFolderAndOnceOverall() {
        // P and Q both sit under A, so the file reaches A twice and must still count once there.
        val tree = totals(folder("A", root), folder("P", "A"), folder("Q", "A"), file("x", 1_000, "P", "Q"))
        assertEquals(1, tree.root.fileCount)
        assertEquals(1_000, tree.root.bytes)
        assertEquals(1, tree.folders.getValue("A").fileCount)
        assertEquals(1_000, tree.folders.getValue("A").bytes)
        assertEquals(1_000, tree.folders.getValue("P").bytes)
        assertEquals(1_000, tree.folders.getValue("Q").bytes)
    }

    @Test
    fun aRepeatedEntryFromTheListingIsCountedOnce() {
        val tree = totals(folder("A", root), file("x", 7, "A"), file("x", 7, "A"))
        assertEquals(1, tree.root.fileCount)
        assertEquals(7, tree.root.bytes)
    }

    @Test
    fun shortcutsAreCountedAsItemsAndNeverFollowed() {
        val tree = totals(
            folder("A", root), folder("B", root),
            file("target", 5_000, "A"),
            shortcut("s1", "B"),
        )
        assertEquals(2, tree.root.fileCount)
        assertEquals("the target's size is counted once, in A only", 5_000, tree.root.bytes)
        val b = tree.folders.getValue("B")
        assertEquals(1, b.fileCount)
        assertEquals(1, b.shortcutCount)
        assertEquals(0, b.bytes)
        assertEquals(0, b.unsizedCount)
        assertEquals(1, tree.root.shortcutCount)
    }

    @Test
    fun filesWithoutASizeAreCountedButAddNoBytes() {
        val tree = totals(folder("A", root), doc("d1", "A"), doc("d2", "A"), file("f", 40, "A"))
        val a = tree.folders.getValue("A")
        assertEquals(3, a.fileCount)
        assertEquals(2, a.unsizedCount)
        assertEquals(40, a.bytes)
    }

    @Test
    fun bytesOtherPeopleOwnAreCountedAndReportedApart() {
        val tree = totals(folder("A", root), file("mine", 100, "A"), file("theirs", 900, "A", mine = false))
        assertEquals(1_000, tree.root.bytes)
        assertEquals(900, tree.root.othersBytes)
    }

    @Test
    fun itemsOutsideMyDriveAreNotCounted() {
        val tree = totals(
            folder("A", root), file("in", 10, "A"),
            // Shared with the user, not added: its parent is someone else's folder.
            folder("S", "someone-else"), file("shared", 999, "S"), file("loose", 5),
        )
        assertEquals(1, tree.root.fileCount)
        assertEquals(10, tree.root.bytes)
        assertEquals(3, tree.outsideCount)
        assertNull(tree.folders["S"])
    }

    @Test
    fun aLoopInTheParentsEndsAndCountsEachFolderOnce() {
        // A -> B -> A loop with B also under the root. Drive should never report this.
        val tree = totals(folder("A", root, "B"), folder("B", "A"), file("x", 3, "B"))
        assertEquals(1, tree.root.fileCount)
        assertEquals(3, tree.folders.getValue("A").bytes)
        assertEquals(3, tree.folders.getValue("B").bytes)
        // A loop with no way to the root is outside My Drive.
        val detached = totals(folder("L1", "L2"), folder("L2", "L1"), file("y", 1, "L1"))
        assertEquals(0, detached.root.fileCount)
        assertEquals(3, detached.outsideCount)
    }

    @Test
    fun anEmptyDriveHasAnEmptyRoot() {
        val tree = totals()
        assertEquals(0, tree.root.fileCount)
        assertEquals(0, tree.root.bytes)
        assertTrue(tree.root.children.isEmpty())
    }

    @Test
    fun pathToGoesFromTheRootDownAndFallsBackToTheRoot() {
        val tree = totals(folder("A", root), folder("B", "A"), file("x", 1, "B"))
        assertEquals(listOf(root, "A", "B"), tree.pathTo("B").map { it.id })
        assertEquals(listOf(root), tree.pathTo("unknown").map { it.id })
        assertEquals(listOf("B"), tree.childrenOf("A").map { it.id })
        assertTrue(tree.childrenOf("unknown").isEmpty())
    }

    @Test
    fun aLargeFlatDriveIsCountedQuickly() {
        val entries = ArrayList<DriveEntry>()
        repeat(200) { f -> entries += folder("F$f", root) }
        repeat(100_000) { i -> entries += file("x$i", 1_000, "F${i % 200}") }
        val tree = driveFolderTotals(root, "My Drive", entries)
        assertEquals(100_000, tree.root.fileCount)
        assertEquals(100_000_000L, tree.root.bytes)
        assertEquals(500, tree.folders.getValue("F0").fileCount)
    }

    // ------------------------------------------------------------------ page walking

    private fun describe(e: Exception) = ScanStop.Error(e.message ?: "error", needsSignIn = false)

    private fun pages(vararg sizes: Int): Map<String?, DriveEntryPage> {
        val out = LinkedHashMap<String?, DriveEntryPage>()
        var token: String? = null
        var n = 0
        sizes.forEachIndexed { index, size ->
            val next = if (index == sizes.lastIndex) null else "t${index + 1}"
            out[token] = DriveEntryPage(List(size) { file("p${n++}", 1, root) }, next)
            token = next
        }
        return out
    }

    @Test
    fun readsEveryPageUntilThereIsNoNextToken() = runBlocking {
        val source = pages(1000, 1000, 3)
        val progress = mutableListOf<Pair<Int, Int>>()
        val scan = scanDriveEntries({ source.getValue(it) }, { true }, ::describe) { items, p -> progress += items to p }
        assertTrue(scan.complete)
        assertEquals(2003, scan.entries.size)
        assertEquals(3, scan.pages)
        assertEquals(listOf(1000 to 1, 2000 to 2, 2003 to 3), progress)
    }

    @Test
    fun anErrorPartWayKeepsWhatWasReadAndSaysWhy() = runBlocking {
        val source = pages(1000, 1000, 1000)
        val scan = scanDriveEntries({ token ->
            if (token == "t2") throw IOException("timeout")
            source.getValue(token)
        }, { true }, ::describe)
        assertFalse(scan.complete)
        assertEquals(2000, scan.entries.size)
        assertEquals(ScanStop.Error("timeout", needsSignIn = false), scan.stop)
    }

    @Test
    fun aRepeatedPageTokenEndsTheCountInsteadOfLooping() = runBlocking {
        val scan = scanDriveEntries({ DriveEntryPage(listOf(file("a", 1, root)), "same") }, { true }, ::describe)
        assertTrue(scan.stop is ScanStop.Error)
        assertEquals(2, scan.pages)
    }

    @Test
    fun theCountStopsAtTheCeiling() = runBlocking {
        val source = pages(10, 10, 10)
        val scan = scanDriveEntries({ source.getValue(it) }, { true }, ::describe, maxItems = 15)
        assertEquals(ScanStop.TooMany, scan.stop)
        assertEquals(20, scan.entries.size)
        // Reaching the ceiling on the last page is still complete.
        val exact = scanDriveEntries({ pages(10, 5).getValue(it) }, { true }, ::describe, maxItems = 15)
        assertTrue(exact.complete)
    }

    @Test
    fun anAccountChangeStopsBeforeTheNextPageIsAskedFor() = runBlocking {
        val source = pages(10, 10, 10)
        var pinned = true
        val asked = mutableListOf<String?>()
        val scan = scanDriveEntries({ token ->
            asked += token
            source.getValue(token).also { if (token == "t1") pinned = false }
        }, { pinned }, ::describe)
        assertEquals(ScanStop.AccountChanged, scan.stop)
        assertEquals(listOf(null, "t1"), asked)
        assertEquals("the page read after the change is not kept", 10, scan.entries.size)
    }

    @Test
    fun aStopRequestEndsAfterTheCurrentPageAndKeepsWhatWasRead() = runBlocking {
        val source = pages(10, 10, 10)
        var stop = false
        val scan = scanDriveEntries(
            { token -> source.getValue(token).also { if (token == "t1") stop = true } },
            { true }, ::describe, stopRequested = { stop },
        )
        assertEquals(ScanStop.ByUser, scan.stop)
        assertEquals(20, scan.entries.size)
    }

    @Test
    fun cancellationIsPassedOnNotTurnedIntoAnIncompleteResult() {
        try {
            runBlocking { scanDriveEntries({ throw CancellationException("gone") }, { true }, ::describe) }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }
}
