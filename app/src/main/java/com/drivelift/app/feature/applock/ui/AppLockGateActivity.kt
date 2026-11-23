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
import com.drivelift.app.ui.Tokens
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
    private var showPinInput by mutableStateOf(false)
    private var isBiometricActive by mutableStateOf(false)
    private var biometricPrompt: BiometricPrompt? = null
    private var hasAutoPrompted = false

    internal var biometricPromptTriggerCount: Int = 0
        private set

    internal fun isPinInputVisibleForTest(): Boolean = showPinInput
    internal fun isBiometricActiveForTest(): Boolean = isBiometricActive

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots, screen recording, and Recents snapshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true

        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)

        parseIntent(intent)

        if (savedInstanceState != null) {
            hasAutoPrompted = savedInstanceState.getBoolean(KEY_HAS_AUTO_PROMPTED, false)
            showPinInput = savedInstanceState.getBoolean(KEY_SHOW_PIN_INPUT, false)
            isBiometricActive = savedInstanceState.getBoolean(KEY_IS_BIOMETRIC_ACTIVE, false)
        }

        val graph = Graph.get(this)
        val appLock = graph.appLock
        lockoutRemainingMs = appLock.engine.currentLockoutState().remainingLockoutMs(graph.clock())

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    handleBiometricSucceeded()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    handleBiometricError(errorCode, errString)
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

        val canBiometricStrong = isBiometricStrongAvailable()
        val shouldAutoPrompt = savedInstanceState == null &&
            !hasAutoPrompted &&
            appLock.prefs.config.value.allowBiometric &&
            canBiometricStrong &&
            lockoutRemainingMs <= 0L

        if (savedInstanceState == null) {
            showPinInput = !shouldAutoPrompt
            isBiometricActive = shouldAutoPrompt
        }

        setContent {
            val config = appLock.prefs.config.value
            val hasPin = appLock.prefs.hasPinConfigured.value
            val expectedPinLength = appLock.prefs.pinLength.value

            LaunchedEffect(lockoutRemainingMs) {
                if (lockoutRemainingMs > 0L) {
                    delay(Tokens.AppLock.LOCKOUT_TICK_MS)
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
                        showPinInput = showPinInput || lockoutRemainingMs > 0L,
                        isBiometricActive = isBiometricActive,
                        errorMessage = errorMessage,
                        errorShakeTrigger = errorShakeTrigger,
                        lockoutRemainingMs = lockoutRemainingMs,
                    ),
                    onDigitPressed = { digit ->
                        if (lockoutRemainingMs > 0L || !hasPin || !showPinInput) return@AppLockGateScreen
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
                    onBiometricPressed = { triggerBiometricPrompt(userInitiated = true) },
                    onUsePinPressed = { revealPinInputFromUserAction() },
                    onCancelPressed = { handleCancelOrBack() },
                )
            }
        }

        // Harden the Compose content root view against obscured touches as well.
        window.decorView.findViewById<android.view.View>(android.R.id.content)?.filterTouchesWhenObscured = true

        if (shouldAutoPrompt) {
            hasAutoPrompted = true
            isBiometricActive = false
            triggerBiometricPrompt(userInitiated = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HAS_AUTO_PROMPTED, hasAutoPrompted)
        outState.putBoolean(KEY_SHOW_PIN_INPUT, showPinInput)
        outState.putBoolean(KEY_IS_BIOMETRIC_ACTIVE, isBiometricActive)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val previousMode = gateMode
        val previousPackage = targetPackage
        parseIntent(intent)
        if (gateMode == previousMode && targetPackage == previousPackage) {
            // Same gate launch: never re-trigger BiometricPrompt on duplicate focus/intent delivery.
            return
        }
        enteredPin = ""
        errorMessage = null
        hasAutoPrompted = false
        isBiometricActive = false
        val appLock = Graph.get(this).appLock
        val canStrong = isBiometricStrongAvailable()
        if (appLock.prefs.config.value.allowBiometric && canStrong && lockoutRemainingMs <= 0L) {
            hasAutoPrompted = true
            showPinInput = false
            triggerBiometricPrompt(userInitiated = false)
        } else {
            showPinInput = true
        }
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

    internal fun handleBiometricSucceeded() {
        isBiometricActive = false
        // Commit unlocked state in memory synchronously BEFORE finishing so the foreground monitor
        // never re-locks the app as focus returns to it.
        Graph.get(this).appLock.onAuthenticationSucceeded(gateMode, targetPackage)
        finishUnlocked()
    }

    internal fun handleBiometricError(errorCode: Int, errString: CharSequence) {
        isBiometricActive = false
        showPinInput = true
        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
            errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
            errorCode != BiometricPrompt.ERROR_CANCELED
        ) {
            errorMessage = errString.toString().ifBlank { "Biometric check could not complete." }
        }
    }

    internal fun revealPinInputFromUserAction() {
        runCatching { biometricPrompt?.cancelAuthentication() }
        isBiometricActive = false
        showPinInput = true
    }

    private fun isBiometricStrongAvailable(): Boolean =
        biometricAvailableOverrideForTest ?: (
            BiometricManager.from(this)
                .canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            )

    internal fun triggerBiometricPrompt(userInitiated: Boolean = true) {
        if (isBiometricActive) return
        if (lockoutRemainingMs > 0L) {
            showPinInput = true
            return
        }
        val canStrong = isBiometricStrongAvailable()
        if (!canStrong) {
            showPinInput = true
            return
        }

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

        isBiometricActive = true
        showPinInput = false
        errorMessage = null
        biometricPromptTriggerCount += 1

        val testLauncher = biometricLauncherForTest
        if (testLauncher != null) {
            testLauncher(info)
        } else {
            val prompt = biometricPrompt
            if (prompt == null) {
                isBiometricActive = false
                showPinInput = true
                return
            }
            prompt.authenticate(info)
        }
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
        private const val KEY_HAS_AUTO_PROMPTED = "applock_has_auto_prompted"
        private const val KEY_SHOW_PIN_INPUT = "applock_show_pin_input"
        private const val KEY_IS_BIOMETRIC_ACTIVE = "applock_is_biometric_active"

        /** Replaceable in tests to verify BiometricPrompt launch count without system hardware. */
        internal var biometricLauncherForTest: ((BiometricPrompt.PromptInfo) -> Unit)? = null

        /** Replaceable in tests to simulate Class 3 biometric availability deterministically. */
        internal var biometricAvailableOverrideForTest: Boolean? = null

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
