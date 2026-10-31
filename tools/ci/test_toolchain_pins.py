"""Direct workflow inputs are fixed; this is not a transitive action lockfile."""
from pathlib import Path
import re
import unittest
from unittest.mock import patch
import yaml

ROOT = Path(__file__).resolve().parents[2]


def problems(document):
    errors = []
    for job_name, job in document.get('jobs', {}).items():
        for step in job.get('steps', []):
            action = step.get('uses', '')
            if action and not re.fullmatch(r'[\w.-]+/[\w./-]+@[0-9a-f]{40}', action):
                errors.append('unpinned action: ' + action)
            inputs = step.get('with', {})
            if action.startswith('actions/setup-python@'):
                expected = '3.11.9' if job.get('runs-on') == 'macos-26' else '3.11.17'
                if inputs.get('python-version') != expected:
                    errors.append('Python drift: ' + job_name)
            if action.startswith('astral-sh/setup-uv@') and inputs.get('version') != '0.13.6':
                errors.append('uv drift')
            if action.startswith('actions/setup-java@') and (inputs.get('java-version') != '17.0.21+9' or inputs.get('distribution') != 'temurin'):
                errors.append('Java drift')
            if action.startswith('subosito/flutter-action@') and inputs.get('flutter-version') != '3.44.2':
                errors.append('Flutter drift')
            if 'uv run python' in step.get('run', ''):
                errors.append('uv run must enforce lock')
    return errors


class ToolchainPinsTests(unittest.TestCase):
    def test_all_actual_workflows(self):
        paths = sorted((ROOT / '.github/workflows').glob('*.yml'))
        self.assertEqual(len(paths), 3)
        for path in paths:
            with self.subTest(path=path.name):
                self.assertEqual(problems(yaml.load(path.read_text(), Loader=yaml.BaseLoader)), [])

    def test_mutable_or_short_action_refs_fail(self):
        for action in ['actions/checkout@v4', 'actions/checkout@main', 'actions/checkout@1234567']:
            self.assertTrue(problems({'jobs': {'a': {'steps': [{'uses': action}]}}}))

    def test_tool_version_drift_fails(self):
        for action, inputs in [('astral-sh/setup-uv', {'version': 'latest'}), ('actions/setup-python', {'python-version': '3.11'}), ('actions/setup-java', {'java-version': '17'}), ('subosito/flutter-action', {'flutter-version': 'stable'})]:
            self.assertTrue(problems({'jobs': {'a': {'steps': [{'uses': action + '@' + 'a' * 40, 'with': inputs}]}}}))

    def test_browserstack_artifact_gate_survives_sha_pinning(self):
        from tools.release import verify_ios_cloud as gate
        original = Path.read_text
        def missing(path, *args, **kwargs):
            content = original(path, *args, **kwargs)
            if path.name == 'ios-browserstack.yml':
                return content.replace('actions/download-artifact@', 'other/action@')
            return content
        gate.FAILURES.clear()
        gate.verify_workflows()
        self.assertEqual(gate.FAILURES, [])
        with patch.object(Path, 'read_text', missing):
            gate.verify_workflows()
        self.assertTrue(any('verified Linux-produced' in error for error in gate.FAILURES))
        gate.FAILURES.clear()

    def test_unlocked_uv_run_fails(self):
        self.assertTrue(problems({'jobs': {'a': {'steps': [{'run': 'uv run python test.py'}]}}}))


if __name__ == '__main__':
    unittest.main()
