"""Regression tests for APK and Activity launch diagnostics (offline)."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock
from zipfile import ZipFile

from verify_m0_apk import (
    ACTIVITY, COMPONENT, PKG, apk_contents, launch, inspect_installed
)
from run_m0_android import ACTIVITY as RUNNER_COMPONENT


class M0ApkPreflightTest(unittest.TestCase):
    def test_runner_component_matches_verified_android_component(self):
        self.assertEqual(RUNNER_COMPONENT, COMPONENT)

    def make_apk(self, directory: str, *, manifest=True, dex=True,
                 utf16=False) -> Path:
        output = Path(directory, "app-debug.apk")
        name = ACTIVITY.encode("utf-16le" if utf16 else "utf-8")
        with ZipFile(output, "w") as archive:
            archive.writestr("AndroidManifest.xml", name if manifest else b"MainActivity")
            archive.writestr("classes.dex", (
                b"Lart/gaoge/dance/engine/comparison/ComparisonM0Activity;"
                if dex else b"Lart/gaoge/dance/MainActivity;"
            ))
        return output

    def test_valid_debug_apk_detects_manifest_and_dex(self):
        with tempfile.TemporaryDirectory() as d:
            apk = self.make_apk(d)
            result = apk_contents(apk)
            self.assertTrue(result["m0DebugComponentPresent"])
            self.assertEqual(result["dexFiles"], ["classes.dex"])
            self.assertEqual(len(result["sha256"]), 64)

    def test_utf16_manifest_string_pool(self):
        with tempfile.TemporaryDirectory() as d:
            result = apk_contents(self.make_apk(d, utf16=True))
            self.assertTrue(result["m0DebugComponentPresent"])

    def test_manifest_only_is_not_enough(self):
        with tempfile.TemporaryDirectory() as d:
            result = apk_contents(self.make_apk(d, dex=False))
            self.assertTrue(result["hasCompiledManifestActivityName"])
            self.assertFalse(result["hasDexActivityType"])
            self.assertFalse(result["m0DebugComponentPresent"])

    def test_dex_only_is_not_enough(self):
        with tempfile.TemporaryDirectory() as d:
            result = apk_contents(self.make_apk(d, manifest=False))
            self.assertFalse(result["hasCompiledManifestActivityName"])
            self.assertTrue(result["hasDexActivityType"])
            self.assertFalse(result["m0DebugComponentPresent"])

    def test_launch_uses_valid_android_component_not_bare_class(self):
        completed = mock.Mock(returncode=0, stdout="Starting: Intent", stderr="")
        with mock.patch("verify_m0_apk.subprocess.run", return_value=completed) as runner:
            launch("adb", None, 3_000_000)
            args = runner.call_args.args[0]
            self.assertEqual(COMPONENT, f"{PKG}/{ACTIVITY}")
            self.assertEqual(args[args.index("-n") + 1], COMPONENT)
            self.assertEqual(args[-3:], ["--el", "offset_us", "3000000"])

    def test_android_error_type_three_fails_even_with_zero_exit_code(self):
        completed = mock.Mock(returncode=0, stdout=(
            "Error type 3\nError: Activity class does not exist\n"
        ), stderr="")
        with mock.patch("verify_m0_apk.subprocess.run", return_value=completed):
            with self.assertRaisesRegex(RuntimeError, "could not start"):
                launch("adb", "TEST_SERIAL", 0)

    def test_inspect_device_without_installed_package(self):
        completed = mock.Mock(returncode=0, stdout="", stderr="")
        with mock.patch("verify_m0_apk.subprocess.run", return_value=completed):
            self.assertEqual(
                inspect_installed("adb", None),
                {"installed": False, "reason": "Package not installed for adb user"},
            )


if __name__ == "__main__":
    unittest.main()
