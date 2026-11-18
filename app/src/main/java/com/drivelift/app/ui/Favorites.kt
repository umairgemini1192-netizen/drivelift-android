package com.drivelift.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.drivelift.app.data.FavoriteKind
import com.drivelift.app.data.FavoriteRow
import com.drivelift.app.data.FavoriteState
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.drivePreviewTypeLabel
import kotlinx.coroutines.launch

/**
 * The star that adds an item to favorites or takes it out. Filled in the accent colour while the
 * item is a favorite. The touch target is [Tokens.TouchTarget].
 */
@Composable
fun FavoriteStar(favorite: Boolean, name: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier.size(Tokens.TouchTarget)) {
        Icon(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (favorite) "Remove $name from favorites" else "Add $name to favorites",
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The way into Favorites from the Phone and Drive tabs. Shown only once something is starred. */
@Composable
fun FavoritesEntryRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier.fillMaxWidth(), onClick = onClick, onClickLabel = "Open favorites") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = Tokens.Medium)) {
                Text("Favorites", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (count == 1) "1 folder or file" else "$count folders and files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Every favorite, phone first, then Google Drive. A folder opens in its tab; a photo, video or
 * Drive file opens in the viewer here. An item that cannot be found or opened stays listed with
 * the reason and a Remove action, so nothing disappears without the user seeing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenPhoneFolder: (Long) -> Unit,
    onOpenDriveFolder: (DriveFile) -> Unit,
) {
    val context = LocalContext.current
    val loadedRows by vm.favoriteRows.collectAsStateWithLifecycle()
    val rows = loadedRows.orEmpty()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val connectedAccount by vm.connectedAccountHash.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The phone file or Drive file open in the viewer, kept across rotation.
    var viewingMediaId by rememberSaveable { mutableLongStateOf(NONE) }
    var viewingDrive by rememberSaveable(stateSaver = OpenDriveFileSaver) { mutableStateOf<DriveFile?>(null) }
    // Read from the phone listing, not from the favorites, so taking the star off in the viewer
    // does not close it.
    val media by vm.media.collectAsStateWithLifecycle()
    val viewingMedia = remember(viewingMediaId, media) { media.firstOrNull { it.id == viewingMediaId } }
    val viewing = viewingMedia != null || viewingDrive != null

    // Drive favorites of the connected account are looked up when the view opens or the account
    // changes. Favorites of other accounts are never looked up.
    val driveCount = remember(favorites) { favorites.count { !it.onPhone } }
    LaunchedEffect(connectedAccount, driveCount) { vm.checkDriveFavorites() }

    BackHandler(enabled = !viewing, onBack = onBack)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (viewing) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
        ) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    Column {
                        Text("Favorites", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (loadedRows != null) Text(
                            if (rows.size == 1) "1 folder or file" else "${rows.size} folders and files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
            if (loadedRows == null) {
                Box(Modifier.fillMaxWidth().padding(Tokens.EmptyState), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (rows.isEmpty()) {
                Text(
                    "No favorites yet. Tap the star on a folder, or in the viewer of a photo, video or " +
                        "Drive file, to keep it here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Tokens.Edge),
                )
            } else {
                val phone = rows.filter { it.favorite.onPhone }
                val drive = rows.filterNot { it.favorite.onPhone }
                LazyColumn(Modifier.fillMaxSize().testTag("favorites-list")) {
                    if (phone.isNotEmpty()) {
                        item(key = "phone-heading") { SectionHeading("On this phone") }
                        items(phone, key = { it.favorite.id }) { row ->
                            FavoriteRowItem(
                                row = row,
                                onOpen = {
                                    when (val state = row.state) {
                                        is FavoriteState.PhoneFolder -> onOpenPhoneFolder(state.folder.bucketId)
                                        is FavoriteState.PhoneMedia -> viewingMediaId = state.item.id
                                        else -> Unit
                                    }
                                },
                                onRemove = { vm.removeFavorite(row.favorite.id) },
                                onRetry = vm::checkDriveFavorites,
                            )
                        }
                    }
                    if (drive.isNotEmpty()) {
                        item(key = "drive-heading") { SectionHeading("Google Drive") }
                        items(drive, key = { it.favorite.id }) { row ->
                            FavoriteRowItem(
                                row = row,
                                onOpen = {
                                    val file = (row.state as? FavoriteState.DriveItem)?.file
                                    when {
                                        file == null -> Unit
                                        file.isFolder -> onOpenDriveFolder(file)
                                        else -> viewingDrive = file
                                    }
                                },
                                onRemove = { vm.removeFavorite(row.favorite.id) },
                                onRetry = vm::checkDriveFavorites,
                            )
                        }
                    }
                }
            }
        }
        viewingMedia?.let { item ->
            FullScreenOverlay {
                LocalMediaViewer(vm = vm, item = item, onClose = { viewingMediaId = NONE })
            }
        }
        viewingDrive?.let { file ->
            FullScreenOverlay {
                DriveFileViewer(
                    vm = vm,
                    file = file,
                    cachedThumb = null,
                    onClose = { viewingDrive = null },
                    onOpenExternally = {
                        openInDrive(context, file.id)?.let { scope.launch { vm.messages.emit(it) } }
                    },
                )
            }
        }
    }
}

private const val NONE = -1L

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = Tokens.Edge, end = Tokens.Edge, top = Tokens.Gutter, bottom = Tokens.Tiny),
    )
}

/**
 * One favorite. Available: the whole row opens it and the star takes it out of favorites.
 * Checking: a small spinner. Unavailable: the reason in full, with Remove (and Try again when the
 * check itself failed) on their own line, so they never squeeze the text.
 */
@Composable
private fun FavoriteRowItem(row: FavoriteRow, onOpen: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val favorite = row.favorite
    val state = row.state
    val available = state is FavoriteState.PhoneFolder || state is FavoriteState.PhoneMedia || state is FavoriteState.DriveItem
    val kind = favorite.favoriteKind
    val title = when (state) {
        is FavoriteState.PhoneFolder -> state.folder.name
        is FavoriteState.PhoneMedia -> state.item.name
        is FavoriteState.DriveItem -> state.file.name
        else -> favorite.name
    }
    val detail = when (state) {
        is FavoriteState.PhoneFolder -> "Folder  ·  ${state.folder.count} items  ·  ${formatBytes(context, state.folder.bytes)}"
        is FavoriteState.PhoneMedia -> listOf(
            if (state.item.isVideo) "Video" else "Photo",
            formatBytes(context, state.item.size),
            state.item.relativePath.trimEnd('/'),
        ).filter { it.isNotEmpty() }.joinToString("  ·  ")
        is FavoriteState.DriveItem -> if (state.file.isFolder) {
            listOf("Drive folder", favorite.location).filter { it.isNotEmpty() }.joinToString("  ·  ")
        } else {
            listOf(drivePreviewTypeLabel(state.file), formatBytes(context, state.file.size), favorite.location)
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
        }
        FavoriteState.Checking -> "Checking where it is now"
        is FavoriteState.Unavailable -> state.reason
    }
    val icon: ImageVector = when (kind) {
        FavoriteKind.PHONE_FOLDER, FavoriteKind.DRIVE_FOLDER -> Icons.Outlined.Folder
        FavoriteKind.PHONE_MEDIA -> if (favorite.mime.startsWith("video/")) Icons.Outlined.Videocam else Icons.Outlined.Image
        else -> when {
            favorite.mime.startsWith("image/") -> Icons.Outlined.Image
            favorite.mime.startsWith("video/") -> Icons.Outlined.Videocam
            else -> Icons.AutoMirrored.Outlined.InsertDriveFile
        }
    }
    val thumb = when (state) {
        is FavoriteState.PhoneFolder -> state.folder.cover
        is FavoriteState.PhoneMedia -> state.item.uri
        else -> null
    }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
        onClick = if (available) onOpen else null,
        onClickLabel = if (available) "Open $title" else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = Tokens.Medium, end = Tokens.Tiny, top = Tokens.Medium, bottom = Tokens.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(Tokens.FolderThumb).clip(Tokens.ThumbShape).background(GlassPalette.Thumb),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(thumb).size(192).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = Tokens.Gutter)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state is FavoriteState.Unavailable) {
                        Text(
                            "Unavailable",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (state is FavoriteState.Unavailable) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    available -> FavoriteStar(favorite = true, name = title, onToggle = onRemove)
                    state == FavoriteState.Checking -> Box(Modifier.size(Tokens.TouchTarget), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(Tokens.Icon), strokeWidth = Tokens.Stroke)
                    }
                    else -> Spacer(Modifier.width(Tokens.Tiny))
                }
            }
            if (state is FavoriteState.Unavailable) {
                Row(
                    Modifier.fillMaxWidth().padding(top = Tokens.Small, end = Tokens.Small),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.canRetry) TextButton(onClick = onRetry) { Text("Try again") }
                    OutlinedButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}
