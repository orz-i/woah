"""Owned resolution contract; external plugins and generated ABI choice are bounded."""
from pathlib import Path
import tempfile
import unittest
from tools.ci.check_dependency_locks import ROOT, LOCKS, fingerprints


class DependencyLockTests(unittest.TestCase):
    def test_all_nine_real_locks_are_present(self):
        result = fingerprints(ROOT)
        self.assertEqual(len(result), 9)
        self.assertTrue(all(len(value) == 64 for value in result.values()))

    def test_missing_lock_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                fingerprints(Path(directory))

    def test_gradle_scope_and_closed_exceptions(self):
        policy = (ROOT / 'tools/gradle/woah-dependency-locking.gradle.kts').read_text()
        self.assertIn('LockMode.STRICT', policy)
        self.assertNotIn('lockAllConfigurations', policy)
        self.assertNotIn('io.flutter:*', policy)
        self.assertIn('module.version == woahEngineVersion', policy)
        self.assertIn('module.version == "2.3.20"', policy)
        self.assertIn('UnresolvedDependencyResult', policy)
        for path in ('mobile/app/android/app/gradle.lockfile', 'mobile/packages/dance_native/android/gradle.lockfile'):
            text = (ROOT / path).read_text()
            self.assertIn('io.flutter:flutter_embedding_debug:1.0.0-', text)
            self.assertIn('debugRuntimeClasspath', text)
            self.assertIn('releaseCompileClasspath', text)
            self.assertNotIn('io.flutter:arm64_v8a_debug:', text)

    def test_cocoapods_uses_deployment_and_post_check(self):
        script = (ROOT / 'tools/ci/resolve_ios_dependencies.py').read_text()
        self.assertIn("['pod', 'install', '--deployment']", script)
        self.assertIn('lock.read_bytes() != before', script)
        self.assertIn("version != '1.17.0'", script)
        self.assertIn('--enforce-lockfile', script)
        for phase in range(3, 7):
            gate = (ROOT / f'tools/ios/run_phase{phase}_macos_gate.py').read_text()
            self.assertIn('tools/ci/resolve_ios_dependencies.py', gate)

    def test_gradle_distribution_checksum_is_pinned(self):
        properties = (ROOT / 'mobile/app/android/gradle/wrapper/gradle-wrapper.properties').read_text()
        self.assertIn('distributionSha256Sum=b84e04fa845fecba48551f425957641074fcc00a88a84d2aae5808743b35fc85', properties)

    def test_workflow_resolves_locks_before_build(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertEqual(ci.count('flutter pub get --enforce-lockfile --no-example'), 5)
        self.assertIn('(cd example && flutter pub get --enforce-lockfile --no-example)', ci)
        self.assertIn('check_dependency_locks.py --fresh-dart', ci)
        self.assertIn(':app:verifyWoahDependencyLocks :dance_native:verifyWoahDependencyLocks', ci)
        for name in ('ios-cloud.yml', 'ios-browserstack.yml'):
            self.assertIn('tools/ci/resolve_ios_dependencies.py', (ROOT / '.github/workflows' / name).read_text())


if __name__ == '__main__':
    unittest.main()
