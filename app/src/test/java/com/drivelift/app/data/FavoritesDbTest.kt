package com.drivelift.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The favorites table, and the step from database version 1 (transfers and presets only) to 2.
 * The update must keep every transfer row: the ledger is what stops a file being removed twice
 * or removed unverified.
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesDbTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "favorites-test.db"

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    private val transfer = TransferEntity(
        mediaUri = "content://media/external/images/media/1", name = "IMG_0001.jpg", mime = "image/jpeg",
        size = 3_400_000, dateModifiedMs = 1, dateTakenMs = 1, bucketName = "Camera", destFolderId = "folder-1",
        destLabel = "DriveLift Backup / Camera", status = TransferStatus.VERIFIED.name, driveFileId = "drive-1",
    )

    /**
     * Builds a real version 1 database: the schema Room creates for transfers and presets (the same
     * entities as today), with the favorites table taken away and the version set back to 1. Then
     * opens it with the migrations, the way the app opens it after an update.
     */
    @Test
    fun updatingFromVersionOneKeepsTheTransferLedgerAndAddsFavorites() = runBlocking {
        open().apply {
            transfers().insertAll(listOf(transfer))
            presets().upsert(PresetEntity("folder-1", "Camera", "DriveLift Backup / Camera", createdAt = 5))
            close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE favorites")
            db.version = 1
        }

        // Room runs MIGRATION_1_2 and then checks every table against the entities; a table that
        // does not match makes this open fail.
        val db = open()
        val rows = db.transfers().byStatus(TransferStatus.VERIFIED.name, 10)
        assertEquals(1, rows.size)
        assertEquals("drive-1", rows.single().driveFileId)
        assertEquals("folder-1", db.presets().observe().first().single().folderId)
        assertEquals(2, db.openHelper.readableDatabase.version)

        // The new table works on the updated database.
        val fav = FavoriteEntity(
            id = phoneFolderKey(100), kind = FavoriteKind.PHONE_FOLDER.name, itemId = "100", accountHash = null,
            uri = null, name = "Camera", mime = "", size = 0, location = "DCIM/Camera",
        )
        db.favorites().upsert(fav)
        assertEquals(fav, db.favorites().get(fav.id))
        db.close()
    }

    @Test
    fun toggleAddsThenRemovesAndFavoritesSurviveReopening() = runBlocking {
        val fav = FavoriteEntity(
            id = driveKey(accountHash("me@example.com"), "d1"), kind = FavoriteKind.DRIVE_FILE.name, itemId = "d1",
            accountHash = accountHash("me@example.com"), uri = null, name = "Scan.jpg", mime = "image/jpeg",
            size = 10, location = "My Drive", addedAt = 7,
        )
        open().apply {
            assertTrue(favorites().toggle(fav))
            close()
        }
        // A new process reads it back from disk.
        open().apply {
            assertEquals(listOf(fav), favorites().observe().first())
            assertFalse(favorites().toggle(fav))
            assertNull(favorites().get(fav.id))
            assertEquals(0, favorites().delete(fav.id))
            close()
        }
    }

    @Test
    fun theSameDriveIdFromTwoAccountsIsTwoFavorites() = runBlocking {
        val file = com.drivelift.app.drive.DriveFile("d1", "Scan.jpg", "image/jpeg", 10, null, false)
        open().apply {
            favorites().upsert(favoriteOf(file, accountHash("a@example.com"), "My Drive").copy(addedAt = 1))
            favorites().upsert(favoriteOf(file, accountHash("b@example.com"), "My Drive").copy(addedAt = 2))
            val saved = favorites().observe().first()
            assertEquals(2, saved.size)
            // Newest first.
            assertEquals(accountHash("b@example.com"), saved.first().accountHash)
            close()
        }
    }
}
