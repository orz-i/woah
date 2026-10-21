#!/usr/bin/env python3
"""Verify signed Release APK identity using Android SDK tools before device install.

All operations are local and read-only. No media, user app data or signing
keystore is opened. Only the signing certificate's public SHA-256 fingerprint,
package ID and version are used as structural evidence, NOT privacy approval.
"""
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
from typing import Callable

ROOT = Path(__file__).resolve().parents[2]
LOCAL_PROPERTIES = ROOT / "mobile/app/android/local.properties"
EXPECTED_APP_ID = "art.gaoge.dance"

SIGNER_RE = re.compile(
    r"^Signer\s+#(\d+)\s+certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$",
    re.MULTILINE,
)
PACKAGE_RE = re.compile(
    r"^package:\s+name='([^']+)'\s+versionCode='([0-9]+)'\s+versionName='([^']+)'",
    re.MULTILINE,
)


def locate_sdk(
    *, env: dict[str, str] | None = None, properties: Path = LOCAL_PROPERTIES
) -> Path:
    variables = os.environ if env is None else env
    sdk_roots = [
        Path(value).expanduser().resolve()
        for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME")
        if (value := variables.get(key, "").strip())
    ]
    existing = [path for path in sdk_roots if path.is_dir()]
    if len(set(existing)) > 1:
        raise ValueError("ANDROID_SDK_ROOT and ANDROID_HOME reference different SDKs")
    if existing:
        return existing[0]
    if properties.is_file():
        for row in properties.read_text(encoding="utf-8-sig").splitlines():
            if row.strip().startswith("sdk.dir="):
                value = row.partition("=")[2].strip()
                # Gradle properties may encode escaped colon or backslashes.
                value = value.replace(r"\:", ":").replace(r"\\", "\\")
                result = Path(value).expanduser().resolve()
                if result.is_dir():
                    return result
                break
    raise ValueError("Android SDK directory missing (ANDROID_SDK_ROOT/ANDROID_HOME/sdk.dir)")


def locate_build_tools(
    *, sdk: Path | None = None
) -> tuple[Path, Path]:
    root = locate_sdk() if sdk is None else sdk
    folder = root / "build-tools"
    if not folder.is_dir():
        raise ValueError("Android SDK build-tools are not installed")
    versions = []
    for version in folder.iterdir():
        if version.is_dir():
            parsed = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version.name)
            if parsed:
                versions.append((tuple(map(int, parsed.groups())), version))
    for _, version in sorted(versions, reverse=True):
        for signer, aapt in (
            ("apksigner.bat", "aapt.exe"), ("apksigner", "aapt")
        ):
            tools = (version / signer, version / aapt)
            if all(path.is_file() for path in tools):
                return tools
    raise ValueError("Android build-tools must provide apksigner and aapt")


def _read_tool(
    command: list[str], *, runner: Callable = subprocess.run
) -> str:
    result = runner(
        command, capture_output=True, text=True,
        check=False, timeout=40,
    )
    if result.returncode:
        raise ValueError(
            f"{Path(command[0]).name} rejected Release APK (exit {result.returncode})"
        )
    if not isinstance(result.stdout, str):
        raise ValueError("APK verifier produced no text output")
    return result.stdout


def parse_apk_identity(signing: str, package: str) -> dict[str, str | int]:
    certs = SIGNER_RE.findall(signing)
    if len(certs) != 1 or certs[0][0] != "1":
        raise ValueError("Release APK requires exactly one verifiable signer")
    if re.search(r"Signer\s+#1 certificate DN:\s*.*Android Debug", signing, re.I):
        raise ValueError("Release APK uses an Android Debug certificate")
    packages = PACKAGE_RE.findall(package)
    if len(packages) != 1:
        raise ValueError("Release APK package/version metadata is missing or ambiguous")
    app_id, code, name = packages[0]
    if app_id != EXPECTED_APP_ID or not name or len(name) > 64:
        raise ValueError("Release APK application ID or version name mismatch")
    version_code = int(code)
    if version_code < 1 or version_code > 2_100_000_000:
        raise ValueError("Release APK version code invalid")
    return {
        "app_id": app_id,
        "version_code": version_code,
        "version_name": name,
        "signer_sha256": certs[0][1].lower(),
    }


def inspect_release_apk(
    apk: Path, *, runner: Callable = subprocess.run,
    build_tools: tuple[Path, Path] | None = None,
) -> dict[str, str | int]:
    if not apk.is_file():
        raise ValueError("Release APK missing at verification")
    signer, aapt = locate_build_tools() if build_tools is None else build_tools
    return parse_apk_identity(
        _read_tool([str(signer), "verify", "--verbose", "--print-certs", str(apk)],
                   runner=runner),
        _read_tool([str(aapt), "dump", "badging", str(apk)], runner=runner),
    )
