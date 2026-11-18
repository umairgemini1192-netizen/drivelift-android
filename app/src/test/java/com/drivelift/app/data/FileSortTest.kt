package com.drivelift.app.data

import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #36: deterministic sort choices and search within the folder shown. */
class FileSortTest {
    private data class F(val id: String, val name: String, val date: Long, val size: Long)

    private fun sorted(items: List<F>, sort: FileSort) =
        sortedFiles(items, sort, { it.name }, { it.id }, { it.date }, { it.size }).map { it.id }

    private val items = listOf(
        F("a", "IMG_10.jpg", 300, 50),
        F("b", "img_2.jpg", 100, 50),
        F("c", "Beach.jpg", 300, 900),
        F("d", "zebra.png", 200, 10),
    )

    @Test
    fun eachChoiceOrdersAsLabelled() {
        assertEquals(listOf("a", "c", "d", "b"), sorted(items, FileSort.DATE_NEWEST))
        assertEquals(listOf("b", "d", "a", "c"), sorted(items, FileSort.DATE_OLDEST))
        assertEquals(listOf("c", "b", "a", "d"), sorted(items, FileSort.NAME_AZ))
        assertEquals(listOf("d", "a", "b", "c"), sorted(items, FileSort.NAME_ZA))
        // Equal sizes fall back to name order.
        assertEquals(listOf("c", "b", "a", "d"), sorted(items, FileSort.SIZE_LARGEST))
        assertEquals(listOf("d", "b", "a", "c"), sorted(items, FileSort.SIZE_SMALLEST))
    }

    @Test
    fun equalDatesKeepTheListingOrder() {
        val sameDay = listOf(F("x", "b", 5, 1), F("y", "a", 5, 1), F("z", "c", 5, 1))
        assertEquals(listOf("x", "y", "z"), sorted(sameDay, FileSort.DATE_NEWEST))
        assertEquals(listOf("x", "y", "z"), sorted(sameDay, FileSort.DATE_OLDEST))
    }

    @Test
    fun namesCompareNaturallyAndWithoutCase() {
        assertTrue(NaturalOrder.compare("IMG_2", "IMG_10") < 0)
        assertTrue(NaturalOrder.compare("img_2", "IMG_10") < 0)
        assertTrue(NaturalOrder.compare("file9", "file10") < 0)
        assertTrue(NaturalOrder.compare("Archive", "archive 2024") < 0)
        assertEquals(0, NaturalOrder.compare("Same", "same"))
        // Identical ignoring case still has a fixed order through the tie-break.
        val twins = listOf(F("1", "Same", 0, 0), F("2", "same", 0, 0))
        assertEquals(sorted(twins, FileSort.NAME_AZ), sorted(twins.reversed(), FileSort.NAME_AZ))
    }

    @Test
    fun searchMatchesPartOfTheNameIgnoringCaseAndSpaces() {
        assertTrue(matchesQuery("Sunset_0019.jpg", " sunset "))
        assertTrue(matchesQuery("Sunset_0019.jpg", "0019"))
        assertTrue(matchesQuery("anything", "   "))
        assertFalse(matchesQuery("Sunset_0019.jpg", "beach"))
    }

    @Test
    fun driveListingKeepsFoldersFirstAndFiltersBoth() {
        fun folder(id: String, name: String) = DriveFile(id, name, DriveClient.FOLDER_MIME, 0, null, false)
        fun file(id: String, name: String, size: Long, modified: String?) =
            DriveFile(id, name, "image/jpeg", size, "00", false, modifiedTime = modified)
        val entries = listOf(
            file("d1", "small.jpg", 10, "2026-01-02T00:00:00Z"),
            folder("f2", "Zoo"),
            file("d2", "big.jpg", 999, "2026-01-01T00:00:00Z"),
            folder("f1", "Album"),
        )
        assertEquals(listOf("f1", "f2", "d2", "d1"), shownDriveEntries(entries, "", FileSort.SIZE_LARGEST).map { it.id })
        // Folders have no date: a date sort keeps them in Drive's order, still ahead of the files.
        assertEquals(listOf("f2", "f1", "d1", "d2"), shownDriveEntries(entries, "", FileSort.DATE_NEWEST).map { it.id })
        assertEquals(listOf("f1", "d1"), shownDriveEntries(entries, "a", FileSort.NAME_ZA).map { it.id })
        assertEquals(listOf("d2"), shownDriveEntries(entries, "BIG", FileSort.NAME_AZ).map { it.id })
        assertEquals(0L, driveModifiedMs(file("x", "x", 1, "not a date")))
    }

    @Test
    fun storedChoiceFallsBackToEachTabsDefault() {
        assertEquals(FileSort.NAME_ZA, FileSort.fromStored("NAME_ZA", FileSort.PHONE_DEFAULT))
        assertEquals(FileSort.DATE_NEWEST, FileSort.fromStored(null, FileSort.PHONE_DEFAULT))
        assertEquals(FileSort.NAME_AZ, FileSort.fromStored("gone", FileSort.DRIVE_DEFAULT))
    }
}
