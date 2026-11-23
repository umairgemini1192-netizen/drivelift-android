package com.drivelift.app.feature.applock.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.drivelift.app.Graph
import com.drivelift.app.feature.applock.AppLockGateMode
import com.drivelift.app.feature.applock.AppLockGateRequest
import com.drivelift.app.feature.applock.PinVerifyResult
import com.drivelift.app.ui.DriveLiftTheme
import kotlinx.coroutines.delay

/**
 * Hardened full-screen authentication gate for locked apps and Quick Settings 15-minute pause.
 *
 * Security hardening:
 * - `FLAG_SECURE` prevents screenshots and Recents thumbnails.
 * - `excludeFromRecents=true` in AndroidManifest keeps the gate out of the Recents stack.
 * - `filterTouchesWhenObscured = true` blocks tapjacking overlays.
 * - `BiometricPrompt` requires `BIOMETRIC_STRONG` (Class 3).
 * - Back and Cancel in `UNLOCK_APP` mode route directly to the device Home screen before
 *   finishing with zero transition animation so the locked app never flashes.
 */
class AppLockGateActivity : FragmentActivity() {

    private var gateMode: AppLockGateMode = AppLockGateMode.UNLOCK_APP
    private var targetPackage: String = ""
    private var targetAppLabel: String = "Locked app"

    private var enteredPin by mutableStateOf("")
    private var errorMessage by mutableStateOf<String?>(null)
    private var errorShakeTrigger by mutableIntStateOf(0)
    private var lockoutRemainingMs by mutableLongStateOf(0L)
    private var biometricPrompt: BiometricPrompt? = null
    private var hasAutoPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots, screen recording, and Recents snapshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true

        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)

        parseIntent(intent)

        val graph = Graph.get(this)
        val appLock = graph.appLock
        lockoutRemainingMs = appLock.engine.currentLockoutState().remainingLockoutMs(graph.clock())

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLock.onAuthenticationSucceeded(gateMode, targetPackage)
                    finishUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        errorMessage = errString.toString().ifBlank { "Biometric check could not complete." }
                    }
                }
            },
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleCancelOrBack()
                }
            },
        )

        val canBiometricStrong = BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

        setContent {
            val config = appLock.prefs.config.value
            val hasPin = appLock.prefs.hasPinConfigured.value
            val expectedPinLength = appLock.prefs.pinLength.value

            LaunchedEffect(lockoutRemainingMs) {
                if (lockoutRemainingMs > 0L) {
                    delay(AppLockTokens.LOCKOUT_TICK_MS)
                    lockoutRemainingMs = appLock.engine.currentLockoutState().remainingLockoutMs(graph.clock())
                }
            }

            DriveLiftTheme {
                AppLockGateScreen(
                    state = AppLockGateUiState(
                        mode = gateMode,
                        targetAppLabel = targetAppLabel,
                        targetPackageName = targetPackage,
                        enteredDigitsCount = enteredPin.length,
                        expectedPinLength = expectedPinLength,
                        hasPinConfigured = hasPin,
                        biometricAvailable = canBiometricStrong,
                        allowBiometric = config.allowBiometric,
                        errorMessage = errorMessage,
                        errorShakeTrigger = errorShakeTrigger,
                        lockoutRemainingMs = lockoutRemainingMs,
                    ),
                    onDigitPressed = { digit ->
                        if (lockoutRemainingMs > 0L || !hasPin) return@AppLockGateScreen
                        if (enteredPin.length < expectedPinLength) {
                            errorMessage = null
                            enteredPin += digit
                            if (enteredPin.length == expectedPinLength) {
                                submitPin(enteredPin)
                            }
                        }
                    },
                    onBackspacePressed = {
                        if (enteredPin.isNotEmpty()) {
                            enteredPin = enteredPin.dropLast(1)
                            errorMessage = null
                        }
                    },
                    onBiometricPressed = { triggerBiometricPrompt() },
                    onCancelPressed = { handleCancelOrBack() },
                )
            }
        }

        // Harden the Compose content root view against obscured touches as well.
        window.decorView.findViewById<android.view.View>(android.R.id.content)?.filterTouchesWhenObscured = true

        if (!hasAutoPrompted && appLock.prefs.config.value.allowBiometric && canBiometricStrong && lockoutRemainingMs <= 0L) {
            hasAutoPrompted = true
            triggerBiometricPrompt()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent(intent)
        enteredPin = ""
        errorMessage = null
    }

    private fun parseIntent(intent: Intent?) {
        val modeName = intent?.getStringExtra(EXTRA_MODE)
        gateMode = AppLockGateMode.entries.firstOrNull { it.name == modeName }
            ?: AppLockGateMode.UNLOCK_APP
        targetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        targetAppLabel = intent?.getStringExtra(EXTRA_APP_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: if (gateMode == AppLockGateMode.AUTHORIZE_PAUSE) "Pause App Lock" else "Locked app"
    }

    private fun submitPin(candidate: String) {
        val graph = Graph.get(this)
        when (val result = graph.appLock.verifyPin(candidate, gateMode, targetPackage)) {
            PinVerifyResult.Success -> {
                enteredPin = ""
                finishUnlocked()
            }
            is PinVerifyResult.WrongPin -> {
                enteredPin = ""
                val remaining = result.lockoutState.remainingLockoutMs(graph.clock())
                lockoutRemainingMs = remaining
                errorMessage = if (remaining > 0L) {
                    "Incorrect PIN. Lockout active."
                } else {
                    "Incorrect PIN. Try again."
                }
                errorShakeTrigger += 1
            }
            is PinVerifyResult.LockedOut -> {
                enteredPin = ""
                lockoutRemainingMs = result.remainingMs
                errorMessage = "PIN entry is temporarily locked."
                errorShakeTrigger += 1
            }
            PinVerifyResult.NoPinConfigured -> {
                enteredPin = ""
                errorMessage = "No backup PIN configured."
            }
        }
    }

    private fun triggerBiometricPrompt() {
        val prompt = biometricPrompt ?: return
        if (lockoutRemainingMs > 0L) return
        val canStrong = BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (!canStrong) return

        val title = if (gateMode == AppLockGateMode.AUTHORIZE_PAUSE) {
            "Pause App Lock for 15 min"
        } else {
            "Unlock $targetAppLabel"
        }
        val subtitle = if (gateMode == AppLockGateMode.AUTHORIZE_PAUSE) {
            "Confirm with Class 3 biometric to pause protection in memory."
        } else {
            "Confirm with Class 3 biometric to continue."
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    internal fun handleCancelOrBack() {
        if (gateMode == AppLockGateMode.UNLOCK_APP) {
            homeRouter(this)
        }
        finishWithoutFlash()
    }

    private fun finishUnlocked() {
        finishWithoutFlash()
    }

    private fun finishWithoutFlash() {
        finishAndRemoveTask()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_MODE = "com.drivelift.app.applock.EXTRA_MODE"
        const val EXTRA_PACKAGE_NAME = "com.drivelift.app.applock.EXTRA_PACKAGE_NAME"
        const val EXTRA_APP_LABEL = "com.drivelift.app.applock.EXTRA_APP_LABEL"

        /** Replaceable in tests to verify Home routing without relying on a real launcher. */
        internal var homeRouter: (Context) -> Unit = { context ->
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeIntent)
        }

        fun createIntent(context: Context, request: AppLockGateRequest): Intent =
            Intent(context, AppLockGateActivity::class.java).apply {
                putExtra(EXTRA_MODE, request.mode.name)
                putExtra(EXTRA_PACKAGE_NAME, request.packageName)
                putExtra(EXTRA_APP_LABEL, request.appLabel)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

        fun launch(context: Context, request: AppLockGateRequest) {
            context.startActivity(createIntent(context, request))
        }
    }
}
