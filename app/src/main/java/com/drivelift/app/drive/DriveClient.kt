package com.drivelift.app.drive

import android.content.Context
import android.os.ParcelFileDescriptor
import com.drivelift.app.data.Prefs
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DriveHttpException(val code: Int, val reason: String?, message: String) : IOException(message)
class SessionExpiredException : IOException("The upload session expired, starting this file again")
/** A file to be moved is no longer in the folder it was selected in; nothing was changed. */
class NotInFolderException : IOException("It is no longer in this folder")
/** Another file in the folder already has the name asked for; nothing was renamed. */
class NameTakenException(name: String) : IOException("A file named $name is already in this folder")

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

private const val MAX_FALLBACK_DOWNLOAD_BYTES = 128L * 1024L * 1024L

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

    /**
     * Downloads [file] (exporting Google Docs, Sheets, and Slides to PDF) into [destination] so
     * it can be opened natively via FileProvider.
     */
    suspend fun downloadToFile(
        file: DriveFile,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val bytes = loadPreviewBytes(file, MAX_FALLBACK_DOWNLOAD_BYTES, onProgress)
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        return destination
    }

    /** A stream for the in-app video player, authorised for the connected account. */
    suspend fun openVideoStream(file: DriveFile): DriveVideoStream =
        throw IOException("Video playback is not available")

    /**
     * The player could not use the stream [openVideoStream] gave for [file]. Anything kept to open
     * it again quickly (a Google Vids link) is dropped, so Try again asks Drive afresh.
     */
    fun forgetVideoStream(file: DriveFile) {}

    /**
     * Moves one file to Google Drive's trash as [account], the account that listed the file and
     * whose confirmation the user gave (user-directed, issue #36). Recoverable from Drive's trash;
     * never a permanent delete. Throws [AccountChangedException], without sending anything, when
     * the connected account is no longer [account] or Google hands out a token for another one.
     */
    suspend fun moveToTrash(fileId: String, account: String): Unit =
        throw IOException("Moving to the trash is not available")

    /**
     * Moves one file from folder [fromFolderId] into folder [toFolderId] as [account] (user-directed,
     * issue #36). The file keeps its id and content. Throws [NotInFolderException], without changing
     * anything, when the file is no longer in [fromFolderId], and [AccountChangedException] as
     * [moveToTrash] does.
     */
    suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String, account: String): Unit =
        throw IOException("Moving files is not available")

    /**
     * Looks one file or folder up as [account], for a favorite saved from that account. Null when
     * Drive says it does not exist. A trashed item is returned with `trashed` set. Throws
     * [AccountChangedException], without sending anything, when the connected account is no
     * longer [account], so a favorite is never looked up under another account.
     */
    suspend fun fileInfo(fileId: String, account: String): DriveFile? =
        throw IOException("Looking files up is not available")

    /**
     * Renames one file in folder [folderId] to [newName] as [account] (user-directed, issue #42).
     * Only the name changes: the id, content and folder stay the same. Throws [NameTakenException]
     * when another item in [folderId] already has [newName] and [NotInFolderException] when the
     * file is no longer there, both without changing anything, and [AccountChangedException] as
     * [moveToTrash] does. Returns the file as Drive shows it after the rename.
     */
    suspend fun renameFile(fileId: String, folderId: String, newName: String, account: String): DriveFile =
        throw IOException("Renaming files is not available")

    /** The id of the connected account's My Drive root (Storage folder details, issue #42). Read only. */
    suspend fun myDriveRootId(): String = throw IOException("Drive folder details are not available")

    /**
     * One page of every item the connected account can see that is not in the trash, with only
     * what folder totals need (Storage folder details, issue #42). Read only. [pageToken] is null
     * for the first page.
     */
    suspend fun listEntriesPage(pageToken: String?): com.drivelift.app.data.DriveEntryPage =
        throw IOException("Drive folder details are not available")
}

/**
 * Where access tokens come from. Production uses Google Identity Services ([DriveAuth]); tests
 * pass a fake so the real request, account-binding and retry code runs without Google services.
 */
internal interface DriveTokenSource {
    suspend fun tokenOrNull(account: String): String?
    suspend fun invalidate(token: String)
}

private class GoogleTokenSource(private val context: Context) : DriveTokenSource {
    override suspend fun tokenOrNull(account: String): String? = DriveAuth.tokenOrNull(context, account)
    override suspend fun invalidate(token: String) = DriveAuth.invalidate(context, token)
}

/** Thin Drive v3 REST client. Every call is suspendable and cancellable. */
class DriveClient internal constructor(
    private val context: Context,
    private val prefs: Prefs,
    private val tokens: DriveTokenSource,
    baseHttp: OkHttpClient,
    /** Wait between polls of a Google Vids render. A real delay in the app; instant in tests. */
    private val vidsPollWait: suspend (Long) -> Unit,
    /** Wall clock for the Vids render deadline and how long a kept render may be reused. */
    private val clock: () -> Long = System::currentTimeMillis,
) : DriveFolders {
    constructor(context: Context, prefs: Prefs) : this(
        context,
        prefs,
        GoogleTokenSource(context),
        OkHttpClient(),
        { delay(it) },
    )

    private val http = baseHttp.newBuilder()
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
        tokens.tokenOrNull(requirePinnedAccount(prefs.accountEmail)) ?: throw AuthRequiredException()
    } catch (e: ApiException) {
        throw IOException("Google sign-in service error ${e.statusCode}. Check the OAuth setup.", e)
    }

    /**
     * A token for exactly [account], for a change the user confirmed while [account] was
     * connected. Refused when the saved pin has moved to another account since, and every new
     * token is checked with Drive to belong to [account] before it is used, so neither a switch
     * nor a token refresh can carry the change to another identity.
     */
    private suspend fun tokenFor(account: String): String {
        if (!account.equals(prefs.accountEmail, ignoreCase = true)) throw AccountChangedException()
        // A cached token Drive rejects during the ownership check is dropped and replaced once;
        // the replacement is proven the same way before anything is sent.
        for (attempt in 0..1) {
            val token = try {
                tokens.tokenOrNull(account) ?: throw AuthRequiredException()
            } catch (e: ApiException) {
                throw IOException("Google sign-in service error ${e.statusCode}. Check the OAuth setup.", e)
            }
            val owner = provenTokens[token] ?: try {
                about(token).email.also { provenTokens[token] = it }
            } catch (e: DriveHttpException) {
                if (e.code != 401) throw e
                tokens.invalidate(token)
                continue
            }
            if (!owner.equals(account, ignoreCase = true)) throw AccountChangedException()
            return token
        }
        throw AuthRequiredException()
    }

    /** Tokens already checked with Drive, and the account each belongs to. */
    private val provenTokens = java.util.Collections.synchronizedMap(HashMap<String, String>())

    private suspend fun <T> execute(
        request: () -> Request.Builder,
        /** Set for a confirmed change bound to one account; see [tokenFor]. */
        account: String? = null,
        handle: (Response) -> T,
    ): T {
        val nextToken: suspend () -> String = { if (account != null) tokenFor(account) else token() }
        var token = nextToken()
        var response = send(request, token)
        if (response.code == 401) {
            response.close()
            tokens.invalidate(token)
            provenTokens.remove(token)
            token = nextToken()
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

    override suspend fun myDriveRootId(): String {
        val url = "$API/files/$ROOT".toHttpUrl().newBuilder().addQueryParameter("fields", "id").build()
        return getJson(url).getString("id")
    }

    override suspend fun listEntriesPage(pageToken: String?): com.drivelift.app.data.DriveEntryPage {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "trashed = false")
            .addQueryParameter("corpora", "user")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,parents,ownedByMe)")
            .addQueryParameter("pageSize", PAGE_SIZE)
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
        val json = getJson(url)
        val files = json.optJSONArray("files") ?: JSONArray()
        val entries = List(files.length()) { i -> parseEntry(files.getJSONObject(i)) }
        return com.drivelift.app.data.DriveEntryPage(entries, json.optString("nextPageToken").ifEmpty { null })
    }

    private fun parseEntry(o: JSONObject): com.drivelift.app.data.DriveEntry {
        val mime = o.optString("mimeType")
        val parents = o.optJSONArray("parents")
        return com.drivelift.app.data.DriveEntry(
            id = o.getString("id"),
            // Only folder names are shown; file names are not kept, to keep a large listing small.
            name = if (mime == FOLDER_MIME) o.optString("name") else "",
            mimeType = mime,
            size = o.optString("size").toLongOrNull(),
            parents = if (parents == null) emptyList() else List(parents.length()) { parents.getString(it) },
            ownedByMe = o.optBoolean("ownedByMe", true),
        )
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

    override suspend fun downloadToFile(
        file: DriveFile,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val kind = classifyDriveFile(file.mimeType)
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
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.part")
        try {
            executeTrustedMedia(url) { r ->
                if (!r.isSuccessful) fail(r)
                val body = r.body ?: throw IOException("Empty media response")
                val contentLength = body.contentLength()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            output.write(buffer, 0, read)
                            onProgress(total, contentLength)
                        }
                    }
                }
            }
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            return destination
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    override suspend fun openVideoStream(file: DriveFile): DriveVideoStream {
        val current = AtomicReference(token())
        val url = when (drivePlaybackSource(file.mimeType)) {
            DrivePlaybackSource.DIRECT_MEDIA -> "$API/files".toHttpUrl().newBuilder()
                .addPathSegment(file.id)
                .addQueryParameter("alt", "media")
                .build()
            DrivePlaybackSource.VIDS_DOWNLOAD_OPERATION ->
                vidsDownloadUri(file, requirePinnedAccount(prefs.accountEmail))
        }
        val client = http.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            // A token can expire during a long pause or a late seek. On 401 the rejected token is
            // invalidated and the request retried once with a fresh one, as the other Drive calls
            // do; only a second 401 reaches the player as "authorization expired". This runs on
            // the player's loading thread, never the main thread.
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code != 401) return@addInterceptor response
                response.close()
                val rejected = current.get()
                val fresh = runBlocking {
                    tokens.invalidate(rejected)
                    runCatching { token() }.getOrNull()
                }
                if (fresh != null) current.set(fresh)
                chain.proceed(chain.request())
            }
            // Redirects are followed by OkHttp, and every hop passes the trusted-host check before
            // the current token is attached.
            .addNetworkInterceptor { chain -> chain.proceed(authorizedMediaRequest(chain.request(), current.get())) }
            .build()
        return DriveVideoStream(url.toString(), client)
    }

    /**
     * Renders kept per account, file and version; see [VidsRenderMemo]. This client lives as long
     * as the app, so turning the phone (which rebuilds the screen) or a reconnect finds the render
     * here instead of starting a new one, and reopening the video within the hour plays at once.
     */
    private val vidsRenders = java.util.concurrent.ConcurrentHashMap<VidsRenderKey, VidsRenderMemo>()

    override fun forgetVideoStream(file: DriveFile) {
        vidsRenders.keys.removeIf { it.fileId == file.id }
    }

    /**
     * The trusted MP4 link for a Google Vids file. A kept link is reused; a render still running
     * is polled again; otherwise Drive is asked to start one (`files.download`). Drive may refuse
     * a poll of an operation that has finished or expired; then a new render is started once.
     */
    private suspend fun vidsDownloadUri(file: DriveFile, account: String): HttpUrl {
        val key = vidsRenderKey(account, file)
        when (val memo = reusableVidsRender(vidsRenders[key], clock())) {
            is VidsRenderMemo.Ready -> return memo.uri
            is VidsRenderMemo.Running -> try {
                return runVidsRender(key, file.id, account, resume = memo)
            } catch (e: DriveHttpException) {
                if (e.code !in VIDS_OPERATION_GONE) throw e
                vidsRenders.remove(key, memo)
            }
            null -> vidsRenders.remove(key)
        }
        return runVidsRender(key, file.id, account, resume = null)
    }

    /**
     * Polls [resume], or starts Drive's MP4 render (`files.download`), until the link is ready.
     * Gives up after [VIDS_RENDER_TIMEOUT_MS], so the player shows an error instead of spinning
     * forever. The running render is kept for the next try; a failed render is dropped.
     */
    private suspend fun runVidsRender(
        key: VidsRenderKey,
        fileId: String,
        account: String,
        resume: VidsRenderMemo.Running?,
    ): HttpUrl {
        val startUrl = "$API/files".toHttpUrl().newBuilder()
            .addPathSegment(fileId)
            .addPathSegment("download")
            // Drive's default type for Vids is MP4 today, but the reference says the default may
            // change, and the player needs MP4. Google's own Vids example sets it too.
            .addQueryParameter("mimeType", "video/mp4")
            .build()
        val poll: suspend (String) -> DriveRenderOperation = { operationId ->
            val pollUrl = "$API/operations".toHttpUrl().newBuilder().addPathSegments(operationId).build()
            renderOperation(execute({ Request.Builder().url(pollUrl) }, account = account) { r ->
                if (!r.isSuccessful) fail(r)
                JSONObject(r.body!!.string())
            })
        }
        val uri = try {
            awaitVidsRender(
                start = {
                    if (resume != null) {
                        val op = poll(driveOperationName(resume.operationName))
                        if (op.name.isNullOrBlank()) op.copy(name = resume.operationName) else op
                    } else {
                        val op = renderOperation(
                            execute({ Request.Builder().url(startUrl).post(ByteArray(0).toRequestBody(null)) }, account = account) { r ->
                                if (!r.isSuccessful) fail(r)
                                JSONObject(r.body!!.string())
                            },
                        )
                        val name = op.name
                        if (name != null && op.errorMessage == null) vidsRenders[key] = VidsRenderMemo.Running(name, clock())
                        op
                    }
                },
                poll = poll,
                wait = vidsPollWait,
                now = clock,
                timeoutMillis = VIDS_RENDER_TIMEOUT_MS,
                isTrusted = ::isTrustedDriveMediaUrl,
                retryable = ::isTemporaryDriveError,
            )
        } catch (e: VidsRenderException) {
            // Kept only while Drive may still be rendering; a failed or unusable render is dropped.
            if (!e.operationStillValid) vidsRenders.remove(key)
            throw e
        }
        vidsRenders[key] = VidsRenderMemo.Ready(uri, clock())
        return uri
    }

    /**
     * A poll failure worth asking again: rate limit, a Drive server error or a dropped connection.
     * An account change, a sign-in problem or a refusal (4xx) is not.
     */
    private fun isTemporaryDriveError(e: Exception): Boolean = when (e) {
        is AccountChangedException -> false
        is DriveHttpException -> e.code == 429 || e.code >= 500
        is IOException -> true
        else -> false
    }

    /** Reads an operation resource. Missing or wrong-typed fields become null or false, never a crash. */
    private fun renderOperation(o: JSONObject) = DriveRenderOperation(
        name = o.optString("name").ifEmpty { null },
        done = o.optBoolean("done", false),
        errorMessage = o.optJSONObject("error")?.let { e -> e.optString("message").ifBlank { VIDS_RENDER_FAILED } },
        downloadUri = o.optJSONObject("response")?.optString("downloadUri")?.ifEmpty { null },
    )

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
                tokens.invalidate(token)
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

    override suspend fun fileInfo(fileId: String, account: String): DriveFile? {
        val url = "$API/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FILE_FIELDS).build()
        return execute({ Request.Builder().url(url) }, account = account) { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> fail(r)
                else -> parseFile(JSONObject(r.body!!.string()))
            }
        }
    }

    override suspend fun moveToTrash(fileId: String, account: String) {
        // Unlike cleanup of a bad copy, a file that is already gone is reported, not ignored.
        val body = JSONObject().put("trashed", true).toString().toRequestBody(JSON)
        execute({ Request.Builder().url("$API/files/$fileId").patch(body) }, account = account) { r ->
            if (!r.isSuccessful) fail(r)
        }
    }

    override suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String, account: String) {
        val from = folderIdFor(fromFolderId, account)
        val to = folderIdFor(toFolderId, account)
        // Read where the file is now: it is moved only out of the folder the user saw it in.
        val readUrl = "$API/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("fields", "parents").build()
        val parents = execute({ Request.Builder().url(readUrl) }, account = account) { r ->
            if (!r.isSuccessful) fail(r)
            parentsOf(JSONObject(r.body!!.string()))
        }
        if (from !in parents) throw NotInFolderException()
        if (to in parents) return
        val url = "$API/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("addParents", to)
            .addQueryParameter("removeParents", from)
            .addQueryParameter("fields", "parents")
            .build()
        val body = "{}".toRequestBody(JSON)
        val after = execute({ Request.Builder().url(url).patch(body) }, account = account) { r ->
            if (!r.isSuccessful) fail(r)
            parentsOf(JSONObject(r.body!!.string()))
        }
        if (to !in after || from in after) throw IOException("Drive did not confirm the move")
    }

    override suspend fun renameFile(fileId: String, folderId: String, newName: String, account: String): DriveFile {
        val folder = folderIdFor(folderId, account)
        // Read where the file is now: it is renamed only while it is in the folder the user saw.
        val readUrl = "$API/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("fields", "parents").build()
        val parents = execute({ Request.Builder().url(readUrl) }, account = account) { r ->
            if (!r.isSuccessful) fail(r)
            parentsOf(JSONObject(r.body!!.string()))
        }
        if (folder !in parents) throw NotInFolderException()
        // Drive allows two files with one name; DriveLift never makes one, so a taken name is refused.
        val query = "name = '${escape(newName)}' and '${escape(folder)}' in parents and trashed = false"
        val findUrl = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "10")
            .build()
        val taken = execute({ Request.Builder().url(findUrl) }, account = account) { r ->
            if (!r.isSuccessful) fail(r)
            val files = JSONObject(r.body!!.string()).optJSONArray("files") ?: JSONArray()
            (0 until files.length()).any { files.getJSONObject(it).optString("id") != fileId }
        }
        if (taken) throw NameTakenException(newName)
        val url = "$API/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("fields", FILE_FIELDS).build()
        val body = JSONObject().put("name", newName).toString().toRequestBody(JSON)
        val after = execute({ Request.Builder().url(url).patch(body) }, account = account) { r ->
            if (!r.isSuccessful) fail(r)
            parseFile(JSONObject(r.body!!.string()))
        }
        if (after.id != fileId || after.name != newName) throw IOException("Drive did not confirm the new name")
        return after
    }

    /** The real id of [folderId], resolving the "root" alias once for [account]. */
    private suspend fun folderIdFor(folderId: String, account: String): String {
        if (folderId != ROOT) return folderId
        rootIds[account.lowercase()]?.let { return it }
        val url = "$API/files/$ROOT".toHttpUrl().newBuilder().addQueryParameter("fields", "id").build()
        val id = execute({ Request.Builder().url(url) }, account = account) { r ->
            if (!r.isSuccessful) fail(r)
            JSONObject(r.body!!.string()).getString("id")
        }
        rootIds[account.lowercase()] = id
        return id
    }

    private val rootIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun parentsOf(json: JSONObject): List<String> {
        val array = json.optJSONArray("parents") ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
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
        private const val VIDS_RENDER_TIMEOUT_MS = 15L * 60L * 1000L

        /** Drive's answers to a poll of an operation it no longer serves: start a new render. */
        private val VIDS_OPERATION_GONE = setOf(403, 404, 410)
        private const val MAX_THUMB_BYTES = 2L * 1024L * 1024L
        private const val MAX_THUMB_FALLBACK_BYTES = 8L * 1024L * 1024L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
