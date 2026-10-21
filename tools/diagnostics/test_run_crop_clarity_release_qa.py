"""Host-only preflight tests for single-command paired Release QA.

Simulates Flutter/ADB, creates tiny harmless APK placeholders outside the repo.
Never installs anything, connects to a device, decodes video or reads secrets.
"""
from __future__ import annotations

import contextlib
from io import StringIO
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.diagnostics import run_crop_clarity_release_qa as workflow
from tools.diagnostics.test_crop_clarity_release_perf import record as fixture


HEAD = "a" * 40
BRANCH = "feat/crop-clarity-phase2-quality-performance"


class FakeBuilder:
    def __init__(self, target: Path, *, dirty: bool = False,
                 fail_on: str = "") -> None:
        self.target = target
        self.calls: list[list[str]] = []
        self.dirty = dirty
        self.fail_on = fail_on

    def __call__(self, args, **kwargs):
        args = list(args)
        self.calls.append(args)
        joined = " ".join(args)
        if args and args[0] == "flutter":
            if kwargs.get("env", {}).get("GIT_COMMIT_SHA") != HEAD:
                raise AssertionError("Both Flutter builds must embed the same full Git HEAD")
            if not kwargs.get("stream_output") and kwargs.get("capture_output") is True:
                raise AssertionError("Flutter should surface live build diagnostics")
        if self.fail_on and self.fail_on in joined:
            return subprocess.CompletedProcess(args, 1, "", "synthetic failure")
        if args[:2] == ["git", "rev-parse"]:
            return subprocess.CompletedProcess(args, 0, HEAD + "\n", "")
        if args[:3] == ["git", "branch", "--show-current"]:
            return subprocess.CompletedProcess(args, 0, BRANCH + "\n", "")
        if args[:3] == ["git", "status", "--porcelain"]:
            return subprocess.CompletedProcess(
                args, 0, " M mobile/app/lib/dirty.dart\n" if self.dirty else "", "",
            )
        if args[:3] == ["flutter", "build", "apk"]:
            self.target.parent.mkdir(parents=True, exist_ok=True)
            stamp = b"OFF" if "--dart-define=WOAH_BENCHMARK_CROP_CLARITY_OFF=true" in args else b"ON!"
            self.target.write_bytes(stamp * 800)
        return subprocess.CompletedProcess(args, 0, "OK\n", "")


class FakeInstall:
    def __init__(self, *, fail_mode: str = "") -> None:
        self.commands: list[list[str]] = []
        self.fail_mode = fail_mode

    def __call__(self, args, **kwargs):
        args = list(args)
        self.commands.append(args)
        if self.fail_mode and any(self.fail_mode in arg for arg in args):
            return subprocess.CompletedProcess(args, 1, "", "synthetic install failed")
        return subprocess.CompletedProcess(args, 0, "Success\n", "")


def simulate_capture(
    filename: Path, *, mode: str, repeats: int, timeout_minutes: int,
    adb: str, serial: str | None, expected_commit: str | None
) -> None:
    assert expected_commit == HEAD
    assert repeats == 3
    filename.write_text("".join(
        "I/WoahExportPerf: " + json.dumps(fixture(
            mode, elapsed=1000 + i * 10 if mode == "off" else 1050 + i * 10,
            git_commit=HEAD,
        )) + "\n"
        for i in range(3)
    ), encoding="utf-8")


class ReleasePairWorkflowTest(unittest.TestCase):
    def build(self, output: Path, *, dirty: bool = False) -> tuple[dict, FakeBuilder]:
        fake_path = output.parent / "fixture-generated.apk"
        fake = FakeBuilder(fake_path, dirty=dirty)
        with patch.object(workflow, "GENERATED_APK", fake_path):
            manifest = workflow.build_pair(output, runner=fake)
        return manifest, fake

    def test_build_pins_commit_and_two_distinct_artifact_hashes_outside_git(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            manifest, fake = self.build(output)
            self.assertEqual(manifest["git_commit"], HEAD)
            self.assertEqual(manifest["git_branch"], BRANCH)
            self.assertEqual(set(manifest["apks"]), {"off", "on"})
            self.assertNotEqual(manifest["apks"]["off"]["sha256"],
                                manifest["apks"]["on"]["sha256"])
            self.assertEqual(manifest["release_acceptance"], "NOT_PROVEN")
            self.assertTrue((output / workflow.OFF_APK).is_file())
            self.assertTrue((output / workflow.ON_APK).is_file())
            self.assertTrue((output / workflow.MANIFEST).is_file())
            self.assertFalse((output / workflow.COMPARISON).exists())
            builds = [cmd for cmd in fake.calls if cmd[:3] == [
                "flutter", "build", "apk"
            ]]
            self.assertEqual(len(builds), 2)
            self.assertIn("--dart-define=WOAH_BENCHMARK_CROP_CLARITY_OFF=true", builds[0])
            self.assertNotIn("--dart-define=WOAH_BENCHMARK_CROP_CLARITY_OFF=true", builds[1])
            self.assertEqual(fake.calls[3][:3], ["flutter", "pub", "get"])

    def test_run_installs_existing_pair_and_collects_three_each_then_compares(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            calls: list[str] = []
            fake_installer = FakeInstall()

            def fake_collect(path: Path, **kwargs):
                calls.append(kwargs["mode"])
                simulate_capture(path, **kwargs)

            fake_git = FakeBuilder(output.parent / "irrelevant.apk")
            def runner(args, **kwargs):
                if args[:1] == ["git"]:
                    return fake_git(args, **kwargs)
                return fake_installer(args, **kwargs)

            report = workflow.run_pair(
                output, scenario="same-consented-video",
                acknowledge_install=True, repeats=3,
                runner=runner, collect=fake_collect,
            )
            self.assertEqual(calls, ["off", "on"])
            installs = [cmd for cmd in fake_installer.commands if "install" in cmd]
            self.assertEqual(len(installs), 2)
            self.assertTrue(installs[0][-1].endswith(workflow.OFF_APK))
            self.assertTrue(installs[1][-1].endswith(workflow.ON_APK))
            self.assertNotIn("uninstall", str(installs))
            self.assertNotIn("logcat -c", str(installs))
            self.assertEqual(report["off"]["runs"], 3)
            self.assertEqual(report["on"]["runs"], 3)
            self.assertEqual(report["release_acceptance"], "REQUIRES_DEVICE_REVIEW")
            self.assertEqual(report["wall_elapsed_slowdown_percent"], 4.95)
            saved = json.loads((output / workflow.COMPARISON).read_text())
            self.assertEqual(saved, report)
            self.assertFalse((output / (workflow.COMPARISON + ".partial")).exists())

    def test_install_requires_explicit_acknowledgement_before_any_adb(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            fake = FakeInstall()
            with self.assertRaisesRegex(ValueError, "acknowledge-test-install"):
                workflow.run_pair(
                    output, scenario="same-consented-video",
                    acknowledge_install=False, runner=fake,
                )
            self.assertEqual(fake.commands, [])

    def test_pair_rejects_tampered_apk_and_wrong_git_before_any_install(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            file = output / workflow.OFF_APK
            file.write_bytes(b"E" * file.stat().st_size)
            fake_git = FakeBuilder(output.parent / "irrelevant.apk")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                workflow.verify_pair(output, runner=fake_git)
            with self.assertRaises(ValueError):
                workflow.run_pair(
                    output, scenario="same-consented-video",
                    acknowledge_install=True, runner=fake_git,
                )
            self.assertFalse(any("install" in cmd for cmd in fake_git.calls))

    def test_wrong_output_directory_or_dirty_git_blocks_build(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            with self.assertRaisesRegex(ValueError, "outside the Git"):
                workflow.build_pair(workflow.ROOT / "leaked-release", runner=FakeBuilder(
                    Path(dirname) / "generated.apk"
                ))
            with self.assertRaisesRegex(ValueError, "clean named Git branch"):
                self.build(output, dirty=True)
            self.assertFalse((output / workflow.OFF_APK).exists())

    def test_existing_dir_never_overwritten_and_missing_artifact_fails(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            with self.assertRaisesRegex(ValueError, "already contains files"):
                self.build(output)
            (output / workflow.ON_APK).unlink()
            with self.assertRaisesRegex(ValueError, "missing or size mismatch"):
                workflow.verify_pair(
                    output, runner=FakeBuilder(Path(dirname) / "unused.apk")
                )

    def test_failed_install_never_uninstalls_or_collects_data(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            fake_git = FakeBuilder(output.parent / "irrelevant.apk")
            fake_installer = FakeInstall(fail_mode=workflow.OFF_APK)
            def runner(args, **kwargs):
                if args[:1] == ["git"]:
                    return fake_git(args, **kwargs)
                return fake_installer(args, **kwargs)
            with self.assertRaisesRegex(RuntimeError, "step failed"):
                workflow.run_pair(
                    output, scenario="same-consented-video",
                    acknowledge_install=True, runner=runner,
                    collect=lambda *args, **kwargs: self.fail("capture after install failure"),
                )
            self.assertEqual(len(fake_installer.commands), 1)
            self.assertFalse((output / workflow.COMPARISON).exists())

    def test_manifest_is_strict_and_synthetic_data_cannot_claim_acceptance(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            manifest = json.loads((output / workflow.MANIFEST).read_text())
            manifest["privacy_visual_acceptance"] = "PASS"
            (output / workflow.MANIFEST).write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "invalid or wrong Git HEAD"):
                workflow.verify_pair(
                    output, runner=FakeBuilder(output.parent / "unused.apk")
                )

    def test_all_action_requires_install_permission_before_expensive_flutter_build(self):
        with tempfile.TemporaryDirectory() as dirname:
            with patch.object(workflow, "build_pair", side_effect=AssertionError("unexpected build")), \
                 patch("sys.argv", [
                     "release-qa", "--action", "all",
                     "--output-dir", str(Path(dirname) / "release"),
                 ]), contextlib.redirect_stderr(StringIO()) as error:
                self.assertEqual(workflow.main(), 1)
                self.assertIn("acknowledge-test-install", error.getvalue())

    def test_invalid_nan_budget_never_installs_an_app(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            runner = FakeInstall()
            with self.assertRaisesRegex(ValueError, "invalid provisional slowdown"):
                workflow.run_pair(
                    output, scenario="same-consented-video",
                    acknowledge_install=True, max_slowdown_percent=float("nan"),
                    runner=runner,
                )
            self.assertEqual(runner.commands, [])

    def test_build_failure_never_creates_success_manifest(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            generated = Path(dirname) / "newly-generated.apk"
            fake = FakeBuilder(generated, fail_on="flutter build apk")
            with patch.object(workflow, "GENERATED_APK", generated):
                with self.assertRaisesRegex(RuntimeError, "step failed"):
                    workflow.build_pair(output, runner=fake)
            self.assertFalse((output / workflow.MANIFEST).exists())
            self.assertFalse((output / workflow.OFF_APK).exists())

    def test_feature_branch_runs_production_ci_on_push_before_merge(self):
        workflow_source = (workflow.ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn(
            "branches: [ main, master, feat/crop-clarity-phase2-quality-performance ]",
            workflow_source,
        )
        self.assertIn("workflow_dispatch:", workflow_source)
        self.assertIn("./gradlew :dance_native:testDebugUnitTest", workflow_source)
        self.assertIn("./gradlew :dance_native:compileDebugAndroidTestKotlin", workflow_source)

    def test_native_package_id_and_release_signing_contract_are_pinned(self):
        source = (workflow.APP_DIR / "android/app/build.gradle.kts").read_text()
        self.assertIn('applicationId = "art.gaoge.dance"', source)
        self.assertIn("validateReleaseSigning", source)
        self.assertIn("hasReleaseSigning", source)
        self.assertIn("WOAH_KEYSTORE_PATH", source)

    def test_wrapper_source_has_no_video_reader_unsafe_install_or_data_clear(self):
        source = Path(workflow.__file__).read_text(encoding="utf-8")
        for token in (
            "logcat -c", "adb uninstall", "import cv2", "import numpy",
            "from PIL", "ffmpeg", "adb shell pm clear", "requests",
        ):
            self.assertNotIn(token, source)
        self.assertIn("acknowledge_install is not True", source)
        self.assertIn('prefix + ["install", "-r", str(output / filename)]', source)


if __name__ == "__main__":
    unittest.main()
