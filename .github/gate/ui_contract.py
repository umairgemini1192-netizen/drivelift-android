"""Validate UI evidence metadata. This never judges screenshots or reviewer honesty."""
import json
import os
import re
import subprocess
from pathlib import Path
from urllib.parse import urlparse


BLOCK = re.compile(r"^```ui-review\s*\n(.*?)^```\s*$", re.M | re.S)
PLACEHOLDER = re.compile(r"\b(todo|tbd|placeholder)\b|<!--|<[^>]+>", re.I)
SCENARIOS = ("standard", "large_font", "compact")
ENTRY_POINTS = ("AGENTS.md", "CLAUDE.md")


def is_ui_path(path):
    # Resources, activities and the manifest count as UI, so a resource-only redesign or a
    # renamed screen cannot skip review.
    # A view model holds state and draws nothing, so it is reviewed as logic, not as UI.
    if path.endswith("ViewModel.kt"):
        return False
    return path.startswith("app/src/main/") and (
        "/ui/" in path or "/res/" in path or path.endswith("Activity.kt")
        or path.endswith("AndroidManifest.xml"))


def useful(value):
    return isinstance(value, str) and len(value.strip()) >= 3 and not PLACEHOLDER.search(value)


def evidence_errors(files, body, head):
    if not any(is_ui_path(p) for p in files):
        return []
    blocks = BLOCK.findall(body.replace("\r\n", "\n"))
    if len(blocks) != 1:
        return ["Production UI changes require exactly one fenced ui-review JSON block."]
    try:
        data = json.loads(blocks[0])
    except (ValueError, TypeError):
        return ["ui-review must contain valid JSON."]
    if not isinstance(data, dict):
        return ["ui-review must be a JSON object."]
    errors = []
    if data.get("head") != head:
        errors.append("UI evidence head must equal the full current PR head SHA.")
    for field in ("reference", "interactions", "accessibility", "reviewer", "exceptions"):
        if not useful(data.get(field)):
            errors.append(f"UI evidence needs a concrete {field} value.")
    shots = data.get("screenshots")
    for scenario in SCENARIOS:
        value = shots.get(scenario) if isinstance(shots, dict) else None
        if not useful(value) or urlparse(value).scheme != "https" or not urlparse(value).netloc:
            errors.append(f"UI evidence needs an HTTPS screenshot link for {scenario}.")
    return errors


def instruction_errors(root):
    errors = []
    for name in ENTRY_POINTS:
        p = root / name
        content = p.read_text(encoding="utf-8") if p.is_file() else ""
        if "docs/AI_CONTEXT.md" not in content or "docs/UI_WORKFLOW.md" not in content:
            errors.append(f"{name} must point to the shared rulebook and the UI workflow.")
    master = root / "docs/AI_CONTEXT.md"
    if not master.is_file() or "## Native design system" not in master.read_text(encoding="utf-8"):
        errors.append("docs/AI_CONTEXT.md must contain the native design system rules.")
    if not (root / "docs/UI_WORKFLOW.md").is_file():
        errors.append("docs/UI_WORKFLOW.md must exist.")
    return errors


def main():
    root = Path(__file__).resolve().parents[2]
    errors = instruction_errors(root)
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if event_path:
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        pr = event.get("pull_request")
        if pr:
            # --no-renames lists both old and new paths, covering moved or deleted UI.
            result = subprocess.run(
                ["git", "diff", "--no-renames", "--name-only", "-z",
                 pr["base"]["sha"] + "..." + pr["head"]["sha"]],
                cwd=root, check=True, capture_output=True)
            files = result.stdout.decode("utf-8").split("\0")
            errors += evidence_errors(files, pr.get("body") or "", pr["head"]["sha"])
    for error in errors:
        print(error)
    if not errors:
        print("UI contract metadata valid. Visual approval still requires a reviewer.")
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
