package com.drivelift.app.feature.cleaner.cache

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationCommandOptions
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.ShizukuCommandSpec
import com.drivelift.app.feature.hibernation.engine.ShizukuHibernationCommandBuilder
import com.drivelift.app.feature.hibernation.engine.SleepCommandExecutor

/**
 * Runtime detection state for optional Shizuku silent execution.
 */
enum class ShizukuAvailability {
    READY,
    PERMISSION_REQUIRED,
    NOT_RUNNING,
    NOT_INSTALLED,
    UNSUPPORTED_ANDROID_VERSION,
}

/**
 * Snapshot of Shizuku readiness and the resulting execution mode.
 */
data class ShizukuStatusSnapshot(
    val availability: ShizukuAvailability,
    val effectiveMode: HibernationMode,
    val detail: String,
)

/**
 * Injectable runtime bridge around optional Shizuku binder/process APIs.
 * Keeps the main app free of hard Shizuku compile dependencies and fully testable on the JVM.
 */
interface ShizukuRuntimeBridge {
    fun isShizukuAppInstalled(): Boolean
    fun isBinderAlive(): Boolean
    fun hasPermission(): Boolean
    suspend fun executeCommand(args: List<String>): Result<Unit>
}

/**
 * Default reflection-backed [ShizukuRuntimeBridge] that detects Shizuku safely at runtime.
 * Never crashes when Shizuku is absent, stopped, or restricted on Android 16/17.
 */
class ReflectionShizukuRuntimeBridge(
    private val context: Context,
) : ShizukuRuntimeBridge {

    override fun isShizukuAppInstalled(): Boolean = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(SHIZUKU_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    override fun isBinderAlive(): Boolean = runCatching {
        val clazz = Class.forName(SHIZUKU_CLASS_NAME)
        val method = clazz.getMethod("pingBinder")
        (method.invoke(null) as? Boolean) == true
    }.getOrDefault(false)

    override fun hasPermission(): Boolean = runCatching {
        if (!isBinderAlive()) return false
        val clazz = Class.forName(SHIZUKU_CLASS_NAME)
        val method = clazz.getMethod("checkSelfPermission")
        (method.invoke(null) as? Int) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override suspend fun executeCommand(args: List<String>): Result<Unit> = runCatching {
        require(args.isNotEmpty()) { "Command arguments must not be empty." }
        check(isBinderAlive() && hasPermission()) {
            "Shizuku binder is unavailable or permission is not granted."
        }
        val clazz = Class.forName(SHIZUKU_CLASS_NAME)
        val newProcess = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        newProcess.isAccessible = true
        val process = newProcess.invoke(null, args.toTypedArray(), null, null) as? Process
            ?: error("Shizuku did not return a process handle.")
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("Shizuku command exited with code $exitCode.")
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_CLASS_NAME = "rikka.shizuku.Shizuku"
    }
}

/**
 * Optional Shizuku silent adapter with automatic Android 16/17 Guided Pill fallback.
 *
 * Implements [SleepCommandExecutor] for Phase 2's [com.drivelift.app.feature.hibernation.engine.SleepSessionStateMachine].
 */
class ShizukuSilentAdapter(
    private val bridge: ShizukuRuntimeBridge,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val commandOptions: HibernationCommandOptions = HibernationCommandOptions(),
    private val protectedPackagesProvider: () -> Set<String> = { emptySet() },
) : SleepCommandExecutor {

    /**
     * Evaluates Shizuku runtime readiness without throwing. On Android 17+ (API > 36) or whenever
     * Shizuku is absent, stopped, or ungranted, selects [HibernationMode.GUIDED_PILL].
     */
    fun inspectStatus(): ShizukuStatusSnapshot {
        val sdkInt = sdkIntProvider()
        if (sdkInt > MAX_VERIFIED_SHIZUKU_SDK) {
            return ShizukuStatusSnapshot(
                availability = ShizukuAvailability.UNSUPPORTED_ANDROID_VERSION,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = "Guided Pill mode active on Android API $sdkInt.",
            )
        }

        val installed = runCatching { bridge.isShizukuAppInstalled() }.getOrDefault(false)
        if (!installed) {
            return ShizukuStatusSnapshot(
                availability = ShizukuAvailability.NOT_INSTALLED,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = "Shizuku not installed. Guided Pill fallback is ready.",
            )
        }

        val alive = runCatching { bridge.isBinderAlive() }.getOrDefault(false)
        if (!alive) {
            return ShizukuStatusSnapshot(
                availability = ShizukuAvailability.NOT_RUNNING,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = "Shizuku service is stopped. Guided Pill fallback is ready.",
            )
        }

        val granted = runCatching { bridge.hasPermission() }.getOrDefault(false)
        if (!granted) {
            return ShizukuStatusSnapshot(
                availability = ShizukuAvailability.PERMISSION_REQUIRED,
                effectiveMode = HibernationMode.GUIDED_PILL,
                detail = "Grant Shizuku permission for silent mode, or use Guided Pill.",
            )
        }

        return ShizukuStatusSnapshot(
            availability = ShizukuAvailability.READY,
            effectiveMode = HibernationMode.SHIZUKU_SILENT,
            detail = "Shizuku silent mode is active.",
        )
    }

    override suspend fun sleepApp(candidate: AppDrainCandidate): Result<List<String>> {
        val status = inspectStatus()
        if (status.effectiveMode != HibernationMode.SHIZUKU_SILENT) {
            return Result.failure(IllegalStateException(status.detail))
        }

        val specs: List<ShizukuCommandSpec> = runCatching {
            ShizukuHibernationCommandBuilder.buildSleepSequence(
                packageName = candidate.packageName,
                options = commandOptions,
                protectedPackages = protectedPackagesProvider(),
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }

        val executed = mutableListOf<String>()
        for (spec in specs) {
            val result = bridge.executeCommand(spec.args)
            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull() ?: IllegalStateException("Command failed: ${spec.commandLine}"),
                )
            }
            executed.add(spec.commandLine)
        }
        return Result.success(executed)
    }

    /**
     * Clears an application's cache silently via Shizuku after strict package validation.
     */
    suspend fun clearAppCacheSilent(packageName: String): Result<String> {
        val status = inspectStatus()
        if (status.effectiveMode != HibernationMode.SHIZUKU_SILENT) {
            return Result.failure(IllegalStateException(status.detail))
        }
        val validPkg = runCatching {
            ShizukuHibernationCommandBuilder.validatePackageName(packageName)
        }.getOrElse { return Result.failure(it) }

        val args = listOf("pm", "trim-caches", "999999999999")
        val targetArgs = listOf("cmd", "package", "clear-cache", validPkg)
        val primary = bridge.executeCommand(targetArgs)
        if (primary.isSuccess) {
            return Result.success(targetArgs.joinToString(" "))
        }
        val fallback = bridge.executeCommand(args)
        return if (fallback.isSuccess) {
            Result.success(args.joinToString(" "))
        } else {
            Result.failure(
                primary.exceptionOrNull()
                    ?: fallback.exceptionOrNull()
                    ?: IllegalStateException("Unable to clear cache for $validPkg."),
            )
        }
    }

    companion object {
        /**
         * Highest Android API level verified for Shizuku binder execution.
         * Newer versions (Android 17+ / API 37+) automatically fall back to Guided Pill mode.
         */
        const val MAX_VERIFIED_SHIZUKU_SDK = 36

        /**
         * Builds an Android App Info settings intent for Guided Clean and Guided Sleep steps.
         */
        fun createAppInfoIntent(packageName: String): Intent {
            val safePackage = ShizukuHibernationCommandBuilder.validatePackageName(packageName)
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", safePackage, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
