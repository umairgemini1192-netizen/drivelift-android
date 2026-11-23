package com.drivelift.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies the unified PR #92 design system:
 * - Consolidated color tokens (#00D4FF cyan, #A78BFA violet, #34F5A0 neon green, #FF4D6D red)
 * - Concentric corner radius calculations
 * - Single GlassSurface levels (Panel, Bar, Island, Hero), opacity range, API 31+ blur / API 30 fallback
 * - Non-nesting guard (LocalInsideGlassSurface)
 * - Matching GlassWidgetStyle Glance bitmap renderer
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GlassSurfaceAndTokensTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test
    fun colorSchemeUsesUnifiedElectricCyanSoftVioletNeonGreenAndDestructiveRed() {
        var primary = Color.Unspecified
        var secondary = Color.Unspecified
        var tertiary = Color.Unspecified
        var error = Color.Unspecified
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        primary = MaterialTheme.colorScheme.primary
                        secondary = MaterialTheme.colorScheme.secondary
                        tertiary = MaterialTheme.colorScheme.tertiary
                        error = MaterialTheme.colorScheme.error
                    }
                }
            }
            compose.waitForIdle()
        }
        assertEquals(GlassPalette.PrimaryCyan, primary)
        assertEquals(GlassPalette.SecondaryViolet, secondary)
        assertEquals(GlassPalette.SuccessGreen, tertiary)
        assertEquals(GlassPalette.DestructiveRed, error)
    }

    @Test
    fun concentricCornersComputeOuterMinusInsetWithMinimumFloor() {
        assertEquals(18.dp, Tokens.concentricRadius(Tokens.Cockpit.HudOuterRadius, Tokens.Small))
        assertEquals(16.dp, Tokens.concentricRadius(Tokens.Cockpit.HudOuterRadius, Tokens.Row))
        assertEquals(16.dp, Tokens.concentricRadius(Tokens.IslandRadius, Tokens.Medium))
        assertEquals(Tokens.Tiny, Tokens.concentricRadius(6.dp, 12.dp))
    }

    @Test
    fun glassLevelsEnforceNavyOpacityRangeBorderThicknessAndApi30DenserFallback() {
        assertFalse(supportsGlassBlur(sdkInt = 30))
        assertTrue(supportsGlassBlur(sdkInt = 31))
        assertTrue(supportsGlassBlur(sdkInt = 35))

        for (level in GlassLevel.entries) {
            val spec = glassLevelSpec(level)
            assertTrue(
                "$level fillTop alpha ${spec.fillTop.alpha} must be in 0.70..0.85",
                spec.fillTop.alpha in 0.70f..0.85f,
            )
            assertTrue(
                "$level fillBottom alpha ${spec.fillBottom.alpha} must be in 0.70..0.86",
                spec.fillBottom.alpha in 0.70f..0.85f,
            )
            assertTrue(
                "$level API 30 fallback must be denser than API 31+ fill",
                spec.fallbackTop.alpha > spec.fillTop.alpha && spec.fallbackBottom.alpha > spec.fillBottom.alpha,
            )
            assertTrue(
                "$level border width ${spec.borderWidth} must be in 1..1.5dp",
                spec.borderWidth >= 1.dp && spec.borderWidth <= 1.5.dp,
            )
        }
    }

    @Test
    fun glassSurfaceMarksNestedContextSoGlassIsNeverNestedInsideGlass() {
        var outerSawNested: Boolean? = null
        var innerSawNested: Boolean? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DriveLiftTheme {
                        GlassBackdrop {
                            outerSawNested = LocalInsideGlassSurface.current
                            GlassSurface(level = GlassLevel.Hero, modifier = Modifier.testTag("outer-glass")) {
                                innerSawNested = LocalInsideGlassSurface.current
                                GlassSurface(level = GlassLevel.Panel, modifier = Modifier.testTag("nested-well")) {
                                    Text("Nested content")
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("outer-glass").assertIsDisplayed()
            compose.onNodeWithTag("nested-well").assertIsDisplayed()
        }
        assertEquals(false, outerSawNested)
        assertEquals(true, innerSawNested)
    }

    @Test
    fun glassWidgetStyleRendersTranslucentSurfacesAcrossAllLevels() {
        for (level in GlassWidgetLevel.entries) {
            val bmp = GlassWidgetStyle.renderSurfaceBitmap(
                widthPx = 240,
                heightPx = 120,
                cornerRadiusDp = 20f,
                density = 2f,
                level = level,
                accentColor = GlassPalette.PrimaryCyan,
                active = level == GlassWidgetLevel.Tile,
                enabled = true,
            )
            assertTrue("$level bitmap width > 0", bmp.width > 0)
            assertTrue("$level bitmap height > 0", bmp.height > 0)
            val centerAlpha = (bmp.getPixel(bmp.width / 2, bmp.height / 2) ushr 24) and 0xFF
            assertTrue("$level center pixel must be visible (alpha=$centerAlpha)", centerAlpha > 150)
        }
    }
}
