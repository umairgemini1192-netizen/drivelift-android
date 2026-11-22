package com.drivelift.app.feature.hibernation.engine

/**
 * Options controlling which Shizuku hibernation commands are emitted for a target package.
 */
data class HibernationCommandOptions(
    val restrictBackgroundAppOp: Boolean = true,
    val setInactive: Boolean = true,
    val forceStop: Boolean = true,
    val suspendPackage: Boolean = false,
)

/**
 * Validated, argument-tokenized command ready for Shizuku binder/process execution without a shell.
 */
data class ShizukuCommandSpec(
    val args: List<String>,
    val description: String,
) {
    val commandLine: String
        get() = args.joinToString(" ")
}

/**
 * Builds safe, validated command sequences for Shizuku-backed app hibernation and recovery.
 */
object ShizukuHibernationCommandBuilder {

    private const val MAX_PACKAGE_LENGTH = 255

    // Strict Android applicationId / packageName grammar: at least two dot-separated segments,
    // each starting with an ASCII letter and containing only ASCII letters, digits, or underscores.
    private val PACKAGE_NAME_REGEX =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /**
     * Validates [packageName] against strict Android package naming rules and returns the
     * trimmed package name, or throws [IllegalArgumentException] if invalid or unsafe.
     */
    fun validatePackageName(packageName: String): String {
        val trimmed = packageName.trim()
        require(trimmed.isNotEmpty()) { "Package name must not be blank." }
        require(trimmed.length <= MAX_PACKAGE_LENGTH) {
            "Package name exceeds $MAX_PACKAGE_LENGTH characters."
        }
        require(PACKAGE_NAME_REGEX.matches(trimmed)) {
            "Invalid Android package name: '$trimmed'."
        }
        return trimmed
    }

    /**
     * Returns true if [packageName] passes strict validation.
     */
    fun isValidPackageName(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return trimmed.isNotEmpty() &&
            trimmed.length <= MAX_PACKAGE_LENGTH &&
            PACKAGE_NAME_REGEX.matches(trimmed)
    }

    /**
     * Builds the ordered sleep command sequence for [packageName].
     *
     * Rejects invalid package names and any package in [protectedPackages] or
     * [BackgroundDrainerScorer.ALWAYS_PROTECTED_PACKAGES].
     */
    fun buildSleepSequence(
        packageName: String,
        options: HibernationCommandOptions = HibernationCommandOptions(),
        protectedPackages: Set<String> = emptySet(),
    ): List<ShizukuCommandSpec> {
        val pkg = validatePackageName(packageName)
        require(pkg !in BackgroundDrainerScorer.ALWAYS_PROTECTED_PACKAGES) {
            "Refusing to build hibernation commands for protected system/self package: $pkg"
        }
        require(pkg !in protectedPackages) {
            "Refusing to build hibernation commands for whitelisted package: $pkg"
        }

        return buildList {
            if (options.restrictBackgroundAppOp) {
                add(
                    ShizukuCommandSpec(
                        args = listOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "ignore"),
                        description = "Restrict background execution via AppOps",
                    ),
                )
            }
            if (options.setInactive) {
                add(
                    ShizukuCommandSpec(
                        args = listOf("am", "set-inactive", pkg, "true"),
                        description = "Mark app inactive in ActivityManager",
                    ),
                )
            }
            if (options.forceStop) {
                add(
                    ShizukuCommandSpec(
                        args = listOf("am", "force-stop", pkg),
                        description = "Force-stop background processes",
                    ),
                )
            }
            if (options.suspendPackage) {
                add(
                    ShizukuCommandSpec(
                        args = listOf("pm", "suspend", pkg),
                        description = "Suspend package until unsuspended",
                    ),
                )
            }
        }
    }

    /**
     * Builds the command sequence to wake/restore [packageName] from hibernation or suspension.
     */
    fun buildWakeSequence(
        packageName: String,
        unsuspend: Boolean = true,
        restoreAppOp: Boolean = true,
        clearInactive: Boolean = true,
    ): List<ShizukuCommandSpec> {
        val pkg = validatePackageName(packageName)
        return buildList {
            if (unsuspend) {
                add(
                    ShizukuCommandSpec(
                        args = listOf("pm", "unsuspend", pkg),
                        description = "Unsuspend package",
                    ),
                )
            }
            if (restoreAppOp) {
                add(
                    ShizukuCommandSpec(
                        args = listOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
                        description = "Restore background execution AppOp",
                    ),
                )
            }
            if (clearInactive) {
                add(
                    ShizukuCommandSpec(
                        args = listOf("am", "set-inactive", pkg, "false"),
                        description = "Clear inactive state in ActivityManager",
                    ),
                )
            }
        }
    }
}
