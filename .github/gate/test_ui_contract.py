import json
import unittest
from pathlib import Path

from ui_contract import evidence_errors, instruction_errors, is_ui_path

HEAD = "a" * 40
UI_FILE = "app/src/main/java/com/drivelift/app/ui/TransfersScreen.kt"
EV = "https://github.com/umairgemini1192-netizen/drivelift-android/blob/evidence/" + HEAD + "/"


def body(**overrides):
    data = {
        "head": HEAD,
        "reference": "Transfers screen and its shared rows",
        "screenshots": {
            "standard": EV + "_sheet-standard.png",
            "large_font": EV + "_sheet-large_font.png",
            "compact": EV + "_sheet-compact.png",
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
        shots = {"standard": EV + "a.png", "large_font": EV + "b.png"}
        self.assertTrue(evidence_errors([UI_FILE], body(screenshots=shots), HEAD))

    def test_non_https_link_fails(self):
        shots = {"standard": EV.replace("https", "http") + "a.png", "large_font": EV + "b.png",
                 "compact": EV + "c.png"}
        self.assertTrue(evidence_errors([UI_FILE], body(screenshots=shots), HEAD))

    def test_pr_page_link_fails(self):
        page = "https://github.com/umairgemini1192-netizen/drivelift-android/pull/100"
        shots = {"standard": page, "large_font": page, "compact": page}
        self.assertTrue(evidence_errors([UI_FILE], body(screenshots=shots), HEAD))

    def test_image_from_another_head_folder_fails(self):
        other = EV.replace(HEAD, "b" * 40)
        shots = {"standard": other + "s.png", "large_font": other + "l.png", "compact": other + "c.png"}
        self.assertTrue(evidence_errors([UI_FILE], body(screenshots=shots), HEAD))

    def test_non_image_link_in_head_folder_fails(self):
        shots = {"standard": EV + "notes.txt", "large_font": EV + "l.png", "compact": EV + "c.png"}
        self.assertTrue(evidence_errors([UI_FILE], body(screenshots=shots), HEAD))

    def test_raw_githubusercontent_link_passes(self):
        raw = "https://raw.githubusercontent.com/umairgemini1192-netizen/drivelift-android/evidence/" + HEAD + "/"
        shots = {"standard": raw + "s.png", "large_font": raw + "l.png", "compact": raw + "c.png"}
        self.assertEqual(evidence_errors([UI_FILE], body(screenshots=shots), HEAD), [])

    def test_unpublished_image_fails(self):
        self.assertTrue(evidence_errors([UI_FILE], body(), HEAD, file_exists=lambda ref, path: False))

    def test_published_images_pass(self):
        seen = []
        ok = evidence_errors([UI_FILE], body(), HEAD, file_exists=lambda ref, path: seen.append((ref, path)) or True)
        self.assertEqual(ok, [])
        self.assertEqual(seen[0], ("evidence", HEAD + "/_sheet-standard.png"))

    def test_two_blocks_fail(self):
        self.assertTrue(evidence_errors([UI_FILE], body() + body(), HEAD))


class InstructionTest(unittest.TestCase):
    def test_repository_entry_points_are_valid(self):
        self.assertEqual(instruction_errors(Path(__file__).resolve().parents[2]), [])


if __name__ == "__main__":
    unittest.main()
