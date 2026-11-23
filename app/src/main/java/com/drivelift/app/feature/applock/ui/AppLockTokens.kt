package com.drivelift.app.feature.applock.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Dimensions, shapes, and motion constants for Phase 5 App Lock Gate and App Lock Manager.
 */
object AppLockTokens {
    val GateCardShape = RoundedCornerShape(28.dp)
    val StatusCardShape = RoundedCornerShape(22.dp)
    val KeyShape = CircleShape
    val ChipShape = RoundedCornerShape(percent = 50)

    val GateMaxWidth = 380.dp
    val GateShieldSize = 56.dp
    val GateShieldIconSize = 28.dp
    val PinDotSize = 14.dp
    val PinDotGap = 14.dp
    val KeypadButtonSize = 68.dp
    val KeypadButtonCompactSize = 58.dp
    val KeypadHorizontalGap = 18.dp
    val KeypadVerticalGap = 12.dp
    val KeypadCompactGap = 10.dp
    val AppIconBadgeSize = 40.dp
    val StatusDotSize = 8.dp
    val ShakeDistance = 14.dp
    val BackdropBlurRadiusPx = 32f

    const val KEY_PRESS_SCALE = 0.91f
    const val KEY_SPRING_DAMPING = 0.62f
    const val KEY_SPRING_STIFFNESS = 520f
    const val SHAKE_STEP_MS = 55
    const val LOCKOUT_TICK_MS = 1_000L
}
