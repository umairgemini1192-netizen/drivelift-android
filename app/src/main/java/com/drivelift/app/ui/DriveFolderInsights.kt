package com.drivelift.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.drivelift.app.data.DRIVE_SCAN_MAX_ITEMS
import com.drivelift.app.data.DriveFolderTotal
import com.drivelift.app.data.DriveFolderTree
import com.drivelift.app.data.ScanStop

/*
 * Storage insights: Google Drive folder details (issue #42). File counts and sizes per My Drive
 * folder, counted on request from a read-only listing. The last count is saved on the phone and
 * shown at the next launch, then refreshed in the background when Storage opens (issue #14). Kept
 * apart from the account quota, which Drive, Gmail and Google Photos share.
 */

/** Said under every count: what the folder figures are, and what they are not. */
internal const val DRIVE_FOLDERS_SCOPE =
    "These are file sizes in My Drive folders, not the account quota below, which Drive, Gmail and " +
        "Google Photos share. Shared drives are not counted."

internal const val DRIVE_FOLDERS_SUBFOLDERS = "Totals include every file in subfolders. Each file is counted once."

private fun num(n: Int): String = "%,d".format(n)

private fun files(n: Int): String = if (n == 1) "1 file" else "${num(n)} files"

private fun folders(n: Int): String = if (n == 1) "1 folder" else "${num(n)} folders"

/** Why a count is incomplete, as one sentence. */
internal fun incompleteReason(stop: ScanStop, items: Int): String = when (stop) {
    ScanStop.ByUser -> "Incomplete: you stopped the count after ${num(items)} items."
    ScanStop.TooMany ->
        "Incomplete: the count stops at ${num(DRIVE_SCAN_MAX_ITEMS)} items to stay within the phone's memory."
    ScanStop.AccountChanged -> "Incomplete: the connected account changed during the count."
    is ScanStop.Error -> "Incomplete: the count stopped after ${num(items)} items. ${stop.message}"
} + " Some folders may be missing files, so the real totals can be higher."

/** "at 14:05" on the day of the count, "on 19 Nov 2026 at 14:05" on a later day. */
internal fun countedWhen(at: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (day == today) {
        "at ${formatClock(at)}"
    } else {
        "on ${day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))} at ${formatClock(at)}"
    }
}

/** Why a recount left the earlier figures in place, as one or two sentences. */
internal fun refreshMissReason(miss: AppViewModel.DriveFolderScan.RefreshMiss): String = when (val stop = miss.stop) {
    ScanStop.ByUser -> "You stopped the recount, so these figures are from the earlier count."
    ScanStop.TooMany ->
        "The recount stopped at ${num(DRIVE_SCAN_MAX_ITEMS)} items, so these figures are from the earlier count."
    ScanStop.AccountChanged -> "The connected account changed during the recount."
    is ScanStop.Error -> "The recount did not finish. ${stop.message} These figures are from the earlier count."
}

/**
 * The lead panel of the Drive folders section: the state of the count and what it covers. The
 * last count is saved on the phone, so its figures show at launch, before Drive connects, and stay
 * on screen while a recount runs.
 */
@Composable
internal fun DriveFoldersSummary(
    scan: AppViewModel.DriveFolderScan,
    conn: DriveConn,
    openId: String?,
    now: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnect: () -> Unit,
) {
    val shown = scan as? AppViewModel.DriveFolderScan.Done ?: (scan as? AppViewModel.DriveFolderScan.Running)?.previous
    GlassPanel(Modifier.fillMaxWidth().testTag("drive-folders")) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
            if (conn != DriveConn.CONNECTED) {
                Text(
                    if (shown != null) {
                        "Google Drive is not connected. These are the figures saved from the last count."
                    } else {
                        "Google Drive is not connected, so its folders are not counted."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (conn == DriveConn.CONNECTING || conn == DriveConn.UNKNOWN) {
                    CircularProgressIndicator(Modifier.padding(top = Tokens.Small).size(Tokens.Icon), strokeWidth = Tokens.Stroke)
                } else {
                    Button(onClick = onConnect, modifier = Modifier.padding(top = Tokens.Small)) { Text("Connect") }
                }
                if (shown == null) return@Column
                Spacer(Modifier.height(Tokens.Medium))
            }
            if (scan is AppViewModel.DriveFolderScan.Running && scan.previous != null) {
                RecountProgress(scan, onStop)
                DriveCountFigures(scan.previous, openId, now, onStart = null, onConnect = onConnect)
                return@Column
            }
            when (scan) {
                AppViewModel.DriveFolderScan.Idle -> {
                    Text(
                        "Counts the files in each My Drive folder and adds up their sizes. A large Drive can take " +
                            "a few minutes. Read only: nothing on Drive is changed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onStart, modifier = Modifier.padding(top = Tokens.Medium)) { Text("Count Drive folders") }
                }
                is AppViewModel.DriveFolderScan.Running -> {
                    Text("Counting My Drive: ${num(scan.items)} items read", style = MaterialTheme.typography.bodyLarge)
                    // The total is not known until the last page, so the bar shows activity, not a share.
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Small).height(Tokens.ProgressHeight),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = GlassPalette.Dim,
                    )
                    Text(
                        "${if (scan.pages == 1) "1 page" else "${num(scan.pages)} pages"} of up to 1,000 items read. " +
                            "Nothing on Drive is changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onStop, enabled = !scan.stopping, modifier = Modifier.padding(top = Tokens.Small)) {
                        Text(if (scan.stopping) "Stopping after this page" else "Stop")
                    }
                }
                is AppViewModel.DriveFolderScan.Failed -> {
                    Text("Drive folders could not be counted.", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        scan.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Tiny),
                    )
                    if (scan.needsSignIn) {
                        Button(onClick = onConnect, modifier = Modifier.padding(top = Tokens.Medium)) { Text("Reconnect") }
                        // Signed in again some other way: counting can still be started from here.
                        TextButton(onClick = onStart, modifier = Modifier.padding(top = Tokens.Tiny)) { Text("Try again") }
                    } else {
                        Button(onClick = onStart, modifier = Modifier.padding(top = Tokens.Medium)) { Text("Try again") }
                    }
                }
                is AppViewModel.DriveFolderScan.Done -> DriveCountFigures(
                    scan, openId, now,
                    onStart = onStart.takeIf { conn == DriveConn.CONNECTED },
                    onConnect = onConnect,
                )
            }
        }
    }
}

/** A recount running behind the figures of the earlier count. */
@Composable
private fun RecountProgress(scan: AppViewModel.DriveFolderScan.Running, onStop: () -> Unit) {
    Column(Modifier.testTag("drive-folders-recount")) {
        Text(
            "Counting again in the background: ${num(scan.items)} items read. The figures below stay until it finishes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Small).height(Tokens.ProgressHeight),
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = GlassPalette.Dim,
        )
        OutlinedButton(onClick = onStop, enabled = !scan.stopping) {
            Text(if (scan.stopping) "Stopping after this page" else "Stop")
        }
        Spacer(Modifier.height(Tokens.Medium))
    }
}

/** The figures of one count: the open folder, its totals, what they leave out, and when. */
@Composable
private fun DriveCountFigures(
    scan: AppViewModel.DriveFolderScan.Done,
    openId: String?,
    now: Long,
    /** Null while a recount runs or Drive is not connected: no new count can start then. */
    onStart: (() -> Unit)?,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val tree = scan.tree
    val current = openId?.let { tree.folders[it] } ?: tree.root
    val path = tree.pathTo(current.id)
    scan.refreshMiss?.let { miss ->
        Text(
            refreshMissReason(miss),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = Tokens.Small).testTag("drive-folders-refresh-miss"),
        )
        if ((miss.stop as? ScanStop.Error)?.needsSignIn == true) {
            Button(onClick = onConnect, modifier = Modifier.padding(bottom = Tokens.Small)) { Text("Reconnect") }
        }
    }
    Text(
        path.joinToString(" / ") { it.name },
        style = MaterialTheme.typography.titleSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        "${formatBytes(context, current.bytes)} in ${files(current.fileCount)}" +
            if (current.folderCount > 0) ", ${folders(current.folderCount)}" else "",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        DRIVE_FOLDERS_SUBFOLDERS,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    scan.stop?.let { stop ->
        Text(
            incompleteReason(stop, scan.items),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Tokens.Small).testTag("drive-folders-incomplete"),
        )
    }
    driveFolderNotes(context, current, if (current.id == tree.rootId) tree.outsideCount else 0).forEach { note ->
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Small),
        )
    }
    Text(
        // An incomplete count is not called counted: it read only part of the Drive.
        "${if (scan.complete) "Counted" else "Partly counted"} ${countedWhen(scan.at, now)} " +
            "for ${scan.account}. $DRIVE_FOLDERS_SCOPE",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.Small),
    )
    if (onStart != null) {
        TextButton(onClick = onStart, modifier = Modifier.padding(top = Tokens.Tiny)) { Text("Count again") }
    }
}

/**
 * What the figures of [folder] leave out or treat specially: files with no size, shortcuts, bytes
 * other people own, and (for My Drive itself) items shared with the user that are not in it.
 */
internal fun driveFolderNotes(context: android.content.Context, folder: DriveFolderTotal, outside: Int): List<String> = buildList {
    if (folder.unsizedCount > 0) add(
        (if (folder.unsizedCount == 1) "1 file reports" else "${num(folder.unsizedCount)} files report") +
            " no size: Google Docs, Sheets, Slides and similar files. They are counted, but not in the sizes.",
    )
    if (folder.shortcutCount > 0) add(
        (if (folder.shortcutCount == 1) "1 shortcut is" else "${num(folder.shortcutCount)} shortcuts are") +
            " counted as a link. The files they point to are not counted again.",
    )
    if (folder.othersBytes > 0) add(
        "${formatBytes(context, folder.othersBytes)} of this is in files other people own, which do not use your quota.",
    )
    if (outside > 0) add(
        (if (outside == 1) "1 item" else "${num(outside)} items") +
            " shared with you but not added to My Drive " + (if (outside == 1) "is" else "are") + " not counted.",
    )
}

/** The folders inside the open Drive folder, largest first, with a way to list more. */
@Composable
internal fun DriveFolderList(
    tree: DriveFolderTree,
    openId: String?,
    shown: Int,
    onOpen: (String?) -> Unit,
    onMore: () -> Unit,
) {
    val context = LocalContext.current
    val current = openId?.let { tree.folders[it] } ?: tree.root
    val children = tree.childrenOf(current.id)
    val path = tree.pathTo(current.id)
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(start = Tokens.Edge, end = Tokens.Edge, top = Tokens.Small)
            .testTag("drive-folder-list"),
    ) {
        Column(Modifier.padding(vertical = Tokens.Small)) {
            // Back sits on the list itself, so it stays in reach while the list is on screen.
            if (path.size > 1) {
                val parent = path[path.size - 2]
                TextButton(
                    onClick = { onOpen(if (parent.id == tree.rootId) null else parent.id) },
                    modifier = Modifier.padding(horizontal = Tokens.Small),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(Tokens.Small))
                    Text("Back to ${parent.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Inside ${current.name}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Tiny),
                )
            }
            if (children.isEmpty()) {
                EmptyLine("No folders inside ${current.name}.")
            }
            children.take(shown).forEach { folder -> DriveFolderRow(folder, onOpen = { onOpen(folder.id) }) }
            if (current.directFileCount > 0) {
                LabelValueRow(
                    title = "Files directly in ${current.name}",
                    detail = "${files(current.directFileCount)}, not in a subfolder",
                    value = formatBytes(context, current.directBytes),
                )
            }
            val more = children.size - shown
            if (more > 0) {
                TextButton(
                    onClick = onMore,
                    modifier = Modifier.padding(horizontal = Tokens.Small).testTag("drive-folders-more"),
                ) {
                    Text(
                        when {
                            more == 1 -> "Show 1 more folder"
                            more <= AppViewModel.DRIVE_FOLDERS_MORE -> "Show ${num(more)} more folders"
                            else -> "Show ${AppViewModel.DRIVE_FOLDERS_MORE} more of ${num(more)} folders"
                        },
                    )
                }
            }
        }
    }
}

/** One folder: name, file count and size, including subfolders. A tap lists its own folders. */
@Composable
private fun DriveFolderRow(folder: DriveFolderTotal, onOpen: () -> Unit) {
    val context = LocalContext.current
    val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    val detail = buildString {
        append(files(folder.fileCount))
        if (folder.folderCount > 0) append(", ").append(if (folder.folderCount == 1) "1 subfolder" else "${num(folder.folderCount)} subfolders")
        if (folder.unsizedCount > 0) append(", ").append("${num(folder.unsizedCount)} without a size")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.TouchTarget)
            .clickable(onClickLabel = "Show what is inside ${folder.name}", onClick = onOpen)
            .padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Small, bottom = Tokens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.Icon),
        )
        Column(Modifier.weight(1f).padding(start = Tokens.Medium)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stacked) Text(formatBytes(context, folder.bytes), style = MaterialTheme.typography.bodyLarge)
        }
        if (!stacked) {
            Spacer(Modifier.width(Tokens.Medium))
            Text(formatBytes(context, folder.bytes), style = MaterialTheme.typography.bodyLarge)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.Icon),
        )
    }
}
