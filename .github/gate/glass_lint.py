"""Lightweight lint check enforcing DriveLift's single shared GlassSurface and token rules.

Prevents screens and feature packages from defining ad-hoc local glass variants, local
RenderEffect blurs, raw hex Color literals outside ui/Theme.kt, or scattered *Tokens objects
outside ui/Tokens.kt.
"""
import re
from pathlib import Path

SOURCE_ROOT = Path("app/src/main/java/com/drivelift/app")

ALLOWED_GLASS_FILES = {
    "app/src/main/java/com/drivelift/app/ui/Glass.kt",
    "app/src/main/java/com/drivelift/app/ui/GlassWidgetStyle.kt",
}
ALLOWED_COLOR_FILES = {
    "app/src/main/java/com/drivelift/app/ui/Theme.kt",
}
ALLOWED_TOKEN_FILES = {
    "app/src/main/java/com/drivelift/app/ui/Tokens.kt",
}

LOCAL_GLASS_DEF_RE = re.compile(
    r"\bfun\s+(?:Modifier\.)?(?:[A-Za-z0-9_]*Glass(?:Panel|Card|Surface|Box|Tile|Bar|Scrubber|Container|Pill)[A-Za-z0-9_]*|glass[A-Z][A-Za-z0-9_]*|glass)\s*\("
)
LOCAL_BLUR_RE = re.compile(r"\bRenderEffect\.createBlurEffect\s*\(")
RAW_COLOR_LITERAL_RE = re.compile(r"\bColor\(\s*0x[0-9A-Fa-f]{6,8}\b")
SCATTERED_TOKENS_RE = re.compile(r"\bobject\s+(?:Cockpit|Cleaner|AppLock|[A-Za-z0-9_]+?)Tokens\b")
DIRECT_GLASS_FILL_RE = re.compile(
    r"\bGlassPalette\.(?:(?:Panel|Bar|Island|Hero)(?:Fill|Fallback)(?:Top|Bottom)|FillTop|FillBottom|BarTop|BarBottom)\b"
)


def strip_comments(source: str) -> str:
    """Remove /* ... */ and // ... comments while preserving line count."""
    def replace_block(match: re.Match) -> str:
        return "\n" * match.group(0).count("\n")

    no_blocks = re.sub(r"/\*.*?\*/", replace_block, source, flags=re.S)
    return re.sub(r"//[^\n]*", "", no_blocks)


def check_kotlin_source(rel_path: str, source: str) -> list[str]:
    """Inspect one Kotlin source file and return a list of lint violation messages."""
    norm = rel_path.replace("\\", "/")
    errors: list[str] = []
    cleaned = strip_comments(source)

    for lineno, line in enumerate(cleaned.splitlines(), start=1):
        if norm not in ALLOWED_GLASS_FILES:
            if LOCAL_GLASS_DEF_RE.search(line):
                errors.append(
                    f"{norm}:{lineno}: ad-hoc local glass composable/modifier is forbidden; "
                    "use GlassSurface, GlassPanel, Modifier.glass, Modifier.glassBar, or GlassWidgetStyle."
                )
            if LOCAL_BLUR_RE.search(line):
                errors.append(
                    f"{norm}:{lineno}: direct RenderEffect.createBlurEffect is forbidden outside ui/Glass.kt; "
                    "use GlassBackdrop(blurred = true) or GlassSurface."
                )
            if DIRECT_GLASS_FILL_RE.search(line):
                errors.append(
                    f"{norm}:{lineno}: direct GlassPalette glass-surface fill token is forbidden outside ui/Glass.kt; "
                    "use GlassSurface or GlassWidgetStyle."
                )
        if norm not in ALLOWED_COLOR_FILES and RAW_COLOR_LITERAL_RE.search(line):
            errors.append(
                f"{norm}:{lineno}: raw Color(0x...) literal is forbidden outside ui/Theme.kt."
            )
        if norm not in ALLOWED_TOKEN_FILES and SCATTERED_TOKENS_RE.search(line):
            errors.append(
                f"{norm}:{lineno}: scattered *Tokens object is forbidden; consolidate into ui/Tokens.kt."
            )
    return errors


def check_repository(root: Path) -> list[str]:
    """Scan all production Kotlin files under app/src/main/java/com/drivelift/app."""
    base = root / SOURCE_ROOT
    if not base.is_dir():
        return [f"Source root not found: {base}"]
    errors: list[str] = []
    for kt_file in sorted(base.rglob("*.kt")):
        rel = kt_file.relative_to(root).as_posix()
        errors.extend(check_kotlin_source(rel, kt_file.read_text(encoding="utf-8")))
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    errors = check_repository(root)
    for error in errors:
        print(error)
    if not errors:
        print("Glass & token lint passed: zero ad-hoc local glass variants, raw colors, or scattered token objects.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
