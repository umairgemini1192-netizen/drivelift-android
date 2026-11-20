package com.drivelift.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The saved Drive folder count (issue #14): what is written reads back exactly, and nothing else does. */
class DriveFolderStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val root = "ROOT"

    private fun folder(id: String, name: String, vararg parents: String) =
        DriveEntry(id, name, FOLDER_MIME_TYPE, null, parents.toList())

    private fun file(id: String, size: Long?, vararg parents: String, mine: Boolean = true) =
        DriveEntry(id, "", "image/jpeg", size, parents.toList(), ownedByMe = mine)

    /** Names with every separator the file format uses, an escape lookalike, and non-ASCII text. */
    private val tree = driveFolderTotals(
        root, "My Drive",
        listOf(
            folder("a", "Tabs\tand, commas", root),
            folder("b", "100% done\nsecond line", "a"),
            folder("c-%41", "Fotos été الصور", root),
            file("1", 1_000, "a"),
            file("2", null, "b"),
            file("3", 5_000_000_000L, "c-%41", mine = false),
            file("4", 7, root),
            file("x", 9, "outside"),
        ),
    )

    private fun store() = DriveFolderStore(File(dir.root, "nested/drive-folder-counts.txt"))

    @Test
    fun aCompleteCountReadsBackExactly() {
        val saved = SavedDriveCount("Someone@Example.com", tree, items = 8, at = 1_773_583_500_000L, stop = null)
        store().save(saved)
        assertEquals(saved, store().load())
    }

    @Test
    fun everyIncompleteReasonReadsBack() {
        for (stop in listOf(
            ScanStop.ByUser,
            ScanStop.TooMany,
            ScanStop.Error("Connection problem: timeout, 50% read.\tCheck it.", needsSignIn = false),
            ScanStop.Error("Google Drive needs you to sign in again.", needsSignIn = true),
        )) {
            val saved = SavedDriveCount("a@b.c", tree, 40, 1L, stop)
            store().save(saved)
            assertEquals(saved, store().load())
        }
    }

    @Test
    fun aCountForAChangedAccountIsNeverSaved() {
        try {
            store().save(SavedDriveCount("a@b.c", tree, 1, 1L, ScanStop.AccountChanged))
            fail("saved")
        } catch (expected: IllegalArgumentException) {
        }
        assertNull(store().load())
    }

    @Test
    fun aNewerSaveReplacesTheOlderOne() {
        store().save(SavedDriveCount("a@b.c", tree, 8, 1L, ScanStop.ByUser))
        val newer = SavedDriveCount("a@b.c", tree, 8, 2L, null)
        store().save(newer)
        assertEquals(newer, store().load())
        assertFalse("no temporary file is left", File(dir.root, "nested/drive-folder-counts.txt.tmp").exists())
    }

    @Test
    fun clearRemovesTheSavedCount() {
        store().save(SavedDriveCount("a@b.c", tree, 8, 1L, null))
        store().clear()
        assertNull(store().load())
    }

    @Test
    fun nothingSavedReadsAsNothing() {
        assertNull(store().load())
    }

    @Test
    fun aCutShortDamagedOrOlderFileReadsAsNothing() {
        val path = File(dir.root, "nested/drive-folder-counts.txt")
        store().save(SavedDriveCount("a@b.c", tree, 8, 1L, null))
        val lines = path.readLines()

        // Cut short: the end line, or a folder line with it, is missing.
        path.writeText(lines.dropLast(1).joinToString("\n"))
        assertNull(store().load())
        path.writeText((lines.dropLast(2) + lines.last()).joinToString("\n"))
        assertNull(store().load())

        // Another format version, garbage, a damaged number, an empty file.
        path.writeText((listOf("drivelift-drive-folders\t2") + lines.drop(1)).joinToString("\n"))
        assertNull(store().load())
        path.writeText("not a saved count")
        assertNull(store().load())
        path.writeText(lines.joinToString("\n").replace("\nitems\t8", "\nitems\teight"))
        assertNull(store().load())
        path.writeText("")
        assertNull(store().load())
    }

    @Test
    fun escapingRoundTripsAndRejectsACutShortEscape() {
        for (s in listOf("", "plain", "%", "%%41", "a\tb,c\nd\re%25")) {
            assertEquals(s, DriveFolderStore.unesc(DriveFolderStore.esc(s)))
        }
        try {
            DriveFolderStore.unesc("abc%4")
            fail("accepted")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
