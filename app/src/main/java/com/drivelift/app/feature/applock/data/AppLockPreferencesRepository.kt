package com.drivelift.app.feature.applock.data

import android.content.Context
import android.content.SharedPreferences
import com.drivelift.app.feature.applock.engine.AppLockConfig
import com.drivelift.app.feature.applock.engine.HashedPinCredential
import com.drivelift.app.feature.applock.engine.PinCredentialHasher
import com.drivelift.app.feature.applock.engine.ReLockGracePeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-private preference store for App Lock configuration and salted PBKDF2-HMAC-SHA256
 * PIN credentials. Plaintext PINs are never persisted, and RAM-only pause state is never
 * stored here.
 */
class AppLockPreferencesRepository(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<AppLockConfig> = _config.asStateFlow()

    private val _hasPinConfigured = MutableStateFlow(readPinCredential() != null)
    val hasPinConfigured: StateFlow<Boolean> = _hasPinConfigured.asStateFlow()

    private val _pinLength = MutableStateFlow(readPinLength())
    val pinLength: StateFlow<Int> = _pinLength.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _config.value = readConfig()
    }

    fun setLockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_LOCKED_PACKAGES, packages.toSet()).apply()
        _config.value = readConfig()
    }

    fun toggleLockedPackage(packageName: String) {
        val current = _config.value.lockedPackages
        val updated = if (packageName in current) current - packageName else current + packageName
        setLockedPackages(updated)
    }

    fun setGracePeriod(gracePeriod: ReLockGracePeriod) {
        prefs.edit().putString(KEY_GRACE_PERIOD, gracePeriod.name).apply()
        _config.value = readConfig()
    }

    fun setAllowBiometric(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_BIOMETRIC, allow).apply()
        _config.value = readConfig()
    }

    fun setTamperProtectionEnabled(enabled: Boolean) {
        val currentPackages = _config.value.lockedPackages
        val nextPackages = if (enabled) {
            currentPackages + AppLockConfig.SETTINGS_PACKAGE_NAME
        } else {
            currentPackages - AppLockConfig.SETTINGS_PACKAGE_NAME
        }
        prefs.edit()
            .putBoolean(KEY_TAMPER_PROTECTION, enabled)
            .putStringSet(KEY_LOCKED_PACKAGES, nextPackages)
            .apply()
        _config.value = readConfig()
    }

    fun savePinCredential(credential: HashedPinCredential, pinLength: Int) {
        prefs.edit()
            .putString(KEY_PIN_SALT, credential.saltBase64)
            .putString(KEY_PIN_HASH, credential.hashBase64)
            .putInt(KEY_PIN_ITERATIONS, credential.iterations)
            .putInt(KEY_PIN_LENGTH, pinLength.coerceIn(MIN_PIN_LENGTH, MAX_PIN_LENGTH))
            .apply()
        _hasPinConfigured.value = true
        _pinLength.value = readPinLength()
    }

    fun readPinCredential(): HashedPinCredential? {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return null
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return null
        val iterations = prefs.getInt(KEY_PIN_ITERATIONS, PinCredentialHasher.DEFAULT_ITERATIONS)
        if (salt.isBlank() || hash.isBlank() || iterations <= 0) return null
        return HashedPinCredential(
            saltBase64 = salt,
            hashBase64 = hash,
            iterations = iterations,
        )
    }

    private fun readPinLength(): Int =
        prefs.getInt(KEY_PIN_LENGTH, DEFAULT_PIN_LENGTH).coerceIn(MIN_PIN_LENGTH, MAX_PIN_LENGTH)

    private fun readConfig(): AppLockConfig {
        val graceName = prefs.getString(KEY_GRACE_PERIOD, ReLockGracePeriod.IMMEDIATE.name)
        val grace = ReLockGracePeriod.entries.firstOrNull { it.name == graceName }
            ?: ReLockGracePeriod.IMMEDIATE
        val locked = prefs.getStringSet(KEY_LOCKED_PACKAGES, emptySet())
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        return AppLockConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            lockedPackages = locked,
            gracePeriod = grace,
            allowBiometric = prefs.getBoolean(KEY_ALLOW_BIOMETRIC, true),
            tamperProtectionEnabled = prefs.getBoolean(KEY_TAMPER_PROTECTION, false),
        )
    }

    companion object {
        const val DEFAULT_PREFS_NAME = "drivelift_app_lock_prefs"
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 6
        const val DEFAULT_PIN_LENGTH = 6

        private const val KEY_ENABLED = "enabled"
        private const val KEY_LOCKED_PACKAGES = "locked_packages"
        private const val KEY_GRACE_PERIOD = "grace_period"
        private const val KEY_ALLOW_BIOMETRIC = "allow_biometric"
        private const val KEY_TAMPER_PROTECTION = "tamper_protection_enabled"
        private const val KEY_PIN_SALT = "pin_salt_b64"
        private const val KEY_PIN_HASH = "pin_hash_b64"
        private const val KEY_PIN_ITERATIONS = "pin_iterations"
        private const val KEY_PIN_LENGTH = "pin_length"
    }
}
