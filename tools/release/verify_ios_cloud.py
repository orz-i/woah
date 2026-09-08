#!/usr/bin/env python3
"""Host-independent checks for the Woah cloud-Mac / BrowserStack iOS lanes."""

from __future__ import annotations

import hashlib
import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_workflows() -> None:
    cloud = (ROOT / ".github/workflows/ios-cloud.yml").read_text(encoding="utf-8")
    browserstack = (ROOT / ".github/workflows/ios-browserstack.yml").read_text(encoding="utf-8")

    check("runs-on: macos-26" in cloud, "iOS compile lane must use the macOS 26 GitHub runner")
    check("flutter-version: '3.44.2'" in cloud, "iOS compile lane must pin the repository Flutter baseline")
    check(
        "flutter build ios --debug --no-codesign --target lib/main.dart" in cloud,
        "iOS compile lane must build the production entrypoint for iphoneos",
    )
    check("BROWSERSTACK_" not in cloud, "Automatic iOS compile lane must not depend on BrowserStack secrets")

    check("workflow_dispatch:" in browserstack, "BrowserStack lane must remain opt-in/manual")
    check("pull_request:" not in browserstack and "push:" not in browserstack,
          "BrowserStack real-device lane must not spend third-party minutes on normal pushes/PRs")
    check("browserstack-secrets:" in browserstack and "enabled=false" in browserstack,
          "BrowserStack workflow must safely skip when credentials are absent")
    check("needs.browserstack-secrets.outputs.enabled == 'true'" in browserstack,
          "macOS real-device job must be gated behind the secret check")
    check("phase1-model:" in browserstack and "runs-on: ubuntu-latest" in browserstack,
          "Phase 1 model reproduction must run on Linux before the macOS device build")
    check("actions/download-artifact@v4" in browserstack and "models/litert" in browserstack,
          "macOS BrowserStack job must consume the verified Linux-produced TFLite artifact")
    check("runs-on: macos-26" in browserstack, "BrowserStack build lane must use macOS 26")
    check("--target lib/cloud_probe_main.dart" in browserstack,
          "BrowserStack lane must build the isolated cloud-probe entrypoint")
    check("codesign --force --deep --sign -" in browserstack,
          "BrowserStack IPA should be ad-hoc signed before BrowserStack re-provisions it")
    check("--allow-unpinned-hash" in browserstack,
          "First cloud model run must surface a bootstrap hash instead of pretending it is already pinned")


def verify_probe_contract() -> None:
    source_fixture = ROOT / "tools/litert/test_frame.jpg"
    bundled_fixture = (
        ROOT
        / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources/InferenceAssets"
        / "yolo_phase1_test_frame.jpg"
    )
    check(source_fixture.is_file() and bundled_fixture.is_file(), "Phase 1 cloud fixture must be tracked")
    if source_fixture.is_file() and bundled_fixture.is_file():
        check(sha256(source_fixture) == sha256(bundled_fixture),
              "Bundled iOS cloud fixture must be byte-identical to tools/litert/test_frame.jpg")

    dart = (ROOT / "mobile/app/lib/cloud_probe_main.dart").read_text(encoding="utf-8")
    plugin = (
        ROOT
        / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native/DanceNativePlugin.swift"
    ).read_text(encoding="utf-8")
    client = (
        ROOT / "mobile/packages/dance_native/lib/src/dance_native_client.dart"
    ).read_text(encoding="utf-8")
    runner = (ROOT / "tools/ios/browserstack/run_phase1_probe.py").read_text(encoding="utf-8")

    for backend in ("auto", "tflite_coreml", "tflite_metal", "tflite_xnnpack"):
        check(backend in dart, f"Cloud probe entrypoint must exercise backend {backend}")
    check("woah-ios-phase1-cloud-report" in dart and "WOAH_PHASE1_REPORT:" in dart,
          "Cloud probe must expose a deterministic accessibility report surface")
    check('case "runIOSYoloPhase1BundledProbe"' in plugin,
          "Native plugin must expose the bundled fixture diagnostic method")
    check("runIOSYoloPhase1BundledProbe" in client,
          "Dart native client must expose the bundled fixture diagnostic method")
    check("https://api-cloud.browserstack.com/app-automate/upload" in runner,
          "BrowserStack harness must use the App Automate upload API")
    check("https://api-cloud.browserstack.com/app-automate/sessions/" in runner,
          "BrowserStack harness must update App Automate session status through the correct API")


def verify_report_evaluator() -> None:
    module_path = ROOT / "tools/ios/browserstack/phase1_report.py"
    spec = importlib.util.spec_from_file_location("phase1_report", module_path)
    if spec is None or spec.loader is None:
        FAILURES.append("Could not load Phase 1 report evaluator")
        return
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    detection = {
        "confidence": 0.9,
        "bbox": [0.1, 0.1, 0.6, 0.9],
        "mask_coverage": 0.25,
    }
    payload = {
        "reports": [
            {
                "backend": "auto",
                "ok": True,
                "report": {"detection_count": 1, "detections": [detection]},
            },
            {
                "backend": "tflite_xnnpack",
                "ok": True,
                "report": {"detection_count": 1, "detections": [detection]},
            },
        ]
    }
    result = module.evaluate_phase1_report(payload)
    check(result.get("ok") is True, "Synthetic valid BrowserStack Phase 1 report must pass")
    payload["reports"][1]["report"]["detection_count"] = 0
    payload["reports"][1]["report"]["detections"] = []
    result = module.evaluate_phase1_report(payload)
    check(result.get("ok") is False, "Detection drift in a required backend must fail the cloud gate")


def main() -> int:
    verify_workflows()
    verify_probe_contract()
    verify_report_evaluator()
    if FAILURES:
        print("iOS cloud verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS cloud/static BrowserStack verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
