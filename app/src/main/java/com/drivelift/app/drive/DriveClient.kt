package com.drivelift.app.drive

import android.content.Context
import android.os.ParcelFileDescriptor
import com.drivelift.app.data.Prefs
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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

/** Thin Drive v3 REST client. Every call is suspendable and cancellable. */
class DriveClient(private val context: Context, private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

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

    suspend fun listChildren(folderId: String, foldersOnly: Boolean): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var page: String? = null
        do {
            val query = buildString {
                append("'").append(escape(folderId)).append("' in parents and trashed = false")
                if (foldersOnly) append(" and mimeType = '").append(FOLDER_MIME).append("'")
            }
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("orderBy", "folder,name_natural")
                .addQueryParameter("pageSize", "200")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = getJson(url)
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) out += parseFile(files.getJSONObject(i))
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null && out.size < MAX_LISTED)
        return out
    }

    suspend fun createFolder(name: String, parentId: String): DriveFile {
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
        private const val FILE_FIELDS = "id,name,mimeType,size,md5Checksum,trashed"
        private const val MAX_LISTED = 2000
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
