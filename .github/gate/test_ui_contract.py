import json
import unittest
from pathlib import Path

from ui_contract import evidence_errors, instruction_errors, is_ui_path

HEAD = "a" * 40
UI_FILE = "app/src/main/java/com/drivelift/app/ui/TransfersScreen.kt"


def body(**overrides):
    data = {
        "head": HEAD,
        "reference": "Transfers screen and its shared rows",
        "screenshots": {
            "standard": "https://example.com/standard.png",
            "large_font": "https://example.com/large.png",
            "compact": "https://example.com/compact.png",
        },
        "interactions": "Tab change and row cancel exercised in a Compose test",
        "accessibility": "Touch targets inspected in source; contrast not measured",
        "reviewer": "ChatGPT",
        "exceptions": "none",
    }
    data.update(overrides)
    return "Intro\n\n```ui-review\n" + json.dumps(data, indent=2) + "\n```\n"


class UiPathTest(unittest.TestCase):
    def test_ui_paths(self):
        self.assertTrue(is_ui_path(UI_FILE))
        self.assertTrue(is_ui_path("app/src/main/res/values/strings.xml"))
        self.assertTrue(is_ui_path("app/src/main/AndroidManifest.xml"))

    def test_non_ui_paths(self):
        self.assertFalse(is_ui_path("app/src/main/java/com/drivelift/app/drive/DriveClient.kt"))
        self.assertFalse(is_ui_path("app/src/test/java/com/drivelift/app/ui/ScreenshotTest.kt"))
        self.assertFalse(is_ui_path("docs/UI_WORKFLOW.md"))
        self.assertFalse(is_ui_path("app/src/main/java/com/drivelift/app/ui/AppViewModel.kt"))


class EvidenceTest(unittest.TestCase):
    def test_non_ui_change_needs_nothing(self):
        self.assertEqual(evidence_errors(["README.md"], "", HEAD), [])

    def test_ui_change_without_block_fails(self):
        self.assertTrue(evidence_errors([UI_FILE], "no evidence", HEAD))

    def test_complete_current_evidence_passes(self):
        self.assertEqual(evidence_errors([UI_FILE], body(), HEAD), [])

    def test_windows_line_endings_pass(self):
        self.assertEqual(evidence_errors([UI_FILE], body().replace("\n", "\r\n"), HEAD), [])

    def test_stale_head_fails(self):
        self.assertTrue(evidence_errors([UI_FILE], body(head="b" * 40), HEAD))

    def test_placeholder_fails(self):
        self.assertTrue(evidence_errors([UI_FILE], body(reviewer="TODO named reviewer"), HEAD))

    def test_missing_scenario_fails(self):
        shots = {"standard": "https://example.com/a.png", "large_font": "https://example.com/b.png"}
        self.assertTrue(evidence_errors([UI_FILE], body(screenshots=shots), HEAD))

    def test_non_https_link_fails(self):
        shots = {"standard": "http://example.com/a.png", "large_font": "https://example.com/b.png",
                 "compact": "https://example.com/c.png"}
        self.assertTrue(evidence_errors([UI_FILE], body(screenshots=shots), HEAD))

    def test_two_blocks_fail(self):
        self.assertTrue(evidence_errors([UI_FILE], body() + body(), HEAD))


class InstructionTest(unittest.TestCase):
    def test_repository_entry_points_are_valid(self):
        self.assertEqual(instruction_errors(Path(__file__).resolve().parents[2]), [])


if __name__ == "__main__":
    unittest.main()
