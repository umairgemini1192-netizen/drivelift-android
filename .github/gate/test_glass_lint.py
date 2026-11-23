import unittest
from pathlib import Path

from glass_lint import check_kotlin_source, check_repository


class GlassLintRuleTest(unittest.TestCase):
    def test_rejects_local_glass_composable_in_screen(self):
        src = "@Composable\nprivate fun LocalGlassCard() {}\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/ui/FooScreen.kt", src)
        self.assertEqual(len(errors), 1)
        self.assertIn("ad-hoc local glass", errors[0])

    def test_rejects_local_glass_modifier_in_feature(self):
        src = "fun Modifier.glassPanelCustom(): Modifier = this\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/feature/cockpit/ui/Foo.kt", src)
        self.assertEqual(len(errors), 1)
        self.assertIn("ad-hoc local glass", errors[0])

    def test_allows_glass_definitions_inside_shared_glass_kt(self):
        src = "@Composable\nfun GlassSurface() {}\nval e = RenderEffect.createBlurEffect(24f, 24f, TileMode.CLAMP)\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/ui/Glass.kt", src)
        self.assertEqual(errors, [])

    def test_rejects_direct_render_effect_blur_in_screen(self):
        src = "val effect = RenderEffect.createBlurEffect(32f, 32f, Shader.TileMode.CLAMP)\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/feature/applock/ui/Gate.kt", src)
        self.assertEqual(len(errors), 1)
        self.assertIn("RenderEffect.createBlurEffect", errors[0])

    def test_rejects_raw_color_literal_outside_theme(self):
        src = "val accent = Color(0xFF00D4FF)\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/ui/FooScreen.kt", src)
        self.assertEqual(len(errors), 1)
        self.assertIn("raw Color(0x...)", errors[0])

    def test_allows_raw_color_literal_inside_theme(self):
        src = "val primary = Color(0xFF00D4FF)\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/ui/Theme.kt", src)
        self.assertEqual(errors, [])

    def test_rejects_scattered_tokens_object(self):
        src = "object FeatureTokens { val Gap = 8.dp }\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/feature/foo/FooTokens.kt", src)
        self.assertEqual(len(errors), 1)
        self.assertIn("scattered *Tokens", errors[0])

    def test_rejects_direct_glass_fill_tokens_outside_glass_files(self):
        src = "val c = GlassPalette.PanelFillTop\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/ui/FooScreen.kt", src)
        self.assertEqual(len(errors), 1)
        self.assertIn("direct GlassPalette glass-surface fill", errors[0])

    def test_ignores_matches_inside_comments(self):
        src = "// fun LocalGlassCard() {} Color(0xFF123456)\n/* object FooTokens */\nval ok = GlassPalette.Well\n"
        errors = check_kotlin_source("app/src/main/java/com/drivelift/app/ui/FooScreen.kt", src)
        self.assertEqual(errors, [])

    def test_repository_passes_glass_lint(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
