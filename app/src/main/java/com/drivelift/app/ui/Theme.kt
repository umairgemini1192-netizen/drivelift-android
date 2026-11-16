package com.drivelift.app.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * The single source of colour for the app. Warm neutrals carry the weight,
 * one green accent marks the primary action, and red (error) is used only
 * for removal. Screens read roles from MaterialTheme, never raw values.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF1F5F4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6EBE1),
    onPrimaryContainer = Color(0xFF0C2A20),
    secondary = Color(0xFF55524C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E2DA),
    onSecondaryContainer = Color(0xFF1C1B19),
    tertiary = Color(0xFF1F5F4A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF8F4),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFAF8F4),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFECE8E1),
    onSurfaceVariant = Color(0xFF55524C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2EC),
    surfaceContainer = Color(0xFFF0ECE5),
    surfaceContainerHigh = Color(0xFFEAE6DE),
    surfaceContainerHighest = Color(0xFFE4E0D8),
    outline = Color(0xFF7A766E),
    outlineVariant = Color(0xFFD9D4CB),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD1B6),
    onPrimary = Color(0xFF0C2A20),
    primaryContainer = Color(0xFF1F4A3B),
    onPrimaryContainer = Color(0xFFD6EBE1),
    secondary = Color(0xFFBDB8AF),
    onSecondary = Color(0xFF1C1B19),
    secondaryContainer = Color(0xFF3A3733),
    onSecondaryContainer = Color(0xFFECE8E1),
    tertiary = Color(0xFF8FD1B6),
    onTertiary = Color(0xFF0C2A20),
    background = Color(0xFF141311),
    onBackground = Color(0xFFECE8E1),
    surface = Color(0xFF141311),
    onSurface = Color(0xFFECE8E1),
    surfaceVariant = Color(0xFF2A2825),
    onSurfaceVariant = Color(0xFFBDB8AF),
    surfaceContainerLowest = Color(0xFF0F0E0D),
    surfaceContainerLow = Color(0xFF1B1A18),
    surfaceContainer = Color(0xFF201E1C),
    surfaceContainerHigh = Color(0xFF2A2825),
    surfaceContainerHighest = Color(0xFF353230),
    outline = Color(0xFF948F86),
    outlineVariant = Color(0xFF3A3733),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        // The one lead figure on a screen: well over 2.5x the 16sp body.
        displayLarge = TextStyle(fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
        displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun DriveLiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "under a minute"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
