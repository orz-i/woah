"""Regression guards for the real, currently shipped resolution selector.

Prevents Production CI from depending on a retired widget key / UI copy while
still failing if the editor loses the accessible preset action or export grows
a smaller source simply because the user selected an FHD/HD ceiling.
"""
from __future__ import annotations

import contextlib
import io
import unittest
from unittest.mock import patch

from tools.release import verify_export_media_contract as contract


class ExportMediaContractVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        contract.FAILURES.clear()

    def tearDown(self) -> None:
        contract.FAILURES.clear()

    def run_with(self, file_path: str | None = None,
                 before: str | None = None,
                 after: str = "") -> tuple[int, str]:
        original_read = contract.read

        def read(path: str) -> str:
            text = original_read(path)
            if path == file_path:
                assert before is not None and before in text
                return text.replace(before, after)
            return text

        output = io.StringIO()
        with patch.object(contract, "read", side_effect=read):
            with contextlib.redirect_stdout(output):
                exit_code = contract.main()
        return exit_code, output.getvalue()

    def test_current_top_row_resolution_ui_and_export_contract_pass(self) -> None:
        exit_code, message = self.run_with()
        self.assertEqual(exit_code, 0, message)
        self.assertIn("verification passed", message)

    def test_missing_top_row_resolution_trigger_is_rejected(self) -> None:
        code, message = self.run_with(
            "mobile/app/lib/features/protection_editor/presentation/protection_editor_screen.dart",
            "protection-editor-resolution-trigger",
            "removed-resolution-trigger",
        )
        self.assertEqual(code, 1)
        self.assertIn("protection-editor-resolution-trigger", message)

    def test_ui_must_reach_mutable_preset_handler(self) -> None:
        code, message = self.run_with(
            "mobile/app/lib/features/protection_editor/presentation/protection_editor_screen.dart",
            "effectController.updateOutputResolutionPreset(value);",
            "// disabled handler",
        )
        self.assertEqual(code, 1)
        self.assertIn("updateOutputResolutionPreset(value)", message)

    def test_fhd_hd_presets_must_never_upscale_smaller_source(self) -> None:
        code, message = self.run_with(
            "mobile/packages/dance_domain/lib/src/export_plan.dart",
            "if (width <= capWidth && height <= capHeight)",
            "if (false)",
        )
        self.assertEqual(code, 1)
        self.assertIn("if (width <= capWidth && height <= capHeight)", message)


if __name__ == "__main__":
    unittest.main()
