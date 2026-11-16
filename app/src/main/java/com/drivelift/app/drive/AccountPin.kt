package com.drivelift.app.drive

import java.io.IOException

/** Where the email of the connected Drive account is kept. */
interface AccountPinStore {
    var accountEmail: String?
}

/**
 * The account every Drive call that is not part of connecting must be pinned to. With no saved
 * account there is nothing to pin to, so the call is refused instead of letting Google choose an
 * account: on a phone with several accounts that could send a file to the wrong Drive.
 */
fun requirePinnedAccount(email: String?): String =
    email?.takeIf { it.isNotBlank() } ?: throw AuthRequiredException()

/**
 * Statuses whose rows still hold Drive ids of the connected account and can call Drive again:
 * waiting, uploading, waiting for verification, and failed (which the user can retry).
 */
val ACCOUNT_BOUND_STATUSES = listOf("QUEUED", "UPLOADING", "UPLOADED", "FAILED")

/**
 * What to show after a connection attempt that proved nothing new (cancelled, failed, refused):
 * connected only when the account connected before the attempt is still the saved pin.
 */
fun stillConnected(connectedEmail: String?, pinnedEmail: String?): Boolean =
    !connectedEmail.isNullOrBlank() && connectedEmail.equals(pinnedEmail, ignoreCase = true)

sealed interface ConnectOutcome {
    /** [about] is the proven account and is now the saved pin. */
    data class Connected(val about: DriveAbout, val switched: Boolean) : ConnectOutcome

    /** Consent was closed without a grant. The saved pin is unchanged. */
    data object Cancelled : ConnectOutcome

    /** Another account was chosen while transfers still belong to [current]. The pin is unchanged. */
    data class SwitchRefused(val current: String, val requested: String) : ConnectOutcome
}

/**
 * Decides which Google account the app is pinned to. The saved pin changes in exactly one place:
 * after Drive has answered, with the token of a successful authorization, which account that
 * token belongs to. A cancelled consent, a failed sign-in or a failed account lookup leave the
 * earlier pin as it was, so background work stays on the account it was started for.
 *
 * Transfers and pinned destinations hold Drive folder and file ids, which only mean something in
 * the account they came from. So a switch to another account is refused while any transfer could
 * still call Drive ([openTransfers]), and when it is allowed the pinned destinations of the old
 * account are forgotten ([forgetDestinations]) before the new pin is saved.
 */
class AccountConnector(
    private val pin: AccountPinStore,
    private val identify: suspend (token: String) -> DriveAbout,
    private val openTransfers: suspend () -> Int,
    private val forgetDestinations: suspend () -> Unit,
) {
    /** [token] is the access token of the authorization that just succeeded, or null if none did. */
    suspend fun establish(token: String?): ConnectOutcome {
        if (token.isNullOrBlank()) return ConnectOutcome.Cancelled
        val about = identify(token)
        if (about.email.isBlank()) throw IOException("Drive did not say which account this is")
        val saved = pin.accountEmail?.takeIf { it.isNotBlank() }
        val switched = saved != null && !saved.equals(about.email, ignoreCase = true)
        if (switched) {
            if (openTransfers() > 0) return ConnectOutcome.SwitchRefused(saved!!, about.email)
            forgetDestinations()
        }
        pin.accountEmail = about.email
        return ConnectOutcome.Connected(about, switched)
    }
}
