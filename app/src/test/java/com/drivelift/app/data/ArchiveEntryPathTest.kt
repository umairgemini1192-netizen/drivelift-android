package com.drivelift.app.data

import com.drivelift.app.data.ArchiveEntryPath.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scoped-storage/SAF design slice (issue #42): archive entry names and archive limits. */
class ArchiveEntryPathTest {
    private fun refused(raw: String): String {
        val result = ArchiveEntryPath.check(raw)
        assertTrue("expected refusal for [$raw] but got $result", result is Result.Refused)
        return (result as Result.Refused).reason
    }

    private fun accepted(raw: String): List<String> {
        val result = ArchiveEntryPath.check(raw)
        assertTrue("expected acceptance for [$raw] but got $result", result is Result.Accepted)
        return (result as Result.Accepted).components
    }

    @Test
    fun ordinaryNestedPathsAreAccepted() {
        assertEquals(listOf("DCIM", "Camera", "IMG_1.jpg"), accepted("DCIM/Camera/IMG_1.jpg"))
        assertEquals(listOf("docs"), accepted("docs/"))
        assertEquals(listOf("a b", "c-d.txt"), accepted("a b/c-d.txt"))
    }

    @Test
    fun traversalAndAbsolutePathsAreRefused() {
        refused("../secret.txt")
        refused("a/../../secret.txt")
        refused("a/./b.txt")
        refused("/etc/passwd")
        refused("C:/Windows/x.dll")
        refused("C:evil.txt")
        refused("..\\evil.txt")
        refused("a\\..\\evil.txt")
    }

    @Test
    fun emptyAndDoubledSeparatorsAreRefused() {
        refused("")
        refused("a//b.txt")
        refused("//host/share")
    }

    @Test
    fun controlAndNulCharactersAreRefused() {
        refused("a\u0000b.txt")
        refused("a\nb.txt")
        refused("tab\tname")
    }

    @Test
    fun namesStorageCannotKeepAreRefused() {
        refused("trailing./x")
        refused("name./x")
        refused("name /x")
    }

    @Test
    fun overlongNamesAreRefused() {
        refused("a".repeat(ArchiveEntryPath.MAX_COMPONENT_BYTES + 1) + ".txt")
        refused("a/".repeat(600) + "b")
        assertNull(
            (ArchiveEntryPath.check("a".repeat(ArchiveEntryPath.MAX_COMPONENT_BYTES)) as? Result.Refused)?.reason,
        )
    }

    @Test
    fun multiByteNamesAreCountedInBytes() {
        // Each "e" with an acute accent is 2 bytes in UTF-8, so 128 of them is 256 bytes.
        val name = "\u00e9".repeat(128)
        refused(name)
    }

    @Test
    fun limitsReportTheFirstBreach() {
        val limits = ArchiveLimits(maxEntries = 3, maxDepth = 2, maxTotalBytes = 100)
        assertNull(limits.violation(entries = 3, depth = 2, totalBytes = 100))
        assertTrue(limits.violation(entries = 4, depth = 0, totalBytes = 0)!!.contains("entries"))
        assertTrue(limits.violation(entries = 0, depth = 3, totalBytes = 0)!!.contains("deeper"))
        assertTrue(limits.violation(entries = 0, depth = 0, totalBytes = 101)!!.contains("expands"))
    }
}
