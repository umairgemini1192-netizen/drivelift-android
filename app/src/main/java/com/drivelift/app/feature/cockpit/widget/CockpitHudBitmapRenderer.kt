package com.drivelift.app.feature.cockpit.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.drivelift.app.ui.GlassPalette
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared Canvas-to-Bitmap renderer powering both the Home Screen `CockpitGlanceWidget` and the
 * 1:1 in-app Bento HUD preview in `CockpitSetupScreen`. Pre-renders smoked obsidian glass shells,
 * 270-degree neon telemetry arc gauges (charging halo + lightning bolt, `<= 20%` low warning,
 * and `Used / Total` RAM context), sculptural ON/OFF/disabled control plates, and app halo badges.
 */
object CockpitHudBitmapRenderer {

    private const val MAX_SYSTEM_WIDGET_RADIUS_DP = 28f
    private const val ARC_START_DEG = 135f
    private const val ARC_SWEEP_MAX_DEG = 270f
    private const val LOW_BATTERY_THRESHOLD = 20
    private const val AMBER_BATTERY_THRESHOLD = 30
    private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    fun batteryArcColor(percent: Int, isCharging: Boolean = false): Color = when {
        isCharging -> GlassPalette.HudEmerald
        percent <= LOW_BATTERY_THRESHOLD -> GlassPalette.HudRoseLow
        percent <= AMBER_BATTERY_THRESHOLD -> GlassPalette.HudAmber
        else -> GlassPalette.HudEmerald
    }

    fun batterySubLabel(percent: Int, isCharging: Boolean = false): String = when {
        isCharging -> "CHRG"
        percent in 0..LOW_BATTERY_THRESHOLD -> "LOW"
        else -> "BATT"
    }

    fun formatRamHudMetric(availBytes: Long, totalBytes: Long): RamHudMetric {
        if (totalBytes <= 0L) {
            return RamHudMetric(
                freeGb = 0.0,
                usedGb = 0.0,
                totalGb = 0.0,
                fraction = 0f,
                valueText = "--",
                label = "RAM",
            )
        }
        val safeAvail = availBytes.coerceIn(0L, totalBytes)
        val totalGb = totalBytes / BYTES_PER_GB
        val freeGb = safeAvail / BYTES_PER_GB
        val usedGb = (totalGb - freeGb).coerceAtLeast(0.0)
        val fraction = (usedGb / totalGb).toFloat().coerceIn(0.05f, 1f)
        val totalRounded = totalGb.roundToInt().coerceAtLeast(1)
        val valueText = "%.1f/%dG".format(Locale.US, usedGb, totalRounded)
        val subLabel = "%.1fG FREE".format(Locale.US, freeGb)
        return RamHudMetric(
            freeGb = freeGb,
            usedGb = usedGb,
            totalGb = totalGb,
            fraction = fraction,
            valueText = valueText,
            label = subLabel,
        )
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
        isCharging: Boolean = false,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(48)
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val strokePx = (4.6f * density).coerceIn(4f, size * 0.11f)
        val pad = strokePx * 1.45f
        val oval = RectF(pad, pad, size - pad, size - pad)
        val cx = size * 0.5f
        val cy = size * 0.5f

        // Dark recessed circular well.
        val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = GlassPalette.HudTrackRecessed.toArgb()
        }
        canvas.drawCircle(cx, cy, (size * 0.5f) - strokePx * 0.45f, wellPaint)

        // Glowing outer emerald charging halo ring when plugged in.
        if (isCharging) {
            val outerAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx * 1.15f
                color = GlassPalette.HudEmerald.copy(alpha = 0.28f).toArgb()
            }
            canvas.drawCircle(cx, cy, (size * 0.5f) - strokePx * 0.42f, outerAuraPaint)

            val haloRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * density
                color = GlassPalette.HudEmerald.copy(alpha = 0.85f).toArgb()
            }
            canvas.drawCircle(cx, cy, (size * 0.5f) - 1.1f * density, haloRingPaint)
        } else {
            // Outer dim concentric border on the well.
            val wellEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.8f * density
                color = GlassPalette.HudRimBottom.toArgb()
            }
            canvas.drawCircle(cx, cy, (size * 0.5f) - strokePx * 0.5f, wellEdgePaint)
        }

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
                color = GlassPalette.HudGaugeTextPrimary.copy(alpha = 0.92f).toArgb()
            }
            canvas.drawCircle(tipX, tipY, strokePx * 0.34f, tipGlow)
        }

        // Crisp lightning bolt icon inside the upper-center when charging.
        if (isCharging) {
            drawChargingBolt(canvas = canvas, cx = cx, cy = cy - size * 0.18f, boltHeight = size * 0.17f)
        }

        // Centered metric value (tabular figures) scaled cleanly for longer strings like "10.5/12G".
        val maxTextWidth = oval.width() * 0.72f
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GlassPalette.HudGaugeTextPrimary.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = if (isCharging) size * 0.20f else size * 0.225f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        val measuredVal = valuePaint.measureText(valueText)
        if (measuredVal > maxTextWidth && measuredVal > 0f) {
            valuePaint.textSize = (valuePaint.textSize * (maxTextWidth / measuredVal))
                .coerceAtLeast(size * 0.135f)
        }
        val valueFm = valuePaint.fontMetrics
        val valueBaseline = if (isCharging) {
            cy - (valueFm.ascent + valueFm.descent) * 0.45f + size * 0.04f
        } else {
            cy - (valueFm.ascent + valueFm.descent) * 0.45f - size * 0.04f
        }
        canvas.drawText(valueText, cx, valueBaseline, valuePaint)

        // Sub-label ("CHRG", "LOW", "BATT", or "1.5G FREE") inside bottom arc gap.
        // Kept narrower and higher than the arc's end caps (which sit near 0.27 to 0.33 of the size
        // below centre) so a long label such as "1.8G FREE" never touches the bottom of the ring.
        val labelMaxWidth = size * 0.36f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = size * 0.125f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            letterSpacing = 0.02f
        }
        val measuredLabel = labelPaint.measureText(subLabel)
        if (measuredLabel > labelMaxWidth && measuredLabel > 0f) {
            labelPaint.textSize = (labelPaint.textSize * (labelMaxWidth / measuredLabel))
                .coerceAtLeast(size * 0.082f)
        }
        canvas.drawText(subLabel, cx, cy + size * 0.285f, labelPaint)
        return bitmap
    }

    private fun drawChargingBolt(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        boltHeight: Float,
    ) {
        val halfH = boltHeight * 0.5f
        val halfW = boltHeight * 0.36f
        val path = Path().apply {
            moveTo(cx + halfW * 0.18f, cy - halfH)
            lineTo(cx - halfW, cy + halfH * 0.08f)
            lineTo(cx - halfW * 0.08f, cy + halfH * 0.08f)
            lineTo(cx - halfW * 0.22f, cy + halfH)
            lineTo(cx + halfW, cy - halfH * 0.10f)
            lineTo(cx + halfW * 0.08f, cy - halfH * 0.10f)
            close()
        }
        val boltGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (boltHeight * 0.18f).coerceAtLeast(1.5f)
            strokeJoin = Paint.Join.ROUND
            color = GlassPalette.HudEmerald.copy(alpha = 0.45f).toArgb()
        }
        canvas.drawPath(path, boltGlowPaint)

        val boltFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = GlassPalette.HudEmerald.toArgb()
        }
        canvas.drawPath(path, boltFillPaint)
    }

    fun renderControlTileBitmap(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusDp: Float = 16f,
        active: Boolean,
        accentColor: Color,
        density: Float = 2.5f,
        enabled: Boolean = true,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(64)
        val h = heightPx.coerceAtLeast(36)
        val radiusPx = cornerRadiusDp.coerceAtMost(24f) * density
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = when {
                !enabled -> GlassPalette.HudTileDisabled.toArgb()
                active -> accentColor.copy(alpha = 0.24f).toArgb()
                else -> GlassPalette.HudTileRecessed.toArgb()
            }
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)

        if (enabled && active) {
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

        val strokePx = if (enabled && active) 1.5f * density else 1f * density
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
                active -> color = accentColor.copy(alpha = 0.92f).toArgb()
                else -> shader = LinearGradient(
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
    ): Bitmap {
        val w = widthPx.coerceAtLeast(64)
        val h = heightPx.coerceAtLeast(24)
        val radiusPx = (14f * density).coerceAtMost(h * 0.5f)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // High-contrast dark obsidian backing keeps status badges sharp on light & dark wallpapers.
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = GlassPalette.HudStatusPillBg.toArgb()
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, basePaint)

        if (active) {
            val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = accentColor.copy(alpha = 0.22f).toArgb()
            }
            canvas.drawRoundRect(rect, radiusPx, radiusPx, tintPaint)
        }

        val strokePx = 1.2f * density
        val strokeRect = RectF(
            strokePx * 0.5f,
            strokePx * 0.5f,
            w - strokePx * 0.5f,
            h - strokePx * 0.5f,
        )
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = accentColor.copy(alpha = if (active) 0.9f else 0.7f).toArgb()
        }
        canvas.drawRoundRect(strokeRect, radiusPx, radiusPx, borderPaint)
        return bitmap
    }

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
