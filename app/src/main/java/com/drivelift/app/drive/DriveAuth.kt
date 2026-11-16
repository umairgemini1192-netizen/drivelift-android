package com.drivelift.app.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AuthRequiredException : Exception("Sign in to Google Drive again to continue")

/**
 * Google Drive access through Google Identity Services. No client secret or
 * client id lives in the app: Google matches the request to the OAuth client
 * registered for this package name and signing certificate.
 */
object DriveAuth {
    /** Full Drive scope: needed to list existing folders and upload into them. */
    const val SCOPE = "https://www.googleapis.com/auth/drive"

    private fun request(email: String?): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .apply {
                if (!email.isNullOrBlank()) setAccount(Account(email, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE))
            }
            .build()

    /**
     * Returns a token silently when access was granted before, otherwise a consent intent.
     * [email] pins the request to the account connected earlier, so a background job on a phone
     * with several Google accounts gets that account's token without an account picker.
     */
    suspend fun authorize(context: Context, email: String? = null): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request(email)).await()

    suspend fun tokenOrNull(context: Context, email: String? = null): String? {
        val result = authorize(context, email)
        return if (result.hasResolution()) null else result.accessToken
    }

    fun tokenFromConsent(context: Context, data: Intent?): String? =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken

    /** Drops a token Drive rejected so the next request mints a fresh one. */
    suspend fun invalidate(context: Context, token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
        Unit
    }
}
