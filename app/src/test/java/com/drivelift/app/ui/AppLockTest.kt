package com.drivelift.app.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.transfer.Notifications
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue 19: biometric lock at a fresh launch and after the screen turns off, and never on an
 * ordinary return to the app. The system prompt is replaced by [FakeAuthenticator]; each test
 * reports the outcome to [AppLock] the way BiometricPrompt's callback does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class AppLockTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    private class FakeAuthenticator(
        private val lock: AppLock,
        var available: BiometricAvailability = BiometricAvailability.READY,
    ) : Authenticator {
        var prompts = 0
        override fun availability(context: Context) = available
        override fun authenticate(context: Context) {
            prompts++
            lock.onPromptShown()
        }
    }

    @Before
    fun fakeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun pump(times: Int = 10) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(20)
    }

    private fun ComponentActivity.showGate(lock: AppLock, auth: Authenticator) = setContent {
        DriveLiftTheme { LockGate(lock, auth) { Text("Private content") } }
    }

    private fun gate(
        lock: AppLock = AppLock(),
        configure: (FakeAuthenticator) -> Unit = {},
        test: (ActivityScenario<ComponentActivity>, AppLock, FakeAuthenticator) -> Unit,
    ) {
        val auth = FakeAuthenticator(lock).also(configure)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.showGate(lock, auth) }
            pump()
            test(scenario, lock, auth)
        }
    }

    private fun assertLocked() {
        compose.onNodeWithText("DriveLift is locked").assertExists()
        assertTrue(
            "Nothing behind the lock may be composed",
            compose.onAllNodesWithText("Private content").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun assertUnlocked() {
        compose.onNodeWithText("Private content").assertExists()
        assertTrue(compose.onAllNodesWithText("DriveLift is locked").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun newProcessStartsLockedAndNothingIsStored() {
        assertFalse(AppLock().state.value.unlocked)
        assertFalse(Graph.get(app).lock.state.value.unlocked)
        // The session is never persisted: no preference file carries it.
        val prefs = app.getSharedPreferences("drivelift", Context.MODE_PRIVATE).all.keys
        assertTrue("Unexpected lock state in preferences: $prefs", prefs.none { it.contains("lock", ignoreCase = true) })
    }

    @Test
    fun lockedLaunchShowsNeutralScreenAndPromptsOnce() = gate { _, _, auth ->
        assertLocked()
        assertEquals(1, auth.prompts)
        pump(20)
        assertEquals("The prompt must not repeat on its own", 1, auth.prompts)
    }

    @Test
    fun successfulAuthenticationShowsTheApp() = gate { _, lock, _ ->
        lock.onUnlocked()
        pump()
        assertUnlocked()
    }

    @Test
    fun cancelKeepsAppLockedWithRetryAndNoPromptLoop() = gate { scenario, lock, auth ->
        lock.onProblem(LockProblem.Cancelled)
        pump()
        assertLocked()
        compose.onNodeWithText("Unlock was cancelled", substring = true).assertExists()
        assertFalse(lock.state.value.unlocked)

        // Leaving and returning does not start another automatic prompt for the same lock.
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        pump()
        assertEquals(1, auth.prompts)

        compose.onNodeWithText("Try again").performClick()
        pump()
        assertEquals("Retry is explicit", 2, auth.prompts)
    }

    @Test
    fun failedAuthenticationKeepsAppLocked() = gate { _, lock, _ ->
        lock.onProblem(BiometricAuthenticator.problemFor(BiometricPrompt.ERROR_VENDOR, "sensor error"))
        pump()
        assertLocked()
        compose.onNodeWithText("Couldn't confirm it's you", substring = true).assertExists()
        compose.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun noEnrolledBiometricShowsGuidanceAndNeverBypasses() =
        gate(configure = { it.available = BiometricAvailability.NOT_ENROLLED }) { _, lock, auth ->
            assertLocked()
            assertEquals(0, auth.prompts)
            assertEquals(LockProblem.NotEnrolled, lock.state.value.problem)
            compose.onNodeWithText("Set up fingerprint or face").assertExists()
            compose.onNodeWithText("Try again").performClick()
            pump()
            assertLocked()
        }

    @Test
    fun unavailableBiometricKeepsAppLocked() =
        gate(configure = { it.available = BiometricAvailability.UNAVAILABLE }) { _, lock, auth ->
            assertLocked()
            assertEquals(0, auth.prompts)
            assertEquals(LockProblem.Unavailable, lock.state.value.problem)
            compose.onNodeWithText("can't use fingerprint or face", substring = true).assertExists()
        }

    @Test
    fun lockoutExplainsWhatToDo() = gate { _, lock, _ ->
        lock.onProblem(BiometricAuthenticator.problemFor(BiometricPrompt.ERROR_LOCKOUT, ""))
        pump()
        compose.onNodeWithText("Too many attempts", substring = true).assertExists()
        lock.onProblem(BiometricAuthenticator.problemFor(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, ""))
        pump()
        compose.onNodeWithText("unlock it with your PIN", substring = true).assertExists()
        assertLocked()
    }

    @Test
    fun promptErrorCodesMapToLockedStates() {
        val map = BiometricAuthenticator.Companion::problemFor
        assertEquals(LockProblem.Cancelled, map(BiometricPrompt.ERROR_USER_CANCELED, ""))
        assertEquals(LockProblem.Cancelled, map(BiometricPrompt.ERROR_NEGATIVE_BUTTON, ""))
        assertEquals(LockProblem.Cancelled, map(BiometricPrompt.ERROR_CANCELED, ""))
        assertEquals(LockProblem.NotEnrolled, map(BiometricPrompt.ERROR_NO_BIOMETRICS, ""))
        assertEquals(LockProblem.Unavailable, map(BiometricPrompt.ERROR_HW_UNAVAILABLE, ""))
        assertEquals(LockProblem.Lockout(permanent = true), map(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, ""))
    }

    @Test
    fun backgroundAndForegroundKeepTheSession() = gate { scenario, lock, auth ->
        lock.onUnlocked()
        pump()
        // Home, Recents, an external viewer, Google authorization or Android's removal consent
        // all stop the activity without finishing it.
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        pump()
        assertUnlocked()
        assertEquals(1, auth.prompts)
    }

    @Test
    fun configurationChangeKeepsTheSession() = gate { scenario, lock, auth ->
        lock.onUnlocked()
        pump()
        scenario.recreate()
        scenario.onActivity { it.showGate(lock, auth) }
        pump()
        assertUnlocked()
        assertEquals(1, auth.prompts)
    }

    @Test
    fun screenOffEndsTheSessionAndTheNextReturnAsksAgain() {
        val lock = Graph.get(app).lock
        gate(lock = lock) { scenario, _, auth ->
            lock.onUnlocked()
            pump()
            assertUnlocked()

            scenario.moveToState(Lifecycle.State.CREATED)
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            pump()
            assertFalse("Screen off must end the unlocked session", lock.state.value.unlocked)

            scenario.moveToState(Lifecycle.State.RESUMED)
            pump()
            assertLocked()
            assertEquals("One new prompt for the new lock", 2, auth.prompts)
        }
    }

    @Test
    fun lockingNeverTouchesTransfersOrBackgroundWork() {
        val graph = Graph.get(app)
        runBlocking {
            graph.db.transfers().insertAll(
                listOf(
                    TransferEntity(
                        id = 1, mediaUri = "content://media/external/images/media/1", name = "IMG_0001.jpg",
                        mime = "image/jpeg", size = 1_000, dateModifiedMs = 1L, dateTakenMs = 1L,
                        bucketName = "Camera", destFolderId = null, destLabel = "DriveLift Backup / Camera",
                        status = TransferStatus.QUEUED.name, driveFileId = null, attempts = 0, lastError = null,
                    ),
                ),
            )
        }
        graph.transfers.kickUpload()
        pump()
        graph.lock.onUnlocked()
        graph.lock.lock()
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        pump()
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("drivelift-upload").get()
        assertTrue("Upload work must stay scheduled while locked", work.any { it.state == WorkInfo.State.ENQUEUED })
        val row = runBlocking { graph.db.transfers().get(1) }
        assertEquals(TransferStatus.QUEUED.name, row?.status)
    }

    @Test
    fun appEntryIsGatedAndFinishingEndsTheSession() {
        val graph = Graph.get(app)
        val auth = FakeAuthenticator(graph.lock)
        graph.authenticator = auth
        compose.mainClock.autoAdvance = false
        // The notification entry route: it asks for Transfers, which must stay hidden until unlock.
        val intent = Intent(app, MainActivity::class.java).putExtra(Notifications.EXTRA_OPEN_TRANSFERS, true)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        pump(15)
        compose.onNodeWithText("DriveLift is locked").assertExists()
        assertTrue(compose.onAllNodesWithText("Transfers").fetchSemanticsNodes().isEmpty())
        assertEquals(1, auth.prompts)

        graph.lock.onUnlocked()
        pump(15)
        compose.onNodeWithText("Transfers").assertExists()

        // Back from the root screen ends the session. Since Android 12 the platform backgrounds
        // the task instead of finishing it, so the lock must not depend on onDestroy.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        runCatching { scenario.moveToState(Lifecycle.State.CREATED) }
        pump()
        assertFalse("Back from the root must end the session", graph.lock.state.value.unlocked)
        scenario.close()
    }

    @Test
    fun homeWithoutBackKeepsTheSessionInTheRealActivity() {
        val graph = Graph.get(app)
        val auth = FakeAuthenticator(graph.lock)
        graph.authenticator = auth
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            pump(15)
            graph.lock.onUnlocked()
            pump(10)
            // Home, Recents, an external viewer or a system consent screen: stopped, not Back.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            pump(10)
            assertTrue(graph.lock.state.value.unlocked)
            assertEquals(1, auth.prompts)
        }
    }

    @Test
    fun finishingTheActivityEndsTheSession() {
        val graph = Graph.get(app)
        graph.authenticator = FakeAuthenticator(graph.lock)
        compose.mainClock.autoAdvance = false
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        pump(15)
        graph.lock.onUnlocked()
        pump()
        // A swipe away in Recents finishes the activity.
        scenario.close()
        pump()
        assertFalse(graph.lock.state.value.unlocked)
    }
}
