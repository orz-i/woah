"""ADB helpers for observed main-app UI journeys; never clear app data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
import run_4k as device
import inspect_4k as media
from review_frames import make_crops

ROOT = Path(__file__).resolve().parent
PKG = "art.gaoge.dance"

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def snapshot(name):
    path = ROOT / ("ui-" + name)
    device.adb("shell", "screencap", "-p", "/sdcard/privacy-4k-screen.png")
    device.adb("pull", "/sdcard/privacy-4k-screen.png", str(path.with_suffix(".png")))
    device.adb("shell", "rm", "-f", "/sdcard/privacy-4k-window.xml")
    dumped = device.adb("shell", "uiautomator", "dump", "/sdcard/privacy-4k-window.xml", timeout=35)
    assert b"dumped to" in dumped.stdout, "Fresh screenshot saved; UI tree unavailable"
    device.adb("pull", "/sdcard/privacy-4k-window.xml", str(path.with_suffix(".xml")))
    rows = [{"label": n.get("text") or n.get("content-desc"), "bounds": n.get("bounds"), "enabled": n.get("enabled"), "selected": n.get("selected")} for n in ET.parse(path.with_suffix(".xml")).iter("node") if n.get("text") or n.get("content-desc")]
    for row in rows: print(json.dumps(row, ensure_ascii=False), flush=True)
    return rows

def records():
    return json.loads(device.adb("shell", "run-as", PKG, "cat", "files/export_jobs.json").stdout)

def gallery_rows():
    columns = "_id:_display_name:_size:width:height:duration:relative_path:is_pending"
    command = (f'content query --uri content://media/external_primary/video/media '
               f'--projection {columns} --where "relative_path=\'Movies/Woah/\' AND is_pending=0"')
    raw = device.adb("shell", command).stdout.decode("utf-8", errors="replace").strip()
    rows = {}
    for line in raw.splitlines():
        match = re.fullmatch(r"Row: \d+ (.*)", line)
        if match:
            row = dict(part.split("=", 1) for part in re.split(r", (?=[\w]+?=)", match[1]))
            rows[row["_id"]] = row
    assert rows or "No result found" in raw, "MediaStore query did not return readable records"
    return rows


def verified_gallery_copy(previous_ids, output, digest):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        for row_id, row in gallery_rows().items():
            if row_id in previous_ids or row.get("_size") != str(output.stat().st_size):
                continue
            name = row["_display_name"]
            if not re.fullmatch(r"Woah_[0-9a-f-]{36}\.mp4", name):
                continue
            path = "/sdcard/Movies/Woah/" + name
            saved_digest = device.adb("shell", "sha256sum", path).stdout.decode().split()[0]
            if saved_digest == digest:
                return {**row, "content_uri": "content://media/external_primary/video/media/" + row_id,
                        "path": path, "sha256": saved_digest, "matches_export_bytes": True}
        time.sleep(2)
    raise AssertionError("A newly published MediaStore copy matching the complete export was not found")


def pid():
    return device.adb("shell", "pidof", PKG).stdout.decode().strip()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("snapshot", "start", "collect", "status"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source", choices=("group", "solo"))
    parser.add_argument("--tap", nargs=2, type=int)
    parser.add_argument("--key")
    parser.add_argument("--swipe", nargs=5, type=int)
    parser.add_argument("--wait", type=float, default=1)
    a = parser.parse_args()
    state_path = ROOT / ("ui-" + a.tag + "-job.json")
    if a.action == "snapshot":
        if a.tap: device.adb("shell", "input", "tap", *a.tap)
        if a.swipe: device.adb("shell", "input", "swipe", *a.swipe)
        if a.key: device.adb("shell", "input", "keyevent", a.key)
        time.sleep(a.wait)
        snapshot(a.tag)
    elif a.action == "start":
        assert a.source
        assert not state_path.exists(), "Use a new UI journey tag"
        before = {r["jobId"] for r in records()}
        process = pid()
        gallery_ids_before = sorted(gallery_rows())
        device.adb("shell", "input", "tap", 985, 187)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            jobs = [r for r in records() if r["jobId"] not in before]
            if jobs:
                job = max(jobs, key=lambda r:r["createdAt"])
                expected_dimensions = (3840,2160) if a.source == "group" else (2160,4096)
                assert (job["targetWidth"],job["targetHeight"]) == expected_dimensions, "UI must select original resolution and original aspect"
                assert not json.loads(job["followJson"])["enabled"], "Full-source UI regression must have no hero/follow crop"
                filename = "group-mirror-8956295.mp4" if a.source == "group" else "solo-portrait-7570258.mp4"
                source_digest = device.adb("shell", "run-as", PKG, "sha256sum", job["sourceUri"]).stdout.decode().split()[0]
                expected = hashlib.sha256((ROOT.parent / "privacy-e2e-2026-11-04" / filename).read_bytes()).hexdigest()
                assert source_digest == expected, "UI selected a different original"
                save(state_path, {"tag":a.tag,"source":a.source,"input":filename,"pid":process,"source_sha256":source_digest,"gallery_ids_before":gallery_ids_before,"job":job})
                print(json.dumps(job, ensure_ascii=False), flush=True)
                break
            time.sleep(1)
        else: raise AssertionError("UI did not submit a production export")
    else:
        state = json.loads(state_path.read_text(encoding="utf-8"))
        job_id = state["job"]["jobId"]
        deadline = time.monotonic() + (1200 if a.action == "collect" else 0)
        while True:
            job = next(r for r in records() if r["jobId"] == job_id)
            print(json.dumps({k:job.get(k) for k in ("jobId","state","currentFrame","totalFrames","targetWidth","targetHeight","errorCode","errorMessage")},ensure_ascii=False),flush=True)
            if a.action == "status": break
            if job["state"] in ("completed","cancelled","failed","interrupted"):
                assert job["state"] == "completed", job
                assert pid() == state["pid"], "Main app process changed"
                device.adb("shell", "input", "keyevent", "224")
                device.adb("shell", "wm", "dismiss-keyguard")
                time.sleep(3)
                labels = snapshot(a.tag + "-saved")
                assert any("已保存至系统相册" in r["label"] for r in labels), "Gallery success not observed"
                output = ROOT / ("ui-" + a.tag + ".mp4")
                output.write_bytes(device.adb("exec-out","run-as",PKG,"cat",job["outputUri"]).stdout)
                group = state["source"] == "group"
                width,height = (3840,2160) if group else (2160,4096)
                case={"case":a.tag,"input":state["input"],"width":width,"height":height,"trim_start_ms":0,"trim_end_ms":23240 if group else 26496,"result":"passed"}
                checked = media.inspect(output,case,media.pts(ROOT.parent / "privacy-e2e-2026-11-04" / state["input"]))
                assert checked["result"] == "passed", checked
                assert (job["targetWidth"],job["targetHeight"]) == (width,height), job
                gallery = verified_gallery_copy(set(state["gallery_ids_before"]), output, checked["sha256"])
                comparison = make_crops(state["input"], output)
                row={**state,"job":job,"media":checked,"gallery":gallery,"comparison_artifact":comparison.name,"saved_to_gallery":True,"source_resolution_preserved":True,"result":"passed"}
                target=ROOT/"ui-results.json"
                rows=json.loads(target.read_text(encoding="utf-8")) if target.exists() else []
                rows=[r for r in rows if r["tag"]!=a.tag]+[row]
                assert len({r["pid"] for r in rows})==1,"Exports were not in the same main app process"
                save(target,rows)
                print(json.dumps({"tag":a.tag,"result":"passed","decoded_frames":checked["decoded_frames"],"dimensions":[width,height]},ensure_ascii=False),flush=True)
                break
            assert time.monotonic() < deadline, "Main app UI export did not terminate"
            time.sleep(20)
