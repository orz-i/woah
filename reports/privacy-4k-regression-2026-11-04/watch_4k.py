"""Follow this one 4K test batch; collect and independently verify each finished artifact."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

import inspect_4k as media
import run_4k as device

ROOT = Path(__file__).resolve().parent


def log(value):
    print(json.dumps({"recorded_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **value}, ensure_ascii=False), flush=True)


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    folder = ROOT / args.run
    folder.mkdir(exist_ok=True)
    inspected_path = folder / "media-inspection.json"
    inspected = {r["file"]: r for r in json.loads(inspected_path.read_text(encoding="utf-8"))} if inspected_path.exists() else {}
    holes_path = folder / "opaque-face-hole-audit.json"
    holes = {r["file"]: r for r in json.loads(holes_path.read_text(encoding="utf-8"))} if holes_path.exists() else {}
    source_pts_path = ROOT / "source-timestamps.json"
    source_pts = json.loads(source_pts_path.read_text(encoding="utf-8")) if source_pts_path.exists() else {}
    disconnected = 0
    while True:
        try:
            result = json.loads(device.adb("shell", "cat", f"{device.REMOTE}/{args.run}/results.json", timeout=30).stdout)
            disconnected = 0
        except (subprocess.SubprocessError, ValueError) as error:
            disconnected += 1
            log({"connection_retry": disconnected, "error": str(error)})
            if disconnected >= 6:
                raise
            time.sleep(30)
            continue
        save(folder / "results.json", result)
        for case in result["cases"]:
            # Keep failures' artifacts too; their structural and privacy outcomes remain distinct.
            if case.get("state") != "completed" or "output_bytes" not in case:
                continue
            name = case["case"] + ".mp4"
            path = folder / name
            if not path.exists():
                device.adb("pull", f"{device.REMOTE}/{args.run}/{name}", str(path), timeout=120)
            assert path.stat().st_size == case["output_bytes"], name
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if name not in inspected or inspected[name]["sha256"] != digest:
                if case["input"] not in source_pts:
                    source_pts[case["input"]] = media.pts(ROOT.parent / "privacy-e2e-2026-11-04" / case["input"])
                    save(source_pts_path, source_pts)
                checked = media.inspect(path, case, source_pts[case["input"]])
                inspected[name] = checked
                save(inspected_path, list(inspected.values()))
                log({"media_checked": name, "result": checked["result"], "dimensions": [checked["width"], checked["height"]],
                     "frames": checked["decoded_frames"], "checks": checked["checks"]})
                assert checked["result"] == "passed", checked
                subprocess.run([sys.executable, str(ROOT / "capture_diag.py"), "--job", case["job_id"],
                                "--output", f"{args.run}/native-{case['case']}-samples.json"], check=True, capture_output=True)
            if case["face_only"] and case["fill"] == "solid" and (
                name not in holes or holes[name].get("algorithm_version") != media.HOLE_AUDIT_VERSION
            ):
                audit = media.audit_face_solid(path)
                holes[name] = audit
                save(holes_path, list(holes.values()))
                log({"opaque_face_audit": name, "frames": audit["frames"], "frames_with_candidates": audit["frames_with_candidates"]})
        current = result["cases"][-1] if result["cases"] else {}
        log({"pid": result["pid"], "result": result.get("result"),
             "completed": sum(r.get("result") == "passed" for r in result["cases"]),
             "total": len(result["planned_cases"]), "case": current.get("case"), "state": current.get("state"),
             "current_frame": current.get("current_frame"), "total_frames": current.get("total_frames"),
             "elapsed_seconds": current.get("elapsed_seconds")})
        if result.get("result") is not None:
            assert result["result"] == "passed", current
            break
        time.sleep(35)
