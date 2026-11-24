package com.drivelift.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.drivelift.app.data.LocalFileCategory
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.MediaFolder
import com.drivelift.app.data.MediaItem
import com.drivelift.app.data.SortField
import com.drivelift.app.data.matchesQuery
import com.drivelift.app.data.sortedFiles
import com.drivelift.app.drive.DriveAbout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Folder list
// ---------------------------------------------------------------------------

@Composable
fun PhoneScreen(
    vm: AppViewModel,
    onOpen: (Long) -> Unit,
    onMoveFolder: (Long) -> Unit,
    onOpenFavorites: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAppLock: () -> Unit = {},
    onOpenCockpit: () -> Unit = {},
    onOpenCleaner: () -> Unit = {},
    onOpenExplorer: (LocalFileCategory) -> Unit = {},
    onOpenDrive: () -> Unit = {},
    onOpenWhatsAppQuickSender: () -> Unit = {},
    onSendFolderToWhatsApp: (Long) -> Unit = {},
    /** Offline OCR search and Smart rules; the row is hidden when null. */
    onOpenIntelligence: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val favoriteCounts by vm.favoriteCounts.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val loaded by vm.mediaLoaded.collectAsStateWithLifecycle()
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val totalBytes = remember(media) { media.sumOf { it.size } }

    val listState = rememberPersistentLazyListState(vm.scrollPositions, "tab:phone:root")

    Column(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().testTag("phone-root-list"),
    ) {
        item(key = "header") {
            PhoneCommandHeader(
                totalBytes = totalBytes,
                mediaCount = media.size,
                folderCount = folders.size,
                conn = conn,
                account = account,
                onDrivePillClick = {
                    if (conn == DriveConn.CONNECTED) onOpenDrive() else vm.connectDrive(interactive = true)
                },
            )
        }
        item(key = "bento") {
            PhoneCommandBentoGrid(
                favoriteSubtitle = when {
                    favoriteCounts.shown == 1 -> "1 folder or file"
                    favoriteCounts.shown > 1 -> "${favoriteCounts.shown} saved"
                    favoriteCounts.hidden > 0 -> "Another account"
                    else -> "Starred items"
                },
                onOpenAppLock = onOpenAppLock,
                onOpenCockpit = onOpenCockpit,
                onOpenCleaner = onOpenCleaner,
                onOpenStorage = onOpenStorage,
                onOpenFavorites = onOpenFavorites,
                onOpenAllFiles = { onOpenExplorer(LocalFileCategory.ALL) },
                modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
            )
        }
        item(key = "categories") {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phone-category-filters"),
                contentPadding = PaddingValues(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                val quickCategories = listOf(
                    LocalFileCategory.DOCUMENTS,
                    LocalFileCategory.DOWNLOADS,
                    LocalFileCategory.APKS_ZIPS,
                    LocalFileCategory.PHOTOS,
                    LocalFileCategory.VIDEOS,
                )
                items(quickCategories, key = { it.name }) { category ->
                    ExplorerCategoryChip(
                        label = category.label,
                        selected = false,
                        onClick = { onOpenExplorer(category) },
                        modifier = Modifier.testTag("phone-cat-${category.name.lowercase()}"),
                    )
                }
            }
        }
        if (MediaAccess.isPartial(context)) {
            item(key = "partial") {
                Text(
                    "Only the photos you selected are visible. Allow full access in system settings to see every folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
                )
            }
        }
        item(key = "heading") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Tokens.Edge, end = Tokens.Small, top = Tokens.Tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenWhatsAppQuickSender,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                        .testTag("whatsapp-quick-sender-entry"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Spacer(Modifier.width(Tokens.Tiny))
                    Text("WhatsApp")
                }
            }
        }
        if (!loaded) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(Tokens.EmptyState), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (folders.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No photos or videos found on this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Tokens.Edge),
                )
            }
        }
        items(folders, key = { it.bucketId }) { folder ->
            FolderRow(
                folder,
                favorite = com.drivelift.app.data.phoneFolderKey(folder.bucketId) in favoriteIds,
                onToggleFavorite = { vm.toggleFavorite(folder) },
                onOpen = { onOpen(folder.bucketId) },
                onMove = { onMoveFolder(folder.bucketId) },
                onSendWhatsApp = { onSendFolderToWhatsApp(folder.bucketId) },
            )
        }
        // After the folders, so the folder list keeps its place on screen.
        if (onOpenIntelligence != null) {
            item(key = "intelligence") {
                IntelligenceEntryRow(onOpenIntelligence, Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Small))
            }
        }
    }
    // Phone files waiting to be moved: the list is not a folder, so the pill asks to open one.
    PhoneMovePillBar(vm = vm, visible = true, hereBucketId = null, hereName = "", enabled = true, onMoveHere = {})
    }
}

@Composable
private fun PhoneCommandHeader(
    totalBytes: Long,
    mediaCount: Int,
    folderCount: Int,
    conn: DriveConn,
    account: DriveAbout?,
    onDrivePillClick: () -> Unit,
) {
    val context = LocalContext.current
    val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Tokens.Edge, end = Tokens.Edge, top = Tokens.Medium, bottom = Tokens.Small),
    ) {
        if (stacked) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatBytes(context, totalBytes),
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "$mediaCount items  \u00B7  $folderCount folders",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Tokens.Small))
            DriveStatusPill(conn = conn, account = account, onClick = onDrivePillClick)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = Tokens.Small)) {
                    Text(
                        formatBytes(context, totalBytes),
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$mediaCount items  \u00B7  $folderCount folders",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DriveStatusPill(conn = conn, account = account, onClick = onDrivePillClick)
            }
        }
    }
}

/**
 * Compact Drive status pill in the Phone top header, replacing the oversized status banner.
 */
@Composable
fun DriveStatusPill(
    conn: DriveConn,
    account: DriveAbout?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val label = when (conn) {
        DriveConn.CONNECTED -> {
            val free = account?.takeIf { it.limitBytes > 0 }?.let {
                formatBytes(context, (it.limitBytes - it.usedBytes).coerceAtLeast(0L))
            }
            if (free != null) "Drive \u00B7 $free" else "Drive connected"
        }
        DriveConn.CONNECTING, DriveConn.UNKNOWN -> "Connecting"
        DriveConn.DISCONNECTED -> "Connect Drive"
    }
    val dotColor = when (conn) {
        DriveConn.CONNECTED -> MaterialTheme.colorScheme.tertiary
        DriveConn.CONNECTING, DriveConn.UNKNOWN -> MaterialTheme.colorScheme.primary
        DriveConn.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val clickDesc = when (conn) {
        DriveConn.CONNECTED -> "Google Drive connected. Open My Drive"
        DriveConn.CONNECTING, DriveConn.UNKNOWN -> "Connecting to Google Drive"
        DriveConn.DISCONNECTED -> "Connect Google Drive"
    }
    GlassSurface(
        level = GlassLevel.Island,
        shape = Tokens.PillShape,
        modifier = modifier
            .defaultMinSize(minHeight = Tokens.TouchTarget)
            .testTag("phone-drive-status-pill"),
        onClick = onClick,
        onClickLabel = clickDesc,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Tokens.Medium, vertical = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            if (conn == DriveConn.CONNECTING || conn == DriveConn.UNKNOWN) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Tokens.IconSmall),
                    strokeWidth = Tokens.Stroke,
                )
            } else {
                Box(
                    Modifier
                        .size(Tokens.StatusDot)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (conn == DriveConn.DISCONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PhoneCommandBentoGrid(
    favoriteSubtitle: String,
    onOpenAppLock: () -> Unit,
    onOpenCockpit: () -> Unit,
    onOpenCleaner: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenAllFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("phone-bento-grid"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            CommandBentoTile(
                title = "App Lock",
                subtitle = "Biometric & PIN",
                icon = Icons.Outlined.Lock,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onOpenAppLock,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("bento-applock-tile"),
            )
            CommandBentoTile(
                title = "Cockpit",
                subtitle = "Island & Widget",
                icon = Icons.Outlined.Layers,
                iconTint = MaterialTheme.colorScheme.secondary,
                onClick = onOpenCockpit,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("bento-cockpit-tile"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            CommandBentoTile(
                title = "Cleaner & Sleep",
                subtitle = "Junk & Sleep Mode",
                icon = Icons.Outlined.CleaningServices,
                iconTint = MaterialTheme.colorScheme.tertiary,
                onClick = onOpenCleaner,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("bento-cleaner-tile"),
            )
            CommandBentoTile(
                title = "Storage",
                subtitle = "Sizes & duplicates",
                icon = Icons.Outlined.PieChart,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onOpenStorage,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("storage-entry"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            CommandBentoTile(
                title = "Favorites",
                subtitle = favoriteSubtitle,
                icon = Icons.Filled.Star,
                iconTint = MaterialTheme.colorScheme.secondary,
                onClick = onOpenFavorites,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("bento-favorites-tile"),
            )
            CommandBentoTile(
                title = "All Files",
                subtitle = "Device storage",
                icon = Icons.Outlined.FolderOpen,
                iconTint = MaterialTheme.colorScheme.tertiary,
                onClick = onOpenAllFiles,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("bento-explorer-tile"),
            )
        }
    }
}

@Composable
private fun CommandBentoTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wellShape = Tokens.concentricShape(Tokens.PanelRadius, Tokens.Small)
    GlassSurface(
        level = GlassLevel.Panel,
        shape = Tokens.PanelShape,
        modifier = modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
        onClick = onClick,
        onClickLabel = "Open $title",
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Tokens.Medium, vertical = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Tokens.BentoIconWell)
                    .clip(wellShape)
                    .background(GlassPalette.Well)
                    .border(Tokens.Hairline, GlassPalette.WellEdge, wellShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Tokens.IconSmall))
            }
            Column(Modifier.weight(1f).padding(start = Tokens.Small)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The move pill for phone files at the bottom of a Phone screen, rising in while files wait and
 * sinking away once they are moved or let go. [hereBucketId] is the folder shown, or null on the
 * folder list, where "Move here" stays off.
 */
@Composable
private fun ColumnScope.PhoneMovePillBar(
    vm: AppViewModel,
    visible: Boolean,
    hereBucketId: Long?,
    hereName: String,
    enabled: Boolean,
    onMoveHere: () -> Unit,
) {
    val context = LocalContext.current
    val clip by vm.phoneMoveClip.collectAsStateWithLifecycle()
    var shownClip by remember { mutableStateOf(clip) }
    clip?.let { shownClip = it }
    RisingBar(visible = visible && clip != null) {
        val held = shownClip ?: return@RisingBar
        MovePill(
            count = held.items.size,
            fromName = held.fromName,
            sizeLabel = formatBytes(context, held.totalBytes),
            canMoveHere = hereBucketId != null && held.canMoveInto(hereBucketId),
            hereName = hereName,
            enabled = enabled && clip != null,
            onMoveHere = onMoveHere,
            onCancel = vm::cancelPhoneMoveClip,
            modifier = Modifier.padding(horizontal = Tokens.Small, vertical = Tokens.Small),
        )
    }
}

@Composable
private fun IntelligenceEntryRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier.fillMaxWidth().testTag("intelligence-entry"), onClick = onOpen, onClickLabel = "Open search and rules") {
        Row(
            Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = Tokens.Medium)) {
                Text("Search and rules", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Find text in photos and PDFs offline, sort files automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DriveStatusRow(conn: DriveConn, label: String?, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conn == DriveConn.CONNECTED) {
                Icon(
                    Icons.Outlined.CloudDone, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.padding(start = Tokens.Medium)) {
                    Text("Google Drive connected", style = MaterialTheme.typography.titleSmall)
                    if (label != null) Text(
                        label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // With large text the button moves under the message instead of squeezing it.
                val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
                Column(Modifier.weight(1f)) {
                    Text("Google Drive is not connected", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Connect once to choose where files go.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (stacked) ConnectAction(conn, onConnect, Modifier.padding(top = Tokens.Medium))
                }
                if (!stacked) {
                    Spacer(Modifier.width(Tokens.Medium))
                    ConnectAction(conn, onConnect)
                }
            }
        }
    }
}

@Composable
private fun ConnectAction(conn: DriveConn, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    if (conn == DriveConn.CONNECTING || conn == DriveConn.UNKNOWN) {
        CircularProgressIndicator(modifier.size(Tokens.Icon), strokeWidth = Tokens.Stroke)
    } else {
        Button(onClick = onConnect, modifier = modifier) { Text("Connect") }
    }
}

@Composable
private fun FolderRow(
    folder: MediaFolder,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onSendWhatsApp: () -> Unit = {},
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
            onClick = onOpen,
            onLongClick = { menuOpen = true },
            onLongClickLabel = "More actions for ${folder.name}",
        ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Tokens.Medium, end = Tokens.Tiny, top = Tokens.Medium, bottom = Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(folder.cover).size(192).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(Tokens.FolderThumb).clip(Tokens.ThumbShape)
                    .background(GlassPalette.Thumb),
            )
            Column(Modifier.weight(1f).padding(horizontal = Tokens.Gutter)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${folder.count} items  ·  ${formatBytes(context, folder.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folder.path.isNotEmpty()) Text(
                    folder.path, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FavoriteStar(favorite = favorite, name = folder.name, onToggle = onToggleFavorite)
            IconButton(onClick = onMove) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = "Move the whole ${folder.name} folder to Drive",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DropdownMenuItem(
                text = { Text("Send to WhatsApp") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onSendWhatsApp()
                },
            )
            DropdownMenuItem(
                text = { Text("Move folder to Drive") },
                leadingIcon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMove()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Folder contents with bulk selection
// ---------------------------------------------------------------------------

private sealed interface GridEntry {
    data class Header(val day: LocalDate, val label: String, val ids: List<Long>) : GridEntry
    data class Cell(val item: MediaItem, val index: Int) : GridEntry
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    vm: AppViewModel,
    bucketId: Long,
    onBack: () -> Unit,
    onMove: () -> Unit,
    onSendWhatsApp: (List<MediaItem>, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val media by vm.media.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val tracked by vm.trackedUris.collectAsStateWithLifecycle()
    val gridDensity by vm.gridDensity.collectAsStateWithLifecycle()

    val items = remember(media, bucketId) { media.filter { it.bucketId == bucketId } }
    val sort by vm.phoneSort.collectAsStateWithLifecycle()
    var query by rememberSaveable(bucketId) { mutableStateOf("") }
    // The files on screen: those whose name matches the search, in the chosen order. Selection,
    // range drag and Select all act on these; the folder itself is [items].
    val shown = remember(items, query, sort) {
        sortedFiles(
            items.filter { matchesQuery(it.name, query) }, sort,
            name = { it.name }, id = { it.id.toString() }, date = { it.dateTakenMs }, size = { it.size },
        )
    }
    val entries = remember(shown, sort) {
        val out = ArrayList<GridEntry>(shown.size + 32)
        var index = 0
        if (sort.field == SortField.DATE) {
            // Day headers only make sense in date order; the days follow the chosen direction.
            val zone = ZoneId.systemDefault()
            shown.groupBy { Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate() }
                .forEach { (day, list) ->
                    out += GridEntry.Header(day, day.format(DAY_FORMAT), list.map { it.id })
                    list.forEach { out += GridEntry.Cell(it, index++) }
                }
        } else {
            shown.forEach { out += GridEntry.Cell(it, index++) }
        }
        out
    }
    val indexById = remember(shown) { HashMap<Long, Int>().apply { shown.forEachIndexed { i, m -> put(m.id, i) } } }
    val selectedBytes = remember(selection, items) { items.sumOf { if (it.id in selection) it.size else 0L } }
    val title = items.firstOrNull()?.bucketName ?: "Folder"

    LaunchedEffect(items.isEmpty()) { if (items.isEmpty() && media.isNotEmpty()) onBack() }

    // The photo or video open in the viewer, kept across rotation. The grid stays composed
    // beneath it, so closing the viewer returns to the same folder and scroll position.
    var viewingId by rememberSaveable(bucketId) { mutableLongStateOf(NOT_VIEWING) }
    val viewing = remember(viewingId, items) { items.firstOrNull { it.id == viewingId } }
    // Set when a long press selected a cell, so the finger lifting from that press does not
    // also count as a tap on it. Cleared at the start of every touch.
    val longPress = remember { LongPressGuard() }
    // A user-directed delete in progress (issue #36): the items asked about, in selection order.
    // Saved, so a rotation while Android's dialog is open keeps the flow and its result handler.
    var deleting by rememberSaveable(bucketId) { mutableStateOf<LongArray?>(null) }
    val deletingIds = remember(deleting) { deleting?.toList() }
    // A rename in progress (issue #42): the one file asked about. Saved across rotation.
    var renaming by rememberSaveable(bucketId) { mutableStateOf<Long?>(null) }
    // Phone files waiting in the move pill, and a move into this folder waiting for confirmation.
    val phoneClip by vm.phoneMoveClip.collectAsStateWithLifecycle()
    val phoneMoveRun by vm.phoneMoveRun.collectAsStateWithLifecycle()
    val phoneMoving = phoneMoveRun?.working == true
    var phoneMovePlan by remember { mutableStateOf<com.drivelift.app.data.PhoneMovePlan?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(phoneMoveRun?.outcome) {
        if (phoneMoveRun?.outcome == null) return@LaunchedEffect
        phoneMovePlan = null
        vm.phoneMoveApplied()
    }
    fun tapCell(item: MediaItem) {
        if (longPress.consume(item.id)) return
        if (vm.selection.value.isEmpty()) viewingId = item.id else vm.toggle(item.id)
    }

    val gridState = rememberPersistentLazyGridState(vm.scrollPositions, "tab:phone:folder:$bucketId")
    val autoScroll = remember { mutableFloatStateOf(0f) }
    val pinchActive = remember { booleanArrayOf(false) }
    LaunchedEffect(autoScroll.floatValue) {
        if (autoScroll.floatValue != 0f) {
            while (isActive) {
                gridState.scrollBy(autoScroll.floatValue)
                delay(10)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // While the viewer is open the grid stays composed, so its scroll position is kept, but it
        // is not drawn and not offered to TalkBack: the viewer sits on the root backdrop alone.
        Column(
            Modifier
                .fillMaxSize()
                .then(if (viewing != null) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
        ) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    Column {
                        Text(
                            if (selection.isEmpty()) title else "${selection.size} selected",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (selection.isEmpty()) "Tap to open. Hold to select, or hold and drag for a range."
                            else formatBytes(context, selectedBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (selection.isEmpty()) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to folders")
                        }
                    } else {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
                        }
                    }
                },
                actions = {
                    val all = shown.isNotEmpty() && shown.all { it.id in selection }
                    IconButton(
                        onClick = vm::cycleGridDensity,
                        modifier = Modifier.size(Tokens.TouchTarget),
                    ) {
                        Icon(
                            Icons.Outlined.PhotoSizeSelectLarge,
                            contentDescription = "Thumbnail size: ${gridDensity.label}",
                        )
                    }
                    TextButton(onClick = { vm.setSelected(shown.map { it.id }, !all) }) {
                        Text(if (all) "Select none" else "Select all")
                    }
                },
            )

            FolderFindBar(query = query, onQueryChange = { query = it }, sort = sort, onSortChange = vm::setPhoneSort)

            if (shown.isEmpty() && query.isNotBlank()) {
                NoSearchMatches(query, Modifier.weight(1f).fillMaxWidth())
            } else
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridDensity.phoneMinCell),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(Tokens.GridGap),
                verticalArrangement = Arrangement.spacedBy(Tokens.GridGap),
                modifier = Modifier
                    .weight(1f)
                    .testTag("phone-media-grid")
                    .pinchGridDensity(
                        gridState = gridState,
                        density = gridDensity,
                        onDensityChange = vm::setGridDensity,
                        onPinchStateChange = { pinchActive[0] = it },
                    )
                    .dragSelect(
                        state = gridState,
                        isPinching = { pinchActive[0] },
                        autoScroll = { autoScroll.floatValue = it },
                        indexOfKey = { key -> (key as? Long)?.let(indexById::get) },
                        current = { vm.selection.value },
                        guard = longPress,
                        idAt = { shown[it].id },
                        onRange = { base, from, to ->
                            val range = if (from <= to) from..to else to..from
                            vm.selection.value = base + range.map { shown[it].id }
                        },
                    ),
            ) {
                items(
                    entries,
                    key = { e -> when (e) { is GridEntry.Header -> "h${e.day}"; is GridEntry.Cell -> e.item.id } },
                    span = { e -> if (e is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                    contentType = { e -> if (e is GridEntry.Header) 0 else 1 },
                ) { entry ->
                    when (entry) {
                        is GridEntry.Header -> {
                            val allDay = entry.ids.all { it in selection }
                            Row(
                                Modifier.fillMaxWidth().padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${entry.label}\u00A0\u00A0·\u00A0\u00A0${entry.ids.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { vm.setSelected(entry.ids, !allDay) }) {
                                    Text(if (allDay) "Deselect day" else "Select day")
                                }
                            }
                        }
                        is GridEntry.Cell -> MediaCell(
                            item = entry.item,
                            selected = entry.item.id in selection,
                            tracked = entry.item.uri.toString() in tracked,
                            selecting = selection.isNotEmpty(),
                            onTap = { tapCell(entry.item) },
                            onSelect = { vm.setSelected(listOf(entry.item.id), true) },
                        )
                    }
                }
            }

            // The last selection stays drawn while the bar sinks away.
            var barSelection by remember { mutableStateOf(selection) }
            if (selection.isNotEmpty()) barSelection = selection
            val barBytes = remember(barSelection, items) { items.sumOf { if (it.id in barSelection) it.size else 0L } }
            RisingBar(visible = selection.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().glassBar()) {
                    // The count and size sit on their own line above the actions (Clear, trash, move
                    // to another folder, Move to Drive), so they are never squeezed at any size.
                    val summary: @Composable () -> Unit = {
                        Text("${barSelection.size} items", style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatBytes(context, barBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Medium)) {
                        // Rename sits with the count when exactly one file is selected, so the
                        // action row below keeps its width at every text size.
                        SummaryWithRename(
                            showRename = barSelection.size == 1,
                            onRename = { renaming = barSelection.first() },
                        ) { summary() }
                        Row(
                            Modifier.fillMaxWidth().padding(top = Tokens.Small),
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = vm::clearSelection) { Text("Clear") }
                            DeleteSelectedButton { deleting = items.filter { it.id in barSelection }.map { it.id }.toLongArray() }
                            IconButton(
                                onClick = {
                                    vm.holdPhoneForMove(barSelection, bucketId, title)
                                    vm.clearSelection()
                                },
                                enabled = !phoneMoving,
                                modifier = Modifier.size(Tokens.TouchTarget),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.DriveFileMove,
                                    contentDescription = "Move selected to another folder on this phone",
                                )
                            }
                            Button(onClick = onMove, modifier = Modifier.weight(1f)) { Text("Move to Drive") }
                        }
                        OutlinedButton(
                            onClick = { onSendWhatsApp(items.filter { it.id in barSelection }, title) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Tokens.Small)
                                .defaultMinSize(minHeight = Tokens.TouchTarget)
                                .testTag("folder-send-whatsapp"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                modifier = Modifier.size(Tokens.IconSmall),
                            )
                            Spacer(Modifier.width(Tokens.Small))
                            Text("Send to WhatsApp")
                        }
                    }
                }
            }

            PhoneMovePillBar(
                vm = vm,
                visible = selection.isEmpty(),
                hereBucketId = bucketId,
                hereName = title,
                enabled = phoneClip != null && !phoneMoving && phoneMovePlan == null,
                onMoveHere = { scope.launch { phoneMovePlan = vm.planPhoneMove(bucketId) } },
            )
        }
        viewing?.let { item ->
            FullScreenOverlay {
                LocalMediaViewer(
                    vm = vm,
                    item = item,
                    onClose = { viewingId = NOT_VIEWING },
                    onDelete = { deleting = longArrayOf(viewingId) },
                    folderItems = shown,
                    onItemChanged = { viewingId = it.id },
                )
            }
        }
        renaming?.let { id ->
            PhoneRenameFlow(vm = vm, id = id, onDone = { renaming = null })
        }
        phoneMovePlan?.let { plan ->
            PhoneMoveDialog(
                plan = plan,
                working = phoneMoving,
                onConfirm = { vm.startPhoneMove(plan) },
                onDismiss = { if (!phoneMoving) phoneMovePlan = null },
            )
        }
        deletingIds?.let { ids ->
            // A viewed file that goes to the trash drops out of [items] when the listing reloads,
            // which closes the viewer by itself.
            PhoneDeleteFlow(vm = vm, ids = ids, onDone = { deleting = null })
        }
    }
}

private const val NOT_VIEWING = -1L

/** Moves the selected files to the phone's trash, after confirmation (issue #36). */
@Composable
private fun DeleteSelectedButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(Tokens.TouchTarget)) {
        Icon(
            Icons.Outlined.DeleteOutline,
            contentDescription = "Move selected to this phone's trash",
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/** Remembers which cell a long press selected during the current touch. */
private class LongPressGuard {
    var id: Long? = null

    /** True once if [cellId] was just selected by a long press; the tap that ends it is ignored. */
    fun consume(cellId: Long): Boolean {
        if (id != cellId) return false
        id = null
        return true
    }
}

/**
 * A thumbnail. Without a selection a tap opens it in the viewer; while selecting a tap adds or
 * removes it. Holding it selects it (handled by the grid's drag select). For accessibility the
 * cell offers "Open" as its click and "Select" as a custom action, or is a checkbox while selecting.
 */
@Composable
private fun MediaCell(
    item: MediaItem,
    selected: Boolean,
    tracked: Boolean,
    selecting: Boolean,
    onTap: () -> Unit,
    onSelect: () -> Unit,
) {
    val inset by animateDpAsState(
        if (selected) Tokens.SelectedInset else Tokens.NoInset, AppMotion.spec(Tokens.Motion.SELECTION), label = "inset",
    )
    // The picture fades and grows in when it arrives from disk; from the memory cache it just shows.
    var loaded by remember(item.id) { mutableStateOf(false) }
    var animateIn by remember(item.id) { mutableStateOf(true) }
    val pictureShape = RoundedCornerShape(if (selected) Tokens.SelectedCorner else Tokens.NoInset)
    Box(
        Modifier
            .aspectRatio(1f)
            .background(GlassPalette.Thumb)
            .then(
                if (selecting) {
                    Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onTap() })
                } else {
                    Modifier
                        .clickable(onClickLabel = "Open", role = Role.Button, onClick = onTap)
                        .semantics {
                            customActions = listOf(CustomAccessibilityAction("Select") { onSelect(); true })
                        }
                },
            ),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(item.uri).size(256).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                animateIn = state.result.dataSource != coil.decode.DataSource.MEMORY_CACHE
                loaded = true
            },
            modifier = Modifier.fillMaxSize().padding(inset)
                .selectionGlow(selected, pictureShape)
                .clip(pictureShape)
                .revealWhen(loaded, animateIn),
        )
        if (item.isVideo) {
            Text(
                formatDuration(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(Tokens.Snug)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f), Tokens.TagShape)
                    .padding(horizontal = Tokens.TagPaddingHorizontal, vertical = Tokens.Hairline),
            )
        }
        if (tracked) {
            Icon(
                Icons.Outlined.CloudDone,
                contentDescription = "Already in a transfer",
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(Tokens.Snug).size(Tokens.Badge)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f), CircleShape)
                    .padding(Tokens.BadgePadding),
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(Tokens.SelectionStroke, MaterialTheme.colorScheme.primary))
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopStart).padding(Tokens.Snug).size(Tokens.Icon)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

/**
 * Hold a thumbnail, then drag: everything between the first cell and the
 * finger joins the selection, and the grid scrolls itself near the edges.
 */
@Composable
private fun Modifier.dragSelect(
    state: LazyGridState,
    isPinching: () -> Boolean,
    autoScroll: (Float) -> Unit,
    indexOfKey: (Any) -> Int?,
    current: () -> Set<Long>,
    guard: LongPressGuard,
    idAt: (Int) -> Long,
    onRange: (base: Set<Long>, from: Int, to: Int) -> Unit,
): Modifier {
    val lookup by rememberUpdatedState(indexOfKey)
    val range by rememberUpdatedState(onRange)
    val pinchGuard by rememberUpdatedState(isPinching)
    val edge = with(LocalDensity.current) { Tokens.AutoScrollEdge.toPx() }
    val cellId by rememberUpdatedState(idAt)
    val haptics = LocalHapticFeedback.current
    // Every new touch starts with no long press recorded. The Initial pass runs before the cells
    // see the touch, so a tap that follows an earlier long press is never ignored by mistake.
    return pointerInput(guard) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.id = null
        }
    }.pointerInput(Unit) {
        var anchor: Int? = null
        var last: Int? = null
        var base: Set<Long> = emptySet()
        fun indexAt(point: Offset): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            point.x >= info.offset.x && point.x < info.offset.x + info.size.width &&
                point.y >= info.offset.y && point.y < info.offset.y + info.size.height
        }?.key?.let { lookup(it) }

        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                if (pinchGuard()) return@detectDragGesturesAfterLongPress
                indexAt(point)?.let {
                    anchor = it
                    last = it
                    base = current()
                    guard.id = cellId(it)
                    // The hold that starts a selection is confirmed with a short buzz.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    range(base, it, it)
                }
            },
            onDragEnd = {
                anchor = null
                autoScroll(0f)
            },
            onDragCancel = {
                anchor = null
                autoScroll(0f)
            },
            onDrag = { change, _ ->
                if (pinchGuard()) {
                    anchor = null
                    autoScroll(0f)
                    return@detectDragGesturesAfterLongPress
                }
                val start = anchor
                if (start != null) {
                    val fromBottom = state.layoutInfo.viewportSize.height - change.position.y
                    val fromTop = change.position.y
                    autoScroll(
                        when {
                            fromBottom < edge -> (edge - fromBottom) / 4
                            fromTop < edge -> -(edge - fromTop) / 4
                            else -> 0f
                        }
                    )
                    indexAt(change.position)?.let {
                        // A light tick each time the drag reaches another photo.
                        if (it != last) {
                            last = it
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        range(base, start, it)
                    }
                }
            },
        )
    }
}
