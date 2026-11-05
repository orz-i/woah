"""Recheck the affected job-state paths after the terminal-state fix."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

import run_4k as device

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
RUNNER = "art.gaoge.dance.engine.test/androidx.test.runner.AndroidJUnitRunner"
PKG = RUNNER.split('/')[0]
CASES = ["group_4k_body_mosaic_balanced", "solo_4k_cancel", "solo_4k_retry_sticker", "solo_4k_nonzero_trim"]


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def log(**data):
    print(json.dumps(data, ensure_ascii=False), flush=True)


def verify_previous_media():
    previous = read(ROOT / "final-candidate.json")
    folder = ROOT / ("legacy_" + previous["test_apk_sha256"][:8])
    spec = importlib.util.spec_from_file_location("previous_media", ROOT.parent / "privacy-regression-2026-11-04" / "inspect_media.py")
    inspection = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inspection)
    network = read(folder / "network-case-results.json")
    assert len(network) == 19 and all(c["result"] == "passed" for c in network)
    movies = sorted(folder.glob("*.mp4"))
    assert len(movies) == 22
    rows = []
    for path in movies:
        actual, expected = inspection.inspect(path), read(path.with_suffix(".json"))
        checks = {"all_expected_frames": actual["decoded_frames"] == actual["declared_frames"] == actual["pts_count"] == expected["expected_frames"] > 0,
                  "increasing_pts": actual["strictly_increasing_decoded_pts"],
                  "no_black_or_green_frames": actual["black_frames"] == actual["green_frames"] == 0,
                  "streaming_layout": actual["atoms"].get("fast_start_ready") is True,
                  "audio_sync": actual["av_duration_delta_ms"] is None or actual["av_duration_delta_ms"] <= 100}
        row = {**actual, "checks": checks, "result": "passed" if all(checks.values()) else "failed"}
        rows.append(row)
        log(file=path.name, result=row["result"], decoded_frames=actual["decoded_frames"])
        save(folder / "media-inspection.json", rows)
        assert row["result"] == "passed", row
    save(folder / "media-reuse.json", {"result":"passed", "videos":len(rows), "fixture_case_count":len(network),
        "test_apk_sha256":previous["test_apk_sha256"], "reason":"The 22 cases call ExportPipeline directly; the subsequent fix only changes service/store/coordinator status delivery. The rendering and MP4 sources are unchanged."})


def installed_sha(package):
    path = device.adb("shell", "pm", "path", package).stdout.decode().strip()
    assert path.startswith("package:") and len(path.splitlines()) == 1, package
    return device.adb("shell", "sha256sum", path.removeprefix("package:")).stdout.decode().split()[0]


def instrument(method, options, path, timeout=240):
    args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", method]
    for key,value in options.items():
        args.extend(("-e",key,value))
    proc = device.adb(*args, RUNNER, timeout=timeout)
    out = proc.stdout.decode("utf-8", errors="replace")
    path.write_text(out,encoding="utf-8")
    assert "OK (1 test)" in out and "INSTRUMENTATION_STATUS_CODE: 0" in out and "FAILURES!!!" not in out, out[-5000:]


def run_device():
    build = read(ROOT / "lifecycle-candidate.json")
    artifact = ROOT / build["artifact_path"]
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    assert digest == build["test_apk_sha256"]
    for name, expected in build["source_sha256"].items():
        assert hashlib.sha256((REPO/name).read_bytes()).hexdigest() == expected, name
    assert "Success" in device.adb("install", "-r", str(artifact), timeout=180).stdout.decode()
    assert installed_sha(PKG) == digest
    log(stage="installed_test_candidate", sha256=digest)
    legacy = ROOT / ("legacy_" + digest[:8])
    legacy.mkdir(exist_ok=True)
    original = ROOT.parent / "privacy-regression-2026-11-04"
    for name in ("run_existing_device_cases.py","remaining-device-cases.json"):
        shutil.copyfile(original / name, legacy / name)
    save(legacy / "environment.json", {"test_apk_sha256":digest,"app_apk_sha256":read(ROOT/"app-candidate.json")["app_apk_sha256"],"device_serial":"334da6a3"})
    device.adb("shell", "am", "force-stop", PKG)
    log(stage="old_lifecycle", status="started")
    try:
        instrument("art.gaoge.dance.engine.pipeline.NetworkPrivacyWorkflowInstrumentedTest#productionApiReleasesResourcesAfterCompletionAndCancellation",
                   {"lifecycle":"true"}, legacy / "lifecycle-final-instrumentation.txt")
    finally:
        device.adb("pull", device.REMOTE + "/lifecycle.json", str(legacy / "lifecycle-final.json"))
    assert read(legacy / "lifecycle-final.json")["state"] == "passed"
    log(stage="old_lifecycle", status="passed")
    log(stage="4k_lifecycle", status="started", cases=CASES)
    with (ROOT / "terminal-collection.txt").open("w", encoding="utf-8") as trace:
        watcher = subprocess.Popen([sys.executable, str(ROOT / "watch_4k.py"), "--run",build["run"]], stdout=trace,stderr=subprocess.STDOUT)
        try:
            subprocess.run([sys.executable, str(ROOT / "run_4k.py"), "matrix", "--run",build["run"],"--cases",",".join(CASES)], check=True)
            assert watcher.wait(timeout=180) == 0
        finally:
            if watcher.poll() is None:
                watcher.terminate()
                watcher.wait(timeout=10)
    folder = ROOT / build["run"]
    result, media = read(folder/"results.json"), read(folder/"media-inspection.json")
    assert result["result"] == "passed" and len(result["cases"]) == 4 and len({c["pid"] for c in result["cases"]}) == 1
    assert len(media) == 3 and all(m["result"] == "passed" for m in media)
    log(stage="4k_lifecycle", status="passed", native_pid=result["pid"], decoded_frames=sum(m["decoded_frames"] for m in media))
    log(stage="existing_device_40", status="started")
    subprocess.run([sys.executable, str(legacy/"run_existing_device_cases.py"), "--all"], check=True)
    old = read(legacy/"final-device-results.json")
    assert len(old) == 40 and all(r["result"] == "passed" for r in old), [r for r in old if r["result"] != "passed"]
    log(stage="existing_device_40", status="passed")
    instrument("art.gaoge.dance.engine.pipeline.FourKDetectionReviewInstrumentedTest#freshMirrorFaceIsProtectedWhileItsOldIdentityIsDormant",
               {"review4K":"true"}, legacy/"current-fresh-observation-instrumentation.txt", timeout=90)
    device.adb("pull", device.REMOTE + "/fresh-observation-check.json", str(legacy/"current-fresh-observation-check.json"))
    summary={"result":"passed","test_apk_sha256":digest,"native_run":build["run"],"native_lifecycle_cases":4,"native_lifecycle_outputs":3,
             "native_pid":result["pid"],"previous_40_device_cases":len(old),"previous_production_lifecycle":read(legacy/"lifecycle-final.json"),"fresh_observation_proof":True}
    save(legacy/"regression-summary.json",summary)
    log(**summary)


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--verify-previous-media", action="store_true")
    args=parser.parse_args()
    if args.verify_previous_media: verify_previous_media()
    else: run_device()
