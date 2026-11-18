package com.drivelift.app.data

import com.drivelift.app.drive.DriveFile
import java.time.Instant

/** What a folder listing is ordered by (file manager, issue #36). */
enum class SortField { DATE, NAME, SIZE }

/**
 * One of the six sort choices offered in Phone folders and Drive folders. A name or size sort
 * breaks ties by name and then id; a date sort keeps files with the same date in the listing's own
 * order (MediaStore or Drive), so the default view is unchanged and every order is repeatable.
 */
enum class FileSort(val field: SortField, val descending: Boolean, val label: String) {
    DATE_NEWEST(SortField.DATE, true, "Date, newest first"),
    DATE_OLDEST(SortField.DATE, false, "Date, oldest first"),
    NAME_AZ(SortField.NAME, false, "Name, A to Z"),
    NAME_ZA(SortField.NAME, true, "Name, Z to A"),
    SIZE_LARGEST(SortField.SIZE, true, "Size, largest first"),
    SIZE_SMALLEST(SortField.SIZE, false, "Size, smallest first");

    companion object {
        /** Phone folders open newest first, as before sorting existed. */
        val PHONE_DEFAULT = DATE_NEWEST

        /** Drive folders open by name, as Drive listed them before sorting existed. */
        val DRIVE_DEFAULT = NAME_AZ

        fun fromStored(value: String?, default: FileSort): FileSort = entries.firstOrNull { it.name == value } ?: default
    }
}

/**
 * Orders [items] by [sort] with a stable sort. [date] and [size] give the sort keys; for name and
 * size, [name] and then [id] break ties. Names compare without regard to case.
 */
fun <T> sortedFiles(
    items: List<T>,
    sort: FileSort,
    name: (T) -> String,
    id: (T) -> String,
    date: (T) -> Long,
    size: (T) -> Long,
): List<T> {
    val byName = compareBy<T, String>(NaturalOrder) { name(it) }.thenBy { name(it) }
    val ordered: Comparator<T> = when (sort.field) {
        SortField.DATE -> if (sort.descending) compareByDescending<T> { date(it) } else compareBy<T> { date(it) }
        SortField.SIZE -> (if (sort.descending) compareByDescending<T> { size(it) } else compareBy<T> { size(it) })
            .then(byName).thenBy { id(it) }
        SortField.NAME -> (if (sort.descending) byName.reversed() else byName).thenBy { id(it) }
    }
    return items.sortedWith(ordered)
}

/**
 * Compares names the way people read them: without regard to case, and with runs of digits
 * compared as numbers, so "IMG_2" comes before "IMG_10" (as Drive's natural name order does).
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val byValue = numA.compareTo(numB)
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }
}

/** True when [name] contains [query], ignoring case and surrounding spaces. A blank query matches all. */
fun matchesQuery(name: String, query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || name.contains(q, ignoreCase = true)
}

/** Drive's modified time in milliseconds, or 0 when Drive did not give one. */
fun driveModifiedMs(file: DriveFile): Long =
    file.modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

/**
 * A Drive folder listing as shown: entries whose name matches [query], folders first, each group
 * ordered by [sort] (folders have no size, so a size sort orders them by name).
 */
fun shownDriveEntries(entries: List<DriveFile>, query: String, sort: FileSort): List<DriveFile> {
    val matching = entries.filter { matchesQuery(it.name, query) }
    val (folders, files) = matching.partition { it.isFolder }
    val folderSort = if (sort.field == SortField.SIZE) FileSort.NAME_AZ else sort
    return sortedFiles(folders, folderSort, { it.name }, { it.id }, ::driveModifiedMs) { 0L } +
        sortedFiles(files, sort, { it.name }, { it.id }, ::driveModifiedMs) { it.size }
}
