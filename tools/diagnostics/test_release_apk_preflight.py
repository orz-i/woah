"""Read-only signed APK identity preflight: never opens real user packages."""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.diagnostics import release_apk_preflight as identity


CERT = "1a" * 32
SIGNING = (
    "Verifies\n"
    "Verified using v1 scheme (JAR signing): true\n"
    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
    "Signer #1 certificate DN: CN=Woah Release, O=Woah\n"
    f"Signer #1 certificate SHA-256 digest: {CERT}\n"
)
PACKAGE = (
    "package: name='art.gaoge.dance' versionCode='1' "
    "versionName='0.1.0' platformBuildVersionName='16'\n"
)


class SignedReleaseApkPreflightTest(unittest.TestCase):
    def test_strict_release_identity_extracts_only_non_secret_fields(self):
        data = identity.parse_apk_identity(SIGNING, PACKAGE)
        self.assertEqual(data, {
            "app_id": "art.gaoge.dance",
            "version_code": 1,
            "version_name": "0.1.0",
            "signer_sha256": CERT,
        })
        self.assertNotIn("Woah Release", str(data))
        self.assertNotIn("signingKey", str(data))

    def test_wrong_application_id_missing_version_and_bad_signers_fail(self):
        for signing, package in (
            ("", PACKAGE),
            (SIGNING.replace(CERT, "not-hex"), PACKAGE),
            (SIGNING + "Signer #2 certificate SHA-256 digest: " + CERT + "\n", PACKAGE),
            (SIGNING.replace("Woah Release", "Android Debug"), PACKAGE),
            (SIGNING, PACKAGE.replace("art.gaoge.dance", "art.attacker.dance")),
            (SIGNING, PACKAGE.replace("versionCode='1'", "versionCode='0'")),
            (SIGNING, PACKAGE.replace("versionName='0.1.0'", "versionName=''")),
            (SIGNING, ""),
            (SIGNING, PACKAGE + PACKAGE),
        ):
            with self.subTest(signing=signing[:42], package=package[:53]), \
                 self.assertRaises(ValueError):
                identity.parse_apk_identity(signing, package)

    def test_locates_highest_complete_installed_android_build_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            old = sdk / "build-tools/35.0.0"
            new = sdk / "build-tools/36.0.0"
            old.mkdir(parents=True)
            new.mkdir(parents=True)
            for binary in ("apksigner", "aapt"):
                (old / binary).write_text("synthetic tool")
                (new / binary).write_text("synthetic tool")
            self.assertEqual(
                identity.locate_build_tools(sdk=sdk),
                (new / "apksigner", new / "aapt"),
            )
            (new / "aapt").unlink()
            self.assertEqual(
                identity.locate_build_tools(sdk=sdk),
                (old / "apksigner", old / "aapt"),
            )

    def test_windows_tools_are_detected_before_unix_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            tools = sdk / "build-tools/36.0.0"
            tools.mkdir(parents=True)
            for filename in ("apksigner.bat", "aapt.exe"):
                (tools / filename).write_text("synthetic Windows binary")
            self.assertEqual(
                identity.locate_build_tools(sdk=sdk),
                (tools / "apksigner.bat", tools / "aapt.exe"),
            )

    def test_missing_build_tools_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            with self.assertRaisesRegex(ValueError, "build-tools"):
                identity.locate_build_tools(sdk=sdk)

    def test_sdk_lookup_environment_and_properties_are_read_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / "sdk"
            sdk.mkdir()
            local = root / "local.properties"
            local.write_text("sdk.dir=" + str(sdk) + "\n", encoding="utf-8")
            self.assertEqual(identity.locate_sdk(env={}, properties=local), sdk.resolve())
            self.assertEqual(
                identity.locate_sdk(
                    env={"ANDROID_HOME": str(sdk)}, properties=root / "missing"
                ), sdk.resolve(),
            )
            another = root / "other"
            another.mkdir()
            with self.assertRaisesRegex(ValueError, "different SDKs"):
                identity.locate_sdk(env={
                    "ANDROID_HOME": str(sdk), "ANDROID_SDK_ROOT": str(another),
                })
            with self.assertRaisesRegex(ValueError, "SDK directory missing"):
                identity.locate_sdk(env={}, properties=root / "absent")

    def test_inspect_invokes_only_readonly_signed_apk_check_and_aapt_badging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "release.apk"
            apk.write_bytes(b"synthetic signed public payload fixture")
            signer = root / "apksigner.bat"
            aapt = root / "aapt.exe"
            for filename in (signer, aapt):
                filename.write_text("tool placeholder")
            commands: list[list[str]] = []

            def runner(command: list[str], **kwargs):
                commands.append(command)
                self.assertEqual(kwargs["check"], False)
                self.assertTrue(kwargs["capture_output"])
                self.assertEqual(kwargs["timeout"], 40)
                return subprocess.CompletedProcess(
                    command, 0, SIGNING if command[0] == str(signer) else PACKAGE, "",
                )

            parsed = identity.inspect_release_apk(
                apk, runner=runner, build_tools=(signer, aapt),
            )
            self.assertEqual(parsed["signer_sha256"], CERT)
            self.assertEqual(commands, [
                [str(signer), "verify", "--verbose", "--print-certs", str(apk)],
                [str(aapt), "dump", "badging", str(apk)],
            ])
            self.assertTrue(apk.is_file())

    def test_invalid_signature_exit_never_extracts_package_or_accepts_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "bad.apk"
            apk.write_bytes(b"bad")
            commands = []

            def runner(command, **kwargs):
                commands.append(command)
                return subprocess.CompletedProcess(command, 1, "", "verification failure")

            with self.assertRaisesRegex(ValueError, "rejected Release APK"):
                identity.inspect_release_apk(
                    apk, runner=runner,
                    build_tools=(root / "apksigner", root / "aapt"),
                )
            self.assertEqual(len(commands), 1)

    def test_source_does_not_touch_keystores_or_private_user_content(self):
        source = Path(identity.__file__).read_text(encoding="utf-8")
        for forbidden in ("adb install", "uninstall", "MediaCodec", "ffmpeg",
                          "requests.post", "urllib.request", "keytool", "keystore"):
            # The docstring may mention keystore as excluded, not a code call.
            if forbidden == "keystore":
                self.assertNotIn("keystore=", source)
            else:
                self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
