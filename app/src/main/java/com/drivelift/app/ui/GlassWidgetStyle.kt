package com.drivelift.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.background
import com.drivelift.app.R

/**
 * Surface tiers for Glance RemoteViews widgets matching [GlassSurface] in Compose.
 */
enum class GlassWidgetLevel {
    Shell,
    Tile,
    Dock,
    StatusPill,
}

/**
 * Shared Glance RemoteViews glass styling matching [GlassSurface]:
 * - Deep navy/charcoal translucent gradient (70-85% opacity)
 * - Soft top-left specular highlight
 * - 1-1.5 dp top-left lit hairline border
 */
object GlassWidgetStyle {

    private const val MAX_SYSTEM_WIDGET_RADIUS_DP = 28f

    /**
     * Renders a translucent glass surface bitmap for Glance RemoteViews or 1:1 Compose parity
     * widget previews using the unified [GlassPalette] tokens.
     */
    fun renderSurfaceBitmap(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float,
        density: Float = 2.5f,
        level: GlassWidgetLevel = GlassWidgetLevel.Shell,
        accentColor: Color? = null,
        active: Boolean = false,
        enabled: Boolean = true,
    ): Bitmap {
        val minW = if (level == GlassWidgetLevel.Shell) 120 else 64
        val minH = if (level == GlassWidgetLevel.Shell) 80 else 24
        val w = widthPx.coerceAtLeast(minW)
        val h = heightPx.coerceAtLeast(minH)
        val radiusPx = (cornerRadiusDp.coerceAtMost(MAX_SYSTEM_WIDGET_RADIUS_DP) * density)
            .coerceAtMost(h * 0.5f)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        val (startFill, endFill) = when (level) {
            GlassWidgetLevel.Shell -> GlassPalette.PanelFillTop to GlassPalette.IslandFillBottom
            GlassWidgetLevel.Dock -> GlassPalette.HudDockFill to GlassPalette.IslandFillBottom
            GlassWidgetLevel.StatusPill -> GlassPalette.HudStatusPillBg to GlassPalette.IslandFillBottom
            GlassWidgetLevel.Tile -> when {
                !enabled -> GlassPalette.HudTileDisabled to GlassPalette.HudTileDisabled
                active && accentColor != null -> accentColor.copy(alpha = 0.24f) to GlassPalette.HudTileRecessed
                else -> GlassPalette.HudTileRecessed to GlassPalette.PanelFillBottom
            }
        }

        // Translucent navy/charcoal diagonal gradient.
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                startFill.toArgb(),
                endFill.toArgb(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, basePaint)

        // Top-left specular highlight bloom (plus accent glow on active tiles / status pills).
        when {
            level == GlassWidgetLevel.Shell -> {
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = RadialGradient(
                        w * 0.14f,
                        h * 0.12f,
                        maxOf(w, h) * 0.72f,
                        intArrayOf(
                            GlassPalette.HudAuroraCyan.toArgb(),
                            GlassPalette.HudAuroraViolet.toArgb(),
                            android.graphics.Color.TRANSPARENT,
                        ),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(rect, radiusPx, radiusPx, highlightPaint)
            }
            enabled && active && accentColor != null -> {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = RadialGradient(
                        w * 0.5f,
                        h * 0.45f,
                        maxOf(w, h) * 0.65f,
                        intArrayOf(
                            accentColor.copy(alpha = if (level == GlassWidgetLevel.StatusPill) 0.24f else 0.34f).toArgb(),
                            android.graphics.Color.TRANSPARENT,
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(rect, radiusPx, radiusPx, glowPaint)
            }
            enabled && level != GlassWidgetLevel.StatusPill -> {
                val topLeftSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = RadialGradient(
                        w * 0.12f,
                        h * 0.12f,
                        maxOf(w, h) * 0.58f,
                        intArrayOf(
                            GlassPalette.TopLeftHighlight.toArgb(),
                            android.graphics.Color.TRANSPARENT,
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(rect, radiusPx, radiusPx, topLeftSheen)
            }
        }

        // Hairline 1-1.5dp top-left lit border.
        val strokeDp = when {
            level == GlassWidgetLevel.Tile && enabled && active -> 1.5f
            level == GlassWidgetLevel.StatusPill -> 1.2f
            else -> 1f
        }
        val strokePx = strokeDp * density
        val strokeRect = RectF(
            strokePx * 0.5f,
            strokePx * 0.5f,
            w - strokePx * 0.5f,
            h - strokePx * 0.5f,
        )
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            when {
                !enabled -> color = GlassPalette.HudTileDisabledBorder.toArgb()
                level == GlassWidgetLevel.StatusPill && accentColor != null ->
                    color = accentColor.copy(alpha = if (active) 0.92f else 0.72f).toArgb()
                active && accentColor != null ->
                    color = accentColor.copy(alpha = 0.92f).toArgb()
                else -> shader = LinearGradient(
                    0f,
                    0f,
                    w.toFloat(),
                    h.toFloat(),
                    GlassPalette.BorderTopLeft.toArgb(),
                    GlassPalette.BorderBottomRight.toArgb(),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        canvas.drawRoundRect(strokeRect, radiusPx, radiusPx, borderPaint)
        return bitmap
    }

    /** Applies a pre-rendered [GlassWidgetStyle] bitmap surface to a Glance composable. */
    fun GlanceModifier.glassWidgetSurface(bitmap: Bitmap): GlanceModifier =
        this.background(ImageProvider(bitmap))

    /** Applies a matching XML glass drawable to a Glance composable. */
    fun GlanceModifier.glassWidgetDrawable(
        @DrawableRes drawableRes: Int = R.drawable.cockpit_widget_card_bg,
    ): GlanceModifier = this.background(ImageProvider(drawableRes))
}
