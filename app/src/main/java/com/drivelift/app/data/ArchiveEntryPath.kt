package com.drivelift.app.data

/**
 * Checks the path of one entry inside an archive (ZIP, and later TAR, 7z, RAR) before anything is
 * written to storage. Part of the scoped-storage/SAF design (`docs/design/scoped-storage-saf.md`,
 * issue #42). Pure Kotlin with no Android types, so every rule is covered by plain unit tests.
 *
 * The entry name is untrusted. It is accepted only when every component is an ordinary name that
 * stays inside the extraction root. Anything else is refused with a reason, and the caller then
 * skips or fails the whole job; it never "fixes" the name silently.
 */
object ArchiveEntryPath {
    /** Longest entry path, in UTF-8 bytes, that the job accepts. */
    const val MAX_PATH_BYTES = 1024

    /** Longest single path component, in UTF-8 bytes (the usual file-system limit). */
    const val MAX_COMPONENT_BYTES = MAX_NAME_BYTES

    /** The result of checking one entry: its safe components, or the reason it was refused. */
    sealed interface Result {
        data class Accepted(val components: List<String>) : Result
        data class Refused(val reason: String) : Result
    }

    /**
     * Checks [raw], the entry name exactly as the archive stores it. Separators are "/" in archives;
     * a backslash is refused rather than treated as a separator, because Windows-made archives can
     * use it to hide a parent-directory step. A trailing "/" marks a directory and is allowed.
     */
    fun check(raw: String): Result {
        if (raw.isEmpty()) return Result.Refused("The entry has no name.")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) {
            return Result.Refused("The entry path is longer than $MAX_PATH_BYTES bytes.")
        }
        if (raw.any { it == '\u0000' }) return Result.Refused("The entry name contains a NUL character.")
        if (raw.any { it.isISOControl() }) return Result.Refused("The entry name contains a control character.")
        if (raw.contains('\\')) return Result.Refused("The entry name contains a backslash.")
        if (raw.startsWith("/")) return Result.Refused("The entry path is absolute.")
        if (raw.length >= 2 && raw[1] == ':') return Result.Refused("The entry path starts with a drive letter.")

        val trimmed = raw.removeSuffix("/")
        val parts = trimmed.split('/')
        val safe = ArrayList<String>(parts.size)
        for (part in parts) {
            when {
                part.isEmpty() -> return Result.Refused("The entry path has an empty component.")
                part == "." -> return Result.Refused("The entry path has a \".\" component.")
                part == ".." -> return Result.Refused("The entry path climbs out of its folder with \"..\".")
                part.toByteArray(Charsets.UTF_8).size > MAX_COMPONENT_BYTES ->
                    return Result.Refused("A name in the entry path is longer than $MAX_COMPONENT_BYTES bytes.")
                part.endsWith(" ") || part.endsWith(".") ->
                    return Result.Refused("A name in the entry path ends with a space or dot, which storage cannot keep.")
            }
            safe += part
        }
        return Result.Accepted(safe)
    }
}

/**
 * Limits for one archive job, checked while the archive is read as a stream (never from the
 * size the archive header claims). A job that reaches a limit stops and reports it; it does not
 * continue with a partial result that looks complete.
 */
data class ArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxDepth: Int = 32,
    val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
) {
    /** Why a count, depth or byte total breaks a limit, or null when it does not. */
    fun violation(entries: Int, depth: Int, totalBytes: Long): String? = when {
        entries > maxEntries -> "The archive has more than $maxEntries entries."
        depth > maxDepth -> "The archive nests deeper than $maxDepth folders."
        totalBytes > maxTotalBytes -> "The archive expands to more than ${maxTotalBytes / (1024 * 1024)} MB."
        else -> null
    }
}
