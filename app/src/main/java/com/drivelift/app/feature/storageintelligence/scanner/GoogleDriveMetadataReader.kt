package com.drivelift.app.feature.storageintelligence.scanner

import android.content.Context
import com.drivelift.app.data.Prefs
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveAuth
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveHttpException
import com.drivelift.app.drive.requirePinnedAccount
import com.drivelift.app.transfer.Backoff
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Rich Drive file metadata needed for zero-download checksum matching, Large-File Radar,
 * and cloud orphan inspection.
 */
data class DriveMetadataEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val md5Checksum: String?,
    val sha256Checksum: String?,
    val modifiedAtMs: Long,
    val parents: List<String>,
    val trashed: Boolean,
    val thumbnailLink: String?,
    val ownedByMe: Boolean,
) {
    val isFolder: Boolean get() = mimeType == DriveClient.FOLDER_MIME
    val isGoogleWorkspaceDoc: Boolean
        get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder
}

data class DriveMetadataPage(
    val entries: List<DriveMetadataEntry>,
    val nextPageToken: String?,
)

data class CloudTrashSummary(
    val itemCount: Int,
    val totalBytes: Long,
    val truncated: Boolean = false,
)

/**
 * Read-only metadata abstraction over Google Drive REST v3 so cloud scanning, rate-limit backoff,
 * and server-sorted large-file queries can be verified cleanly with fakes.
 */
interface DriveMetadataApi {
    fun isConnected(): Boolean
    fun pinnedAccountEmail(): String?
    suspend fun myDriveRootId(): String
    suspend fun listActiveFilesPage(pageToken: String?): DriveMetadataPage
    suspend fun queryLargestFiles(minSizeBytes: Long, limit: Int): List<DriveMetadataEntry>
    suspend fun summarizeTrash(): CloudTrashSummary
}

internal interface MetadataTokenSource {
    suspend fun tokenOrNull(account: String): String?
    suspend fun invalidate(token: String)
}

private class GisMetadataTokenSource(private val context: Context) : MetadataTokenSource {
    override suspend fun tokenOrNull(account: String): String? = DriveAuth.tokenOrNull(context, account)
    override suspend fun invalidate(token: String) = DriveAuth.invalidate(context, token)
}

/**
 * Metadata-only Drive v3 REST reader reusing [DriveAuth] and pinned account preferences without
 * altering [DriveClient]'s public surface. Never downloads file contents and talks only to
 * Google's Drive API host.
 */
class HttpGoogleDriveMetadataApi internal constructor(
    private val prefs: Prefs,
    private val tokens: MetadataTokenSource,
    baseHttp: OkHttpClient = OkHttpClient(),
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : DriveMetadataApi {

    constructor(context: Context, prefs: Prefs) : this(
        prefs = prefs,
        tokens = GisMetadataTokenSource(context),
    )

    private val http = baseHttp.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun isConnected(): Boolean = !prefs.accountEmail.isNullOrBlank()

    override fun pinnedAccountEmail(): String? = prefs.accountEmail?.takeIf { it.isNotBlank() }

    override suspend fun myDriveRootId(): String {
        val url = "$API/files/${DriveClient.ROOT}".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id")
            .build()
        return getJsonWithBackoff(url).getString("id")
    }

    override suspend fun listActiveFilesPage(pageToken: String?): DriveMetadataPage {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "trashed = false")
            .addQueryParameter("corpora", "user")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "nextPageToken,files($METADATA_FIELDS)")
            .addQueryParameter("pageSize", PAGE_SIZE)
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
        val json = getJsonWithBackoff(url)
        val array = json.optJSONArray("files") ?: JSONArray()
        val entries = List(array.length()) { i -> parseMetadataEntry(array.getJSONObject(i)) }
        return DriveMetadataPage(entries, json.optString("nextPageToken").ifEmpty { null })
    }

    override suspend fun queryLargestFiles(minSizeBytes: Long, limit: Int): List<DriveMetadataEntry> {
        val collected = ArrayList<DriveMetadataEntry>()
        val seenTokens = HashSet<String>()
        var pageToken: String? = null
        var useServerOrder = true
        var pagesRead = 0
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "q",
                    "trashed = false and mimeType != '${DriveClient.FOLDER_MIME}'",
                )
                .addQueryParameter("corpora", "user")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files($METADATA_FIELDS)")
                .addQueryParameter("pageSize", LARGE_PAGE_SIZE)
                .apply {
                    if (useServerOrder) addQueryParameter("orderBy", "quotaBytesUsed desc")
                    pageToken?.let { addQueryParameter("pageToken", it) }
                }
                .build()
            val json = try {
                getJsonWithBackoff(url)
            } catch (e: DriveHttpException) {
                if (useServerOrder && e.code == 400) {
                    useServerOrder = false
                    pageToken = null
                    seenTokens.clear()
                    collected.clear()
                    continue
                }
                throw e
            }
            pagesRead++
            val array = json.optJSONArray("files") ?: JSONArray()
            var hitBelowThreshold = false
            for (i in 0 until array.length()) {
                val entry = parseMetadataEntry(array.getJSONObject(i))
                if (!entry.isFolder && entry.size >= minSizeBytes) {
                    collected += entry
                } else if (useServerOrder && !entry.isFolder && entry.size in 1 until minSizeBytes) {
                    hitBelowThreshold = true
                }
            }
            val next = json.optString("nextPageToken").ifEmpty { null }
            if (useServerOrder && (collected.size >= limit || hitBelowThreshold)) {
                break
            }
            if (next != null && !seenTokens.add(next)) {
                throw IOException("Drive repeated a page while listing large files")
            }
            pageToken = next
        } while (pageToken != null && pagesRead < MAX_LARGE_SCAN_PAGES)
        return collected
            .sortedWith(compareByDescending<DriveMetadataEntry> { it.size }.thenBy { it.name })
            .take(limit)
    }

    override suspend fun summarizeTrash(): CloudTrashSummary {
        var count = 0
        var totalBytes = 0L
        var pageToken: String? = null
        val seenTokens = HashSet<String>()
        var pages = 0
        var truncated = false
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "trashed = true")
                .addQueryParameter("corpora", "user")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files(id,size)")
                .addQueryParameter("pageSize", PAGE_SIZE)
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = getJsonWithBackoff(url)
            pages++
            val array = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                count++
                totalBytes += o.optString("size").toLongOrNull() ?: 0L
            }
            val next = json.optString("nextPageToken").ifEmpty { null }
            if (next != null && !seenTokens.add(next)) break
            pageToken = next
            if (pageToken != null && pages >= MAX_TRASH_SUMMARY_PAGES) {
                truncated = true
                break
            }
        } while (pageToken != null)
        return CloudTrashSummary(itemCount = count, totalBytes = totalBytes, truncated = truncated)
    }

    private suspend fun getJsonWithBackoff(url: HttpUrl): JSONObject {
        val backoff = Backoff(baseMs = 1_500L, maxMs = 30_000L)
        var attempt = 0
        while (true) {
            try {
                return executeJson(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryableRateLimitException) {
                if (attempt++ >= MAX_RATE_LIMIT_RETRIES) throw e.causeException
                val delayMs = e.retryAfterMs ?: backoff.next()
                wait(delayMs)
            }
        }
    }

    private class RetryableRateLimitException(
        val retryAfterMs: Long?,
        val causeException: Exception,
    ) : Exception(causeException)

    private suspend fun executeJson(url: HttpUrl): JSONObject {
        val account = requirePinnedAccount(prefs.accountEmail)
        var token = fetchToken(account)
        var response = send(url, token)
        if (response.code == 401) {
            response.close()
            tokens.invalidate(token)
            token = fetchToken(account)
            response = send(url, token)
            if (response.code == 401) {
                response.close()
                throw AuthRequiredException()
            }
        }
        return withContext(Dispatchers.IO) {
            response.use { r ->
                if (!r.isSuccessful) {
                    val retryAfterMs = parseRetryAfterMs(r.header("Retry-After"))
                    val err = buildHttpException(r)
                    if (r.code == 429 || r.code >= 500) {
                        throw RetryableRateLimitException(retryAfterMs, err)
                    }
                    throw err
                }
                JSONObject(r.body!!.string())
            }
        }
    }

    private suspend fun fetchToken(account: String): String = try {
        tokens.tokenOrNull(account) ?: throw AuthRequiredException()
    } catch (e: ApiException) {
        throw IOException("Google sign-in service error ${e.statusCode}", e)
    }

    private suspend fun send(url: HttpUrl, token: String): Response {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        return http.newCall(req).await()
    }

    private fun buildHttpException(response: Response): DriveHttpException {
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
        var message = "HTTP ${response.code}"
        var reason: String? = null
        runCatching {
            val error = JSONObject(body).getJSONObject("error")
            message = error.optString("message", message)
            reason = error.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
        }
        return DriveHttpException(response.code, reason, message)
    }

    private fun parseRetryAfterMs(header: String?): Long? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        return (seconds.coerceIn(1L, 60L)) * 1000L
    }

    private fun parseMetadataEntry(o: JSONObject): DriveMetadataEntry {
        val parentsJson = o.optJSONArray("parents")
        val parents = if (parentsJson == null) emptyList() else List(parentsJson.length()) { parentsJson.getString(it) }
        val modifiedMs = o.optString("modifiedTime").takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
        } ?: 0L
        return DriveMetadataEntry(
            id = o.getString("id"),
            name = o.optString("name"),
            mimeType = o.optString("mimeType"),
            size = o.optString("size").toLongOrNull() ?: 0L,
            md5Checksum = o.optString("md5Checksum").ifEmpty { null },
            sha256Checksum = o.optString("sha256Checksum").ifEmpty { null },
            modifiedAtMs = modifiedMs,
            parents = parents,
            trashed = o.optBoolean("trashed", false),
            thumbnailLink = o.optString("thumbnailLink").ifEmpty { null },
            ownedByMe = o.optBoolean("ownedByMe", true),
        )
    }

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
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val PAGE_SIZE = "1000"
        private const val LARGE_PAGE_SIZE = "250"
        private const val MAX_LARGE_SCAN_PAGES = 20
        private const val MAX_TRASH_SUMMARY_PAGES = 20
        private const val MAX_RATE_LIMIT_RETRIES = 4
        private const val METADATA_FIELDS =
            "id,name,mimeType,size,md5Checksum,sha256Checksum,modifiedTime,parents,trashed,thumbnailLink,ownedByMe"
    }
}
