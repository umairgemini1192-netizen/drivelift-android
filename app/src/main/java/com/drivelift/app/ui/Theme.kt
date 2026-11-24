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
import com.drivelift.app.BuildConfig

/*
 * The single source of colour for the app, and the only file that holds raw colour values.
 *
 * One theme, always dark: a deep indigo night sky that warms to violet, with deep navy/charcoal
 * translucent glass surfaces floating on it.
 * - Primary: #00D4FF electric cyan for primary actions and lead highlights.
 * - Secondary: #A78BFA soft violet for secondary accents and atmosphere.
 * - Live / Success: #34F5A0 neon green (tertiary) for live, charging, verified, and success states.
 * - Error / Destructive: #FF4D6D red outline + glow strictly for stop and destructive actions.
 *
 * Screens read roles from MaterialTheme.colorScheme and from GlassPalette. Sheets, dialogs and
 * menus use the solid surfaceContainer roles so text on them stays readable.
 */
private val Night = darkColorScheme(
    primary = Color(0xFF00D4FF),
    onPrimary = Color(0xFF002028),
    primaryContainer = Color(0xFF003B4D),
    onPrimaryContainer = Color(0xFFBDF4FF),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1A0E3D),
    secondaryContainer = Color(0xFF332366),
    onSecondaryContainer = Color(0xFFEBE4FF),
    tertiary = Color(0xFF34F5A0),
    onTertiary = Color(0xFF002916),
    tertiaryContainer = Color(0xFF094228),
    onTertiaryContainer = Color(0xFFC2FFE2),
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
    inversePrimary = Color(0xFF006780),
    outline = Color(0xFF8985B5),
    outlineVariant = Color(0xFF373A72),
    scrim = Color(0xFF000000),
    error = Color(0xFFFF4D6D),
    onError = Color(0xFF3B0614),
    errorContainer = Color(0xFF5E1429),
    onErrorContainer = Color(0xFFFFD9E0),
)

/** Colours of the backdrop, unified GlassSurface levels, and semantic HUD accents. */
object GlassPalette {
    // Backdrop: night sky at the top, violet glow rising from the bottom.
    val SkyTop = Color(0xFF070A1E)
    val SkyMid = Color(0xFF15114A)
    val SkyBottom = Color(0xFF45228C)
    val GlowBlue = Color(0x5527348F)
    val GlowViolet = Color(0x668A45D8)

    // Unified semantic accents.
    val PrimaryCyan = Color(0xFF00D4FF)
    val SecondaryViolet = Color(0xFFA78BFA)
    val SuccessGreen = Color(0xFF34F5A0)
    val DestructiveRed = Color(0xFFFF4D6D)
    val DestructiveGlow = Color(0x4DFF4D6D)

    // Unified GlassSurface deep navy/charcoal fills (70-85% opacity on API 31+, denser on API 30).
    val PanelFillTop = Color(0xB80E1428)
    val PanelFillBottom = Color(0xC7090D1C)
    val PanelFallbackTop = Color(0xD60E1428)
    val PanelFallbackBottom = Color(0xE0090D1C)

    val BarFillTop = Color(0xCC10162E)
    val BarFillBottom = Color(0xD80A0E1F)
    val BarFallbackTop = Color(0xE610162E)
    val BarFallbackBottom = Color(0xF00A0E1F)

    val IslandFillTop = Color(0xD4070B16)
    val IslandFillBottom = Color(0xD8050811)
    val IslandFallbackTop = Color(0xEB070B16)
    val IslandFallbackBottom = Color(0xF5050811)

    val HeroFillTop = Color(0xBF121936)
    val HeroFillBottom = Color(0xD10B1024)
    val HeroFallbackTop = Color(0xDB121936)
    val HeroFallbackBottom = Color(0xE80B1024)

    // Top-left lit specular glass border and highlight catch.
    val BorderTopLeft = Color(0x5CFFFFFF)
    val BorderMid = Color(0x1FFFFFFF)
    val BorderBottomRight = Color(0x0DFFFFFF)
    val HeroBorderTopLeft = Color(0x7A00D4FF)
    val TopLeftHighlight = Color(0x28FFFFFF)
    val TopLeftCyanHighlight = Color(0x2600D4FF)

    // Shared fill and edge aliases kept in sync with Panel / Bar levels.
    val FillTop = PanelFillTop
    val FillBottom = PanelFillBottom
    val EdgeTop = BorderTopLeft
    val EdgeBottom = BorderBottomRight
    val BarTop = BarFillTop
    val BarBottom = BarFillBottom

    /** An unlit dot of the progress gauge, and the track of a progress bar. */
    val Dim = Color(0x24FFFFFF)

    /**
     * Flat control wells and pills inside a glass surface (rule 3): a plain tint and a thin edge,
     * no glass effect of their own.
     */
    val Well = Color(0x1FFFFFFF)
    val WellEdge = Color(0x33FFFFFF)

    /** Placeholder behind a thumbnail that has not loaded. */
    val Thumb = Color(0x1AFFFFFF)

    /** Bottom gradient scrim over media tiles so white captions remain legible. */
    val ThumbScrim = Color(0xCC070A1E)

    /** Pure OLED black surface for immersive video playback with zero purple/navy bars. */
    val OledBlack = Color(0xFF000000)

    /** Dark top gradient scrim over full-screen video so header icons stay readable on bright frames. */
    val VideoHeaderScrimTop = Color(0xE6000000)
    val VideoHeaderScrimMid = Color(0x99000000)
    val VideoHeaderScrimBottom = Color(0x00000000)

    // Futuristic Cyber-Glass Bento HUD tokens (shared between GlassWidgetStyle and in-app parity preview).
    val HudShellFill = PanelFillTop
    val HudShellDeep = IslandFillBottom
    val HudAuroraCyan = Color(0x3800D4FF)
    val HudAuroraViolet = Color(0x2EA78BFA)
    val HudRimTop = BorderTopLeft
    val HudRimBottom = BorderBottomRight
    val HudTrackRecessed = Color(0x66050811)
    val HudTrackRing = Color(0x26FFFFFF)
    val HudTileRecessed = Color(0xA6101628)
    val HudDockFill = Color(0xBF070B14)
    val HudEmerald = SuccessGreen
    val HudAmber = Color(0xFFFBBF24)
    val HudCoral = DestructiveRed
    val HudRoseLow = DestructiveRed
    val HudElectricCyan = PrimaryCyan
    val HudVioletBlue = SecondaryViolet
    val HudTileDisabled = Color(0x590B101C)
    val HudTileDisabledBorder = Color(0x22FFFFFF)
    val HudStatusPillBg = Color(0xEB070A14)
    val HudAmberGlow = Color(0x4DFBBF24)
    val HudEmeraldGlow = Color(0x4D34F5A0)
    val HudShimmerBase = Color(0x18FFFFFF)
    val HudShimmerHighlight = Color(0x38FFFFFF)
    val HudGaugeTextPrimary = Color(0xFFF3F1FF)
    val HudGaugeLabelMuted = Color(0xFFBDB9DE)
}

/**
 * Dynamic Island live activity colours (call, screen recording, navigation). The island sits on a
 * pure OLED black surface so it melts into the camera cutout, aligned with the unified palette.
 */
object CockpitLivePalette {
    val Oled = Color(0xFF000000)
    val Decline = Color(0xFFFF4D6D)
    val Answer = Color(0xFF34F5A0)
    val Charcoal = Color(0xFF2C2C2E)
    val OnLight = Color(0xFF1C1C1E)
    val Emerald = Color(0xFF34F5A0)
    val Cyan = Color(0xFF00D4FF)
    val Recording = Color(0xFFFF4D6D)
    val White = Color(0xFFFFFFFF)
}

/**
 * Tabular digits (rule 11 in docs/AI_CONTEXT.md): every digit is the same width, so times,
 * speeds, sizes, percentages and counters do not shift sideways as they change. Set once on
 * every text style, so no screen has to remember it. Letters are unaffected.
 */
internal const val TABULAR_DIGITS = "tnum"

private fun TextStyle.tabular() = copy(fontFeatureSettings = TABULAR_DIGITS)

internal val AppTypography = Typography().let { base ->
    val styled = base.copy(
        // The one lead figure on a screen: well over 2.5x the 16sp body.
        displayLarge = TextStyle(fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
        displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    styled.copy(
        displayLarge = styled.displayLarge.tabular(),
        displayMedium = styled.displayMedium.tabular(),
        displaySmall = styled.displaySmall.tabular(),
        headlineLarge = styled.headlineLarge.tabular(),
        headlineMedium = styled.headlineMedium.tabular(),
        headlineSmall = styled.headlineSmall.tabular(),
        titleLarge = styled.titleLarge.tabular(),
        titleMedium = styled.titleMedium.tabular(),
        titleSmall = styled.titleSmall.tabular(),
        bodyLarge = styled.bodyLarge.tabular(),
        bodyMedium = styled.bodyMedium.tabular(),
        bodySmall = styled.bodySmall.tabular(),
        labelLarge = styled.labelLarge.tabular(),
        labelMedium = styled.labelMedium.tabular(),
        labelSmall = styled.labelSmall.tabular(),
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
fun appVersionLabel(context: Context, buildStamp: String = BuildConfig.BUILD_STAMP): String {
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
    val cleanStamp = buildStamp.trim()
    val suffix = if (cleanStamp.isNotEmpty()) " \u00B7 $cleanStamp" else ""
    return "DriveLift $name (build $code$suffix)"
}
