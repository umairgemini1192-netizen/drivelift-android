package com.drivelift.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.drivelift.app.data.DriveMoveClip

/**
 * The move pill, pinned above the tab bar while Drive files wait to be moved: how many and from
 * where, "Move here" for the folder shown now, and a way to let them go. In the folder the files
 * came from it says to open another folder instead. "Move here" opens the usual move confirmation;
 * nothing on Drive changes before that. Stacks its text above the buttons at large text sizes and
 * on narrow phones (rule 8).
 */
@Composable
internal fun MovePill(
    clip: DriveMoveClip,
    sizeLabel: String,
    canMoveHere: Boolean,
    hereName: String,
    enabled: Boolean,
    onMoveHere: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
    val count = clip.files.size
    val title = if (count == 1) "1 file to move" else "$count files to move"
    val detail = if (canMoveHere) "From ${clip.fromName}  ·  $sizeLabel"
    else "Open the folder to move them to"
    val shape = if (stacked) Tokens.PanelShape else Tokens.PillShape

    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (stacked) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val cancel: @Composable () -> Unit = {
        IconButton(onClick = onCancel, enabled = enabled, modifier = Modifier.size(Tokens.TouchTarget)) {
            Icon(Icons.Outlined.Close, contentDescription = "Stop moving these files")
        }
    }
    val moveHere: @Composable (Modifier) -> Unit = { m ->
        Button(onClick = onMoveHere, enabled = enabled && canMoveHere, modifier = m) {
            Text(
                if (stacked) "Move here: $hereName" else "Move here",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (stacked) {
        Column(
            modifier.fillMaxWidth().glassBar(shape).testTag("move-pill")
                .padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                text(Modifier.weight(1f))
                cancel()
            }
            moveHere(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier.fillMaxWidth().glassBar(shape).testTag("move-pill")
                .padding(start = Tokens.Edge, end = Tokens.Small, top = Tokens.Small, bottom = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            text(Modifier.weight(1f))
            moveHere(Modifier)
            cancel()
        }
    }
}
