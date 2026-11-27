package com.drivelift.app.feature.cockpit.ui

import android.graphics.Bitmap

/** How a navigation app's turn picture is drawn on the near-black maneuver tile. */
internal enum class ManeuverIconStyle {
    /** Already light or colourful enough to read on the dark tile. */
    AS_IS,

    /** A dark, single-colour arrow on transparent: every opaque pixel is repainted white. */
    WHITE_TINT,

    /** A dark but multi-colour picture: it sits on a light tile instead, so it is never lost on black. */
    LIGHT_BADGE,
}

/**
 * Google Maps ships its turn arrow as a dark glyph on a transparent background. On the OLED black
 * maneuver tile that arrow is nearly invisible, so the picture is classified from its own pixels
 * and drawn white, or on a light badge, whenever it is too dark to read.
 */
internal object ManeuverIconContrast {
    /** Pixels fainter than this alpha are background and are ignored. */
    private const val MIN_OPAQUE_ALPHA = 32
    private const val MIN_OPAQUE_SAMPLES = 8

    /** Opaque pixels with an average relative luminance at or above this already contrast with black. */
    private const val LIGHT_LUMINANCE = 0.45f

    /** Average colour spread (largest minus smallest channel) below this counts as one colour. */
    private const val MONOCHROME_SPREAD = 0.12f

    private const val SAMPLE_EDGE_PX = 24
    private const val CHANNEL_MAX = 255f
    private const val CHANNEL_MASK = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val WEIGHT_RED = 0.2126f
    private const val WEIGHT_GREEN = 0.7152f
    private const val WEIGHT_BLUE = 0.0722f

    fun classify(bitmap: Bitmap): ManeuverIconStyle {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return ManeuverIconStyle.AS_IS
        val sample = if (bitmap.width > SAMPLE_EDGE_PX || bitmap.height > SAMPLE_EDGE_PX) {
            Bitmap.createScaledBitmap(bitmap, SAMPLE_EDGE_PX, SAMPLE_EDGE_PX, true)
        } else {
            bitmap
        }
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== bitmap) sample.recycle()
        return classify(pixels)
    }

    /** Classifies ARGB pixels (not premultiplied). A picture with almost no opaque pixels is left alone. */
    fun classify(pixels: IntArray): ManeuverIconStyle {
        var opaque = 0
        var luminanceSum = 0f
        var spreadSum = 0f
        for (argb in pixels) {
            if ((argb ushr ALPHA_SHIFT) < MIN_OPAQUE_ALPHA) continue
            val red = ((argb shr RED_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX
            val green = ((argb shr GREEN_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX
            val blue = (argb and CHANNEL_MASK) / CHANNEL_MAX
            luminanceSum += WEIGHT_RED * red + WEIGHT_GREEN * green + WEIGHT_BLUE * blue
            spreadSum += maxOf(red, green, blue) - minOf(red, green, blue)
            opaque += 1
        }
        if (opaque < MIN_OPAQUE_SAMPLES) return ManeuverIconStyle.AS_IS
        val luminance = luminanceSum / opaque
        if (luminance >= LIGHT_LUMINANCE) return ManeuverIconStyle.AS_IS
        val spread = spreadSum / opaque
        return if (spread <= MONOCHROME_SPREAD) ManeuverIconStyle.WHITE_TINT else ManeuverIconStyle.LIGHT_BADGE
    }
}
