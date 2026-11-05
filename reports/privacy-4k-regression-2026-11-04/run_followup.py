"""Run the unchanged earlier regression after the 4K process and collection finish."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time
from review_audit import approved

ROOT = Path(__file__).resolve().parent
candidate = json.loads((ROOT / "final-candidate.json").read_text(encoding="utf-8"))
folder = ROOT / candidate["run"]

def read(path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default

def log(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)

while True:
    native = read(folder / "results.json", {})
    if native.get("result") == "failed":
        raise AssertionError("4K matrix failed; do not stop its process for follow-up tests")
    media = read(folder / "media-inspection.json", [])
    holes = read(folder / "opaque-face-hole-audit.json", [])
    instrumentation = ROOT / (candidate["run"] + "-matrix-instrumentation.txt")
    official = instrumentation.read_text(encoding="utf-8") if instrumentation.exists() else ""
    if "FAILURES!!!" in official:
        raise AssertionError("Instrumentation did not complete successfully")
    if "OK (1 test)" in official and "INSTRUMENTATION_STATUS_CODE: 0" in official and native.get("result") == "passed" and len(media) == 24 and len(holes) == 2 and all(approved(h, read(folder / "opaque-face-visual-review.json", []), folder) for h in holes):
        assert all(m["result"] == "passed" for m in media), media
        assert read(ROOT / "app-candidate.json", {}).get("source_sha256", {}).get(next(iter(candidate["source_sha256"]))) == candidate["source_sha256"][next(iter(candidate["source_sha256"]))]
        break
    time.sleep(35)

subprocess.run([sys.executable, str(ROOT / "prepare_legacy.py")], check=True)
legacy = ROOT / ("legacy_" + candidate["test_apk_sha256"][:8])
for name, args in (("run_regression_media.py", []), ("run_existing_device_cases.py", ["--all"])):
    log({"stage": name, "status": "started"})
    subprocess.run([sys.executable, str(legacy / name), *args], check=True)
    log({"stage": name, "status": "finished"})
proof = subprocess.run(["adb", "-s", "334da6a3", "shell", "am", "instrument", "-w", "-r", "-e", "class", "art.gaoge.dance.engine.pipeline.FourKDetectionReviewInstrumentedTest#freshMirrorFaceIsProtectedWhileItsOldIdentityIsDormant", "-e", "review4K", "true", "art.gaoge.dance.engine.test/androidx.test.runner.AndroidJUnitRunner"], check=True, capture_output=True, timeout=90).stdout.decode("utf-8", errors="replace")
(legacy / "current-fresh-observation-instrumentation.txt").write_text(proof, encoding="utf-8")
assert "OK (1 test)" in proof and "INSTRUMENTATION_STATUS_CODE: 0" in proof, proof
subprocess.run(["adb", "-s", "334da6a3", "pull", "/sdcard/Android/data/art.gaoge.dance.engine.test/files/network_privacy_qa/fresh-observation-check.json", str(legacy / "current-fresh-observation-check.json")], check=True, capture_output=True)
old = ROOT.parent / "privacy-regression-2026-11-04"
spec = importlib.util.spec_from_file_location("previous_media_inspection", old / "inspect_media.py")
inspection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspection)
rows = []
for path in sorted(legacy.glob("*.mp4")):
    row = inspection.inspect(path)
    expected = read(path.with_suffix(".json"), {})
    checks = {"complete_decode": row["decoded_frames"] == row["declared_frames"] == row["pts_count"] > 0,
              "increasing_pts": row["strictly_increasing_decoded_pts"],
              "no_black_or_green_frames": row["black_frames"] == row["green_frames"] == 0,
              "streaming_layout": row["atoms"].get("fast_start_ready") is True,
              "audio_sync": row["av_duration_delta_ms"] is None or row["av_duration_delta_ms"] <= 100}
    row["checks"] = checks
    row["result"] = "passed" if all(checks.values()) else "failed"
    rows.append(row)
    (legacy / "media-inspection.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log({"media": path.name, "result": row["result"], "checks": checks})
    assert row["result"] == "passed", row
network = read(legacy / "network-case-results.json", [])
devices = read(legacy / "final-device-results.json", [])
lifecycle = read(legacy / "lifecycle-final.json", {})
summary = {"result": "passed" if len(network) == 19 and all(r["result"] == "passed" for r in network) and len(rows) == 22 and all(r["result"] == "passed" for r in rows) and len(devices) == 40 and all(r["result"] == "passed" for r in devices) else "failed", "media_exports": len(rows), "device_cases": len(devices), "production_lifecycle": lifecycle}
(legacy / "regression-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
log(summary)
assert summary["result"] == "passed", summary
