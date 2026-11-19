package com.drivelift.app.data

import com.drivelift.app.drive.DriveFile

/**
 * Renaming one item from the file manager (issues #36 and #42), worked out before anything is
 * asked or changed. Only the name changes: the bytes, the phone file's address (its MediaStore
 * URI) and the Drive file's id stay the same, so transfer rows keep pointing at the same file.
 */
enum class RenameWhere { PHONE, DRIVE }

/** The longest name, in UTF-8 bytes, that phone storage and Drive both accept. */
const val MAX_NAME_BYTES = 255

/** Characters Android's shared storage does not keep in a file name (it would change them). */
private const val PHONE_FORBIDDEN = "\\/:*?\"<>|"

/** The checked name ([name] is trimmed) and why it cannot be used, or null when it can. */
data class NameCheck(val name: String, val error: String?)

/** The ending of [name] from its last dot (".jpg"), or "" when it has none. */
fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot)
}

/** Length of the part before the ending, used to select it when the dialog opens. */
fun baseNameLength(name: String): Int = name.length - extensionOf(name).length

/**
 * Checks a new name for [original] in a folder that also holds [siblings] (the other items'
 * names, not the item itself). Nothing is ever overwritten: a name already in the folder is
 * refused. On the phone the ending must stay the same, because Android ties it to the file's
 * type and would otherwise change the name itself.
 */
fun checkNewName(original: String, typed: String, siblings: Collection<String>, where: RenameWhere): NameCheck {
    val name = typed.trim()
    fun bad(reason: String) = NameCheck(name, reason)
    return when {
        name.isEmpty() -> bad("Enter a name.")
        name == original -> bad("Enter a different name.")
        name == "." || name == ".." -> bad("\"$name\" is reserved. Choose another name.")
        '/' in name -> bad("A name can't contain /.")
        name.any { it.isISOControl() } -> bad("A name can't contain control characters.")
        name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> bad("This name is too long. Use a shorter one.")
        where == RenameWhere.PHONE && name.any { it in PHONE_FORBIDDEN } ->
            bad("A phone file name can't contain any of these: \\ / : * ? \" < > |")
        where == RenameWhere.PHONE && name.startsWith(".") ->
            bad("A name that starts with a dot would hide the file. Choose another name.")
        where == RenameWhere.PHONE && !extensionOf(name).equals(extensionOf(original), ignoreCase = true) -> {
            val ending = extensionOf(original)
            if (ending.isEmpty()) bad("Keep the name without an ending: Android ties it to the file's type.")
            else bad("Keep the ending $ending: Android ties it to the file's type.")
        }
        siblings.any { if (where == RenameWhere.PHONE) it.equals(name, ignoreCase = true) else it == name } ->
            bad("A file named $name is already in this folder. Choose another name.")
        else -> NameCheck(name, null)
    }
}

/**
 * A note shown under a Drive name whose ending was changed on purpose: Drive keeps the file's
 * content and type, only the name changes. Null when the ending is unchanged.
 */
fun endingChangeNote(original: String, name: String): String? {
    val from = extensionOf(original)
    val to = extensionOf(name)
    if (from.equals(to, ignoreCase = true)) return null
    return when {
        from.isEmpty() -> "The name now ends in $to. The file's content and type stay the same."
        to.isEmpty() -> "The name no longer ends in $from. The file's content and type stay the same."
        else -> "The ending changes from $from to $to. The file's content and type stay the same."
    }
}

/**
 * A phone rename: the file, whether a transfer still depends on it (then it is refused), and the
 * names already in its folder, the file itself left out.
 */
data class PhoneRenamePlan(
    val item: MediaItem,
    val heldByTransfer: Boolean,
    val siblings: List<String> = emptyList(),
)

fun planPhoneRename(item: MediaItem, transfers: List<TransferEntity>, siblings: List<String> = emptyList()) = PhoneRenamePlan(
    item = item,
    heldByTransfer = transfers.any { it.mediaUri == item.uri.toString() && it.status in TRANSFER_HOLDS_FILE },
    siblings = siblings,
)

fun checkPhoneRename(plan: PhoneRenamePlan, typed: String): NameCheck =
    checkNewName(plan.item.name, typed, plan.siblings, RenameWhere.PHONE)

/**
 * A Drive rename of one file in folder [folderId], for the account connected when the plan was
 * made ([account]; null when none is connected, and then nothing is renamed). Folders are not
 * renamed in this version; files a transfer still depends on are refused, as for trash and move.
 * [siblings] are the other names in the folder listing; [uploadingNames] the names of files a
 * transfer is still uploading.
 */
data class DriveRenamePlan(
    val file: DriveFile,
    val folderId: String,
    val heldByTransfer: Boolean,
    val siblings: List<String> = emptyList(),
    val uploadingNames: Set<String> = emptySet(),
    val account: String? = null,
) {
    val isFolder: Boolean get() = file.isFolder
    val allowed: Boolean get() = !isFolder && !heldByTransfer
}

fun planDriveRename(
    file: DriveFile,
    folderId: String,
    transfers: List<TransferEntity>,
    siblings: List<String> = emptyList(),
    account: String? = null,
): DriveRenamePlan = DriveRenamePlan(
    file = file,
    folderId = folderId,
    heldByTransfer = !file.isFolder && transferHolds(file, transfers),
    siblings = siblings,
    uploadingNames = transfers.filter { it.status in ACTIVE_UPLOAD }.map { it.name }.toSet(),
    account = account?.takeIf { it.isNotBlank() },
)

private val ACTIVE_UPLOAD = setOf(TransferStatus.QUEUED.name, TransferStatus.UPLOADING.name)

/**
 * Whether [name] is the name of a file a transfer is still uploading. Refused as a new Drive
 * name, so an upload never lands next to a renamed file of the same name and the rule that holds
 * same-named files during an upload never catches the renamed one.
 */
fun nameOfActiveUpload(name: String, transfers: List<TransferEntity>): Boolean =
    transfers.any { it.name == name && it.status in ACTIVE_UPLOAD }

fun checkDriveRename(plan: DriveRenamePlan, typed: String): NameCheck {
    val check = checkNewName(plan.file.name, typed, plan.siblings, RenameWhere.DRIVE)
    return if (check.error == null && check.name in plan.uploadingNames) {
        check.copy(error = "A transfer is uploading a file named ${check.name}. Choose another name.")
    } else check
}

/** What happened to a confirmed Drive rename. */
data class DriveRenameOutcome(
    val file: DriveFile,
    val newName: String,
    /** The file as Drive returned it after the rename; null when it was not renamed. */
    val renamed: DriveFile? = null,
    /** Why it was not renamed, in words for the user. */
    val failure: String? = null,
    /** True when the name was taken in the folder by the time Drive was asked: the dialog stays open. */
    val nameTaken: Boolean = false,
    val folderId: String = "",
) {
    fun message(): String = when {
        renamed != null -> "Renamed to ${renamed.name}"
        nameTaken -> "A file named $newName is already in this folder. Nothing was renamed."
        else -> "Could not rename ${file.name}: ${failure ?: "stopped"}"
    }
}

/** What MediaStore shows for the file after a phone rename was sent. */
fun phoneRenameMessage(original: String, requested: String, after: String?): String = when (after) {
    requested -> "Renamed to $requested"
    null -> "Sent the new name for $original, but it could not be checked. Look for $requested in the folder."
    original -> "Android did not rename $original. Nothing was changed."
    else -> "Android saved the name as $after instead of $requested."
}

/**
 * Sets [uri]'s DISPLAY_NAME to [name]. MediaStore renames the file on disk in the same folder;
 * its URI and bytes stay the same. Throws SecurityException (often a RecoverableSecurityException)
 * when write consent is needed, and IOException when MediaStore changed nothing.
 */
fun updateDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri, name: String) {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
    }
    val rows = resolver.update(uri, values, null, null)
    if (rows < 1) throw java.io.IOException("Android did not accept the new name")
}

/** [uri]'s DISPLAY_NAME as MediaStore shows it now, or null when it cannot be read. */
fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? = try {
    resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

/**
 * Names of every file MediaStore shows DriveLift in the folder [relativePath] (any type, not only
 * photos and videos). Files DriveLift cannot see are not listed; MediaStore itself never
 * overwrites one, and the name it saves is read back after the rename.
 */
fun queryFolderNames(resolver: android.content.ContentResolver, relativePath: String): List<String> = try {
    val col = android.provider.MediaStore.MediaColumns.DISPLAY_NAME
    val pathCol = android.provider.MediaStore.MediaColumns.RELATIVE_PATH
    resolver.query(
        android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL),
        arrayOf(col, pathCol), "$pathCol = ?", arrayOf(relativePath), null,
    )?.use { c ->
        buildList {
            while (c.moveToNext()) {
                if (c.getString(1) == relativePath) c.getString(0)?.let(::add)
            }
        }
    } ?: emptyList()
} catch (_: Exception) {
    emptyList()
}
