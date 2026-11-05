"""Install the frozen app only after the native device runs have finished."""
import datetime
import hashlib
import json
from pathlib import Path
import time

import run_4k as device
from run_terminal_followup import installed_sha
from ui_4k import snapshot

ROOT = Path(__file__).resolve().parent

def read(path):
    return json.loads(path.read_text(encoding="utf-8"))

def save(path, row):
    path.write_text(json.dumps(row, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

build = read(ROOT / "lifecycle-candidate.json")
legacy = ROOT / ("legacy_" + build["test_apk_sha256"][:8])
assert read(legacy / "regression-summary.json")["result"] == "passed"
assert installed_sha("art.gaoge.dance.engine.test") == build["test_apk_sha256"]
manifest = ROOT / "app-candidate.json"
app = read(manifest)
artifact = ROOT / app["artifact_path"]
with artifact.open("rb") as source:
    assert hashlib.file_digest(source, "sha256").hexdigest() == app["app_apk_sha256"]
assert "Success" in device.adb("install", "-r", str(artifact), timeout=180).stdout.decode()
actual = installed_sha("art.gaoge.dance")
assert actual == app["app_apk_sha256"]
app.update(installed_on_device=True, installed_apk_sha256=actual,
           installed_at_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
save(manifest, app)
local_sticker = ROOT.parent / "privacy-e2e-2026-11-04" / "custom-sticker.png"
album = "/sdcard/Pictures/Woah4KQASticker_" + actual[:8]
remote = album + "/custom-sticker.png"
device.adb("shell", "mkdir", "-p", album)
device.adb("push", str(local_sticker), remote)
expected = hashlib.sha256(local_sticker.read_bytes()).hexdigest()
assert device.adb("shell", "sha256sum", remote).stdout.decode().split()[0] == expected
scanner = device.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://"+remote)
save(ROOT / "ui-installation.json", {"apk_sha256":actual, "device_serial":"334da6a3", "userdata_preserved":True,
     "sticker_album":album, "sticker_sha256":expected, "scan_result":scanner.stdout.decode().strip(),
     "video_fixture_album":"/sdcard/Movies/Woah4KQA_cf7515d4"})
device.adb("shell", "input", "keyevent", "224")
device.adb("shell", "wm", "dismiss-keyguard")
device.adb("shell", "am", "start", "-n", "art.gaoge.dance/.MainActivity")
time.sleep(4)
snapshot("candidate-home")
