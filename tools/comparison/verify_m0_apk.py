#!/usr/bin/env python3
"""Verify a Woah M0 debug APK before installation or launching it.

Offline ZIP/DEX/compiled-manifest checks require only Python 3.9+. Optional
--install and --inspect-device use adb. Never uninstalls or clears app data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

PKG = "art.gaoge.dance"
ACTIVITY = "art.gaoge.dance.engine.comparison.ComparisonM0Activity"
COMPONENT = f"{PKG}/{ACTIVITY}"
GL_ACTIVITY = "art.gaoge.dance.engine.comparison.ComparisonGlM0Activity"
GL_COMPONENT = f"{PKG}/{GL_ACTIVITY}"


def apk_contents(apk: Path) -> dict:
    if not apk.is_file():
        raise FileNotFoundError(f"APK does not exist: {apk}")
    with zipfile.ZipFile(apk) as archive:
        try:
            manifest = archive.read("AndroidManifest.xml")
        except KeyError as exc:
            raise ValueError("APK has no compiled AndroidManifest.xml") from exc
        dex_names = sorted(name for name in archive.namelist()
                           if name.startswith("classes") and name.endswith(".dex"))
        # AAPT2 string pools can be UTF-8 or UTF-16LE. This is a conservative
        # diagnostic check for the *name*; Android aapt remains authoritative.
        named = "ComparisonM0Activity"
        manifest_name_present = (
            named.encode("utf-8") in manifest or
            named.encode("utf-16le") in manifest
        )
        full_descriptor = b"Lart/gaoge/dance/engine/comparison/ComparisonM0Activity;"
        dex_type_present = False
        gl_dex_present = False
        gl_descriptor = b"Lart/gaoge/dance/engine/comparison/ComparisonGlM0Activity;"
        for name in dex_names:
            dex = archive.read(name)
            dex_type_present |= full_descriptor in dex
            gl_dex_present |= gl_descriptor in dex
        gl_named = "ComparisonGlM0Activity"
        gl_manifest_present = (gl_named.encode("utf-8") in manifest or
                               gl_named.encode("utf-16le") in manifest)
    digest = hashlib.sha256()
    with apk.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    result = {
        "path": str(apk),
        "bytes": apk.stat().st_size,
        "sha256": digest.hexdigest(),
        "hasCompiledManifestActivityName": manifest_name_present,
        "hasDexActivityType": dex_type_present,
        "dexFiles": dex_names,
    }
    result["m0DebugComponentPresent"] = manifest_name_present and dex_type_present
    result["m0GlDebugComponentPresent"] = gl_manifest_present and gl_dex_present
    return result


def adb_run(adb: str, serial: str | None, *args: str) -> subprocess.CompletedProcess:
    cmd = [adb, *(['-s', serial] if serial else []), *args]
    result = subprocess.run(cmd, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(
            f"adb command failed ({result.returncode}): {' '.join(cmd)}\n"
            f"{result.stdout}\n{result.stderr}"
        )
    return result


def inspect_installed(adb: str, serial: str | None) -> dict:
    output = adb_run(adb, serial, "shell", "pm", "path", PKG).stdout
    paths = [line.partition("package:")[2].strip()
             for line in output.splitlines() if line.startswith("package:")]
    if not paths:
        return {"installed": False, "reason": "Package not installed for adb user"}

    # A standard Flutter debug APK is monolithic. For bundle/splits, pull only
    # base.apk to check the Activity (defined in the base app).
    remote = next((path for path in paths if path.endswith("/base.apk")), paths[0])
    with tempfile.TemporaryDirectory(prefix="woah-m0-installed-") as directory:
        local = Path(directory, "installed-base.apk")
        adb_run(adb, serial, "pull", remote, str(local))
        inspected = apk_contents(local)
    inspected["path"] = remote
    inspected["installed"] = True
    return inspected


def launch(adb: str, serial: str | None, offset_us: int) -> None:
    result = adb_run(
        adb, serial, "shell", "am", "start",
        "-n", COMPONENT, "--el", "offset_us", str(offset_us)
    )
    message = (result.stdout + result.stderr).strip()
    if any(term in message for term in (
        "Error type 3", "does not exist", "Error: Activity", "Permission Denial",
    )):
        raise RuntimeError(f"M0 Activity could not start:\n{message}")
    print(message)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path,
                        help="Local APK to check, or omit when inspecting the device only")
    parser.add_argument("--install", action="store_true",
                        help="Install this checked APK with adb install -r (preserves app data)")
    parser.add_argument("--inspect-device", action="store_true",
                        help="Pull and inspect the currently installed APK")
    parser.add_argument("--launch", action="store_true",
                        help="Start the M0 Activity after validating the checked APK")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", help="adb serial if multiple devices are connected")
    parser.add_argument("--offset-us", type=int, default=3_000_000)
    args = parser.parse_args()

    try:
        local_path = args.apk
        if local_path is None and (args.install or not args.inspect_device):
            local_path = Path("mobile/app/build/app/outputs/flutter-apk/app-debug.apk")
        if local_path is not None:
            local = apk_contents(local_path)
            print("LOCAL_APK=" + json.dumps(local, ensure_ascii=False))
            if not local["m0DebugComponentPresent"]:
                print(
                    "M0_APK=FAIL: APK is missing the Debug Activity name and/or DEX class. "
                    "Verify feat/dance-comparison-m0 was checked out and that you "
                    "built mobile/app with flutter build apk --debug. "
                    "Do not try to launch this APK.",
                    file=sys.stderr,
                )
                return 2
            print("M0_APK=HAS_MANIFEST_NAME_AND_DEX_CLASS")

        if args.install:
            assert local_path is not None
            result = adb_run(args.adb, args.serial, "install", "-r", str(local_path))
            install_result = (result.stdout + result.stderr).strip()
            print("INSTALL=" + install_result)
            if "Success" not in install_result:
                raise RuntimeError(f"adb install did not confirm Success: {install_result}")

        if args.inspect_device:
            installed = inspect_installed(args.adb, args.serial)
            print("INSTALLED_APK=" + json.dumps(installed, ensure_ascii=False))
            if not installed.get("m0DebugComponentPresent"):
                print("M0_DEVICE=FAIL: Installed APK lacks the M0 Activity. "
                      "Install the checked debug APK with --install.",
                      file=sys.stderr)
                return 3
            print("M0_DEVICE=HAS_MANIFEST_NAME_AND_DEX_CLASS")

        if args.launch:
            launch(args.adb, args.serial, args.offset_us)
            print("M0_LAUNCH=REQUEST_SENT")
        return 0
    except (FileNotFoundError, ValueError, RuntimeError, zipfile.BadZipFile,
            OSError) as error:
        print(f"M0_PREFLIGHT=FAIL: {error}", file=sys.stderr)
        print("Do not uninstall the existing app automatically; signing mismatches "
              "must be resolved deliberately to avoid losing app data.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
