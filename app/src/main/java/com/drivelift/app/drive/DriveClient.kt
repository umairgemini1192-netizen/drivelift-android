package com.drivelift.app.drive

import android.content.Context
import android.os.ParcelFileDescriptor
import com.drivelift.app.data.Prefs
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DriveHttpException(val code: Int, val reason: String?, message: String) : IOException(message)
class SessionExpiredException : IOException("The upload session expired, starting this file again")

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val md5: String?,
    val trashed: Boolean,
    val thumbnailLink: String? = null,
    val durationMillis: Long = 0L,
    val modifiedTime: String? = null,
) {
    val isFolder: Boolean get() = mimeType == DriveClient.FOLDER_MIME
}

data class DriveAbout(val name: String, val email: String, val usedBytes: Long, val limitBytes: Long)

sealed interface SessionState {
    /** Drive already holds bytes [0, next). */
    data class Offset(val next: Long) : SessionState
    data class Done(val file: DriveFile) : SessionState
    data object Expired : SessionState
}

/** "bytes=0-1048575" -> 1048576, the next byte Drive expects. Null header means nothing stored. */
fun nextOffsetFromRange(rangeHeader: String?): Long {
    val end = rangeHeader?.substringAfterLast('-')?.trim()?.toLongOrNull() ?: return 0
    return end + 1
}

/**
 * Reads a paged listing to its end: [fetch] gets a page token (null for the first page) and
 * returns that page with the token of the next one, or null when there is none. Nothing is cut
 * off at a fixed count. A token that repeats would loop forever, so it is reported as an error.
 */
suspend fun <T> collectAllPages(fetch: suspend (pageToken: String?) -> Pair<List<T>, String?>): List<T> {
    val out = ArrayList<T>()
    val seen = HashSet<String>()
    var token: String? = null
    do {
        val (items, next) = fetch(token)
        out += items
        if (next != null && !seen.add(next)) throw IOException("Drive repeated a page while listing a folder")
        token = next
    } while (token != null)
    return out
}

/**
 * Drive calls used by the browser, folder picker, and in-app media gallery. An interface so UI and
 * screenshot tests can run against synthetic entries with no network. [DriveClient] is the only
 * production implementation.
 */
interface DriveFolders {
    suspend fun listChildren(folderId: String, foldersOnly: Boolean): List<DriveFile>
    suspend fun createFolder(name: String, parentId: String): DriveFile
    suspend fun loadThumbnail(file: DriveFile, maxEdgePx: Int): ByteArray? = null
    suspend fun loadPreviewBytes(
        file: DriveFile,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): ByteArray = throw IOException("Preview not available")
}

/** Thin Drive v3 REST client. Every call is suspendable and cancellable. */
class DriveClient(private val context: Context, private val prefs: Prefs) : DriveFolders {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    // Media and thumbnail requests validate every redirect hop manually so an OAuth token is
    // never forwarded to an untrusted host.
    private val mediaHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val thumbSemaphore = Semaphore(MAX_CONCURRENT_THUMBNAILS)
    private val previewCache = DrivePreviewCache(File(context.cacheDir, "drive_preview"))

    fun clearPreviewCache() {
        previewCache.clear()
    }

    /** Background and browsing calls are always pinned to the saved account. See [requirePinnedAccount]. */
    private suspend fun token(): String = try {
        DriveAuth.tokenOrNull(context, requirePinnedAccount(prefs.accountEmail)) ?: throw AuthRequiredException()
    } catch (e: ApiException) {
        throw IOException("Google sign-in service error ${e.statusCode}. Check the OAuth setup.", e)
    }

    private suspend fun <T> execute(request: () -> Request.Builder, handle: (Response) -> T): T {
        var token = token()
        var response = send(request, token)
        if (response.code == 401) {
            response.close()
            DriveAuth.invalidate(context, token)
            token = token()
            response = send(request, token)
            if (response.code == 401) {
                response.close()
                throw AuthRequiredException()
            }
        }
        return withContext(Dispatchers.IO) { response.use(handle) }
    }

    private suspend fun send(request: () -> Request.Builder, token: String): Response =
        http.newCall(request().header("Authorization", "Bearer $token").build()).await()

    private fun fail(response: Response): Nothing {
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
        var message = "HTTP ${response.code}"
        var reason: String? = null
        runCatching {
            val error = JSONObject(body).getJSONObject("error")
            message = error.optString("message", message)
            reason = error.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
        }
        throw DriveHttpException(response.code, reason, message)
    }

    private fun parseFile(o: JSONObject) = DriveFile(
        id = o.getString("id"),
        name = o.optString("name"),
        mimeType = o.optString("mimeType"),
        size = o.optString("size").toLongOrNull() ?: 0,
        md5 = o.optString("md5Checksum").ifEmpty { null },
        trashed = o.optBoolean("trashed", false),
        thumbnailLink = o.optString("thumbnailLink").ifEmpty { null },
        durationMillis = o.optJSONObject("videoMediaMetadata")?.optString("durationMillis")?.toLongOrNull() ?: 0L,
        modifiedTime = o.optString("modifiedTime").ifEmpty { null },
    )

    private suspend fun getJson(url: HttpUrl): JSONObject =
        execute({ Request.Builder().url(url) }) { r ->
            if (!r.isSuccessful) fail(r)
            JSONObject(r.body!!.string())
        }

    /**
     * The account [token] belongs to. This is the only call made with a token handed in instead
     * of the pinned one: connecting uses it to learn which account to pin to.
     */
    suspend fun about(token: String): DriveAbout {
        val response = send({ Request.Builder().url(ABOUT_URL) }, token)
        return parseAbout(withContext(Dispatchers.IO) {
            response.use { r ->
                if (!r.isSuccessful) fail(r)
                JSONObject(r.body!!.string())
            }
        })
    }

    suspend fun about(): DriveAbout = parseAbout(getJson(ABOUT_URL))

    private fun parseAbout(json: JSONObject): DriveAbout {
        val user = json.optJSONObject("user") ?: JSONObject()
        val quota = json.optJSONObject("storageQuota") ?: JSONObject()
        return DriveAbout(
            name = user.optString("displayName"),
            email = user.optString("emailAddress"),
            usedBytes = quota.optString("usage").toLongOrNull() ?: 0,
            limitBytes = quota.optString("limit").toLongOrNull() ?: 0,
        )
    }

    /** Every child of a folder. Follows Drive's paging to the end, however long the folder is. */
    override suspend fun listChildren(folderId: String, foldersOnly: Boolean): List<DriveFile> {
        val query = buildString {
            append("'").append(escape(folderId)).append("' in parents and trashed = false")
            if (foldersOnly) append(" and mimeType = '").append(FOLDER_MIME).append("'")
        }
        return collectAllPages { pageToken ->
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("orderBy", "folder,name_natural")
                .addQueryParameter("pageSize", PAGE_SIZE)
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = getJson(url)
            val files = json.optJSONArray("files") ?: JSONArray()
            val page = List(files.length()) { parseFile(files.getJSONObject(it)) }
            page to json.optString("nextPageToken").ifEmpty { null }
        }
    }

    override suspend fun createFolder(name: String, parentId: String): DriveFile {
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
            .toString().toRequestBody(JSON)
        val url = "$API/files".toHttpUrl().newBuilder().addQueryParameter("fields", FILE_FIELDS).build()
        return execute({ Request.Builder().url(url).post(body) }) { r ->
            if (!r.isSuccessful) fail(r)
            parseFile(JSONObject(r.body!!.string()))
        }
    }

    suspend fun findOrCreateFolder(name: String, parentId: String): String {
        val query = "name = '${escape(name)}' and '${escape(parentId)}' in parents " +
            "and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .build()
        val existing = getJson(url).optJSONArray("files")?.optJSONObject(0)?.optString("id")
        return if (!existing.isNullOrEmpty()) existing else createFolder(name, parentId).id
    }

    override suspend fun loadThumbnail(file: DriveFile, maxEdgePx: Int): ByteArray? {
        val email = requirePinnedAccount(prefs.accountEmail)
        val variant = driveCacheVariant("thumb_$maxEdgePx", file)
        previewCache.getBytes(email, file.id, variant)?.let { return it }
        return thumbSemaphore.withPermit {
            previewCache.getBytes(email, file.id, variant)?.let { return@withPermit it }
            val bytes = fetchThumbnailBytes(file, maxEdgePx)
            if (bytes != null && bytes.isNotEmpty()) {
                previewCache.putBytes(email, file.id, variant, bytes)
            }
            bytes
        }
    }

    private suspend fun fetchThumbnailBytes(file: DriveFile, maxEdgePx: Int): ByteArray? {
        val initial = file.thumbnailLink?.let { upgradedThumbnailUrl(it, maxEdgePx) }
        if (initial != null) {
            val attempt = tryFetchBoundedBytes(initial, MAX_THUMB_BYTES)
            if (attempt.bytes != null) return attempt.bytes
            if (attempt.expiredLink) {
                val refreshed = runCatching { getFile(file.id) }.getOrNull()
                val freshUrl = refreshed?.thumbnailLink?.let { upgradedThumbnailUrl(it, maxEdgePx) }
                if (freshUrl != null) {
                    val retry = tryFetchBoundedBytes(freshUrl, MAX_THUMB_BYTES)
                    if (retry.bytes != null) return retry.bytes
                }
            }
        }
        if (classifyDriveFile(file.mimeType) == DrivePreviewKind.IMAGE &&
            file.size in 1L..MAX_THUMB_FALLBACK_BYTES
        ) {
            val mediaUrl = "$API/files".toHttpUrl().newBuilder()
                .addPathSegment(file.id)
                .addQueryParameter("alt", "media")
                .build()
            return tryFetchBoundedBytes(mediaUrl, MAX_THUMB_FALLBACK_BYTES).bytes
        }
        return null
    }

    private data class MediaFetchAttempt(val bytes: ByteArray?, val expiredLink: Boolean = false)

    private suspend fun tryFetchBoundedBytes(url: HttpUrl, maxBytes: Long): MediaFetchAttempt =
        executeTrustedMedia(url) { r ->
            when {
                r.code == 403 || r.code == 404 || r.code == 410 -> MediaFetchAttempt(null, expiredLink = true)
                !r.isSuccessful -> MediaFetchAttempt(null)
                else -> MediaFetchAttempt(readBounded(r, maxBytes) { _, _ -> })
            }
        }

    override suspend fun loadPreviewBytes(
        file: DriveFile,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): ByteArray {
        val email = requirePinnedAccount(prefs.accountEmail)
        val kind = classifyDriveFile(file.mimeType)
        val variant = driveCacheVariant("preview_${kind.name}_$maxBytes", file)
        previewCache.getBytes(email, file.id, variant)?.let {
            onProgress(it.size.toLong(), it.size.toLong())
            return it
        }
        val url = if (kind == DrivePreviewKind.WORKSPACE_PDF_EXPORT) {
            "$API/files".toHttpUrl().newBuilder()
                .addPathSegment(file.id)
                .addPathSegment("export")
                .addQueryParameter("mimeType", "application/pdf")
                .build()
        } else {
            "$API/files".toHttpUrl().newBuilder()
                .addPathSegment(file.id)
                .addQueryParameter("alt", "media")
                .build()
        }
        val bytes = executeTrustedMedia(url) { r ->
            if (!r.isSuccessful) fail(r)
            readBounded(r, maxBytes, onProgress)
        }
        if (bytes.isNotEmpty()) previewCache.putBytes(email, file.id, variant, bytes)
        return bytes
    }

    private suspend fun <T> executeTrustedMedia(initialUrl: HttpUrl, handle: suspend (Response) -> T): T {
        var current = initialUrl
        for (hop in 0 until MAX_REDIRECTS) {
            if (!isTrustedDriveMediaUrl(current)) {
                throw IOException("Blocked media request to untrusted host")
            }
            var token = token()
            var response = mediaHttp.newCall(
                Request.Builder().url(current).header("Authorization", "Bearer $token").build()
            ).await()
            if (response.code == 401) {
                response.close()
                DriveAuth.invalidate(context, token)
                token = token()
                response = mediaHttp.newCall(
                    Request.Builder().url(current).header("Authorization", "Bearer $token").build()
                ).await()
                if (response.code == 401) {
                    response.close()
                    throw AuthRequiredException()
                }
            }
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                val next = location?.let { current.resolve(it) }
                    ?: throw IOException("Drive redirect did not include a destination")
                if (!isTrustedDriveMediaUrl(next)) {
                    throw IOException("Blocked redirect to untrusted host")
                }
                current = next
                continue
            }
            return withContext(Dispatchers.IO) {
                try {
                    handle(response)
                } finally {
                    response.close()
                }
            }
        }
        throw IOException("Too many redirects while loading media")
    }

    private suspend fun readBounded(
        response: Response,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): ByteArray {
        val body = response.body ?: throw IOException("Empty media response")
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            throw IOException("File is larger than the in-app preview limit")
        }
        val out = ByteArrayOutputStream(if (contentLength in 1..1_048_576) contentLength.toInt() else 16_384)
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        body.byteStream().use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("File is larger than the in-app preview limit")
                }
                out.write(buffer, 0, read)
                onProgress(total, contentLength)
            }
        }
        return out.toByteArray()
    }

    /** Null when the file does not exist any more. */
    suspend fun getFile(fileId: String): DriveFile? {
        val url = "$API/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FILE_FIELDS).build()
        return execute({ Request.Builder().url(url) }) { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> fail(r)
                else -> parseFile(JSONObject(r.body!!.string()))
            }
        }
    }

    /** Moves a file to the Drive trash (recoverable for 30 days), never a hard delete. */
    suspend fun trashFile(fileId: String) {
        val body = JSONObject().put("trashed", true).toString().toRequestBody(JSON)
        execute({ Request.Builder().url("$API/files/$fileId").patch(body) }) { r ->
            if (!r.isSuccessful && r.code != 404) fail(r)
        }
    }

    /** Opens a resumable upload session and returns its session URI. */
    suspend fun startUpload(
        name: String, mime: String, parentId: String, size: Long, modifiedMs: Long, createdMs: Long,
    ): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
        if (modifiedMs > 0) meta.put("modifiedTime", Instant.ofEpochMilli(modifiedMs).toString())
        if (createdMs > 0) meta.put("createdTime", Instant.ofEpochMilli(createdMs).toString())
        val body = meta.toString().toRequestBody(JSON)
        val url = "$UPLOAD/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        return execute({
            Request.Builder().url(url).post(body)
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", size.toString())
        }) { r ->
            if (!r.isSuccessful) fail(r)
            r.header("Location") ?: throw IOException("Drive did not return an upload session")
        }
    }

    /** Asks Drive how much of an interrupted upload it already has. */
    suspend fun querySession(sessionUri: String, total: Long): SessionState =
        execute({
            Request.Builder().url(sessionUri).put(ByteArray(0).toRequestBody(null))
                .header("Content-Range", "bytes */$total")
        }) { r ->
            when (r.code) {
                308 -> SessionState.Offset(nextOffsetFromRange(r.header("Range")))
                200, 201 -> SessionState.Done(parseFile(JSONObject(r.body!!.string())))
                400, 404, 410 -> SessionState.Expired
                else -> fail(r)
            }
        }

    /** Streams bytes [offset, total) of the open file into the session. */
    suspend fun uploadFrom(
        sessionUri: String,
        pfd: ParcelFileDescriptor,
        offset: Long,
        total: Long,
        mime: String,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): DriveFile = execute({
        val builder = Request.Builder().url(sessionUri)
            .put(FdBody(pfd, offset, total, mime.toMediaTypeOrNull(), onProgress, cancelled))
        if (total > 0) builder.header("Content-Range", "bytes $offset-${total - 1}/$total")
        builder
    }) { r ->
        when (r.code) {
            200, 201 -> parseFile(JSONObject(r.body!!.string()))
            308 -> throw IOException("Drive received only part of the file, resuming")
            404, 410 -> throw SessionExpiredException()
            else -> fail(r)
        }
    }

    private class FdBody(
        private val pfd: ParcelFileDescriptor,
        private val offset: Long,
        private val total: Long,
        private val type: MediaType?,
        private val onProgress: (Long) -> Unit,
        private val cancelled: () -> Boolean,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = total - offset

        override fun writeTo(sink: BufferedSink) {
            // The stream is deliberately not closed: closing it would close the
            // caller's descriptor, which is reused if OkHttp replays the body.
            val channel = FileInputStream(pfd.fileDescriptor).channel
            channel.position(offset)
            val buffer = ByteBuffer.allocate(256 * 1024)
            var sent = offset
            while (sent < total) {
                if (cancelled()) throw IOException("Cancelled")
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), total - sent).toInt())
                val read = channel.read(buffer)
                if (read < 0) throw IOException("The file changed while it was uploading")
                sink.write(buffer.array(), 0, read)
                sent += read
                onProgress(sent)
            }
        }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val ROOT = "root"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val ABOUT_URL = "$API/about".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "user(displayName,emailAddress),storageQuota(limit,usage)")
            .build()
        private const val FILE_FIELDS =
            "id,name,mimeType,size,md5Checksum,modifiedTime,trashed,thumbnailLink,videoMediaMetadata(durationMillis)"
        private const val PAGE_SIZE = "1000"
        private const val MAX_CONCURRENT_THUMBNAILS = 4
        private const val MAX_REDIRECTS = 5
        private const val MAX_THUMB_BYTES = 2L * 1024L * 1024L
        private const val MAX_THUMB_FALLBACK_BYTES = 8L * 1024L * 1024L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
