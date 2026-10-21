"""Regression tests for supported iOS leg stretch and fail-closed export inputs.

These are source-contract tests, not an Xcode build or visual privacy proof.
"""
from __future__ import annotations

import unittest
from unittest.mock import patch

from tools.release import verify_ios_phase4 as phase4


class IOSPhase4LegStretchContractTest(unittest.TestCase):
    def tearDown(self) -> None:
        phase4.FAILURES.clear()

    def _verify_modified(self, filename: str, original: str, replacement: str = "") -> list[str]:
        actual_source = phase4.source

        def changed_source(name: str) -> str:
            text = actual_source(name)
            if name == filename:
                if original not in text:
                    raise AssertionError(f"Missing test mutation anchor in {filename}: {original}")
                return text.replace(original, replacement)
            return text

        phase4.FAILURES.clear()
        with patch.object(phase4, "source", side_effect=changed_source):
            phase4.verify_media_pipeline()
        return list(phase4.FAILURES)

    def test_current_export_and_metal_supported_effect_pass(self) -> None:
        phase4.FAILURES.clear()
        phase4.verify_media_pipeline()
        self.assertEqual(phase4.FAILURES, [])

    def test_no_follow_target_must_not_silently_drop_leg_stretch(self) -> None:
        failures = self._verify_modified(
            "IOSExportPipeline.swift",
            "!request.follow.enabled || request.follow.targetPersonId == nil",
            "false",
        )
        self.assertTrue(any("leg stretch export contract" in f for f in failures), failures)

    def test_export_must_pass_protagonist_root_into_renderer(self) -> None:
        failures = self._verify_modified(
            "IOSExportPipeline.swift",
            "legStretchTargetId: followTargetId",
            "legStretchTargetId: nil",
        )
        self.assertTrue(any("leg stretch export contract" in f for f in failures), failures)

    def test_renderer_must_enable_mask_only_for_requested_leg_stretch(self) -> None:
        failures = self._verify_modified(
            "IOSMetalPreviewRenderer.swift",
            "effects.legStretchEnabled ? legStretchTargetId : nil",
            "nil",
        )
        self.assertTrue(any("renderer leg stretch implementation" in f for f in failures), failures)

    def test_renderer_must_retain_leg_mask_shader_warp(self) -> None:
        failures = self._verify_modified(
            "IOSMetalPreviewRenderer.swift",
            "if (legStretchEnabled == 1 && legStretch > 1.0",
            "if (false && legStretch > 1.0",
        )
        self.assertTrue(any("renderer leg stretch implementation" in f for f in failures), failures)

    def test_unsupported_skin_whitening_remains_rejected(self) -> None:
        failures = self._verify_modified(
            "IOSExportPipeline.swift",
            "request.effects.skinWhiten > 0",
            "false",
        )
        self.assertTrue(any("unsupported-effect rejection" in f for f in failures), failures)


if __name__ == "__main__":
    unittest.main()
