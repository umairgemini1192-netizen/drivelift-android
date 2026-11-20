package com.drivelift.app.data

import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move pill: which Drive files it holds before, during and after a move. */
class DriveMoveClipTest {
    private fun file(id: String, size: Long = 100) = DriveFile(id, "$id.jpg", "image/jpeg", size, "00", false)
    private fun folder(id: String) = DriveFile(id, id, DriveClient.FOLDER_MIME, 0, null, false)

    private fun clip(vararg ids: String) =
        holdForMove(ids.map { file(it) }, "src", "Holiday", "me@example.com")!!

    @Test
    fun holdingKeepsFilesOnlyAndNeedsAtLeastOne() {
        val held = holdForMove(listOf(file("a", 10), folder("f"), file("b", 20)), "src", "Holiday", "me@example.com")!!
        assertEquals(listOf("a", "b"), held.files.map { it.id })
        assertEquals(30L, held.totalBytes)
        assertNull(holdForMove(listOf(folder("f")), "src", "Holiday", "me@example.com"))
        assertNull(holdForMove(emptyList(), "src", "Holiday", "me@example.com"))
        assertNull(holdForMove(listOf(file("a")), "src", "Holiday", " ")!!.account)
    }

    @Test
    fun theSourceFolderIsNotATarget() {
        val held = clip("a")
        assertFalse(held.canMoveInto("src"))
        assertTrue(held.canMoveInto("dst"))
    }

    @Test
    fun movedAndHeldFilesLeaveFailedAndUntriedOnesStay() {
        val held = clip("a", "b", "c", "d", "e")
        val plan = DriveMovePlan(
            eligible = listOf(file("a"), file("b"), file("c"), file("d")),
            folders = emptyList(),
            heldByTransfer = listOf(file("e")),
            fromFolderId = "src", toFolderId = "dst", toLabel = "My Drive / Dst",
        )
        val outcome = DriveMoveOutcome(
            toName = "Dst",
            moved = listOf(file("a")),
            failed = listOf(file("b") to "network"),
            skipped = listOf(file("c")),
            notTried = listOf(file("d")),
        )
        assertEquals(listOf("b", "d"), held.afterMove(plan, outcome)!!.files.map { it.id })
    }

    @Test
    fun aFullMoveEmptiesThePill() {
        val held = clip("a")
        val plan = DriveMovePlan(listOf(file("a")), emptyList(), emptyList(), "src", "dst", "Dst")
        assertNull(held.afterMove(plan, DriveMoveOutcome("Dst", listOf(file("a")), emptyList())))
    }

    @Test
    fun aMoveFromAnotherFolderLeavesThePillAlone() {
        val held = clip("a")
        val plan = DriveMovePlan(listOf(file("a")), emptyList(), emptyList(), "other", "dst", "Dst")
        assertSame(held, held.afterMove(plan, DriveMoveOutcome("Dst", listOf(file("a")), emptyList())))
    }

    @Test
    fun trashedFilesLeaveAndRenamesAreKept() {
        val held = clip("a", "b")
        assertEquals(listOf("b"), held.without(setOf("a"))!!.files.map { it.id })
        assertNull(held.without(setOf("a", "b")))
        val renamed = file("b").copy(name = "Beach.jpg")
        assertEquals("Beach.jpg", held.renamed(renamed).files[1].name)
    }

    @Test
    fun foldersLeftOutOfMoveAreNamed() {
        assertNull(holdNotice(listOf(file("a"), file("b"))))
        assertEquals("Folders can't be moved yet. Select files to move.", holdNotice(listOf(folder("f"), folder("g"))))
        assertEquals("1 folder stays where it is; folders can't be moved yet.", holdNotice(listOf(file("a"), folder("f"))))
        assertEquals(
            "2 folders stay where they are; folders can't be moved yet.",
            holdNotice(listOf(folder("f"), file("a"), folder("g"), file("b"), file("c"))),
        )
    }
}
