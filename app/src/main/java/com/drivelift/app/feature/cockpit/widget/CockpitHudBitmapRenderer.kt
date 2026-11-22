package com.drivelift.app.feature.cockpit.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.drivelift.app.ui.GlassPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared Canvas-to-Bitmap renderer powering both the Home Screen `CockpitGlanceWidget` and the
 * 1:1 in-app Bento HUD preview in `CockpitSetupScreen`. Pre-renders smoked obsidian glass shells,
 * 270-degree neon telemetry arc gauges, sculptural ON/OFF control plates, and app halo badges so
 * Glance RemoteViews and Compose preview render identically.
 */
object CockpitHudBitmapRenderer {

    private const val MAX_SYSTEM_WIDGET_RADIUS_DP = 28f
    private const val ARC_START_DEG = 135f
    private const val ARC_SWEEP_MAX_DEG = 270f

    fun batteryArcColor(percent: Int): Color = when {
        percent > 30 -> GlassPalette.HudEmerald
        percent >= 15 -> GlassPalette.HudAmber
        else -> GlassPalette.HudCoral
    }

    fun renderShellBitmap(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float = 26f,
        density: Float = 2.5f,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(120)
        val h = heightPx.coerceAtLeast(80)
        val radiusPx = cornerRadiusDp.coerceAtMost(MAX_SYSTEM_WIDGET_RADIUS_DP) * density
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // Base smoked obsidian gradient shell (~82% alpha).
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                GlassPalette.HudShellFill.toArgb(),
                GlassPalette.HudShellDeep.toArgb(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, basePaint)

        // Upper-left radial aurora bloom inside the obsidian shell.
        val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        canvas.drawRoundRect(rect, radiusPx, radiusPx, auroraPaint)

        // 1dp diagonal specular rim stroke (#40FFFFFF -> #0DFFFFFF).
        val strokePx = 1f * density
        val rimRect = RectF(
            strokePx * 0.5f,
            strokePx * 0.5f,
            w - strokePx * 0.5f,
            h - strokePx * 0.5f,
        )
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            shader = LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                GlassPalette.HudRimTop.toArgb(),
                GlassPalette.HudRimBottom.toArgb(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rimRect, radiusPx, radiusPx, rimPaint)
        return bitmap
    }

    fun renderArcGaugeBitmap(
        sizePx: Int,
        fraction: Float,
        valueText: String,
        subLabel: String,
        accentColor: Color,
        density: Float = 2.5f,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(48)
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val strokePx = (5f * density).coerceIn(4f, size * 0.12f)
        val pad = strokePx * 1.4f
        val oval = RectF(pad, pad, size - pad, size - pad)
        val cx = size * 0.5f
        val cy = size * 0.5f

        // Dark recessed circular well.
        val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = GlassPalette.HudTrackRecessed.toArgb()
        }
        canvas.drawCircle(cx, cy, (size * 0.5f) - strokePx * 0.45f, wellPaint)

        // Outer dim concentric border on the well.
        val wellEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
            color = GlassPalette.HudRimBottom.toArgb()
        }
        canvas.drawCircle(cx, cy, (size * 0.5f) - strokePx * 0.5f, wellEdgePaint)

        // 270-degree recessed track.
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            color = GlassPalette.HudTrackRing.toArgb()
        }
        canvas.drawArc(oval, ARC_START_DEG, ARC_SWEEP_MAX_DEG, false, trackPaint)

        val sweep = ARC_SWEEP_MAX_DEG * clampedFraction
        if (sweep > 0.5f) {
            val accentArgb = accentColor.toArgb()
            val haloArgb = accentColor.copy(alpha = 0.24f).toArgb()
            val softBloomArgb = accentColor.copy(alpha = 0.45f).toArgb()

            val outerBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx * 2.1f
                strokeCap = Paint.Cap.ROUND
                color = haloArgb
            }
            canvas.drawArc(oval, ARC_START_DEG, sweep, false, outerBloomPaint)

            val midBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx * 1.35f
                strokeCap = Paint.Cap.ROUND
                color = softBloomArgb
            }
            canvas.drawArc(oval, ARC_START_DEG, sweep, false, midBloomPaint)

            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeCap = Paint.Cap.ROUND
                color = accentArgb
            }
            canvas.drawArc(oval, ARC_START_DEG, sweep, false, arcPaint)

            // Glowing tip highlight dot on the active arc end.
            val endAngleRad = Math.toRadians((ARC_START_DEG + sweep).toDouble())
            val arcRadius = oval.width() * 0.5f
            val tipX = (cx + arcRadius * cos(endAngleRad)).toFloat()
            val tipY = (cy + arcRadius * sin(endAngleRad)).toFloat()
            val tipGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = GlassPalette.HudGaugeTextPrimary.copy(alpha = 0.9f).toArgb()
            }
            canvas.drawCircle(tipX, tipY, strokePx * 0.34f, tipGlow)
        }

        // Centered metric value (tabular figures) + compact bottom label.
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GlassPalette.HudGaugeTextPrimary.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = size * 0.23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        val valueFm = valuePaint.fontMetrics
        val valueBaseline = cy - (valueFm.ascent + valueFm.descent) * 0.45f - size * 0.04f
        canvas.drawText(valueText, cx, valueBaseline, valuePaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = size * 0.145f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        canvas.drawText(subLabel, cx, cy + size * 0.25f, labelPaint)
        return bitmap
    }

    fun renderControlTileBitmap(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float = 16f,
        active: Boolean,
        accentColor: Color,
        density: Float = 2.5f,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(64)
        val h = heightPx.coerceAtLeast(36)
        val radiusPx = cornerRadiusDp.coerceAtMost(24f) * density
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (active) {
                accentColor.copy(alpha = 0.24f).toArgb()
            } else {
                GlassPalette.HudTileRecessed.toArgb()
            }
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)

        if (active) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = RadialGradient(
                    w * 0.5f,
                    h * 0.45f,
                    maxOf(w, h) * 0.65f,
                    intArrayOf(
                        accentColor.copy(alpha = 0.34f).toArgb(),
                        android.graphics.Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(rect, radiusPx, radiusPx, glowPaint)
        }

        val strokePx = if (active) 1.5f * density else 1f * density
        val strokeRect = RectF(
            strokePx * 0.5f,
            strokePx * 0.5f,
            w - strokePx * 0.5f,
            h - strokePx * 0.5f,
        )
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            if (active) {
                color = accentColor.copy(alpha = 0.92f).toArgb()
            } else {
                shader = LinearGradient(
                    0f,
                    0f,
                    w.toFloat(),
                    h.toFloat(),
                    GlassPalette.HudTrackRing.toArgb(),
                    GlassPalette.HudRimBottom.toArgb(),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        canvas.drawRoundRect(strokeRect, radiusPx, radiusPx, borderPaint)
        return bitmap
    }

    fun renderStatusPillBitmap(
        widthPx: Int,
        heightPx: Int,
        active: Boolean,
        accentColor: Color,
        density: Float = 2.5f,
    ): Bitmap = renderControlTileBitmap(
        widthPx = widthPx,
        heightPx = heightPx,
        cornerRadiusDp = 14f,
        active = active,
        accentColor = accentColor,
        density = density,
    )

    fun renderDockTrayBitmap(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float = 18f,
        density: Float = 2.5f,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(80)
        val h = heightPx.coerceAtLeast(36)
        val radiusPx = cornerRadiusDp.coerceAtMost(24f) * density
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        val trayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = GlassPalette.HudDockFill.toArgb()
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, trayPaint)

        val strokePx = 1f * density
        val rimRect = RectF(
            strokePx * 0.5f,
            strokePx * 0.5f,
            w - strokePx * 0.5f,
            h - strokePx * 0.5f,
        )
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = GlassPalette.HudTrackRing.toArgb()
        }
        canvas.drawRoundRect(rimRect, radiusPx, radiusPx, rimPaint)
        return bitmap
    }

    fun renderAppHaloBitmap(
        sizePx: Int,
        appIcon: Bitmap?,
        accentColor: Color = GlassPalette.HudElectricCyan,
        density: Float = 2.5f,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size * 0.5f
        val cy = size * 0.5f
        val outerR = size * 0.48f

        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                cx,
                cy,
                outerR,
                intArrayOf(
                    accentColor.copy(alpha = 0.24f).toArgb(),
                    GlassPalette.HudTileRecessed.toArgb(),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, outerR, haloPaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = GlassPalette.HudRimTop.toArgb()
        }
        canvas.drawCircle(cx, cy, outerR - 0.5f * density, rimPaint)

        if (appIcon != null && !appIcon.isRecycled) {
            val iconMargin = size * 0.2f
            val dst = RectF(iconMargin, iconMargin, size - iconMargin, size - iconMargin)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(appIcon, null, dst, bmpPaint)
        }
        return bitmap
    }
}
