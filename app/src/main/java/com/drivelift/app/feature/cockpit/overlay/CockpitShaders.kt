package com.drivelift.app.feature.cockpit.overlay

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.LocalReducedMotion
import com.drivelift.app.ui.Tokens

private const val SPECULAR_RIM_AGSL = """
uniform float2 uResolution;
uniform float uPhase;
uniform float uIntensity;
uniform half4 uPrimaryColor;
uniform half4 uSecondaryColor;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
    float sweep = 0.5 + 0.5 * sin((uv.x * 4.2 - uv.y * 2.1) + uPhase * 6.2831853);
    float topSpecular = pow(clamp(1.0 - uv.y, 0.0, 1.0), 1.8);
    half4 accent = mix(uSecondaryColor, uPrimaryColor, half(sweep));
    half alpha = clamp(half(0.32 + 0.45 * topSpecular + 0.23 * sweep * uIntensity), 0.2h, 1.0h);
    return half4(accent.rgb * alpha + half3(0.14 * topSpecular), alpha);
}
"""

fun Color.toWideGamutP3(): Color = runCatching {
    convert(ColorSpaces.DisplayP3)
}.getOrDefault(this)

/**
 * Applies an HDR+ Display-P3 luminous specular rim around the Dynamic Island pill or card.
 * Uses an AGSL [RuntimeShader] on API 33+ hardware surfaces, with a crisp linear-gradient
 * border fallback on API < 33 and Robolectric JVM renders.
 */
@Composable
fun Modifier.cockpitSpecularRim(
    shape: Shape,
    primaryAccent: Color,
    secondaryAccent: Color,
    intensity: Float = 0.85f,
    strokeWidth: Dp = Tokens.Hairline,
    animateOnActive: Boolean = false,
): Modifier {
    val reducedMotion = LocalReducedMotion.current
    val p3Primary = remember(primaryAccent) { primaryAccent.toWideGamutP3() }
    val p3Secondary = remember(secondaryAccent) { secondaryAccent.toWideGamutP3() }

    val phase = if (animateOnActive && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "cockpit_rim_glow")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = Tokens.Cockpit.RIM_PULSE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        animated
    } else {
        0.25f
    }

    val runtimeShader = remember(Build.VERSION.SDK_INT) {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(SPECULAR_RIM_AGSL) }.getOrNull()
        } else {
            null
        }
    }

    if (runtimeShader == null) {
        val fallbackBrush = Brush.linearGradient(
            listOf(
                GlassPalette.EdgeTop,
                p3Primary.copy(alpha = (0.65f * intensity).coerceIn(0.25f, 1f)),
                p3Secondary.copy(alpha = (0.55f * intensity).coerceIn(0.2f, 1f)),
                GlassPalette.EdgeBottom,
            ),
        )
        return this.border(strokeWidth, fallbackBrush, shape)
    }

    return this.drawWithCache {
        val strokePx = strokeWidth.toPx().coerceAtLeast(1f)
        val outline = shape.createOutline(size, layoutDirection, this)
        val brush = runCatching {
            runtimeShader.setFloatUniform("uResolution", size.width, size.height)
            runtimeShader.setFloatUniform("uPhase", phase)
            runtimeShader.setFloatUniform("uIntensity", intensity.coerceIn(0f, 1.5f))
            runtimeShader.setColorUniform(
                "uPrimaryColor",
                android.graphics.Color.valueOf(
                    p3Primary.red,
                    p3Primary.green,
                    p3Primary.blue,
                    p3Primary.alpha,
                ),
            )
            runtimeShader.setColorUniform(
                "uSecondaryColor",
                android.graphics.Color.valueOf(
                    p3Secondary.red,
                    p3Secondary.green,
                    p3Secondary.blue,
                    p3Secondary.alpha,
                ),
            )
            ShaderBrush(runtimeShader)
        }.getOrElse {
            Brush.linearGradient(listOf(GlassPalette.EdgeTop, p3Primary, p3Secondary))
        }

        onDrawWithContent {
            drawContent()
            drawOutline(
                outline = outline,
                brush = brush,
                style = Stroke(width = strokePx),
            )
        }
    }
}
