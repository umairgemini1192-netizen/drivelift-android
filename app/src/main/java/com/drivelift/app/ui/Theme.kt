package com.drivelift.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * The single source of colour for the app, and the only file that holds raw colour values.
 *
 * One theme, always dark: a deep indigo night sky that warms to violet, with translucent glass
 * panels floating on it. Coral is the one accent and marks the primary action. Rose red (error)
 * is used only for removal and failure. Violet and sky blue are atmosphere: the backdrop and the
 * progress gauge, never an action.
 *
 * Screens read roles from MaterialTheme.colorScheme and from GlassPalette. Sheets, dialogs and
 * menus use the solid surfaceContainer roles so text on them stays readable.
 */
private val Night = darkColorScheme(
    primary = Color(0xFFFF8F70),
    onPrimary = Color(0xFF2B0F06),
    primaryContainer = Color(0xFF5A2A22),
    onPrimaryContainer = Color(0xFFFFDACE),
    secondary = Color(0xFFA596FF),
    onSecondary = Color(0xFF140C40),
    secondaryContainer = Color(0xFF2F2876),
    onSecondaryContainer = Color(0xFFE5E0FF),
    tertiary = Color(0xFF62C6FF),
    onTertiary = Color(0xFF04263A),
    tertiaryContainer = Color(0xFF123E5C),
    onTertiaryContainer = Color(0xFFCDEBFF),
    background = Color(0xFF080B22),
    onBackground = Color(0xFFF3F1FF),
    surface = Color(0xFF0D1030),
    onSurface = Color(0xFFF3F1FF),
    surfaceVariant = Color(0xFF23265C),
    onSurfaceVariant = Color(0xFFBDB9DE),
    surfaceContainerLowest = Color(0xFF0A0D28),
    surfaceContainerLow = Color(0xFF14173F),
    surfaceContainer = Color(0xFF191C4A),
    surfaceContainerHigh = Color(0xFF202358),
    surfaceContainerHighest = Color(0xFF282B66),
    inverseSurface = Color(0xFFECEAFF),
    inverseOnSurface = Color(0xFF14163A),
    inversePrimary = Color(0xFFA5452B),
    outline = Color(0xFF8985B5),
    outlineVariant = Color(0xFF373A72),
    scrim = Color(0xFF000000),
    error = Color(0xFFFF6482),
    onError = Color(0xFF3B0614),
    errorContainer = Color(0xFF5E1429),
    onErrorContainer = Color(0xFFFFD9E0),
)

/** Colours of the backdrop and of glass itself. Used only by Glass.kt. */
object GlassPalette {
    // Backdrop: night sky at the top, violet glow rising from the bottom.
    val SkyTop = Color(0xFF070A1E)
    val SkyMid = Color(0xFF15114A)
    val SkyBottom = Color(0xFF45228C)
    val GlowBlue = Color(0x5527348F)
    val GlowViolet = Color(0x668A45D8)

    // Glass: a faint white film, brighter along the top edge where light would catch it.
    val FillTop = Color(0x24FFFFFF)
    val FillBottom = Color(0x0DFFFFFF)
    val EdgeTop = Color(0x52FFFFFF)
    val EdgeBottom = Color(0x14FFFFFF)

    // Bars sit over scrolling content, so they are denser than panels.
    val BarTop = Color(0xD91B1D52)
    val BarBottom = Color(0xE6121438)

    /** An unlit dot of the progress gauge, and the track of a progress bar. */
    val Dim = Color(0x24FFFFFF)

    /** Placeholder behind a thumbnail that has not loaded. */
    val Thumb = Color(0x1AFFFFFF)

    /** Bottom gradient scrim over media tiles so white captions remain legible. */
    val ThumbScrim = Color(0xCC070A1E)
}

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
    val resolver = LocalContext.current.contentResolver
    // The phone's "Remove animations" setting reports an animator scale of zero.
    val reducedMotion = remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(colorScheme = Night, typography = AppTypography, content = content)
    }
}

/** A size such as "72 MB", with a non-breaking space so the unit never wraps away from the number. */
fun formatBytes(context: Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes).replace(' ', '\u00A0')

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

/** Human-readable app version and numeric build code for diagnostics and preview verification. */
fun appVersionLabel(context: Context): String {
    val info = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()
    val name = info?.versionName?.takeIf { it.isNotBlank() } ?: "1.0.0-preview.10"
    val code = info?.longVersionCode ?: 9L
    return "DriveLift $name (build $code)"
}
