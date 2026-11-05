"""Opt-in ADB runner for the original public 4K fixtures; never clears app data."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
SERIAL = "334da6a3"
PACKAGE = "art.gaoge.dance.engine.test"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/network_privacy_qa"
RUNNER = f"{PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
CLASS = "art.gaoge.dance.engine.pipeline.NetworkPrivacyWorkflowInstrumentedTest#"


def adb(*args: str, **kwargs):
    return subprocess.run(["adb", "-s", SERIAL, *map(str, args)],
                          check=True, capture_output=True, **kwargs)


def save(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def environment():
    previous = json.loads((ROOT.parent / "privacy-regression-2026-11-04/environment.json").read_text(encoding="utf-8"))
    hashes = {}
    for filename, expected in previous["fixture_sha256_matches_original_run"].items():
        path = ROOT.parent / "privacy-e2e-2026-11-04" / filename
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert actual == expected, filename
        hashes[filename] = actual
    remote_hashes = {}
    for filename in ("group-mirror-8956295.mp4", "solo-portrait-7570258.mp4"):
        actual = adb("shell", "sha256sum", f"{REMOTE}/{filename}").stdout.decode().split()[0]
        assert actual == hashes[filename], filename
        remote_hashes[filename] = actual
    installed = {}
    for package in ("art.gaoge.dance", PACKAGE):
        path = adb("shell", "pm", "path", package).stdout.decode().strip().split("package:", 1)[1]
        installed[package] = adb("shell", "sha256sum", path).stdout.decode().split()[0]
    result = {"recorded_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
              "serial": SERIAL, "model": adb("shell", "getprop", "ro.product.model").stdout.decode().strip(),
              "android": adb("shell", "getprop", "ro.build.version.release").stdout.decode().strip(),
              "fixture_sha256_matches_original_run": hashes, "device_4k_fixture_sha256": remote_hashes,
              "installed_apk_sha256": installed,
              "battery": adb("shell", "dumpsys", "battery").stdout.decode(),
              "storage": adb("shell", "df", "-h", "/data").stdout.decode()}
    save(ROOT / "environment.json", result)
    print(json.dumps(result, ensure_ascii=False), flush=True)


def run(method: str, options: dict[str, str], name: str, timeout=3600):
    test_class = CLASS if method == "downloadedClipPreservesProductionPipelineArtifacts" else \
        "art.gaoge.dance.engine.pipeline.FourKPrivacyWorkflowInstrumentedTest#"
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", test_class + method]
    for key, value in options.items():
        command += ["-e", key, value]
    start = time.monotonic()
    result = adb(*command, RUNNER, timeout=timeout)
    output = result.stdout.decode("utf-8", errors="replace")
    (ROOT / f"{name}-instrumentation.txt").write_text(output, encoding="utf-8")
    passed = "OK (1 test)" in output and "INSTRUMENTATION_STATUS_CODE: 0" in output and "FAILURES!!!" not in output
    print(json.dumps({"name": name, "passed": passed, "elapsed_seconds": round(time.monotonic() - start, 2)}), flush=True)
    if not passed:
        print(output, flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("environment", "baseline", "matrix", "status", "pull"))
    parser.add_argument("--cases", default="")
    parser.add_argument("--run", default="4k_20261104")
    parser.add_argument("--leak-frame", action="store_true")
    parser.add_argument("--review-start-ms", default="11600")
    args = parser.parse_args()
    if args.action == "environment":
        environment()
    elif args.action == "baseline":
        name = "baseline_group_4k_solid"
        run("downloadedClipPreservesProductionPipelineArtifacts",
            {"case": name, "input": "group-mirror-8956295.mp4", "faceOnly": "false",
             "fill": "solid", "width": "3840", "height": "2160", "endMs": "1600"}, name)
        for suffix in (".json", ".mp4"):
            adb("pull", f"{REMOTE}/{name}{suffix}", str(ROOT / f"{name}{suffix}"))
    elif args.action == "matrix":
        options = {"fourK": "true", "run": args.run}
        if args.leak_frame:
            options["leakFrame"] = "true"
            options["reviewStartMs"] = args.review_start_ms
        if args.cases:
            options["cases"] = args.cases
        run("fourKFullDurationExportsStayAtSourceResolutionInOneProcess", options, args.run + "-matrix", timeout=14400)
    else:
        result = json.loads(adb("shell", "cat", f"{REMOTE}/{args.run}/results.json").stdout)
        folder = ROOT / args.run
        folder.mkdir(exist_ok=True)
        save(folder / "results.json", result)
        current = [{k: v for k, v in case.items() if k in (
            "case", "state", "stage", "current_frame", "total_frames", "elapsed_seconds", "result",
            "actual_frames", "reviewed_mirror_face_protected_pixels", "error_code", "error_message")}
                   for case in result["cases"][-1:]]
        print(json.dumps({"pid": result["pid"], "result": result.get("result"),
                          "completed": sum(c.get("result") == "passed" for c in result["cases"]),
                          "total": len(result["planned_cases"]), "current": current}, ensure_ascii=False), flush=True)
        if args.action == "pull":
            for case in result["cases"]:
                if case.get("result") == "passed" and case.get("state") == "completed":
                    filename = case["case"] + ".mp4"
                    if (folder / filename).is_file():
                        assert (folder / filename).stat().st_size == case["output_bytes"], filename
                        continue
                    adb("pull", f"{REMOTE}/{args.run}/{filename}", str(folder / filename))
