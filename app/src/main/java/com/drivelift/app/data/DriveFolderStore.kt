package com.drivelift.app.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * Storage insights: the last Google Drive folder count, kept on the phone so the Storage screen
 * shows it at the next launch without counting again (issue #14, owner request of 20 Nov 2026).
 *
 * Only folder totals are kept: folder ids, folder names, counts and sizes. File names are never
 * stored. The file lives in the app's private no-backup folder, so it is not copied into a cloud
 * backup, and it belongs to one account: the app deletes it when the connected account changes.
 */

/** A finished Drive folder count as saved on the phone. */
data class SavedDriveCount(
    val account: String,
    val tree: DriveFolderTree,
    val items: Int,
    val at: Long,
    /** Null for a complete count; otherwise why it stopped early. Never [ScanStop.AccountChanged]. */
    val stop: ScanStop?,
)

/**
 * Reads and writes [SavedDriveCount] as a small versioned text file. A missing, damaged, cut-short
 * or older file reads as nothing saved, never as wrong figures. A write goes to a temporary file
 * first and then replaces the saved one, so a crash mid-write leaves the previous count intact.
 */
class DriveFolderStore(private val file: File) {
    private val lock = Any()
    private val temp get() = File(file.path + ".tmp")

    fun save(count: SavedDriveCount): Unit = synchronized(lock) {
        require(count.stop != ScanStop.AccountChanged) { "a count for a changed account is never saved" }
        file.parentFile?.mkdirs()
        temp.bufferedWriter().use { write(count, it) }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The saved count, or null when there is none or it cannot be read. */
    fun load(): SavedDriveCount? = synchronized(lock) {
        if (!file.isFile) return null
        try {
            file.bufferedReader().useLines { read(it.iterator()) }
        } catch (e: Exception) {
            null
        }
    }

    fun clear(): Unit = synchronized(lock) {
        file.delete()
        temp.delete()
    }

    companion object {
        private const val HEADER = "drivelift-drive-folders"
        private const val VERSION = 1

        /** Escapes the field and list separators, so any folder name or id round-trips. */
        internal fun esc(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                ',' -> append("%2C")
                else -> append(c)
            }
        }

        internal fun unesc(s: String): String {
            if ('%' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '%') {
                    require(i + 3 <= s.length) { "cut-short escape" }
                    out.append(s.substring(i + 1, i + 3).toInt(16).toChar())
                    i += 3
                } else {
                    out.append(s[i])
                    i++
                }
            }
            return out.toString()
        }

        private fun write(count: SavedDriveCount, out: java.io.Writer) {
            fun line(vararg fields: Any) {
                out.write(fields.joinToString("\t"))
                out.write("\n")
            }
            line(HEADER, VERSION)
            line("account", esc(count.account))
            line("at", count.at)
            line("items", count.items)
            when (val stop = count.stop) {
                null -> line("stop", "none")
                ScanStop.ByUser -> line("stop", "user")
                ScanStop.TooMany -> line("stop", "toomany")
                ScanStop.AccountChanged -> error("not saved")
                is ScanStop.Error -> line("stop", "error", stop.needsSignIn, esc(stop.message))
            }
            line("root", esc(count.tree.rootId))
            line("outside", count.tree.outsideCount)
            for (f in count.tree.folders.values) {
                line(
                    "F", esc(f.id), esc(f.name), f.fileCount, f.bytes, f.unsizedCount, f.shortcutCount, f.othersBytes,
                    f.folderCount, f.directFileCount, f.directBytes, f.children.joinToString(",") { esc(it) },
                )
            }
            line("end", count.tree.folders.size)
        }

        private fun read(lines: Iterator<String>): SavedDriveCount? {
            if (!lines.hasNext()) return null
            val head = lines.next().split('\t')
            if (head.size != 2 || head[0] != HEADER || head[1].toIntOrNull() != VERSION) return null
            val values = HashMap<String, List<String>>()
            val folders = LinkedHashMap<String, DriveFolderTotal>()
            var end: Int? = null
            for (raw in lines) {
                if (raw.isEmpty()) continue
                val f = raw.split('\t')
                when (f[0]) {
                    "F" -> {
                        require(f.size == 12) { "bad folder line" }
                        val id = unesc(f[1])
                        folders[id] = DriveFolderTotal(
                            id = id,
                            name = unesc(f[2]),
                            fileCount = f[3].toInt(),
                            bytes = f[4].toLong(),
                            unsizedCount = f[5].toInt(),
                            shortcutCount = f[6].toInt(),
                            othersBytes = f[7].toLong(),
                            folderCount = f[8].toInt(),
                            directFileCount = f[9].toInt(),
                            directBytes = f[10].toLong(),
                            children = if (f[11].isEmpty()) emptyList() else f[11].split(',').map(::unesc),
                        )
                    }
                    "end" -> end = f[1].toInt()
                    else -> values[f[0]] = f.drop(1)
                }
            }
            // A file cut short (no end line, or fewer folders than it says) is not shown.
            if (end == null || end != folders.size) return null
            val rootId = unesc(values.getValue("root")[0])
            if (rootId !in folders) return null
            val stopFields = values.getValue("stop")
            val stop = when (stopFields[0]) {
                "none" -> null
                "user" -> ScanStop.ByUser
                "toomany" -> ScanStop.TooMany
                "error" -> ScanStop.Error(unesc(stopFields[2]), stopFields[1].toBooleanStrict())
                else -> return null
            }
            return SavedDriveCount(
                account = unesc(values.getValue("account")[0]),
                tree = DriveFolderTree(rootId, folders, values.getValue("outside")[0].toInt()),
                items = values.getValue("items")[0].toInt(),
                at = values.getValue("at")[0].toLong(),
                stop = stop,
            )
        }
    }
}
