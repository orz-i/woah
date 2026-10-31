"""Regression contracts for the CI gate, using the real workflow documents."""

from __future__ import annotations

import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

import yaml

from tools.ci.check_dart_format import GENERATED, handwritten_files
from tools.ci.check_required_jobs import REQUIRED_JOBS, validate_results

ROOT = Path(__file__).resolve().parents[2]


def workflow(name: str) -> dict:
    # YAML 1.1 SafeLoader treats the Actions key `on` as a boolean. BaseLoader
    # preserves scalar spelling and still parses actual mappings/sequences.
    return yaml.load((ROOT / ".github/workflows" / name).read_text(), Loader=yaml.BaseLoader)


class RequiredResultsTests(unittest.TestCase):
    def test_only_all_success_is_accepted(self):
        states = ("success", "failure", "cancelled", "skipped", "neutral", "", None)
        for left, right in itertools.product(states, repeat=2):
            with self.subTest(production=left, ios=right):
                needs = {"validate-and-test": {"result": left}, "ios": {"result": right}}
                self.assertEqual(not validate_results(needs), left == right == "success")

    def test_missing_unknown_or_malformed_dependency_is_rejected(self):
        good = {name: {"result": "success"} for name in REQUIRED_JOBS}
        for needs in (None, [], {}, {"ios": {"result": "success"}},
                      {**good, "new-job": {"result": "success"}},
                      {**good, "ios": None}, {**good, "ios": {}}):
            with self.subTest(needs=needs):
                self.assertTrue(validate_results(needs))

    def test_command_fails_closed(self):
        script = ROOT / "tools/ci/check_required_jobs.py"
        cases = [None, "", "not JSON", "null", "{}",
                 json.dumps({"validate-and-test": {"result": "success"},
                             "ios": {"result": "skipped"}})]
        for value in cases:
            env = {key: value for key, value in os.environ.items() if key != "CI_NEEDS_JSON"}
            if value is not None:
                env["CI_NEEDS_JSON"] = value
            result = subprocess.run([sys.executable, str(script)], env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
        env["CI_NEEDS_JSON"] = json.dumps({name: {"result": "success"} for name in REQUIRED_JOBS})
        self.assertEqual(subprocess.run([sys.executable, str(script)], env=env,
                                        capture_output=True).returncode, 0)


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.ci = workflow("ci.yml")
        self.ios = workflow("ios-cloud.yml")

    def test_all_pull_requests_and_pushes_report(self):
        self.assertEqual(set(self.ci["on"]), {"push", "pull_request", "workflow_dispatch"})
        for event in ("push", "pull_request"):
            self.assertEqual(set(self.ci["on"][event]), {"branches"})
            self.assertEqual(set(self.ci["on"][event]["branches"]), {"main", "master"})
        # No path filters means dance_ui, tools/ios, phase4, models, Metal and
        # documentation-only changes all receive the same two platform gates.
        self.assertNotIn("if", self.ci["jobs"]["validate-and-test"])
        self.assertNotIn("if", self.ci["jobs"]["ios"])

    def test_aggregate_needs_every_job_and_always_runs(self):
        jobs = self.ci["jobs"]
        aggregate = jobs["required"]
        self.assertEqual(aggregate["name"], "governance / required")
        self.assertEqual(aggregate["if"], "${{ always() }}")
        self.assertEqual(set(aggregate["needs"]), set(jobs) - {"required"})
        self.assertEqual(set(aggregate["needs"]), REQUIRED_JOBS)
        step = aggregate["steps"][-1]
        self.assertEqual(step["env"]["CI_NEEDS_JSON"], "${{ toJSON(needs) }}")
        self.assertEqual(step["run"], "python3 tools/ci/check_required_jobs.py")

    def test_ios_reuse_is_same_revision_without_duplicate_events(self):
        self.assertEqual(set(self.ios["on"]), {"workflow_call", "workflow_dispatch"})
        self.assertEqual(self.ci["jobs"]["ios"]["uses"], "./.github/workflows/ios-cloud.yml")
        self.assertNotIn("secrets", self.ci["jobs"]["ios"])
        self.assertNotEqual(self.ci["concurrency"]["group"], self.ios["concurrency"]["group"])
        self.assertTrue(self.ci["concurrency"]["group"].startswith("production-ci-"))
        self.assertTrue(self.ios["concurrency"]["group"].startswith("ios-cloud-"))

    def test_minimum_permissions_no_ignored_failures(self):
        for document in (self.ci, self.ios):
            self.assertEqual(document["permissions"], {"contents": "read"})
            self.assertNotIn("pull_request_target", document["on"])
            for job in document["jobs"].values():
                self.assertNotIn("continue-on-error", job)
                if "uses" not in job and job.get("name") != "governance / required":
                    self.assertNotIn("if", job)
                for step in job.get("steps", []):
                    self.assertNotIn("continue-on-error", step)
                    self.assertNotIn("if", step)

    def test_required_quality_and_existing_builds_remain(self):
        steps = self.ci["jobs"]["validate-and-test"]["steps"]
        script = "\n".join(step.get("run", "") for step in steps)
        for token in ("tools/ci/check_dart_format.py", "flutter analyze --no-pub",
                      "dance_domain", "dance_ui", "dance_native", "mobile/app",
                      "flutter test", ":dance_native:lintDebug :app:lintDebug",
                      ":dance_native:testDebugUnitTest", ":dance_native:compileReleaseKotlin",
                      "flutter build apk --debug", "test_governance"):
            if token == "test_governance":
                self.assertIn("unittest discover -s tools/ci", script)
            else:
                self.assertIn(token, script)
        flutter = [step for step in steps if step.get("uses", "").startswith("subosito/flutter-action@")]
        self.assertEqual(flutter[0]["with"]["flutter-version"], "3.44.2")
        self.assertNotIn("channel", flutter[0]["with"])
        for module in ("mobile/app/android/app", "mobile/packages/dance_native/android"):
            gradle = (ROOT / module / "build.gradle.kts").read_text()
            self.assertNotIn("abortOnError = false", gradle)

    def test_phase6_is_explicit_and_runs_exactly_once(self):
        steps = self.ios["jobs"]["ios-build"]["steps"]
        script = "\n".join(step.get("run", "") for step in steps)
        self.assertEqual(script.count("python tools/ios/run_phase6_macos_gate.py"), 1)
        self.assertIn("python tools/release/verify_ios_phase1.py", script)
        self.assertIn("flutter build ios --debug --no-codesign --target lib/main.dart", script)
        self.assertIn("python tools/release/verify_ios_phase4.py", script)
        verifier = (ROOT / "tools/release/verify_ios_phase1.py").read_text()
        self.assertNotIn("subprocess.run", verifier)
        self.assertNotIn("GITHUB_ACTIONS", verifier)
        self.assertTrue(any(step.get("uses", "").startswith("actions/upload-artifact@") for step in steps))
        flutter = [step for step in steps if step.get("uses", "").startswith("subosito/flutter-action@")]
        self.assertEqual(flutter[0]["with"]["flutter-version"], "3.44.2")


class AppleGateMigrationTests(unittest.TestCase):
    def test_each_inherited_phase_rejects_missing_explicit_gate(self):
        from tools.release import verify_ios_phase3 as phase3
        from tools.release import verify_ios_phase4 as phase4
        from tools.release import verify_ios_phase5 as phase5
        from tools.release import verify_ios_phase6 as phase6
        targets = ((phase3, phase3.verify_metal_validation_lanes),
                   (phase4, phase4.verify_real_video_gate),
                   (phase5, phase5.verify_cloud_gate),
                   (phase6, phase6.verify_cloud_gate))
        original = Path.read_text
        workflow_path = ROOT / ".github/workflows/ios-cloud.yml"

        def without_gate(path, *args, **kwargs):
            content = original(path, *args, **kwargs)
            if path == workflow_path:
                return content.replace("run: python tools/ios/run_phase6_macos_gate.py", "run: echo removed")
            return content

        for module, verify in targets:
            with self.subTest(phase=module.__name__):
                module.FAILURES.clear()
                verify()
                self.assertEqual(module.FAILURES, [])
                with patch.object(Path, "read_text", without_gate):
                    verify()
                self.assertTrue(any("explicitly invoke" in failure for failure in module.FAILURES))
                module.FAILURES.clear()


class FormatSelectionTests(unittest.TestCase):
    def test_only_exact_generated_outputs_are_excluded(self):
        handwritten = ["mobile/app/lib/a.dart", "mobile/packages/dance_native/pigeons/dance_api.dart",
                       "mobile/packages/dance_native/example/lib/main.dart",
                       "mobile/packages/dance_native/lib/src/other.g.dart"]
        paths = [*handwritten, *GENERATED, "desktop/vendor/a.dart", "mobile/README.md", ""]
        self.assertEqual(handwritten_files(paths), sorted(handwritten))


if __name__ == "__main__":
    unittest.main()
