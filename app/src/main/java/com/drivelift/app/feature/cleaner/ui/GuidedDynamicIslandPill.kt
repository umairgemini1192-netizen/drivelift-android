package com.drivelift.app.feature.cleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.drivelift.app.feature.cleaner.cache.GuidedPillMode
import com.drivelift.app.feature.cleaner.cache.GuidedPillState
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.GlassLevel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassSurface
import com.drivelift.app.ui.Tokens

/**
 * Floating OLED Dynamic Island progress pill used as the Android 16/17 fallback for both
 * Guided Clean (clear cache in App Info) and Guided Sleep (Force stop in App Info) when
 * Shizuku is absent or unavailable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidedDynamicIslandPill(
    state: GuidedPillState,
    onOpenAppInfo: (String) -> Unit,
    onConfirmStep: () -> Unit,
    onSkipStep: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onNextApp: () -> Unit = onConfirmStep,
) {
    val accent = when (state.mode) {
        GuidedPillMode.GUIDED_CLEAN -> CockpitLivePalette.Emerald
        GuidedPillMode.GUIDED_SLEEP -> CockpitLivePalette.Cyan
    }
    val modeTitle = when (state.mode) {
        GuidedPillMode.GUIDED_CLEAN -> "Guided Clean"
        GuidedPillMode.GUIDED_SLEEP -> "Guided Sleep"
    }

    GlassSurface(
        level = GlassLevel.Island,
        shape = Tokens.Cleaner.PillShape,
        modifier = modifier
            .fillMaxWidth()
            .testTag("guided-dynamic-island-pill"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Row),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    Spacer(
                        modifier = Modifier
                            .size(Tokens.Cleaner.StatusDotSize)
                            .clip(CircleShape)
                            .background(accent),
                    )
                    Text(
                        text = modeTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                }
                Text(
                    text = "${state.stepNumber} of ${state.totalCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = CockpitLivePalette.White,
                    modifier = Modifier.testTag("guided-pill-step-counter"),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                Text(
                    text = state.currentAppLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = CockpitLivePalette.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.instruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Cleaner.PillProgressHeight)
                    .clip(Tokens.Cleaner.CapacityBarShape),
                color = accent,
                trackColor = GlassPalette.HudTrackRecessed,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Button(
                    onClick = { onOpenAppInfo(state.currentPackageName) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = CockpitLivePalette.Oled,
                    ),
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("guided-pill-open-settings"),
                ) {
                    Text("Open App Info")
                }

                Button(
                    onClick = onNextApp,
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("guided-pill-next-app"),
                ) {
                    Text("Next App ->")
                }

                OutlinedButton(
                    onClick = onConfirmStep,
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("guided-pill-confirm"),
                ) {
                    Text("Done")
                }

                TextButton(
                    onClick = onSkipStep,
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("guided-pill-skip"),
                ) {
                    Text("Skip")
                }

                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("guided-pill-cancel"),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
