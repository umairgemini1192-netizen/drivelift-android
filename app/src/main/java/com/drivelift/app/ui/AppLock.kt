package com.drivelift.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

/**
 * Why the app is still locked after an attempt, shown on the locked screen.
 */
sealed interface LockProblem {
    data object Cancelled : LockProblem
    data object NotEnrolled : LockProblem
    data object Unavailable : LockProblem
    data class Lockout(val permanent: Boolean) : LockProblem
    data class Error(val message: String) : LockProblem
}

/**
 * The biometric lock for one app process.
 *
 * Session boundary (issue 19):
 * - A new process always starts locked: nothing here is ever written to storage.
 * - The session ends when the screen turns off ([lock] from the app's screen-off receiver) and
 *   when the activity really finishes (Back from the root or swiped away in Recents), so the next
 *   launch asks again even if background uploads kept the process alive.
 * - Configuration changes, Home, Recents and returning from Google authorization, Android's
 *   removal consent or an external viewer keep the session, because none of them ends it.
 *
 * Locking only hides the UI. Uploads, retries and verification carry on, and unlocking grants
 * nothing beyond showing the app: removal still needs Android's own confirmation.
 */
class AppLock {
    data class State(
        val unlocked: Boolean = false,
        /** The system prompt is on screen; no second one may be started. */
        val prompting: Boolean = false,
        val problem: LockProblem? = null,
        /** Increases on every lock, so each new lock gets exactly one automatic prompt. */
        val epoch: Int = 0,
        val autoPromptedEpoch: Int = -1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun lock() = _state.update { State(epoch = it.epoch + 1) }

    /** Claims the automatic prompt for the current lock. False if it was already used. */
    fun claimAutoPrompt(): Boolean {
        var claimed = false
        _state.update {
            if (it.unlocked || it.prompting || it.autoPromptedEpoch == it.epoch) it
            else {
                claimed = true
                it.copy(autoPromptedEpoch = it.epoch)
            }
        }
        return claimed
    }

    fun onPromptShown() = _state.update { it.copy(prompting = true, problem = null) }
    fun onUnlocked() = _state.update { it.copy(unlocked = true, prompting = false, problem = null) }
    fun onProblem(problem: LockProblem) = _state.update { it.copy(prompting = false, problem = problem) }
}

enum class BiometricAvailability { READY, NOT_ENROLLED, UNAVAILABLE }

/** Starts a biometric check. Results go to [AppLock], not to whoever asked. */
interface Authenticator {
    fun availability(context: Context): BiometricAvailability
    fun authenticate(context: Context)
}

/**
 * Android's own BiometricPrompt. DriveLift never sees, stores or sends biometric data, and there
 * is no fallback to the device PIN: the owner has not approved one.
 */
class BiometricAuthenticator(private val lock: AppLock) : Authenticator {
    private var prompt: WeakReference<BiometricPrompt>? = null

    /**
     * Called from the activity's onCreate, as BiometricPrompt requires, so a prompt that is open
     * during a configuration change reports to the new activity.
     */
    fun bind(activity: FragmentActivity) {
        prompt = WeakReference(
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), Callback(lock)),
        )
    }

    override fun availability(context: Context): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }

    override fun authenticate(context: Context) {
        val current = prompt?.get()
        if (current == null || context.findFragmentActivity() == null) {
            lock.onProblem(LockProblem.Error("the unlock prompt could not be opened"))
            return
        }
        lock.onPromptShown()
        current.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock DriveLift")
                .setSubtitle("Confirm it's you to see your files and transfers.")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .setConfirmationRequired(false)
                .build(),
        )
    }

    private class Callback(private val lock: AppLock) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = lock.onUnlocked()

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            lock.onProblem(problemFor(errorCode, errString.toString()))
        }
        // A single unrecognised finger or face keeps the prompt open; Android counts the attempts.
    }

    companion object {
        fun problemFor(errorCode: Int, message: String): LockProblem = when (errorCode) {
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_TIMEOUT -> LockProblem.Cancelled
            BiometricPrompt.ERROR_LOCKOUT -> LockProblem.Lockout(permanent = false)
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> LockProblem.Lockout(permanent = true)
            BiometricPrompt.ERROR_NO_BIOMETRICS -> LockProblem.NotEnrolled
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE -> LockProblem.Unavailable
            else -> LockProblem.Error(message)
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
