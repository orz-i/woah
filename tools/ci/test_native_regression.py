"""Permanent simulator acceptance, without historical phase subsets."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.ios import run_simulator_regression as runner

ROOT = Path(__file__).resolve().parents[2]


class NativeRegressionTests(unittest.TestCase):
    def test_retired_entrypoints_are_absent(self):
        for i in (3, 4, 5):
            self.assertFalse((ROOT / f"mobile/app/lib/ios_phase{i}_smoke_main.dart").exists())
            self.assertFalse((ROOT / f"tools/ios/run_phase{i}_macos_gate.py").exists())
        self.assertFalse((ROOT / "tools/ios/run_phase3_simulator_smoke.py").exists())

    def test_all_six_runtime_checks_are_required_and_fail_closed(self):
        self.assertEqual(len(set(runner.REQUIRED_MARKERS)), 6)
        complete = "\n".join(runner.REQUIRED_MARKERS)
        with tempfile.TemporaryDirectory() as app:
            for omitted in (None, *runner.REQUIRED_MARKERS, "launch_error"):
                output = complete if omitted in (None, "launch_error") else complete.replace(omitted, "")
                status = 1 if omitted == "launch_error" else 0
                with self.subTest(omitted=omitted), patch.object(runner.sys, "argv", ["runner", "--app", app]), \
                     patch.object(runner, "boot_iphone", return_value=("iOS", "iPhone", "test")), \
                     patch.object(runner, "run", return_value=subprocess.CompletedProcess([], status, output, "")), \
                     patch.object(runner, "safe_run"):
                    if omitted is None:
                        self.assertEqual(runner.main(), 0)
                    else:
                        with self.assertRaises(SystemExit):
                            runner.main()


if __name__ == "__main__":
    unittest.main()
