package com.drivelift.app.data

import android.net.Uri
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Issues #36 and #42: where a favorite is now, and which Drive favorites may be looked up. */
@RunWith(RobolectricTestRunner::class)
class FavoritesTest {
    private val mine = accountHash("me@example.com")
    private val other = accountHash("someone.else@example.com")

    private fun item(id: Long, bucket: Long = 100) = MediaItem(
        id = id, uri = Uri.parse("content://media/external/images/media/$id"), name = "IMG_$id.jpg", size = 100,
        mime = "image/jpeg", dateTakenMs = 0, dateModifiedMs = 0, bucketId = bucket, bucketName = "Camera",
        relativePath = "DCIM/Camera/", isVideo = false, durationMs = 0,
    )

    private fun driveFile(id: String, trashed: Boolean = false, folder: Boolean = false) =
        DriveFile(id, "File $id", if (folder) DriveClient.FOLDER_MIME else "image/jpeg", 10, null, trashed)

    private fun ctx(
        loaded: Boolean = true,
        visibility: MediaVisibility = MediaVisibility.FULL,
        items: List<MediaItem> = emptyList(),
        connected: String? = mine,
        checks: Map<String, DriveCheck> = emptyMap(),
    ) = FavoriteContext(
        phoneLoaded = loaded,
        visibility = visibility,
        mediaById = items.associateBy { it.id },
        foldersByBucket = groupFolders(items).associateBy { it.bucketId },
        connectedAccount = connected,
        driveChecks = checks,
    )

    private fun reason(state: FavoriteState) = (state as FavoriteState.Unavailable).reason

    @Test
    fun accountHashIgnoresCaseAndSpacesAndNeverContainsTheEmail() {
        assertEquals(accountHash("Me@Example.com "), mine)
        assertNotEquals(mine, other)
        assertTrue(!mine.contains("example"))
        assertEquals(64, mine.length)
    }

    @Test
    fun keysKeepPhoneAndEachDriveAccountApart() {
        val file = driveFile("d1")
        assertNotEquals(favoriteOf(file, mine, "My Drive").id, favoriteOf(file, other, "My Drive").id)
        assertNotEquals(phoneFolderKey(5), phoneMediaKey(5))
        assertEquals(FavoriteKind.DRIVE_FOLDER.name, favoriteOf(driveFile("f", folder = true), mine, "").kind)
    }

    @Test
    fun aPhoneFileIsFoundByItsMediaStoreIdEvenInAnotherFolder() {
        val saved = favoriteOf(item(7, bucket = 100))
        // Moved to another folder: MediaStore keeps the id, so the favorite still opens it.
        val state = resolveFavorite(saved, ctx(items = listOf(item(7, bucket = 200))))
        assertEquals(7L, (state as FavoriteState.PhoneMedia).item.id)
    }

    @Test
    fun aMissingPhoneFileOrFolderIsUnavailableWithTheRightReason() {
        val media = favoriteOf(item(7))
        val folder = favoriteOf(groupFolders(listOf(item(7))).single())
        assertEquals(FavoriteReasons.MEDIA_GONE, reason(resolveFavorite(media, ctx(items = listOf(item(8, bucket = 300))))))
        assertEquals(FavoriteReasons.FOLDER_GONE, reason(resolveFavorite(folder, ctx(items = listOf(item(8, bucket = 300))))))
        // With partial access an unseen file may still be there, so it is not called gone.
        assertEquals(FavoriteReasons.PARTIAL_ACCESS, reason(resolveFavorite(media, ctx(visibility = MediaVisibility.PARTIAL))))
        assertEquals(FavoriteReasons.PHOTO_ACCESS_OFF, reason(resolveFavorite(media, ctx(visibility = MediaVisibility.NONE))))
    }

    @Test
    fun aPhoneFavoriteWaitsForTheListingInsteadOfLookingGone() {
        assertEquals(FavoriteState.Checking, resolveFavorite(favoriteOf(item(7)), ctx(loaded = false)))
    }

    @Test
    fun aDriveFavoriteOfAnotherAccountIsUnavailableAndNeverLookedUp() {
        val theirs = favoriteOf(driveFile("d9"), other, "My Drive")
        val ours = favoriteOf(driveFile("d1"), mine, "My Drive")
        val phone = favoriteOf(item(1))
        assertEquals(FavoriteReasons.OTHER_ACCOUNT, reason(resolveFavorite(theirs, ctx())))
        // Even a lookup result recorded under the same Drive id is not used for another account.
        val checks = mapOf(theirs.id to DriveCheck.Found(driveFile("d9")))
        assertEquals(FavoriteReasons.OTHER_ACCOUNT, reason(resolveFavorite(theirs, ctx(checks = checks))))
        assertEquals(listOf(ours), driveFavoritesToCheck(listOf(theirs, ours, phone), mine))
        assertEquals(emptyList<FavoriteEntity>(), driveFavoritesToCheck(listOf(theirs, ours), null))
        assertEquals(FavoriteReasons.DRIVE_NOT_CONNECTED, reason(resolveFavorite(ours, ctx(connected = null))))
    }

    @Test
    fun driveLookupResultsMapToOpenOrAClearReason() {
        val fav = favoriteOf(driveFile("d1"), mine, "My Drive")
        fun state(check: DriveCheck?) = resolveFavorite(fav, ctx(checks = check?.let { mapOf(fav.id to it) } ?: emptyMap()))
        assertEquals(FavoriteState.Checking, state(null))
        assertEquals(FavoriteState.Checking, state(DriveCheck.Checking))
        assertEquals("d1", (state(DriveCheck.Found(driveFile("d1"))) as FavoriteState.DriveItem).file.id)
        assertEquals(FavoriteReasons.DRIVE_TRASHED, reason(state(DriveCheck.Found(driveFile("d1", trashed = true)))))
        assertEquals(FavoriteReasons.DRIVE_MISSING, reason(state(DriveCheck.Missing)))
        assertEquals("No access", reason(state(DriveCheck.Denied("No access"))))
        val failed = state(DriveCheck.Failed("Offline")) as FavoriteState.Unavailable
        assertTrue("A failed check can be tried again", failed.canRetry)
        val folder = favoriteOf(driveFile("f1", folder = true), mine, "My Drive")
        val asFile = resolveFavorite(folder, ctx(checks = mapOf(folder.id to DriveCheck.Found(driveFile("f1")))))
        assertEquals(FavoriteReasons.NOT_A_FOLDER, reason(asFile))
    }

    @Test
    fun onlyARealRefusalCountsAsLostAccessNotASlowDown() {
        assertTrue(isDriveAccessRefused(403, "forbidden"))
        assertTrue(isDriveAccessRefused(403, null))
        assertTrue(!isDriveAccessRefused(403, "userRateLimitExceeded"))
        assertTrue(!isDriveAccessRefused(403, "rateLimitExceeded"))
        assertTrue(!isDriveAccessRefused(403, "dailyLimitExceeded"))
        assertTrue(!isDriveAccessRefused(403, "quotaExceeded"))
        assertTrue(!isDriveAccessRefused(429, null))
        assertTrue(!isDriveAccessRefused(500, null))
    }
}
