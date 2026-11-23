package com.drivelift.app.feature.cleaner.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.drivelift.app.feature.cleaner.engine.StorageCapacityBreakdown
import com.drivelift.app.ui.AppMotion
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.GlassLevel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassSurface
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes
import kotlin.math.roundToLong

private const val MOOD_HERO_AGSL = """
uniform float2 iResolution;
uniform float iSeverity;
uniform half4 uCleanPrimary;
uniform half4 uCleanSecondary;
uniform half4 uAlertPrimary;
uniform half4 uAlertSecondary;
uniform half4 uBaseColor;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(iResolution, float2(1.0, 1.0));
    float s = clamp(iSeverity, 0.0, 1.0);
    half4 primaryTone = mix(uCleanPrimary, uAlertPrimary, half(s));
    half4 secondaryTone = mix(uCleanSecondary, uAlertSecondary, half(s));

    float2 c1 = float2(0.18 + 0.12 * s, 0.22);
    float2 c2 = float2(0.84 - 0.10 * s, 0.78);
    float d1 = smoothstep(0.95, 0.0, length(uv - c1));
    float d2 = smoothstep(1.05, 0.0, length(uv - c2));

    half4 col = uBaseColor;
    col = mix(col, primaryTone, half(d1 * 0.42));
    col = mix(col, secondaryTone, half(d2 * 0.34));
    return half4(col.rgb, 1.0);
}
"""

/**
 * Material 3 Expressive + OLED Tactile Glass mood-shift hero.
 *
 * Uses an AGSL [RuntimeShader] on API 33+ with automatic multi-stop [Brush] fallback on API <33
 * or when shader compilation is unavailable (for example JVM Robolectric rendering).
 * Shifts smoothly from Crimson/Amber (high junk severity) to Cyan/Emerald (clean state).
 */
@Composable
fun CleanerMoodHeroCard(
    reclaimableBytes: Long,
    breakdown: StorageCapacityBreakdown,
    headlineLabel: String,
    subtitle: String,
    isCelebration: Boolean,
    modifier: Modifier = Modifier,
    badgeContent: (@Composable () -> Unit)? = null,
) {
    val targetSeverity = if (isCelebration) 0f else breakdown.healthSeverity.coerceIn(0f, 1f)
    val animatedSeverity by animateFloatAsState(
        targetValue = targetSeverity,
        animationSpec = AppMotion.spec(Tokens.Cleaner.MOOD_TRANSITION_MS),
        label = "cleaner-mood-severity",
    )

    val cleanPrimary = GlassPalette.HudEmerald
    val cleanSecondary = GlassPalette.HudElectricCyan
    val alertPrimary = GlassPalette.HudCoral
    val alertSecondary = GlassPalette.HudAmber

    val activePrimary = lerp(cleanPrimary, alertPrimary, animatedSeverity)
    val activeSecondary = lerp(cleanSecondary, alertSecondary, animatedSeverity)
    val baseColor = CockpitLivePalette.Oled

    val agslShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(MOOD_HERO_AGSL) }.getOrNull()
        } else {
            null
        }
    }

    GlassSurface(
        level = GlassLevel.Hero,
        shape = Tokens.Cleaner.HeroShape,
        borderBrush = Brush.linearGradient(
            listOf(
                activePrimary.copy(alpha = 0.65f),
                GlassPalette.BorderMid,
                GlassPalette.BorderBottomRight,
            ),
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.Cleaner.HeroMinHeight)
            .testTag("cleaner-mood-hero"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.Cleaner.HeroMinHeight)
                .drawBehind {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && agslShader != null) {
                        val renderedWithAgsl = runCatching {
                            agslShader.setFloatUniform("iResolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
                            agslShader.setFloatUniform("iSeverity", animatedSeverity)
                            agslShader.setColorUniform("uCleanPrimary", cleanPrimary.toArgbInt())
                            agslShader.setColorUniform("uCleanSecondary", cleanSecondary.toArgbInt())
                            agslShader.setColorUniform("uAlertPrimary", alertPrimary.toArgbInt())
                            agslShader.setColorUniform("uAlertSecondary", alertSecondary.toArgbInt())
                            agslShader.setColorUniform("uBaseColor", baseColor.toArgbInt())
                            drawRect(brush = ShaderBrush(agslShader))
                        }.isSuccess
                        if (!renderedWithAgsl) {
                            drawFallbackMoodBackdrop(
                                baseColor = baseColor,
                                primary = activePrimary,
                                secondary = activeSecondary,
                            )
                        }
                    } else {
                        drawFallbackMoodBackdrop(
                            baseColor = baseColor,
                            primary = activePrimary,
                            secondary = activeSecondary,
                        )
                    }
                }
                .padding(Tokens.Edge),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = headlineLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = activePrimary,
                    )
                    badgeContent?.invoke()
                }

                RollingOdometerBytesCounter(
                    bytes = reclaimableBytes,
                    modifier = Modifier.testTag("cleaner-odometer-counter"),
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SegmentedCapacityBar(
                    breakdown = breakdown,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFallbackMoodBackdrop(
    baseColor: Color,
    primary: Color,
    secondary: Color,
) {
    drawRect(color = baseColor)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(primary.copy(alpha = 0.36f), Color.Transparent),
            center = Offset(size.width * 0.20f, size.height * 0.20f),
            radius = size.width * 0.85f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(secondary.copy(alpha = 0.28f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.82f),
            radius = size.width * 0.90f,
        ),
    )
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Spring-physics rolling byte counter using tabular digits so numerals never jitter horizontally.
 */
@Composable
fun RollingOdometerBytesCounter(
    bytes: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    val safeTarget = bytes.coerceAtLeast(0L).toFloat()
    val animatedBytes by animateFloatAsState(
        targetValue = safeTarget,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(
                dampingRatio = Tokens.Cleaner.ODOMETER_SPRING_DAMPING,
                stiffness = Tokens.Cleaner.ODOMETER_SPRING_STIFFNESS,
                visibilityThreshold = Spring.DefaultDisplacementThreshold,
            )
        },
        label = "odometer-bytes",
    )

    val displayedBytes = animatedBytes.roundToLong().coerceAtLeast(0L)
    val largeFont = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    val textStyle = if (largeFont) {
        MaterialTheme.typography.displaySmall
    } else {
        MaterialTheme.typography.displayLarge
    }

    Text(
        text = formatBytes(context, displayedBytes),
        style = textStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * Three-segment storage capacity bar showing Safe Unneeded (Emerald), Hidden Caches (Cyan),
 * and Files to Review (Amber) proportions with a responsive tabular legend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentedCapacityBar(
    breakdown: StorageCapacityBreakdown,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val safeBytes = breakdown.safeUnneededBytes.coerceAtLeast(0L)
    val cacheBytes = breakdown.hiddenCacheBytes.coerceAtLeast(0L)
    val reviewBytes = breakdown.filesToReviewBytes.coerceAtLeast(0L)
    val totalReclaimable = (safeBytes + cacheBytes + reviewBytes).coerceAtLeast(0L)

    val safeFraction = if (totalReclaimable > 0L) safeBytes.toFloat() / totalReclaimable.toFloat() else 0f
    val cacheFraction = if (totalReclaimable > 0L) cacheBytes.toFloat() / totalReclaimable.toFloat() else 0f
    val reviewFraction = if (totalReclaimable > 0L) reviewBytes.toFloat() / totalReclaimable.toFloat() else 0f

    Column(
        modifier = modifier.testTag("cleaner-segmented-capacity-bar"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(Tokens.Cleaner.CapacityBarHeight)
                .clip(Tokens.Cleaner.CapacityBarShape)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = breakdown.healthSeverity.coerceIn(0f, 1f),
                        range = 0f..1f,
                    )
                },
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = GlassPalette.HudTrackRecessed,
                size = size,
                cornerRadius = radius,
            )

            val gapPx = Tokens.Cleaner.CapacitySegmentGap.toPx()
            val segments = listOf(
                safeFraction to GlassPalette.HudEmerald,
                cacheFraction to GlassPalette.HudElectricCyan,
                reviewFraction to GlassPalette.HudAmber,
            ).filter { it.first > 0f }

            if (segments.isNotEmpty()) {
                val totalGapPx = gapPx * (segments.size - 1)
                val availableWidth = (size.width - totalGapPx).coerceAtLeast(0f)
                var cursorX = 0f
                for ((fraction, color) in segments) {
                    val segWidth = (availableWidth * fraction).coerceAtLeast(size.height * 0.5f)
                    val clampedWidth = segWidth.coerceAtMost((size.width - cursorX).coerceAtLeast(0f))
                    if (clampedWidth > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(cursorX, 0f),
                            size = Size(clampedWidth, size.height),
                            cornerRadius = radius,
                        )
                        cursorX += clampedWidth + gapPx
                    }
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
            verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            CapacityLegendItem(
                dotColor = GlassPalette.HudEmerald,
                label = "Safe ${formatBytes(context, safeBytes)}",
            )
            CapacityLegendItem(
                dotColor = GlassPalette.HudElectricCyan,
                label = "Caches ${formatBytes(context, cacheBytes)}",
            )
            CapacityLegendItem(
                dotColor = GlassPalette.HudAmber,
                label = "Review ${formatBytes(context, reviewBytes)}",
            )
        }

        if (breakdown.totalDeviceBytes > 0L) {
            Text(
                text = "${formatBytes(context, breakdown.freeDeviceBytes)} free of ${formatBytes(context, breakdown.totalDeviceBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapacityLegendItem(
    dotColor: Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        Spacer(
            modifier = Modifier
                .size(Tokens.Cleaner.LegendDotSize)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
