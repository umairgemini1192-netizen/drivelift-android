package com.drivelift.app.feature.applock.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.drivelift.app.feature.applock.AppLockGateMode
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.Tokens

/**
 * Immutable UI state rendered by [AppLockGateScreen].
 */
data class AppLockGateUiState(
    val mode: AppLockGateMode = AppLockGateMode.UNLOCK_APP,
    val targetAppLabel: String = "Locked app",
    val targetPackageName: String = "",
    val enteredDigitsCount: Int = 0,
    val expectedPinLength: Int = 6,
    val hasPinConfigured: Boolean = true,
    val biometricAvailable: Boolean = true,
    val allowBiometric: Boolean = true,
    val errorMessage: String? = null,
    val errorShakeTrigger: Int = 0,
    val lockoutRemainingMs: Long = 0L,
) {
    val isLockedOut: Boolean get() = lockoutRemainingMs > 0L
    val canUseBiometric: Boolean get() = allowBiometric && biometricAvailable && !isLockedOut
}

/**
 * Full-screen hardened biometric and PIN gate overlay.
 *
 * Uses an API 31+ frosted-glass [RenderEffect] backdrop over [GlassBackdrop] with a dark scrim
 * fallback on API 30, spring-animated PIN pad buttons, horizontal error shake with haptics, and
 * tabular lockout countdowns.
 */
@Composable
fun AppLockGateScreen(
    state: AppLockGateUiState,
    onDigitPressed: (Char) -> Unit,
    onBackspacePressed: () -> Unit,
    onBiometricPressed: () -> Unit,
    onCancelPressed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val reducedMotion = LocalReducedMotion.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val isCompact = config.screenWidthDp < Tokens.COMPACT_WIDTH_DP ||
        density.fontScale >= Tokens.STACK_AT_FONT_SCALE

    val shakeOffsetPx = remember { Animatable(0f) }
    val shakeDistancePx = with(density) { AppLockTokens.ShakeDistance.toPx() }

    LaunchedEffect(state.errorShakeTrigger) {
        if (state.errorShakeTrigger > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!reducedMotion) {
                val spec = tween<Float>(durationMillis = AppLockTokens.SHAKE_STEP_MS)
                shakeOffsetPx.animateTo(-shakeDistancePx, spec)
                shakeOffsetPx.animateTo(shakeDistancePx, spec)
                shakeOffsetPx.animateTo(-shakeDistancePx * 0.6f, spec)
                shakeOffsetPx.animateTo(shakeDistancePx * 0.6f, spec)
                shakeOffsetPx.animateTo(0f, spec)
            }
        }
    }

    val blurModifier = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = runCatching {
                RenderEffect.createBlurEffect(
                    AppLockTokens.BackdropBlurRadiusPx,
                    AppLockTokens.BackdropBlurRadiusPx,
                    Shader.TileMode.CLAMP,
                ).asComposeRenderEffect()
            }.getOrNull()
            if (effect != null) {
                Modifier.graphicsLayer { renderEffect = effect }
            } else {
                Modifier
            }
        } else {
            Modifier
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("applock-gate-root")) {
        // Frosted-glass RenderEffect backdrop (API 31+) with dark scrim fallback on API 30.
        GlassBackdrop(modifier = Modifier.fillMaxSize().then(blurModifier)) {}
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GlassPalette.ThumbScrim),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.Edge, vertical = Tokens.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = AppLockTokens.GateMaxWidth)
                    .testTag("applock-gate-card"),
                shape = AppLockTokens.GateCardShape,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tokens.Edge, vertical = Tokens.Section),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(AppLockTokens.GateShieldSize)
                            .clip(CircleShape)
                            .background(
                                if (state.isLockedOut) MaterialTheme.colorScheme.errorContainer
                                else GlassPalette.Well,
                            )
                            .border(
                                width = Tokens.Hairline,
                                color = if (state.isLockedOut) MaterialTheme.colorScheme.error
                                else GlassPalette.WellEdge,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.mode == AppLockGateMode.AUTHORIZE_PAUSE) {
                                Icons.Outlined.PauseCircleOutline
                            } else {
                                Icons.Outlined.Lock
                            },
                            contentDescription = null,
                            tint = if (state.isLockedOut) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(AppLockTokens.GateShieldIconSize),
                        )
                    }

                    Spacer(Modifier.height(Tokens.Medium))

                    Text(
                        text = if (state.mode == AppLockGateMode.AUTHORIZE_PAUSE) {
                            "Pause App Lock"
                        } else {
                            state.targetAppLabel
                        },
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("applock-gate-title"),
                    )

                    Spacer(Modifier.height(Tokens.Tiny))

                    Text(
                        text = if (state.mode == AppLockGateMode.AUTHORIZE_PAUSE) {
                            "Authenticate to pause App Lock for 15 minutes in memory."
                        } else {
                            "Confirm with biometric or PIN to open this app."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(Tokens.Gutter))

                    if (state.isLockedOut) {
                        LockoutBanner(remainingMs = state.lockoutRemainingMs)
                    } else if (!state.hasPinConfigured) {
                        Text(
                            text = "No backup PIN configured. Use biometric unlock or set a PIN in App Lock settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("applock-gate-no-pin"),
                        )
                    } else {
                        PinDotsRow(
                            enteredCount = state.enteredDigitsCount,
                            totalDots = state.expectedPinLength,
                            hasError = state.errorMessage != null,
                            modifier = Modifier
                                .graphicsLayer { translationX = shakeOffsetPx.value }
                                .testTag("applock-pin-dots"),
                        )
                    }

                    if (!state.errorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(Tokens.Small))
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("applock-gate-error"),
                        )
                    }

                    Spacer(Modifier.height(Tokens.Section))

                    SpringPinKeypad(
                        enabled = !state.isLockedOut && state.hasPinConfigured,
                        canUseBiometric = state.canUseBiometric,
                        canBackspace = state.enteredDigitsCount > 0 && !state.isLockedOut,
                        compact = isCompact,
                        onDigit = onDigitPressed,
                        onBackspace = onBackspacePressed,
                        onBiometric = onBiometricPressed,
                    )

                    Spacer(Modifier.height(Tokens.Gutter))

                    TextButton(
                        onClick = onCancelPressed,
                        modifier = Modifier
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("applock-gate-cancel"),
                    ) {
                        Text(
                            text = if (state.mode == AppLockGateMode.AUTHORIZE_PAUSE) {
                                "Cancel"
                            } else {
                                "Go to Home"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockoutBanner(remainingMs: Long) {
    val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val countdown = if (minutes > 0L) "%d:%02d".format(minutes, seconds) else "${seconds}s"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Tokens.PanelShape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .border(Tokens.Hairline, MaterialTheme.colorScheme.error, Tokens.PanelShape)
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)
            .testTag("applock-lockout-banner"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Too many wrong PIN attempts",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Tokens.Tiny))
        Text(
            text = "Try again in $countdown",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PinDotsRow(
    enteredCount: Int,
    totalDots: Int,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            contentDescription = "$enteredCount of $totalDots PIN digits entered"
        },
        horizontalArrangement = Arrangement.spacedBy(AppLockTokens.PinDotGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until totalDots) {
            val filled = i < enteredCount
            val fillColor = when {
                hasError && filled -> MaterialTheme.colorScheme.error
                filled -> MaterialTheme.colorScheme.primary
                else -> GlassPalette.Dim
            }
            val borderColor = when {
                hasError -> MaterialTheme.colorScheme.error
                filled -> MaterialTheme.colorScheme.primary
                else -> GlassPalette.WellEdge
            }
            Box(
                modifier = Modifier
                    .size(AppLockTokens.PinDotSize)
                    .clip(CircleShape)
                    .background(fillColor)
                    .border(Tokens.Hairline, borderColor, CircleShape),
            )
        }
    }
}

@Composable
private fun SpringPinKeypad(
    enabled: Boolean,
    canUseBiometric: Boolean,
    canBackspace: Boolean,
    compact: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
) {
    val buttonSize = if (compact) AppLockTokens.KeypadButtonCompactSize else AppLockTokens.KeypadButtonSize
    val rowGap = if (compact) AppLockTokens.KeypadCompactGap else AppLockTokens.KeypadVerticalGap
    val colGap = if (compact) AppLockTokens.KeypadCompactGap else AppLockTokens.KeypadHorizontalGap

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        val rows = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
        )
        for (digits in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(colGap)) {
                for (digit in digits) {
                    SpringKeypadKey(
                        label = digit.toString(),
                        contentDescription = "Digit $digit",
                        enabled = enabled,
                        size = buttonSize,
                        tag = "applock-key-$digit",
                        onClick = { onDigit(digit) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(colGap)) {
            SpringKeypadActionKey(
                icon = Icons.Outlined.Fingerprint,
                contentDescription = "Unlock with biometric",
                enabled = canUseBiometric,
                size = buttonSize,
                tag = "applock-key-biometric",
                onClick = onBiometric,
            )
            SpringKeypadKey(
                label = "0",
                contentDescription = "Digit 0",
                enabled = enabled,
                size = buttonSize,
                tag = "applock-key-0",
                onClick = { onDigit('0') },
            )
            SpringKeypadActionKey(
                icon = Icons.AutoMirrored.Outlined.Backspace,
                contentDescription = "Delete last digit",
                enabled = canBackspace,
                size = buttonSize,
                tag = "applock-key-backspace",
                onClick = onBackspace,
            )
        }
    }
}

@Composable
private fun SpringKeypadKey(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    tag: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reducedMotion) AppLockTokens.KEY_PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = AppLockTokens.KEY_SPRING_DAMPING,
            stiffness = AppLockTokens.KEY_SPRING_STIFFNESS,
        ),
        label = "key-scale",
    )

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(AppLockTokens.KeyShape)
            .background(if (enabled) GlassPalette.Well else GlassPalette.HudTileDisabled)
            .border(
                width = Tokens.Hairline,
                color = if (enabled) GlassPalette.WellEdge else GlassPalette.HudTileDisabledBorder,
                shape = AppLockTokens.KeyShape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpringKeypadActionKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    tag: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reducedMotion) AppLockTokens.KEY_PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = AppLockTokens.KEY_SPRING_DAMPING,
            stiffness = AppLockTokens.KEY_SPRING_STIFFNESS,
        ),
        label = "action-key-scale",
    )

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(AppLockTokens.KeyShape)
            .background(if (enabled) GlassPalette.Well else GlassPalette.HudTileDisabled)
            .border(
                width = Tokens.Hairline,
                color = if (enabled) GlassPalette.WellEdge else GlassPalette.HudTileDisabledBorder,
                shape = AppLockTokens.KeyShape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.Icon),
        )
    }
}
