#!/usr/bin/env python3
"""Seed a Phase 7 physical-device acceptance report from the audited Release artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "tools/ios/phase7_device_acceptance_report.template.json"
BASELINE_VIDEO = ROOT / "testdata/videos/01_sample.mp4"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    audit_path = args.audit.resolve()
    if not audit_path.is_file():
        raise SystemExit(f"Phase 7 Release audit does not exist: {audit_path}")
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if audit.get("phase") != 7:
        raise SystemExit("Release audit is not Phase 7 evidence")
    if audit.get("evidence_class") != "apple-only-release-build-not-physical-device-acceptance":
        raise SystemExit("Unexpected Release audit evidence class")

    report = json.loads(TEMPLATE.read_text(encoding="utf-8"))
    bundle = audit.get("bundle", {})
    model = audit.get("model", {})
    dependencies = audit.get("dependencies", {})
    report["artifact"].update(
        {
            "release_audit_path": str(audit_path),
            "release_audit_sha256": sha256(audit_path),
            "git_commit": audit.get("git_commit"),
            "bundle_identifier": bundle.get("identifier"),
            "version": bundle.get("version"),
            "build": bundle.get("build"),
            "executable_sha256": bundle.get("executable_sha256"),
            "pubspec_lock_sha256": dependencies.get("pubspec_lock_sha256"),
            "model_contract_sha256": model.get("contract_sha256"),
            "model_sha256": model.get("packaged_sha256"),
            "privacy_manifests": audit.get("privacy_manifests", []),
        }
    )
    if BASELINE_VIDEO.is_file():
        report["media"]["repository_baseline"]["sha256"] = sha256(BASELINE_VIDEO)

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"IOS_PHASE7_DEVICE_REPORT_PREPARED={output}")
    print(f"IOS_PHASE7_DEVICE_REPORT_AUDIT_SHA256={report['artifact']['release_audit_sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
