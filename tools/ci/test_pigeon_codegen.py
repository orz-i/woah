"""Small comparator/config regressions; CI additionally runs the actual generator."""
import json
from pathlib import Path
import tempfile
import unittest
from tools.ci.pigeon_codegen import ROOT, OUTPUTS, VERSION, compare, generator, snapshot


class PigeonCodegenTests(unittest.TestCase):
    def test_exact_two_schema_six_output_boundary(self):
        self.assertEqual(len(OUTPUTS), 6)
        self.assertEqual(len(set(OUTPUTS)), 6)
        for extension in ('.dart', '.kt', '.swift'):
            self.assertEqual(sum(name.endswith(extension) for name in OUTPUTS), 2)
        self.assertTrue(all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in OUTPUTS))

    def test_equal_outputs_pass(self):
        files = {name: b'generated' for name in OUTPUTS}
        compare(files, dict(files))

    def test_missing_output_fails(self):
        files = {name: b'generated' for name in OUTPUTS}
        missing = dict(files)
        del missing[OUTPUTS[0]]
        with self.assertRaisesRegex(ValueError, 'Pigeon output drift'):
            compare(files, missing)

    def test_changed_output_fails(self):
        files = {name: b'generated' for name in OUTPUTS}
        changed = dict(files)
        changed[OUTPUTS[-1]] = b'stale'
        with self.assertRaisesRegex(ValueError, 'ComparisonApi.g.swift'):
            compare(files, changed)

    def test_missing_baseline_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                snapshot(Path(directory))

    def test_wrong_generator_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.dart_tool').mkdir()
            package = root / 'pigeon'
            package.mkdir()
            (package / 'pubspec.yaml').write_text('name: pigeon\nversion: 0.0.0\n')
            (root / '.dart_tool/package_config.json').write_text(json.dumps({'packages': [
                {'name': 'pigeon', 'rootUri': package.as_uri()}]}))
            with self.assertRaisesRegex(ValueError, 'Pigeon must resolve'):
                generator(root)

    def test_entry_is_required_after_locked_resolution(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('python tools/ci/pigeon_codegen.py --self-test', workflow)
        self.assertLess(workflow.index('Install Flutter Dependencies'), workflow.index('pigeon_codegen.py'))
        pubspec = (ROOT / 'mobile/packages/dance_native/pubspec.yaml').read_text()
        self.assertIn(f'  pigeon: {VERSION}\n', pubspec)
        self.assertNotIn('pigeon: ^', pubspec)


if __name__ == '__main__':
    unittest.main()
