package com.drivelift.app.feature.whatsappsender.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.drivelift.app.data.MediaFolder
import com.drivelift.app.data.MediaItem
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.LocalSourceKind
import com.drivelift.app.feature.whatsappsender.model.WhatsAppSendPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Read-only adapters translating MediaStore models and SAF URIs into [LocalSendItem] payloads. */
object LocalMediaAdapter {

    private const val MAX_TREE_DEPTH = 16
    private const val MAX_TREE_ITEMS = 5_000

    fun fromMediaItem(item: MediaItem, isReadable: Boolean = true): LocalSendItem = LocalSendItem(
        id = "media:${item.id}",
        name = item.name,
        sizeBytes = item.size,
        mimeType = item.mime.ifBlank { if (item.isVideo) "video/*" else "image/*" },
        uriString = item.uri.toString(),
        filePath = null,
        isReadable = isReadable,
        sourceKind = LocalSourceKind.MEDIA_STORE,
        relativeFolderPath = item.relativePath.trim('/'),
        bucketName = item.bucketName,
    )

    fun fromMediaItems(items: List<MediaItem>): List<LocalSendItem> =
        items.map { fromMediaItem(it) }

    fun fromFolder(folder: MediaFolder, allMedia: List<MediaItem>): WhatsAppSendPayload {
        val folderItems = allMedia.filter { it.bucketId == folder.bucketId }
        return WhatsAppSendPayload(
            title = folder.name,
            items = fromMediaItems(folderItems),
            isFolderSource = true,
            sourceBucketId = folder.bucketId,
            mediaItemIds = folderItems.map { it.id },
        )
    }

    suspend fun readSafTree(context: Context, treeUri: Uri): WhatsAppSendPayload = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val rootName = queryDocumentDisplayName(context, rootDocUri)
            ?: DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "Selected folder"

        val items = ArrayList<LocalSendItem>()
        val visited = HashSet<String>()

        suspend fun walk(docId: String, relativePrefix: String, depth: Int) {
            if (depth > MAX_TREE_DEPTH || items.size >= MAX_TREE_ITEMS || !visited.add(docId)) return
            currentCoroutineContext().ensureActive()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idxId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val idxName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val idxMime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val idxSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    while (cursor.moveToNext() && items.size < MAX_TREE_ITEMS) {
                        currentCoroutineContext().ensureActive()
                        val childId = if (idxId >= 0) cursor.getString(idxId) else null
                        if (childId.isNullOrBlank()) continue
                        val name = (if (idxName >= 0) cursor.getString(idxName) else null)
                            ?.takeIf { it.isNotBlank() } ?: "file"
                        val mime = (if (idxMime >= 0) cursor.getString(idxMime) else null)
                            ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val nextPrefix = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                            walk(childId, nextPrefix, depth + 1)
                        } else {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val size = if (idxSize >= 0 && !cursor.isNull(idxSize)) {
                                cursor.getLong(idxSize)
                            } else {
                                querySizeFallback(context, docUri)
                            }
                            items += LocalSendItem(
                                id = "saf:$childId",
                                name = name,
                                sizeBytes = size.coerceAtLeast(0L),
                                mimeType = mime,
                                uriString = docUri.toString(),
                                isReadable = true,
                                sourceKind = LocalSourceKind.SAF_URI,
                                relativeFolderPath = relativePrefix,
                                bucketName = rootName,
                            )
                        }
                    }
                }
            }
        }

        walk(rootDocId, "", 0)

        // Fallback via DocumentFile if the provider did not serve DocumentsContract child queries.
        if (items.isEmpty()) {
            val docRoot = DocumentFile.fromTreeUri(context, treeUri)
            if (docRoot != null && docRoot.isDirectory) {
                fun walkDoc(dir: DocumentFile, prefix: String, depth: Int) {
                    if (depth > MAX_TREE_DEPTH || items.size >= MAX_TREE_ITEMS) return
                    for (child in dir.listFiles()) {
                        if (items.size >= MAX_TREE_ITEMS) break
                        val childName = child.name ?: continue
                        if (child.isDirectory) {
                            val nextPrefix = if (prefix.isEmpty()) childName else "$prefix/$childName"
                            walkDoc(child, nextPrefix, depth + 1)
                        } else if (child.isFile) {
                            items += LocalSendItem(
                                id = "saf:${child.uri}",
                                name = childName,
                                sizeBytes = child.length().coerceAtLeast(0L),
                                mimeType = child.type?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                                uriString = child.uri.toString(),
                                isReadable = child.canRead(),
                                sourceKind = LocalSourceKind.SAF_URI,
                                relativeFolderPath = prefix,
                                bucketName = rootName,
                            )
                        }
                    }
                }
                walkDoc(docRoot, "", 0)
            }
        }

        WhatsAppSendPayload(
            title = rootName,
            items = items,
            isFolderSource = true,
        )
    }

    suspend fun readSafDocuments(context: Context, uris: List<Uri>): WhatsAppSendPayload = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val items = uris.mapIndexed { index, uri ->
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            var displayName: String? = null
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) displayName = c.getString(nameIdx)
                            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                        }
                    }
            }
            if (size < 0L) {
                size = querySizeFallback(context, uri)
            }
            val resolvedName = displayName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "file_${index + 1}"
            val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val readable = canOpenRead(context, uri)
            LocalSendItem(
                id = "doc:$index:$uri",
                name = resolvedName,
                sizeBytes = size.coerceAtLeast(0L),
                mimeType = mime,
                uriString = uri.toString(),
                isReadable = readable,
                sourceKind = LocalSourceKind.SAF_URI,
            )
        }
        WhatsAppSendPayload(
            title = if (items.size == 1) items.first().name else "${items.size} selected files",
            items = items,
            isFolderSource = false,
        )
    }

    fun openItemStream(context: Context, item: LocalSendItem): InputStream? {
        if (!item.isReadable) return null
        val uriStr = item.uriString
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "content") {
                return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            }
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                val f = File(uri.path!!)
                return if (f.isFile && f.canRead()) runCatching { f.inputStream() }.getOrNull() else null
            }
        }
        val path = item.filePath
        if (!path.isNullOrBlank()) {
            val f = File(path)
            return if (f.isFile && f.canRead()) runCatching { f.inputStream() }.getOrNull() else null
        }
        return null
    }

    suspend fun verifyReadability(context: Context, items: List<LocalSendItem>): List<LocalSendItem> =
        withContext(Dispatchers.IO) {
            items.map { item ->
                if (!item.isReadable) {
                    item
                } else {
                    val readable = checkItemReadable(context, item)
                    if (readable == item.isReadable) item else item.copy(isReadable = readable)
                }
            }
        }

    private fun checkItemReadable(context: Context, item: LocalSendItem): Boolean {
        val uriStr = item.uriString
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            return when (uri.scheme) {
                "content" -> canOpenRead(context, uri)
                "file" -> uri.path?.let { File(it) }?.let { it.isFile && it.canRead() } ?: false
                else -> false
            }
        }
        val path = item.filePath
        if (!path.isNullOrBlank()) {
            val f = File(path)
            return f.isFile && f.canRead()
        }
        return false
    }

    private fun canOpenRead(context: Context, uri: Uri): Boolean =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
                ?: context.contentResolver.openInputStream(uri)?.use { true }
                ?: false
        }.getOrDefault(false)

    private fun querySizeFallback(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)

    private fun queryDocumentDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
        }.getOrNull()
}
