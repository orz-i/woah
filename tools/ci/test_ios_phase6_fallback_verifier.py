"""Mutation tests for FULL_BODY conservative fallback preservation.

No synthetic privacy data is allowed to overwrite identity roots; the fresh
class compositor must retain an unresolved selected root's conservative mask.
"""
from pathlib import Path
import unittest
from unittest.mock import patch

from tools.release import verify_ios_phase6 as gate


class IOSPhase6MissingRootFallbackVerifierTests(unittest.TestCase):
    def setUp(self):
        gate.FAILURES.clear()

    def tearDown(self):
        gate.FAILURES.clear()

    def verify_current(self):
        gate.verify_ios_surface()

    def assert_mutation_fails(self, filename, original, replacement):
        read = Path.read_text

        def injected(path, *args, **kwargs):
            content = read(path, *args, **kwargs)
            if path.name == filename:
                self.assertIn(original, content)
                content = content.replace(original, replacement, 1)
            return content

        with patch.object(Path, 'read_text', injected):
            self.verify_current()
        self.assertTrue(gate.FAILURES, f'Unsafe mutation was accepted for {filename}')

    def test_current_selected_fallback_contract(self):
        self.verify_current()
        self.assertEqual([], gate.FAILURES)

    def test_drop_unresolved_root_fails(self):
        self.assert_mutation_fails(
            'IOSMetalPreviewRenderer.swift',
            'let fallbackSelectedArray = unresolvedSelected + observedSupplement',
            'let fallbackSelectedArray = Array(observedSupplement)',
        )

    def test_drop_conservative_selected_filter_fails(self):
        self.assert_mutation_fails(
            'IOSMetalPreviewRenderer.swift',
            '.filter { $0.conservativePrivacyFallback }',
            '.filter { !$0.conservativePrivacyFallback }',
        )

    def test_missing_root_mask_pixel_guard_fails(self):
        self.assert_mutation_fails(
            'IOSPrivacyClassPhase6Smoke.swift',
            "FULL_BODY fresh-class compositor omitted an unresolved selected root's fail-closed mask",
            'removed safety assertion',
        )


if __name__ == '__main__':
    unittest.main()
