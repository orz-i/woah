"""ADB actions and reproducible evidence for this test run (no app data reset)."""
import argparse
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.stdout.reconfigure(encoding="utf-8")
ADB = "adb"

def adb(*args):
    return subprocess.run([ADB, "-s", "334da6a3", *map(str, args)], check=True, capture_output=True)

def snapshot(name):
    (ROOT / (name + '.xml')).unlink(missing_ok=True)
    adb("shell", "screencap", "-p", "/sdcard/privacy-e2e-screen.png")
    adb("pull", "/sdcard/privacy-e2e-screen.png", ROOT / (name + ".png"))
    adb("shell", "rm", "-f", "/sdcard/privacy-e2e-window.xml")
    try:
        dumped = adb("shell", "uiautomator", "dump", "/sdcard/privacy-e2e-window.xml")
    except subprocess.CalledProcessError as error:
        print(f"UI tree unavailable (exit {error.returncode}); fresh screenshot saved: {name}")
        return
    if b"dumped to" not in dumped.stdout:
        print("UI tree unavailable during animation; use the fresh screenshot: " + name)
        return
    adb("pull", "/sdcard/privacy-e2e-window.xml", ROOT / (name + ".xml"))
    nodes = []
    for n in ET.parse(ROOT / (name + ".xml")).iter("node"):
        label = n.get("text") or n.get("content-desc")
        if label:
            nodes.append({"label": label, "bounds": n.get("bounds"), "selected": n.get("selected"), "enabled": n.get("enabled")})
    for n in nodes:
        print(f"{n['label']} | {n['bounds']} | selected={n['selected']}")

def jobs():
    records = json.loads(adb("shell", "run-as", "art.gaoge.dance", "cat", "files/export_jobs.json").stdout)
    environment = json.loads((ROOT / 'environment.json').read_text(encoding='utf-8'))
    records = [r for r in records if r.get("createdAt", 0) >= environment.get('regression_started_ms', 1793779500000)]
    (ROOT / "export-jobs.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    for r in records:
        print(json.dumps({k: r.get(k) for k in ("jobId", "state", "progress", "currentFrame", "totalFrames", "targetWidth", "targetHeight", "targetFps", "errorCode", "errorMessage", "outputUri", "createdAt", "updatedAt")}, ensure_ascii=False))
        if r.get("state") == "completed" and r.get("outputUri"):
            output = ROOT / Path(r["outputUri"]).name
            if not output.exists():
                output.write_bytes(adb("exec-out", "run-as", "art.gaoge.dance", "cat", r["outputUri"]).stdout)

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("name")
    p.add_argument("--tap", nargs=2, type=int, action="append", default=[])
    p.add_argument("--swipe", nargs=5, type=int)
    p.add_argument("--key")
    p.add_argument("--wait", type=float, default=2)
    a = p.parse_args()
    for point in a.tap:
        adb("shell", "input", "tap", *point)
        time.sleep(0.4)
    if a.swipe:
        adb("shell", "input", "swipe", *a.swipe)
    if a.key:
        adb("shell", "input", "keyevent", a.key)
    time.sleep(a.wait)
    if a.name == "jobs":
        jobs()
    else:
        snapshot(a.name)
