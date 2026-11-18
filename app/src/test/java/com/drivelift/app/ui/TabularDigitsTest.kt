package com.drivelift.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Rule 11 in docs/AI_CONTEXT.md: every text style in the theme uses tabular digits. */
class TabularDigitsTest {
    @Test
    fun everyThemeTextStyleUsesTabularDigits() {
        val t = AppTypography
        val styles = mapOf(
            "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
        )
        for ((name, style) in styles) assertEquals("$name must use tabular digits", TABULAR_DIGITS, style.fontFeatureSettings)
    }

    @Test
    fun theLeadFigureKeepsItsSizeAndWeight() {
        assertEquals(64f, AppTypography.displayLarge.fontSize.value)
        assertEquals(androidx.compose.ui.text.font.FontWeight.SemiBold, AppTypography.displayLarge.fontWeight)
    }
}
