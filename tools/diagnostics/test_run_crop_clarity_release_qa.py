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
DEVICE = "WOAH_QA_DEVICE_001"


def fake_inspector(_path: Path) -> dict:
    return {
        "app_id": "art.gaoge.dance",
        "version_code": 1,
        "version_name": "0.1.0",
        "signer_sha256": "b" * 64,
    }


class FakeBuilder:
    def __init__(self, target: Path, *, dirty: bool = False,
                 fail_on: str = "", status: str | None = None) -> None:
        self.target = target
        self.calls: list[list[str]] = []
        self.flutter_environments: list[dict] = []
        self.dirty = dirty
        self.fail_on = fail_on
        self.status = status

    def __call__(self, args, **kwargs):
        args = list(args)
        self.calls.append(args)
        joined = " ".join(args)
        if args and args[0] == "flutter":
            self.flutter_environments.append(kwargs.get("env", {}).copy())
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
                args, 0, (self.status if self.status is not None else
                          " M mobile/app/lib/dirty.dart\n" if self.dirty else ""), "",
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
        if args[-1] == "get-serialno":
            return subprocess.CompletedProcess(args, 0, DEVICE + "\n", "")
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
    def build(self, output: Path, *, dirty: bool = False,
              inspector=fake_inspector) -> tuple[dict, FakeBuilder]:
        fake_path = output.parent / "fixture-generated.apk"
        fake = FakeBuilder(fake_path, dirty=dirty)
        with patch.object(workflow, "GENERATED_APK", fake_path):
            manifest = workflow.build_pair(output, runner=fake, inspector=inspector)
        return manifest, fake

    def test_build_pins_commit_and_two_distinct_artifact_hashes_outside_git(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            manifest, fake = self.build(output)
            self.assertEqual(manifest["schema"], 2)
            self.assertEqual(manifest["git_commit"], HEAD)
            self.assertEqual(manifest["git_branch"], BRANCH)
            self.assertEqual(set(manifest["apks"]), {"off", "on"})
            self.assertNotEqual(manifest["apks"]["off"]["sha256"],
                                manifest["apks"]["on"]["sha256"])
            self.assertEqual(manifest["release_acceptance"], "NOT_PROVEN")
            self.assertEqual(manifest["apks"]["off"]["apk_identity"], fake_inspector(Path("off")))
            self.assertEqual(manifest["apks"]["on"]["apk_identity"], fake_inspector(Path("on")))
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
                self.assertEqual(kwargs["serial"], DEVICE)
                simulate_capture(path, **kwargs)

            fake_git = FakeBuilder(output.parent / "irrelevant.apk")
            def runner(args, **kwargs):
                if args[:1] == ["git"]:
                    return fake_git(args, **kwargs)
                return fake_installer(args, **kwargs)

            report = workflow.run_pair(
                output, scenario="same-consented-video",
                acknowledge_install=True, repeats=3,
                runner=runner, collect=fake_collect, inspector=fake_inspector,
            )
            self.assertEqual(calls, ["off", "on"])
            self.assertNotIn(DEVICE, (output / workflow.COMPARISON).read_text())
            self.assertTrue(all(cmd[:3] == ["adb", "-s", DEVICE]
                                for cmd in fake_installer.commands
                                if "install" in cmd))
            self.assertEqual(sum(cmd[-1] == "get-serialno"
                                 for cmd in fake_installer.commands), 3)
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
                workflow.verify_pair(output, runner=fake_git, inspector=fake_inspector)
            with self.assertRaises(ValueError):
                workflow.run_pair(
                    output, scenario="same-consented-video",
                    acknowledge_install=True, runner=fake_git,
                    inspector=fake_inspector,
                )
            self.assertFalse(any("install" in cmd for cmd in fake_git.calls))

    def test_wrong_output_directory_or_dirty_git_blocks_build(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            with self.assertRaisesRegex(ValueError, "outside the Git"):
                workflow.build_pair(workflow.ROOT / "leaked-release", runner=FakeBuilder(
                    Path(dirname) / "generated.apk"
                ))
            with self.assertRaisesRegex(ValueError, "source provenance blocked"):
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
                    output, runner=FakeBuilder(Path(dirname) / "unused.apk"),
                    inspector=fake_inspector,
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
                    inspector=fake_inspector,
                    collect=lambda *args, **kwargs: self.fail("capture after install failure"),
                )
            self.assertEqual(len([cmd for cmd in fake_installer.commands if "install" in cmd]), 1)
            self.assertFalse((output / workflow.COMPARISON).exists())

    def test_build_rejects_two_sibling_apks_with_different_signing_certs(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            def mismatched_cert(apk):
                identity = fake_inspector(apk)
                if apk.name == workflow.ON_APK:
                    identity["signer_sha256"] = "c" * 64
                return identity
            with self.assertRaisesRegex(ValueError, "different signing certificates"):
                self.build(output, inspector=mismatched_cert)
            self.assertFalse((output / workflow.MANIFEST).exists())

    def test_manifest_and_actual_apk_signer_identity_must_agree_before_install(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            runner = FakeBuilder(output.parent / "unused.apk")
            def changed_cert(apk):
                identity = fake_inspector(apk)
                identity["signer_sha256"] = "c" * 64
                return identity
            with self.assertRaisesRegex(ValueError, "signing/package identity changed"):
                workflow.verify_pair(output, runner=runner, inspector=changed_cert)
            with self.assertRaisesRegex(ValueError, "signing/package identity changed"):
                workflow.run_pair(
                    output, scenario="same-consented-video",
                    acknowledge_install=True, runner=runner, inspector=changed_cert,
                )
            self.assertFalse(any("install" in cmd for cmd in runner.calls))

    def test_wrong_app_id_or_versions_block_apk_pair_before_manifest(self):
        for field, value in (("app_id", "art.attacker"),
                             ("version_code", 0), ("signer_sha256", "invalid")):
            with tempfile.TemporaryDirectory() as dirname:
                output = Path(dirname) / "release-qa"
                def invalid_inspector(apk):
                    identity = fake_inspector(apk)
                    identity[field] = value
                    return identity
                with self.subTest(field=field), self.assertRaisesRegex(
                    ValueError, "invalid signed APK"
                ):
                    self.build(output, inspector=invalid_inspector)
                self.assertFalse((output / workflow.MANIFEST).exists())

    def test_device_switch_between_off_and_on_aborts_before_second_install(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            fake_git = FakeBuilder(output.parent / "unused.apk")
            class SwitchedDevice(FakeInstall):
                def __init__(self):
                    super().__init__()
                    self.serial_reads = 0
                def __call__(self, args, **kwargs):
                    if args[-1] == "get-serialno":
                        self.commands.append(list(args))
                        self.serial_reads += 1
                        serial = "OTHER_DEVICE" if self.serial_reads == 3 else DEVICE
                        return subprocess.CompletedProcess(args, 0, serial + "\n", "")
                    return super().__call__(args, **kwargs)
            device = SwitchedDevice()
            def runner(args, **kwargs):
                return fake_git(args, **kwargs) if args[0] == "git" else device(args, **kwargs)
            with self.assertRaisesRegex(ValueError, "selected Android device identity"):
                workflow.run_pair(
                    output, scenario="same-consented-video",
                    acknowledge_install=True, runner=runner,
                    inspector=fake_inspector, collect=simulate_capture,
                )
            self.assertEqual(sum("install" in cmd for cmd in device.commands), 1)
            self.assertFalse((output / workflow.COMPARISON).exists())

    def test_no_confirmed_online_adb_serial_blocks_install(self):
        for bad_serial in ("unknown", "offline", "", "other device", "unauthorized"):
            def runner(args, **kwargs):
                return subprocess.CompletedProcess(args, 0, bad_serial + "\n", "")
            with self.subTest(serial=bad_serial), self.assertRaises(ValueError):
                workflow.selected_adb_serial(adb="adb", requested=None, runner=runner)

    def test_manifest_is_strict_and_synthetic_data_cannot_claim_acceptance(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            self.build(output)
            manifest = json.loads((output / workflow.MANIFEST).read_text())
            manifest["privacy_visual_acceptance"] = "PASS"
            (output / workflow.MANIFEST).write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "invalid or wrong Git HEAD"):
                workflow.verify_pair(
                    output, runner=FakeBuilder(output.parent / "unused.apk"),
                    inspector=fake_inspector,
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
                    runner=runner, inspector=fake_inspector,
                )
            self.assertEqual(runner.commands, [])

    def test_build_failure_never_creates_success_manifest(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            generated = Path(dirname) / "newly-generated.apk"
            fake = FakeBuilder(generated, fail_on="flutter build apk")
            with patch.object(workflow, "GENERATED_APK", generated):
                with self.assertRaisesRegex(RuntimeError, "step failed"):
                    workflow.build_pair(output, runner=fake, inspector=fake_inspector)
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
        self.assertIn("./gradlew :dance_native:compileReleaseKotlin", workflow_source)
        self.assertIn("./gradlew :dance_native:compileDebugAndroidTestKotlin", workflow_source)
        # Production Release compile is a JVM ABI gate, not a signed APK or
        # a simulated performance test. It must not require a keystore.
        release_step = workflow_source.split("- name: Compile Native Android Release Kotlin", 1)[1].split("- name:", 1)[0]
        self.assertIn("compileReleaseKotlin", release_step)
        self.assertNotIn("assembleRelease", release_step)
        self.assertNotIn("flutter build apk --release", release_step)

    def test_native_package_id_and_release_signing_contract_are_pinned(self):
        source = (workflow.APP_DIR / "android/app/build.gradle.kts").read_text()
        self.assertIn('applicationId = "art.gaoge.dance"', source)
        self.assertIn("validateReleaseSigning", source)
        self.assertIn("hasReleaseSigning", source)
        self.assertIn("WOAH_KEYSTORE_PATH", source)

    def test_main_passes_explicit_qa_signing_to_build_and_install(self):
        with tempfile.TemporaryDirectory() as directory:
            target = str(Path(directory) / "fresh-qa")
            with patch.object(workflow, "build_pair", return_value={"schema": 2}) as builder, \
                 patch.object(workflow, "run_pair", return_value={}) as runner, \
                 patch("sys.argv", [
                     "release-qa", "--action", "all", "--output-dir", target,
                     "--qa-debug-signing", "--acknowledge-test-install",
                 ]):
                self.assertEqual(workflow.main(), 0)
            self.assertTrue(builder.call_args.kwargs["qa_debug_signing"])
            self.assertTrue(runner.call_args.kwargs["qa_debug_signing"])
            self.assertTrue(runner.call_args.kwargs["acknowledge_install"])

    def test_windows_flutter_debug_scaffolds_and_app_ab_zips_are_not_release_source_dirt(self):
        # Actual user workspace showed these four collapsed untracked entries:
        #   ?? mobile/app/android/app/src/debug/
        #   ?? mobile/app/crop_clarity_ab.zip
        #   ?? mobile/app/crop_clarity_ab_raw.zip
        #   ?? mobile/packages/dance_native/example/android/app/src/debug/
        # Git -z -uall enumerates the contained leaf files, rather than dirs.
        actual = (
            "?? mobile/app/android/app/src/debug/AndroidManifest.xml\0"
            "?? mobile/app/crop_clarity_ab.zip\0"
            "?? mobile/app/crop_clarity_ab_raw.zip\0"
            "?? mobile/packages/dance_native/example/android/app/src/debug/AndroidManifest.xml\0"
        )
        with tempfile.TemporaryDirectory() as dirname:
            fake = FakeBuilder(
                Path(dirname) / "apk-fixture.apk", status=actual,
            )
            self.assertEqual(workflow.git_identity(runner=fake), (HEAD, BRANCH))
            self.assertIn(
                ["git", "status", "--porcelain", "-z", "--untracked-files=all"],
                fake.calls,
            )
            self.assertTrue(workflow._safe_untracked_evidence(
                "mobile\\app\\crop_clarity_ab_raw.zip"
            ))

    def test_only_exact_debug_manifest_and_ab_zip_paths_may_be_excluded(self):
        tolerated = (
            "mobile/app/android/app/src/debug/AndroidManifest.xml",
            "mobile/packages/dance_native/example/android/app/src/debug/AndroidManifest.xml",
            "mobile/app/crop_clarity_ab.zip",
            "mobile/app/crop_clarity_ab_raw.zip",
            "mobile/app/crop_clarity_ab(1).zip",
        )
        blocked = (
            # Collapsed directories must never be blindly accepted.
            "mobile/app/android/app/src/debug/",
            "mobile/packages/dance_native/example/android/app/src/debug/",
            # Unknown debug Kotlin/Java/code and other source sets must block.
            "mobile/app/android/app/src/debug/kotlin/Malicious.kt",
            "mobile/app/android/app/src/debug/Helper.java",
            "mobile/app/android/app/src/debug/res/raw/hidden_model.tflite",
            "mobile/app/android/app/src/main/AndroidManifest.xml",
            "mobile/app/android/app/src/release/AndroidManifest.xml",
            "mobile/packages/dance_native/android/src/debug/kotlin/Shadow.kt",
            "mobile/packages/dance_native/example/android/app/src/debug/Extra.kt",
            "mobile/app/android/app/build.gradle.kts",
            # Only two known protected QA archive names, not a broad zip bypass.
            "mobile/app/crop_clarity_other.zip",
            "mobile/app/user-video.zip",
            "mobile/app/lib/crop_clarity_ab.zip",
            "mobile/app/crop_clarity_ab_raw.exe",
        )
        for path in tolerated:
            with self.subTest(tolerated=path):
                self.assertTrue(workflow._safe_untracked_evidence(path))
        for path in blocked:
            with self.subTest(blocked=path):
                self.assertFalse(workflow._safe_untracked_evidence(path))
        with tempfile.TemporaryDirectory() as dirname:
            fake = FakeBuilder(Path(dirname) / "apk", status=(
                "?? mobile/app/android/app/src/debug/AndroidManifest.xml\0"
                "?? mobile/app/android/app/src/debug/kotlin/Shadow.kt\0"
            ))
            with self.assertRaisesRegex(
                ValueError, r"0 tracked changes, 1 unrecognized untracked files"
            ) as raised:
                workflow.git_identity(runner=fake)
            self.assertIn("1 isolated QA artifacts tolerated", str(raised.exception))
            self.assertIn("--untracked-files=all", str(raised.exception))

    def test_untracked_qa_artifacts_are_tolerated_but_untracked_source_is_not(self):
        with tempfile.TemporaryDirectory() as dirname:
            target = Path(dirname) / "generated.apk"
            safe = FakeBuilder(target, status=(
                "?? woah_diag_20261021.zip\n"
                "?? crop_clarity_ab(1).zip\n"
                "?? logs/archived-private-diagnostic.json\n"
            ))
            self.assertEqual(workflow.git_identity(runner=safe), (HEAD, BRANCH))
            # Git status -z is unquoted, so spaces and Unicode in diagnostic
            # filenames do not require manual sanitization on Windows.
            self.assertEqual(workflow.git_identity(runner=FakeBuilder(
                target, status="?? logs/隐私测试 记录.json\0?? crop_clarity_ab(1).zip\0"
            )), (HEAD, BRANCH))
            for path in (
                "tools/diagnostics/new_script.py",
                "mobile/app/lib/new_editor.dart",
                "mobile/packages/dance_native/android/src/main/kotlin/Shadow.kt",
                "release-config.gradle.kts",
            ):
                fake = FakeBuilder(target, status="?? " + path + "\n")
                with self.subTest(path=path), self.assertRaisesRegex(
                    ValueError, "0 tracked changes, 1 unrecognized untracked"
                ):
                    workflow.git_identity(runner=fake)
            self.assertEqual(
                workflow._safe_untracked_evidence("docs/session/report.json"), True
            )
            self.assertEqual(
                workflow._safe_untracked_evidence("artifacts/patch.py"), False
            )

    def test_git_error_differentiates_tracked_edits_from_untracked_data(self):
        with tempfile.TemporaryDirectory() as dirname:
            fake = FakeBuilder(
                Path(dirname) / "unused.apk",
                status=" M mobile/app/lib/export_controller.dart\n?? woah_diag_1.zip\n",
            )
            with self.assertRaisesRegex(ValueError, "1 tracked changes, 0 unrecognized"):
                workflow.git_identity(runner=fake)

    def test_existing_local_debug_keystore_only_sets_qa_process_environment(self):
        with tempfile.TemporaryDirectory() as dirname:
            home = Path(dirname) / "test-user"
            key_dir = home / ".android"
            key_dir.mkdir(parents=True)
            keystore = key_dir / "debug.keystore"
            starting = {"WOAH_KEYSTORE_PATH": "/private/release-secret.jks",
                        "SOME_UNRELATED_CONFIG": "retained"}
            with self.assertRaisesRegex(ValueError, "QA debug-signing keystore absent"):
                workflow._qa_debug_signing_environment(starting, home=home)
            keystore.write_bytes(b"synthetic existing key not used or loaded")
            result = workflow._qa_debug_signing_environment(starting, home=home)
            self.assertEqual(result["WOAH_KEYSTORE_PATH"], str(keystore.resolve()))
            self.assertEqual(result["WOAH_KEY_ALIAS"], "androiddebugkey")
            self.assertEqual(result["WOAH_KEYSTORE_PASSWORD"], "android")
            self.assertEqual(result["WOAH_KEY_PASSWORD"], "android")
            self.assertEqual(result["SOME_UNRELATED_CONFIG"], "retained")
            self.assertEqual(starting["WOAH_KEYSTORE_PATH"], "/private/release-secret.jks")

    def test_qa_debug_signed_release_is_not_mislabelled_or_usable_without_opt_in(self):
        with tempfile.TemporaryDirectory() as dirname:
            output = Path(dirname) / "release-qa"
            generated = Path(dirname) / "flutter-qa-generated.apk"
            fake = FakeBuilder(generated)
            with patch.object(workflow, "GENERATED_APK", generated), \
                 patch.object(
                    workflow, "_qa_debug_signing_environment",
                    side_effect=lambda current: {
                        **current,
                        "WOAH_KEYSTORE_PATH": "SYNTHETIC_DEBUG_KEY",
                        "WOAH_KEY_ALIAS": "androiddebugkey",
                        "WOAH_KEYSTORE_PASSWORD": "android",
                        "WOAH_KEY_PASSWORD": "android",
                    },
                 ):
                manifest = workflow.build_pair(
                    output, runner=fake, inspector=fake_inspector,
                    qa_debug_signing=True,
                )
            self.assertIs(manifest["qa_debug_signing"], True)
            self.assertEqual(
                manifest["signing_scope"], workflow.QA_SIGNING_DESCRIPTION
            )
            self.assertEqual(manifest["release_acceptance"], "NOT_PROVEN")
            for mode in ("off", "on"):
                self.assertTrue((output / manifest["apks"][mode]["filename"]).exists())
            self.assertEqual(len(fake.flutter_environments), 3)
            self.assertTrue(all(
                env["WOAH_KEY_ALIAS"] == "androiddebugkey"
                for env in fake.flutter_environments
            ))
            with self.assertRaisesRegex(ValueError, "manifest invalid or wrong Git HEAD"):
                workflow.verify_pair(output, runner=fake, inspector=fake_inspector)
            verified = workflow.verify_pair(
                output, runner=fake, inspector=fake_inspector,
                qa_debug_signing=True,
            )
            self.assertEqual(verified["signing_scope"], workflow.QA_SIGNING_DESCRIPTION)
            def runner(cmd, **kwargs):
                return fake(cmd, **kwargs) if cmd[0] == "git" else FakeInstall()(cmd, **kwargs)
            report = workflow.run_pair(
                output, scenario="same-consented-video", acknowledge_install=True,
                runner=runner, collect=simulate_capture, inspector=fake_inspector,
                qa_debug_signing=True,
            )
            self.assertEqual(report["apk_signing_scope"], workflow.QA_SIGNING_DESCRIPTION)
            self.assertEqual(report["distribution_acceptance"], "NOT_PROVEN")
            self.assertEqual(report["release_acceptance"], "REQUIRES_DEVICE_REVIEW")

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
