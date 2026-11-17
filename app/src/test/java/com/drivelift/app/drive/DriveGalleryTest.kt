package com.drivelift.app.drive

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveGalleryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun trustedHostValidationBlocksHttpAndSpoofedDomains() {
        assertTrue(isTrustedDriveMediaUrl("https://www.googleapis.com/drive/v3/files/abc?alt=media".toHttpUrl()))
        assertTrue(isTrustedDriveMediaUrl("https://lh3.googleusercontent.com/drive-storage/xyz=s220".toHttpUrl()))
        assertTrue(isTrustedDriveMediaUrl("https://drive.usercontent.google.com/download?id=abc".toHttpUrl()))

        assertFalse(isTrustedDriveMediaUrl("http://www.googleapis.com/drive/v3/files/abc".toHttpUrl()))
        assertFalse(isTrustedDriveMediaUrl("https://evil.example.com/file.jpg".toHttpUrl()))
        assertFalse(isTrustedDriveMediaUrl("https://googleapis.com.evil.com/file.jpg".toHttpUrl()))
        assertFalse(isTrustedDriveMediaUrl("https://googleusercontent.com.evil.com/file.jpg".toHttpUrl()))
    }

    @Test
    fun thumbnailUrlUpgradeClampsEdgeAndRejectsUntrustedLinks() {
        assertEquals(
            "https://lh3.googleusercontent.com/thumb/abc=s512",
            upgradedThumbnailUrl("https://lh3.googleusercontent.com/thumb/abc=s220", 512).toString(),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/thumb/abc=s1024",
            upgradedThumbnailUrl("https://lh3.googleusercontent.com/thumb/abc=s220", 4000).toString(),
        )
        assertNull(upgradedThumbnailUrl("https://evil.example.com/thumb=s220", 512))
        assertNull(upgradedThumbnailUrl("http://lh3.googleusercontent.com/thumb=s220", 512))
    }

    @Test
    fun supportMatrixClassifiesFormatsExplicitly() {
        assertEquals(DrivePreviewKind.IMAGE, classifyDriveFile("image/jpeg"))
        assertEquals(DrivePreviewKind.IMAGE, classifyDriveFile("image/heic"))
        assertEquals(DrivePreviewKind.VIDEO, classifyDriveFile("video/mp4"))
        assertEquals(DrivePreviewKind.VIDEO, classifyDriveFile("video/quicktime"))
        assertEquals(DrivePreviewKind.PDF, classifyDriveFile("application/pdf"))
        assertEquals(DrivePreviewKind.WORKSPACE_PDF_EXPORT, classifyDriveFile("application/vnd.google-apps.document"))
        assertEquals(DrivePreviewKind.WORKSPACE_PDF_EXPORT, classifyDriveFile("application/vnd.google-apps.spreadsheet"))
        assertEquals(DrivePreviewKind.WORKSPACE_PDF_EXPORT, classifyDriveFile("application/vnd.google-apps.presentation"))
        assertEquals(DrivePreviewKind.UNSUPPORTED, classifyDriveFile("application/zip"))
        assertEquals(DrivePreviewKind.UNSUPPORTED, classifyDriveFile("application/octet-stream"))
    }

    @Test
    fun previewCacheIsolatesAccountsAndEvictsOldestEntries() {
        val cache = DrivePreviewCache(tempFolder.newFolder("preview_cache"), maxBytes = 10)
        val aliceBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val bobBytes = byteArrayOf(9, 8, 7, 6)

        assertNotEquals(
            cache.cacheKey("alice@example.com", "same-file", "thumb_512"),
            cache.cacheKey("bob@example.com", "same-file", "thumb_512"),
        )
        assertEquals(
            cache.cacheKey("Alice@Example.com", "same-file", "thumb_512"),
            cache.cacheKey("alice@example.com", "same-file", "thumb_512"),
        )

        cache.putBytes("alice@example.com", "file-1", "thumb_512", aliceBytes)
        cache.putBytes("bob@example.com", "file-1", "thumb_512", bobBytes)
        assertArrayEquals(aliceBytes, cache.getBytes("alice@example.com", "file-1", "thumb_512"))
        assertArrayEquals(bobBytes, cache.getBytes("bob@example.com", "file-1", "thumb_512"))

        // Exceeding 10 bytes evicts the oldest entry (alice).
        val carolBytes = byteArrayOf(10, 11, 12, 13, 14, 15, 16)
        cache.putBytes("carol@example.com", "file-1", "thumb_512", carolBytes)
        assertNull(cache.getBytes("alice@example.com", "file-1", "thumb_512"))
        assertArrayEquals(carolBytes, cache.getBytes("carol@example.com", "file-1", "thumb_512"))

        cache.clear()
        assertNull(cache.getBytes("carol@example.com", "file-1", "thumb_512"))
    }

    @Test
    fun cacheKeysInvalidateWhenDriveFileContentChanges() {
        val cache = DrivePreviewCache(tempFolder.newFolder("versioned_cache"), maxBytes = 1024)
        val original = DriveFile(
            id = "same-id",
            name = "Photo.jpg",
            mimeType = "image/jpeg",
            size = 1_200L,
            md5 = "abc111",
            trashed = false,
            modifiedTime = "2026-11-17T10:00:00Z",
        )
        val replaced = original.copy(
            size = 1_450L,
            md5 = "def222",
            modifiedTime = "2026-11-17T11:00:00Z",
        )

        val v1Variant = driveCacheVariant("thumb_512", original)
        val v2Variant = driveCacheVariant("thumb_512", replaced)
        assertNotEquals(v1Variant, v2Variant)
        assertNotEquals(
            driveMemoryCacheKey("alice@example.com", original),
            driveMemoryCacheKey("alice@example.com", replaced),
        )

        cache.putBytes("alice@example.com", original.id, v1Variant, byteArrayOf(1, 2, 3))
        assertNull(cache.getBytes("alice@example.com", replaced.id, v2Variant))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.getBytes("alice@example.com", original.id, v1Variant))
    }
}
