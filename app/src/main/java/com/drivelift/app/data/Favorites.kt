package com.drivelift.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.drivelift.app.drive.DriveFile
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/** What a favorite points at. Stored by name in [FavoriteEntity.kind]. */
enum class FavoriteKind { PHONE_FOLDER, PHONE_MEDIA, DRIVE_FOLDER, DRIVE_FILE }

/**
 * A folder or file the user starred (file manager, issues #36 and #42). A favorite is a pointer:
 * the folder or file itself is never copied, changed or watched. Where it is now is looked up each
 * time the Favorites view is shown (see [resolveFavorite]).
 *
 * Phone entries are keyed by the MediaStore id (media) or bucket id (folder). Drive entries are
 * keyed by a one-way hash of the Google account they were saved from plus the Drive id, so a
 * favorite never opens, and is never looked up, under another account.
 */
@Entity(tableName = "favorites", indices = [Index("kind")])
data class FavoriteEntity(
    /** [phoneFolderKey], [phoneMediaKey] or [driveKey]. */
    @PrimaryKey val id: String,
    val kind: String,
    /** Phone: the bucket id or MediaStore id. Drive: the file or folder id. */
    val itemId: String,
    /** [accountHash] of the Drive account it was saved from; null for phone entries. */
    val accountHash: String?,
    /** The phone file's content address when saved; null for folders and Drive entries. */
    val uri: String?,
    /** Name when saved, shown when the item cannot be found. */
    val name: String,
    val mime: String,
    val size: Long,
    /** Where it was when saved (phone folder path or Drive path), for display only. */
    val location: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val favoriteKind: FavoriteKind? get() = FavoriteKind.entries.firstOrNull { it.name == kind }
    val onPhone: Boolean get() = kind == FavoriteKind.PHONE_FOLDER.name || kind == FavoriteKind.PHONE_MEDIA.name
}

fun phoneFolderKey(bucketId: Long) = "phone-folder:$bucketId"
fun phoneMediaKey(mediaId: Long) = "phone-media:$mediaId"
fun driveKey(accountHash: String, driveId: String) = "drive:$accountHash:$driveId"

/**
 * A one-way SHA-256 of the account email, lower-cased. Binds a Drive favorite to its account
 * without storing the email itself in the database (rulebook: the email lives only in
 * app-private preferences).
 */
fun accountHash(email: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(email.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun favoriteOf(folder: MediaFolder) = FavoriteEntity(
    id = phoneFolderKey(folder.bucketId),
    kind = FavoriteKind.PHONE_FOLDER.name,
    itemId = folder.bucketId.toString(),
    accountHash = null,
    uri = null,
    name = folder.name,
    mime = "",
    size = 0,
    location = folder.path,
)

fun favoriteOf(item: MediaItem) = FavoriteEntity(
    id = phoneMediaKey(item.id),
    kind = FavoriteKind.PHONE_MEDIA.name,
    itemId = item.id.toString(),
    accountHash = null,
    uri = item.uri.toString(),
    name = item.name,
    mime = item.mime,
    size = item.size,
    location = item.relativePath.trimEnd('/'),
)

fun favoriteOf(file: DriveFile, accountHash: String, location: String) = FavoriteEntity(
    id = driveKey(accountHash, file.id),
    kind = (if (file.isFolder) FavoriteKind.DRIVE_FOLDER else FavoriteKind.DRIVE_FILE).name,
    itemId = file.id,
    accountHash = accountHash,
    uri = null,
    name = file.name,
    mime = file.mimeType,
    size = file.size,
    location = location,
)

@Dao
abstract class FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC, id")
    abstract fun observe(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE id = :id")
    abstract suspend fun get(id: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    abstract suspend fun delete(id: String): Int

    /** Adds [favorite] if it is not saved yet, otherwise removes it. True when it was added. */
    @Transaction
    open suspend fun toggle(favorite: FavoriteEntity): Boolean {
        if (get(favorite.id) != null) {
            delete(favorite.id)
            return false
        }
        upsert(favorite)
        return true
    }
}

/**
 * Version 1 to 2 adds the favorites table and leaves every other table as it is, so the transfer
 * ledger and pinned destinations survive the update. The statements are the ones Room generates
 * for [FavoriteEntity]; Room checks the result against the entity when the database opens, and
 * `FavoritesDbTest` runs this migration on a version 1 database.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorites` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`itemId` TEXT NOT NULL, `accountHash` TEXT, `uri` TEXT, `name` TEXT NOT NULL, " +
                "`mime` TEXT NOT NULL, `size` INTEGER NOT NULL, `location` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_kind` ON `favorites` (`kind`)")
    }
}

/** Every schema step, in order. A missing step fails loudly instead of wiping the ledger. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

// ---------------------------------------------------------------------------------------------
// Where a favorite is now
// ---------------------------------------------------------------------------------------------

/** The result of looking one Drive favorite up for the connected account. */
sealed interface DriveCheck {
    data object Checking : DriveCheck
    data class Found(val file: DriveFile) : DriveCheck
    /** Drive answered that the id does not exist (for this account). */
    data object Missing : DriveCheck
    /** Drive refused access (for example, sharing was removed). */
    data class Denied(val reason: String) : DriveCheck
    /** The check itself failed (offline, authorization expired); trying again may work. */
    data class Failed(val reason: String) : DriveCheck
}

sealed interface FavoriteState {
    /** Not known yet: the phone listing is loading or Drive is being asked. */
    data object Checking : FavoriteState
    data class PhoneFolder(val folder: MediaFolder) : FavoriteState
    data class PhoneMedia(val item: MediaItem) : FavoriteState
    data class DriveItem(val file: DriveFile) : FavoriteState
    /** Cannot be opened now. [reason] says why and what to do; [canRetry] offers Try again. */
    data class Unavailable(val reason: String, val canRetry: Boolean = false) : FavoriteState
}

data class FavoriteRow(val favorite: FavoriteEntity, val state: FavoriteState)

/** Everything [resolveFavorite] needs, gathered by the view model. Free of Android types except the media classes. */
data class FavoriteContext(
    val phoneLoaded: Boolean,
    val visibility: MediaVisibility,
    val mediaById: Map<Long, MediaItem>,
    val foldersByBucket: Map<Long, MediaFolder>,
    /** [accountHash] of the connected Drive account, or null while Drive is not connected. */
    val connectedAccount: String?,
    val driveChecks: Map<String, DriveCheck>,
)

object FavoriteReasons {
    const val PHOTO_ACCESS_OFF = "Photo access is off. Allow access to photos and videos to open it."
    const val PARTIAL_ACCESS =
        "Not visible with the current photo access. Allow access to all photos and videos to open it."
    const val MEDIA_GONE =
        "Not found on this phone. It was deleted, moved to the trash, or copied to a new place by another app."
    const val FOLDER_GONE =
        "No photos or videos are in this folder now. It was emptied, renamed or deleted."
    const val DRIVE_NOT_CONNECTED = "Google Drive is not connected. Connect to open it."
    const val OTHER_ACCOUNT = "Saved from another Google account. Connect that account to open it."
    const val DRIVE_TRASHED = "In Google Drive's trash. Restore it in Google Drive to open it here."
    const val DRIVE_MISSING =
        "Not found in Google Drive. It was deleted for good, or it is no longer shared with this account."
    const val NOT_A_FOLDER = "This is no longer a folder in Google Drive."
}

/**
 * Where [favorite] is now, from what is already known. Never makes a call: Drive results come in
 * [FavoriteContext.driveChecks], which the view model fills only for the connected account.
 */
fun resolveFavorite(favorite: FavoriteEntity, ctx: FavoriteContext): FavoriteState {
    val kind = favorite.favoriteKind ?: return FavoriteState.Unavailable(FavoriteReasons.DRIVE_MISSING)
    return when (kind) {
        FavoriteKind.PHONE_FOLDER, FavoriteKind.PHONE_MEDIA -> {
            if (ctx.visibility == MediaVisibility.NONE) return FavoriteState.Unavailable(FavoriteReasons.PHOTO_ACCESS_OFF)
            if (!ctx.phoneLoaded) return FavoriteState.Checking
            val id = favorite.itemId.toLongOrNull()
            val found = if (kind == FavoriteKind.PHONE_FOLDER) {
                id?.let(ctx.foldersByBucket::get)?.let(FavoriteState::PhoneFolder)
            } else {
                id?.let(ctx.mediaById::get)?.let(FavoriteState::PhoneMedia)
            }
            found ?: FavoriteState.Unavailable(
                when {
                    // With a partial grant an unseen file may well still be there.
                    ctx.visibility == MediaVisibility.PARTIAL -> FavoriteReasons.PARTIAL_ACCESS
                    kind == FavoriteKind.PHONE_FOLDER -> FavoriteReasons.FOLDER_GONE
                    else -> FavoriteReasons.MEDIA_GONE
                },
            )
        }
        FavoriteKind.DRIVE_FOLDER, FavoriteKind.DRIVE_FILE -> {
            val connected = ctx.connectedAccount ?: return FavoriteState.Unavailable(FavoriteReasons.DRIVE_NOT_CONNECTED)
            if (favorite.accountHash != connected) return FavoriteState.Unavailable(FavoriteReasons.OTHER_ACCOUNT)
            when (val check = ctx.driveChecks[favorite.id]) {
                null, DriveCheck.Checking -> FavoriteState.Checking
                DriveCheck.Missing -> FavoriteState.Unavailable(FavoriteReasons.DRIVE_MISSING)
                is DriveCheck.Denied -> FavoriteState.Unavailable(check.reason)
                is DriveCheck.Failed -> FavoriteState.Unavailable(check.reason, canRetry = true)
                is DriveCheck.Found -> when {
                    check.file.trashed -> FavoriteState.Unavailable(FavoriteReasons.DRIVE_TRASHED)
                    kind == FavoriteKind.DRIVE_FOLDER && !check.file.isFolder ->
                        FavoriteState.Unavailable(FavoriteReasons.NOT_A_FOLDER)
                    else -> FavoriteState.DriveItem(check.file)
                }
            }
        }
    }
}

/** The Drive favorites that may be looked up now: only those saved from the connected account. */
fun driveFavoritesToCheck(favorites: List<FavoriteEntity>, connectedAccount: String?): List<FavoriteEntity> =
    if (connectedAccount == null) emptyList()
    else favorites.filter { !it.onPhone && it.accountHash == connectedAccount }
