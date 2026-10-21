"""Synthetic host-only tests for Stage 3 debug-private model transport.

Mocks adb completely: no Android device, APK, media file or model is needed.
"""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

from tools.diagnostics import stage_magic_touch_android_test as staging
from tools.diagnostics.test_analyze_magic_touch_stage3_smoke import (
    record as fake_record, logcat as fake_logcat,
)
import json


SHA = "a" * 64
PACKAGE = "art.gaoge.dance.engine"


def unique_synthetic_tokens():
    # Deterministic and distinct; the production helper uses secrets.token_hex.
    values = iter(("f" * 16, "e" * 16, "d" * 16, "c" * 16, "b" * 16))
    return lambda _: next(values)


class FakeADB:
    def __init__(self, *, device_sha: str = SHA, failing_command: str = "") -> None:
        self.commands: list[list[str]] = []
        self.pushed: bytes = b""
        self.device_sha = device_sha
        self.failing_command = failing_command

    def __call__(self, command, *, stdin=None, capture_output=True, text=True,
                 timeout=45, check=False) -> subprocess.CompletedProcess:
        self.commands.append(list(command))
        if stdin is not None:
            self.pushed = stdin.read()
            self.pushed_from_binary_stdin = not text
        if self.failing_command and self.failing_command in " ".join(command):
            return subprocess.CompletedProcess(command, 1, "" if text else b"", b"mock error")
        output = self.device_sha + "  files/" + staging.REMOTE_BASENAME
        return subprocess.CompletedProcess(command, 0, output if text else output.encode(), "")


class FakeInstrumentedADB(FakeADB):
    def __init__(
        self, *, failure_at: int = -1, valid_component: bool = True,
        history_visible: bool = True,
    ) -> None:
        super().__init__()
        self.synthetic_runs = 0
        self.failure_at = failure_at
        self.valid_component = valid_component
        self.history_visible = history_visible
        self.run_tokens: list[str] = []

    def __call__(self, command, **kwargs):
        completed = super().__call__(command, **kwargs)
        rendered = " ".join(command)
        if "pm list instrumentation" in rendered:
            component = "art.gaoge.dance.engine.test/androidx.test.runner.AndroidJUnitRunner"
            target = PACKAGE if self.valid_component else "com.unknown.app"
            return subprocess.CompletedProcess(
                command, 0,
                f"instrumentation:{component} (target={target})\n", "",
            )
        if "am instrument" in rendered:
            self.synthetic_runs += 1
            success = self.synthetic_runs != self.failure_at
            if success:
                token_index = command.index(staging.TOKEN_ARG)
                self.run_tokens.append(command[token_index + 1])
            output = (
                "INSTRUMENTATION_CODE: -1\nTime: 0.52\n\nOK (1 test)\n"
                if success else "INSTRUMENTATION_CODE: 0\nFAILURES!!!\n"
            )
            return subprocess.CompletedProcess(command, 0, output, "")
        if "logcat -d" in rendered:
            # Android logcat may retain all earlier runs OR just the last one
            # after ring-buffer rotation. A correct host accepts either.
            visible = self.run_tokens if self.history_visible else self.run_tokens[-1:]
            entries = [fake_record(run_token=token) for token in visible]
            entries.insert(0, fake_record(run_token="0" * 16))
            return subprocess.CompletedProcess(command, 0, fake_logcat(*entries), "")
        return completed


class Stage3PublicModelProvisioningTest(unittest.TestCase):
    def test_pinned_model_streams_bytes_only_to_debug_private_storage(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.task"
            path.write_bytes(b"\x00PK\x00\xff\x01\xfe")
            fake = FakeADB()
            staging.stage_model(
                path, PACKAGE, adb="/bin/adb", serial="SYNTHETIC-001",
                run=fake, verifier=lambda _: SHA,
            )
            self.assertEqual(fake.pushed, path.read_bytes())
            self.assertTrue(fake.pushed_from_binary_stdin)
            self.assertEqual(len(fake.commands), 3)
            self.assertEqual(fake.commands[0], [
                "/bin/adb", "-s", "SYNTHETIC-001", "shell", "run-as",
                PACKAGE, "mkdir", "-p", "files",
            ])
            self.assertEqual(fake.commands[1], [
                "/bin/adb", "-s", "SYNTHETIC-001",
                "exec-in", "run-as", PACKAGE, "sh", "-c",
                "cat > files/" + staging.REMOTE_BASENAME,
            ])
            self.assertEqual(fake.commands[2][-3:], [
                PACKAGE, "sha256sum", "files/" + staging.REMOTE_BASENAME,
            ])
            self.assertNotIn("data/local/tmp", str(fake.commands))
            self.assertNotIn("sdcard", str(fake.commands))

    def test_staging_is_blocked_before_adb_if_public_model_verification_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "synthetic.task"
            artifact.write_bytes(b"not a pinned model")
            fake = FakeADB()
            with self.assertRaisesRegex(ValueError, "not verified"):
                staging.stage_model(
                    artifact, PACKAGE, run=fake,
                    verifier=lambda _: (_ for _ in ()).throw(ValueError("not verified")),
                )
            self.assertEqual(fake.commands, [])

    def test_binary_transfer_timeout_also_cleans_private_partial_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.task"
            path.write_bytes(b"synthetic public test data")
            class TimedADB(FakeADB):
                def __call__(self, command, **kwargs):
                    if "exec-in" in command:
                        self.commands.append(list(command))
                        raise subprocess.TimeoutExpired(command, 180)
                    return super().__call__(command, **kwargs)
            fake = TimedADB()
            with self.assertRaises(subprocess.TimeoutExpired):
                staging.stage_model(path, PACKAGE, run=fake, verifier=lambda _: SHA)
            self.assertEqual(fake.commands[-1][-3:], [
                "rm", "-f", "files/" + staging.REMOTE_BASENAME,
            ])

    def test_wrong_model_hash_triggers_exact_private_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.task"
            path.write_bytes(b"public test data")
            fake = FakeADB(device_sha="f" * 64)
            with self.assertRaisesRegex(ValueError, "SHA mismatch"):
                staging.stage_model(
                    path, PACKAGE, run=fake, verifier=lambda _: SHA
                )
            self.assertEqual(fake.commands[-1], [
                "adb", "shell", "run-as", PACKAGE, "rm", "-f",
                "files/" + staging.REMOTE_BASENAME,
            ])

    def test_missing_device_sha_reply_cleans_up_partial_model(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.task"
            path.write_bytes(b"public fixture")
            fake = FakeADB(device_sha="")
            with self.assertRaisesRegex(ValueError, "missing or malformed"):
                staging.stage_model(path, PACKAGE, run=fake, verifier=lambda _: SHA)
            self.assertEqual(fake.commands[-1][-3:], [
                "rm", "-f", "files/" + staging.REMOTE_BASENAME
            ])

    def test_stream_failure_also_cleans_up_partial_model(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.task"
            path.write_bytes(b"public data")
            fake = FakeADB(failing_command="exec-in")
            with self.assertRaisesRegex(RuntimeError, "ADB command failed"):
                staging.stage_model(path, PACKAGE, run=fake, verifier=lambda _: SHA)
            self.assertEqual(fake.commands[-1][-3:],
                             ["rm", "-f", "files/" + staging.REMOTE_BASENAME])

    def test_cleanup_never_wipes_app_or_other_files(self):
        fake = FakeADB()
        staging.cleanup_model(PACKAGE, run=fake)
        self.assertEqual(len(fake.commands), 1)
        self.assertEqual(fake.commands[0][-3:], [
            "rm", "-f", "files/" + staging.REMOTE_BASENAME
        ])
        self.assertNotIn("rm -rf", " ".join(fake.commands[0]))

    def test_rejects_non_woah_package_and_invalid_serial_before_adb(self):
        fake = FakeADB()
        for package in ("com.example.other", "art.gaoge.dance; rm -rf /", "",
                        "art.gaoge", "../art.gaoge.dance"):
            with self.assertRaises(ValueError):
                staging.cleanup_model(package, run=fake)
        with self.assertRaisesRegex(ValueError, "invalid ADB"):
            staging.stage_model(
                Path("/dev/null"), PACKAGE, serial="BAD;CODE",
                verifier=lambda _: SHA, run=fake,
            )
        self.assertEqual(fake.commands, [])

    def test_known_app_and_native_test_package_are_supported(self):
        for package in staging.ACCEPTED_DEBUG_PACKAGES:
            staging.validate_package(package)
        self.assertIn("art.gaoge.dance", staging.ACCEPTED_DEBUG_PACKAGES)

    def test_single_run_command_collects_three_tagged_cpu_smokes_and_cleans_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            output = root / "smoke-report.json"
            artifact.write_bytes(b"synthetic only")
            fake = FakeInstrumentedADB()
            report = staging.run_synthetic_smoke(
                artifact, PACKAGE, output, repeats=3, run=fake,
                verifier=lambda _: SHA, token_generator=unique_synthetic_tokens(),
            )
            self.assertEqual(fake.synthetic_runs, 3)
            self.assertEqual(report["runs"], 3)
            self.assertEqual(report["pixel_carve_authorization"], "NEVER")
            self.assertEqual(report["privacy_visual_acceptance"], "NOT_PROVEN")
            self.assertEqual(json.loads(output.read_text())["run_tokens"], [
                "f" * 16, "e" * 16, "d" * 16,
            ])
            self.assertEqual(report["run_token_policy"],
                             "UNIQUE_VERIFIED_TOKEN_PER_INSTRUMENTATION")
            self.assertEqual(fake.commands[-1][-3:], [
                "rm", "-f", "files/" + staging.REMOTE_BASENAME,
            ])
            joined = " ".join(" ".join(c) for c in fake.commands)
            self.assertNotIn("logcat -c", joined)
            self.assertEqual(joined.count("am instrument"), 3)
            self.assertEqual(joined.count(staging.TEST_CLASS), 3)

    def test_logcat_ring_buffer_rotation_keeps_each_attempt_verified(self):
        # Each new logcat snapshot contains only the latest event. Previously,
        # reading once after all 3 tests would yield 1/3 and a generic error.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic model only")
            fake = FakeInstrumentedADB(history_visible=False)
            report = staging.run_synthetic_smoke(
                artifact, PACKAGE, root / "cpu.json", repeats=3,
                run=fake, verifier=lambda _: SHA,
                token_generator=unique_synthetic_tokens(),
            )
            self.assertEqual(report["runs"], 3)
            self.assertEqual(report["run_tokens"], ["f" * 16, "e" * 16, "d" * 16])
            self.assertEqual(
                sum("logcat -d" in " ".join(args) for args in fake.commands), 3
            )
            self.assertEqual(report["privacy_visual_acceptance"], "NOT_PROVEN")
            self.assertNotIn("logcat -c", " ".join(" ".join(c) for c in fake.commands))
            self.assertEqual(fake.commands[-1][-3:],
                             ["rm", "-f", "files/" + staging.REMOTE_BASENAME])

    def test_duplicate_tagged_event_for_same_attempt_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic model only")
            dest = root / "cpu.json"
            class DuplicateADB(FakeInstrumentedADB):
                def __call__(self, command, **kwargs):
                    result = super().__call__(command, **kwargs)
                    if "logcat -d" in " ".join(command):
                        token = self.run_tokens[-1]
                        return subprocess.CompletedProcess(
                            command, 0,
                            result.stdout + "\n" + fake_logcat(
                                fake_record(run_token=token)),
                            "",
                        )
                    return result
            fake = DuplicateADB()
            with self.assertRaisesRegex(
                RuntimeError, "INCOMPLETE_OR_DUPLICATE_TAGGED_SYNTHETIC_RECORD"
            ):
                staging.run_synthetic_smoke(
                    artifact, PACKAGE, dest, repeats=3,
                    run=fake, verifier=lambda _: SHA,
                    token_generator=unique_synthetic_tokens(),
                )
            self.assertEqual(fake.synthetic_runs, 1)
            self.assertFalse(dest.exists())
            diagnostic = (root / "cpu.failure.txt").read_text()
            self.assertIn("matching_attempt_token=2", diagnostic)
            self.assertIn("valid_attempt_records=2", diagnostic)
            self.assertIn("pixel_carve_authorization=NEVER", diagnostic)
            self.assertNotIn("{", diagnostic)
            self.assertEqual(fake.commands[-1][-3:],
                             ["rm", "-f", "files/" + staging.REMOTE_BASENAME])

    def test_missing_second_attempt_log_does_not_reuse_first_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic model only")
            dest = root / "cpu.json"
            class MissingSecondLogADB(FakeInstrumentedADB):
                def __call__(self, command, **kwargs):
                    result = super().__call__(command, **kwargs)
                    if "logcat -d" in " ".join(command) and self.synthetic_runs == 2:
                        # Old tag events survive, but the current unique token
                        # has no matching event. Never promote the old record.
                        return subprocess.CompletedProcess(
                            command, 0, fake_logcat(fake_record(
                                run_token=self.run_tokens[0])), "",
                        )
                    return result
            fake = MissingSecondLogADB()
            with self.assertRaisesRegex(
                RuntimeError, "MISSING_OR_INVALID_TAGGED_SYNTHETIC_RECORD"
            ):
                staging.run_synthetic_smoke(
                    artifact, PACKAGE, dest, repeats=3,
                    run=fake, verifier=lambda _: SHA,
                    token_generator=unique_synthetic_tokens(),
                )
            self.assertEqual(fake.synthetic_runs, 2)
            self.assertFalse(dest.exists())
            diagnostic = (root / "cpu.failure.txt").read_text()
            self.assertIn("run=2/3", diagnostic)
            self.assertIn("valid_attempt_records=0", diagnostic)
            self.assertIn("other_attempt_tokens=1", diagnostic)
            self.assertEqual(fake.commands[-1][-3:],
                             ["rm", "-f", "files/" + staging.REMOTE_BASENAME])

    def test_malformed_or_truncated_synthetic_log_line_has_scalar_only_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic model only")
            dest = root / "cpu.json"
            class TruncatedLogADB(FakeInstrumentedADB):
                def __call__(self, command, **kwargs):
                    result = super().__call__(command, **kwargs)
                    if "logcat -d" in " ".join(command):
                        return subprocess.CompletedProcess(
                            command, 0,
                            "I/WoahMagicTouchStage3(111): "
                            '{"run_token":"f' + 'f' * 15 + '","other":', "",
                        )
                    return result
            fake = TruncatedLogADB()
            with self.assertRaisesRegex(
                RuntimeError, "MISSING_OR_INVALID_TAGGED_SYNTHETIC_RECORD"
            ):
                staging.run_synthetic_smoke(
                    artifact, PACKAGE, dest, run=fake,
                    verifier=lambda _: SHA,
                    token_generator=unique_synthetic_tokens(),
                )
            diagnostic = (root / "cpu.failure.txt").read_text()
            self.assertIn("malformed_json=1", diagnostic)
            self.assertIn("matching_attempt_token=0", diagnostic)
            self.assertNotIn('"run_token":', diagnostic)
            self.assertFalse(dest.exists())

    def test_reused_random_attempt_token_is_blocked_before_model_transfer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic model only")
            fake = FakeInstrumentedADB()
            with self.assertRaisesRegex(ValueError, "must be distinct 16-hex"):
                staging.run_synthetic_smoke(
                    artifact, PACKAGE, root / "cpu.json", run=fake,
                    verifier=lambda _: SHA,
                    token_generator=lambda _: "f" * 16,
                )
            self.assertEqual(len(fake.commands), 1)
            self.assertNotIn("exec-in", " ".join(fake.commands[0]))


    def test_auto_discovers_single_woah_instrumentation_target_and_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic only")
            fake = FakeInstrumentedADB()
            report = staging.run_synthetic_smoke(
                artifact, "auto", root / "report.json", repeats=3, run=fake,
                verifier=lambda _: SHA, token_generator=unique_synthetic_tokens(),
            )
            self.assertEqual(report["runs"], 3)
            self.assertEqual(fake.commands[-1][-3:], [
                "rm", "-f", "files/" + staging.REMOTE_BASENAME
            ])
            self.assertIn(PACKAGE, fake.commands[1])

    def test_run_fails_before_staging_if_wrong_or_missing_instrumentation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic only")
            fake = FakeInstrumentedADB(valid_component=False)
            with self.assertRaisesRegex(ValueError, "not uniquely installed"):
                staging.run_synthetic_smoke(
                    artifact, "auto", root / "report.json", run=fake,
                    verifier=lambda _: SHA,
                )
            self.assertEqual(len(fake.commands), 1)
            self.assertNotIn("exec-in", " ".join(fake.commands[0]))

    def test_auto_refuses_ambiguous_woah_instrumentation_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic only")
            class AmbiguousADB(FakeInstrumentedADB):
                def __call__(self, command, **kwargs):
                    result = super().__call__(command, **kwargs)
                    if "pm list instrumentation" in " ".join(command):
                        extra = ("instrumentation:art.gaoge.dance.test/"
                                 "androidx.test.runner.AndroidJUnitRunner "
                                 "(target=art.gaoge.dance)\n")
                        return subprocess.CompletedProcess(command, 0, result.stdout + extra, "")
                    return result
            fake = AmbiguousADB()
            with self.assertRaisesRegex(ValueError, "not uniquely installed"):
                staging.run_synthetic_smoke(
                    artifact, "auto", root / "report.json", run=fake,
                    verifier=lambda _: SHA,
                )
            self.assertEqual(len(fake.commands), 1)

    def test_android_raw_runner_success_without_ok_footer_is_accepted(self):
        # With `am instrument -w -r`, the AndroidJUnitRunner may omit the
        # pretty 'OK (1 test)' footer but still report the exact test's
        # start+success status codes and final successful instrumentation code.
        raw = (
            'INSTRUMENTATION_STATUS: numtests=1\n'
            'INSTRUMENTATION_STATUS: class=' + staging.TEST_CLASS + '\n'
            'INSTRUMENTATION_STATUS: test=testPinnedMagicTouchOnSyntheticShapesOnly\n'
            'INSTRUMENTATION_STATUS_CODE: 1\n'
            'INSTRUMENTATION_STATUS: class=' + staging.TEST_CLASS + '\n'
            'INSTRUMENTATION_STATUS: test=testPinnedMagicTouchOnSyntheticShapesOnly\n'
            'INSTRUMENTATION_STATUS_CODE: 0\n'
            'INSTRUMENTATION_CODE: -1\n'
        )
        self.assertEqual(
            staging._instrumentation_result_verdict(raw, 0),
            (True, 'ANDROID_RAW_PER_TEST_SUCCESS'),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / 'synthetic.task'
            artifact.write_bytes(b'synthetic only')
            class RawRunnerADB(FakeInstrumentedADB):
                def __call__(self, command, **kwargs):
                    result = super().__call__(command, **kwargs)
                    if 'am instrument' in ' '.join(command):
                        return subprocess.CompletedProcess(command, 0, raw, '')
                    return result
            fake = RawRunnerADB()
            report = staging.run_synthetic_smoke(
                artifact, PACKAGE, root / 'stage3.json', repeats=3, run=fake,
                verifier=lambda _: SHA, token_generator=unique_synthetic_tokens(),
            )
            self.assertEqual(report['runs'], 3)
            self.assertEqual(report['pixel_carve_authorization'], 'NEVER')
            self.assertEqual(fake.commands[-1][-1], 'files/' + staging.REMOTE_BASENAME)

    def test_fatal_or_skipped_raw_runner_never_yields_privacy_pass(self):
        prefix = (
            'INSTRUMENTATION_STATUS: class=' + staging.TEST_CLASS + '\n'
            'INSTRUMENTATION_STATUS: test=testPinnedMagicTouchOnSyntheticShapesOnly\n'
            'INSTRUMENTATION_STATUS_CODE: 1\n'
        )
        for report in (
            prefix + 'INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1\n',
            prefix + 'INSTRUMENTATION_STATUS_CODE: -3\nINSTRUMENTATION_CODE: -1\n',
            prefix + 'INSTRUMENTATION_STATUS_CODE: -4\nINSTRUMENTATION_CODE: -1\n',
            prefix + 'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: 0\n',
            prefix + 'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS_CODE: 0\n'
                     'INSTRUMENTATION_CODE: -1\nOK (1 test)\n',
            prefix + 'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n'
                      'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n',
            'INSTRUMENTATION_CODE: -1\n',
            '',
        ):
            accepted, _ = staging._instrumentation_result_verdict(report, 0)
            self.assertFalse(accepted, repr(report))

    def test_failing_raw_runner_produces_bounded_diagnostic_outside_git(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / 'synthetic.task'
            artifact.write_bytes(b'synthetic only')
            output = root / 'stage3.json'
            class FailingRawADB(FakeInstrumentedADB):
                def __call__(self, command, **kwargs):
                    result = super().__call__(command, **kwargs)
                    if 'am instrument' in ' '.join(command):
                        return subprocess.CompletedProcess(command, 0, (
                            'INSTRUMENTATION_STATUS: class=' + staging.TEST_CLASS + '\n'
                            'INSTRUMENTATION_STATUS: test=testPinnedMagicTouchOnSyntheticShapesOnly\n'
                            'INSTRUMENTATION_STATUS: stack=java.lang.IllegalStateException: '
                            'Model load error at /data/user/0/hidden-model.task\n'
                            '    at art.gaoge.dance.engine.inference.Test.run(Test.kt:12)\n'
                            'INSTRUMENTATION_STATUS_CODE: -2\n'
                            'INSTRUMENTATION_CODE: -1\n'
                        ), '')
                    return result
            fake = FailingRawADB()
            with self.assertRaisesRegex(RuntimeError, 'TEST_FAILED_SKIPPED_OR_ABORTED'):
                staging.run_synthetic_smoke(
                    artifact, PACKAGE, output, repeats=3, run=fake,
                    verifier=lambda _: SHA, token_generator=unique_synthetic_tokens(),
                )
            diag = root / 'stage3.failure.txt'
            self.assertTrue(diag.is_file())
            diagnostic = diag.read_text(encoding='utf-8')
            self.assertIn('java.lang.IllegalStateException', diagnostic)
            self.assertIn('INSTRUMENTATION_STATUS_CODE: -2', diagnostic)
            self.assertIn('<redacted-device-path>', diagnostic)
            self.assertNotIn('/data/user/', diagnostic)
            self.assertIn('privacy_visual_acceptance=NOT_PROVEN', diagnostic)
            self.assertFalse(output.exists())
            self.assertEqual(fake.commands[-1][-3:], [
                'rm', '-f', 'files/' + staging.REMOTE_BASENAME,
            ])

    def test_failure_report_includes_sanitized_adb_stderr(self):
        with tempfile.TemporaryDirectory() as dirname:
            dest = Path(dirname) / 'stage3-report.json'
            completed = subprocess.CompletedProcess(
                ['adb', 'shell', 'am', 'instrument'], 1,
                'INSTRUMENTATION_CODE: 0\n',
                'Test command failed in /data/user/0/private-path\n',
            )
            path = staging._write_stage3_instrument_failure(
                dest, attempt=1, repeats=3, package=PACKAGE,
                reason='ADB_INSTRUMENTATION_NONZERO_EXIT', result=completed,
            )
            body = path.read_text(encoding='utf-8')
            self.assertIn('ADB_INSTRUMENTATION_NONZERO_EXIT', body)
            self.assertIn('Test command failed', body)
            self.assertIn('<redacted-device-path>', body)
            self.assertNotIn('/data/user/0/', body)

    def test_failure_excerpt_is_bounded_and_masks_potential_paths(self):
        lines = '\n'.join([
            'INSTRUMENTATION_STATUS: stack=java.lang.IllegalArgumentException: '
            'file:///storage/emulated/0/source.mov',
            '    at foo.bar.Test.method(Test.kt:42)',
            'INSTRUMENTATION_CODE: -1',
        ] * 120)
        snippet = staging._safe_instrumentation_excerpt(lines, max_lines=8)
        self.assertIn('<redacted-uri>', snippet)
        self.assertNotIn('source.mov', snippet)
        self.assertIn('TRUNCATED_STAGE3_INSTRUMENTATION_LINES', snippet)
        self.assertLess(len(snippet), 2400)

    def test_failed_instrumentation_stops_and_cleans_model_without_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "synthetic.task"
            artifact.write_bytes(b"synthetic only")
            out = root / "not-saved.json"
            fake = FakeInstrumentedADB(failure_at=2)
            with self.assertRaisesRegex(RuntimeError, "did not pass exactly one"):
                staging.run_synthetic_smoke(
                    artifact, PACKAGE, out, repeats=3, run=fake,
                    verifier=lambda _: SHA, token_generator=unique_synthetic_tokens(),
                )
            self.assertEqual(fake.synthetic_runs, 2)
            self.assertFalse(out.exists())
            self.assertEqual(fake.commands[-1][-3:], [
                "rm", "-f", "files/" + staging.REMOTE_BASENAME,
            ])

    def test_run_cannot_write_report_into_git_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "synthetic.task"
            artifact.write_bytes(b"synthetic only")
            fake = FakeInstrumentedADB()
            with self.assertRaisesRegex(ValueError, "outside Git tree"):
                staging.run_synthetic_smoke(
                    artifact, PACKAGE, staging.ROOT / "private.json", run=fake,
                    verifier=lambda _: SHA,
                )
            self.assertEqual(fake.commands, [])

    def test_real_model_verifier_is_immutable_lock_guard_not_downloader(self):
        module = Path(staging.__file__).read_text(encoding="utf-8")
        self.assertIn("verifier(path)", module)
        self.assertIn("validate_android_dependency_source(", module)
        self.assertIn("verify(path, lock, repo_root=", module)
        self.assertNotIn("requests.get", module)
        self.assertNotIn("urllib.request", module)
        self.assertNotIn("MediaCodec", module)
        self.assertNotIn("GlRenderer", module)


if __name__ == "__main__":
    unittest.main()
