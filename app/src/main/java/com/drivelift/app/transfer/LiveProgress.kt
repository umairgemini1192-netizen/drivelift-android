package com.drivelift.app.transfer

import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveHttpException
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/**
 * What the workers are doing right now. Byte-level progress lives here in
 * memory (the workers and the UI share a process) so the database is written
 * only on real state changes.
 */
object LiveProgress {
    enum class Phase { IDLE, UPLOADING, VERIFYING, RETRY_WAIT }

    data class State(
        val phase: Phase = Phase.IDLE,
        val itemId: Long = -1,
        val name: String = "",
        val sent: Long = 0,
        val total: Long = 0,
        val bytesPerSec: Long = 0,
        val message: String? = null,
        val retryAtMs: Long = 0,
    )

    val state = MutableStateFlow(State())

    /** Set by the UI to abort the file that is uploading at this moment. */
    @Volatile
    var cancelItemId: Long = -1
}

/** Plain-language reason shown next to a file that is being retried. */
fun describeError(e: Throwable): String = when (e) {
    is AuthRequiredException -> "Google Drive needs you to sign in again. Open the app to reconnect"
    is DriveHttpException -> when {
        e.reason == "storageQuotaExceeded" -> "Google Drive storage is full"
        e.code == 404 -> "The destination folder was not found in Drive"
        e.code == 429 || e.reason?.contains("RateLimit", ignoreCase = true) == true ->
            "Drive asked to slow down"
        e.code >= 500 -> "Drive is temporarily unavailable"
        else -> "Drive error ${e.code}: ${e.message}"
    }
    is IOException -> "Connection problem: ${e.message ?: "network unavailable"}"
    else -> e.message ?: e.javaClass.simpleName
}
