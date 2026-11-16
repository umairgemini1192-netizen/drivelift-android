package com.drivelift.app

import com.drivelift.app.drive.ACCOUNT_BOUND_STATUSES
import com.drivelift.app.drive.AccountConnector
import com.drivelift.app.drive.AccountPinStore
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.ConnectOutcome
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.drive.requirePinnedAccount
import com.drivelift.app.drive.stillConnected
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The saved Google account changes only after another account has been proven. */
class AccountPinTest {
    private class Store(override var accountEmail: String?) : AccountPinStore

    private fun about(email: String) = DriveAbout(name = "Test", email = email, usedBytes = 0, limitBytes = 0)

    private class Fixture(
        saved: String?,
        var open: Int = 0,
        val identify: suspend (String) -> DriveAbout,
    ) {
        val store = Store(saved)
        var destinationsForgotten = 0
        val tokensIdentified = ArrayList<String>()
        val connector = AccountConnector(
            pin = store,
            identify = { token -> tokensIdentified += token; identify(token) },
            openTransfers = { open },
            forgetDestinations = { destinationsForgotten++ },
        )
    }

    @Test
    fun cancelledConsentKeepsTheSavedAccount() = runBlocking {
        val f = Fixture(saved = "first@example.com") { error("no token, so nothing to look up") }
        assertEquals(ConnectOutcome.Cancelled, f.connector.establish(null))
        assertEquals(ConnectOutcome.Cancelled, f.connector.establish(""))
        assertEquals("first@example.com", f.store.accountEmail)
        assertTrue(f.tokensIdentified.isEmpty())
    }

    @Test
    fun failedAccountLookupKeepsTheSavedAccount() {
        val f = Fixture(saved = "first@example.com") { throw IOException("offline") }
        assertThrows(IOException::class.java) { runBlocking { f.connector.establish("consent-token") } }
        assertEquals("first@example.com", f.store.accountEmail)
        assertEquals(0, f.destinationsForgotten)
    }

    @Test
    fun failedAccountLookupOnFirstConnectionLeavesNoPin() {
        val f = Fixture(saved = null) { throw IOException("offline") }
        assertThrows(IOException::class.java) { runBlocking { f.connector.establish("consent-token") } }
        assertNull(f.store.accountEmail)
    }

    @Test
    fun lookupWithoutAnEmailIsAFailureNotAnEmptyPin() {
        val f = Fixture(saved = "first@example.com") { about("") }
        assertThrows(IOException::class.java) { runBlocking { f.connector.establish("consent-token") } }
        assertEquals("first@example.com", f.store.accountEmail)
    }

    @Test
    fun identityComesFromTheTokenOfTheSuccessfulAuthorization() = runBlocking {
        val f = Fixture(saved = null) { about("first@example.com") }
        val outcome = f.connector.establish("consent-token")
        assertEquals(ConnectOutcome.Connected(about("first@example.com"), switched = false), outcome)
        assertEquals(listOf("consent-token"), f.tokensIdentified)
        assertEquals("first@example.com", f.store.accountEmail)
    }

    @Test
    fun reconnectingTheSameAccountKeepsItsDestinations() = runBlocking {
        val f = Fixture(saved = "first@example.com", open = 3) { about("First@Example.com") }
        val outcome = f.connector.establish("consent-token") as ConnectOutcome.Connected
        assertFalse(outcome.switched)
        assertEquals(0, f.destinationsForgotten)
        assertEquals("First@Example.com", f.store.accountEmail)
    }

    @Test
    fun switchingAccountIsRefusedWhileTransfersBelongToTheOldOne() = runBlocking {
        val f = Fixture(saved = "first@example.com", open = 1) { about("second@example.com") }
        val outcome = f.connector.establish("consent-token")
        assertEquals(ConnectOutcome.SwitchRefused("first@example.com", "second@example.com"), outcome)
        assertEquals("first@example.com", f.store.accountEmail)
        assertEquals(0, f.destinationsForgotten)
    }

    @Test
    fun switchingAccountWithNothingOpenForgetsOldDestinationsThenPins() = runBlocking {
        val f = Fixture(saved = "first@example.com", open = 0) { about("second@example.com") }
        val outcome = f.connector.establish("consent-token") as ConnectOutcome.Connected
        assertTrue(outcome.switched)
        assertEquals(1, f.destinationsForgotten)
        assertEquals("second@example.com", f.store.accountEmail)
    }

    @Test
    fun rowsThatCanStillCallDriveBlockASwitch() {
        assertEquals(listOf("QUEUED", "UPLOADING", "UPLOADED", "FAILED"), ACCOUNT_BOUND_STATUSES)
    }

    @Test
    fun backgroundRequestsAreRefusedWithoutAPin() {
        assertThrows(AuthRequiredException::class.java) { requirePinnedAccount(null) }
        assertThrows(AuthRequiredException::class.java) { requirePinnedAccount("") }
        assertThrows(AuthRequiredException::class.java) { requirePinnedAccount("  ") }
    }

    @Test
    fun backgroundRequestsStayOnTheOldAccountDuringAFailedReconnection() {
        val f = Fixture(saved = "first@example.com") { throw IOException("offline") }
        // While consent is open, and after it is cancelled or the lookup fails, a worker's
        // token request is still pinned to the account its transfers were started for.
        assertEquals("first@example.com", requirePinnedAccount(f.store.accountEmail))
        runBlocking { f.connector.establish(null) }
        assertEquals("first@example.com", requirePinnedAccount(f.store.accountEmail))
        runCatching { runBlocking { f.connector.establish("consent-token") } }
        assertEquals("first@example.com", requirePinnedAccount(f.store.accountEmail))
    }

    @Test
    fun aFailedAttemptShowsConnectedOnlyIfTheEarlierAccountIsStillPinned() {
        assertTrue(stillConnected("first@example.com", "first@example.com"))
        assertTrue(stillConnected("First@Example.com", "first@example.com"))
        assertFalse(stillConnected(null, "first@example.com"))
        assertFalse(stillConnected("first@example.com", null))
        assertFalse(stillConnected("first@example.com", "second@example.com"))
        assertFalse(stillConnected("", ""))
    }
}
