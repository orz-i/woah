"""Mutation regressions for the Phase2 verifier's lease/path safety contract."""
from pathlib import Path
import unittest
from unittest.mock import patch

from tools.release import verify_ios_phase2 as gate


class IOSCacheVerifierTests(unittest.TestCase):
    def setUp(self):
        gate.FAILURES.clear()

    def tearDown(self):
        gate.FAILURES.clear()

    def verify_all(self):
        gate.verify_cache_contract()
        gate.verify_analyze_pipeline()
        gate.verify_plugin_gating()
        gate.verify_cross_platform_selection_contract()

    def rejected_mutation(self, filename, old, new):
        original = Path.read_text
        def mutated(path, *args, **kwargs):
            value = original(path, *args, **kwargs)
            if path.name == filename:
                self.assertIn(old, value)
                return value.replace(old, new)
            return value
        with patch.object(Path, 'read_text', mutated):
            self.verify_all()
        self.assertTrue(gate.FAILURES, f'unsafe mutation was accepted: {filename}')

    def test_current_cache_contract_passes(self):
        self.verify_all()
        self.assertEqual([], gate.FAILURES)

    def test_missing_leaf_symlink_guard_fails(self):
        self.rejected_mutation('IOSAnalysisCache.swift',
                               'file.resolvingSymlinksInPath() == file', 'true')

    def test_missing_analysis_real_lease_close_fails(self):
        self.rejected_mutation('IOSAnalyzePipeline.swift', 'lease.close()', '// missing cleanup')

    def test_failed_analysis_without_release_intent_fails(self):
        self.rejected_mutation('IOSAnalyzePipeline.swift',
                               'if !committed { registry.requestRelease(cacheId: cacheId, owner: owner) }', '')

    def test_deletion_without_zero_lease_guard_fails(self):
        self.rejected_mutation('IOSRegenerableCacheRegistry.swift',
                               '$0.released && $0.leases == 0', '$0.released')

    def test_same_millisecond_android_id_collision_regression_fails(self):
        self.rejected_mutation('AnalyzePipeline.kt',
                               '${java.util.UUID.randomUUID()}', 'constant')


if __name__ == '__main__':
    unittest.main()
