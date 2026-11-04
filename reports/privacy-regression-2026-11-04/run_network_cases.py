"""Run each real-media case in an isolated test process and retain the outputs."""
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SERIAL = "334da6a3"
PACKAGE = "art.gaoge.dance.engine.test"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/network_privacy_qa"
sys.stdout.reconfigure(encoding="utf-8")

cases = [
    {"case": "solo_face_sticker", "fill": "sticker"},
    {"case": "solo_face_mosaic", "fill": "mosaic"},
    {"case": "solo_face_blur", "fill": "blur"},
    {"case": "solo_face_solid", "fill": "solid"},
    {"case": "solo_face_gradient", "fill": "gradient"},
    *[{"case": "group_full_" + fill, "fill": fill, "faceOnly": "false", "input": "group-hd-8956295.mp4", "width": "960", "height": "540"} for fill in ("solid", "mosaic", "blur", "gradient")],
    {"case": "group_first_solid", "fill": "solid", "faceOnly": "false", "input": "group-hd-8956295.mp4", "width": "960", "height": "540", "select": "first"},
    {"case": "solo_face_balanced", "fill": "sticker", "profile": "balanced"},
    {"case": "solo_face_speed", "fill": "sticker", "profile": "speed"},
    {"case": "solo_nonzero_trim", "fill": "sticker", "startMs": "1000", "endMs": "2600"},
    {"case": "group_face_sticker_follow", "fill": "sticker", "input": "group-hd-8956295.mp4", "hero": "6", "width": "720", "height": "1280", "endMs": "3040"},
    {"case": "group_face_solid_follow", "fill": "solid", "input": "group-hd-8956295.mp4", "hero": "6", "width": "720", "height": "1280", "endMs": "3040"},
    {"case": "group_all_faces", "fill": "solid", "input": "group-hd-8956295.mp4", "width": "960", "height": "540", "endMs": "3040"},
    {"case": "group_4k_full", "fill": "solid", "faceOnly": "false", "input": "group-mirror-8956295.mp4", "width": "960", "height": "540"},
    {"case": "solo_4k_sticker", "fill": "sticker", "input": "solo-portrait-7570258.mp4"},
    {"case": "duo_full_solid", "fill": "solid", "faceOnly": "false", "input": "duo-dance-6940536.mp4", "width": "960", "height": "540"},
]

def adb(*args, **kwargs):
    return subprocess.run(["adb", "-s", SERIAL, *args], check=True, capture_output=True, **kwargs)

rows = []
if '--faces' in sys.argv:
    cases = [case for case in cases if case.get('faceOnly', 'true') == 'true']
    rows = json.loads((ROOT / 'network-case-results.json').read_text(encoding='utf-8'))
priority = {"group_all_faces": 0, "group_face_sticker_follow": 1, "group_face_solid_follow": 2, "group_full_solid": 3}
cases.sort(key=lambda case: priority.get(case['case'], 3))
for case in cases:
    name = case["case"]
    start = time.monotonic()
    adb("shell", "am", "force-stop", PACKAGE)
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", "art.gaoge.dance.engine.pipeline.NetworkPrivacyWorkflowInstrumentedTest#downloadedClipPreservesProductionPipelineArtifacts"]
    for key, value in case.items():
        command += ["-e", key, value]
    command += [PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"]
    try:
        proc = adb(*command, timeout=150)
        output = proc.stdout.decode("utf-8", errors="replace")
        (ROOT / (name + "-instrumentation.txt")).write_text(output, encoding="utf-8")
        status = "passed" if "INSTRUMENTATION_STATUS_CODE: 0" in output and "FAILURES!!!" not in output else "failed"
        for suffix in (".mp4", ".json"):
            pull = subprocess.run(["adb", "-s", SERIAL, "pull", REMOTE + "/" + name + suffix, str(ROOT / (name + suffix))], capture_output=True)
    except subprocess.TimeoutExpired as exc:
        status = "timeout"
        (ROOT / (name + "-instrumentation.txt")).write_bytes(exc.stdout or b"")
        adb("shell", "am", "force-stop", PACKAGE)
    row = {**case, "result": status, "elapsed_seconds": round(time.monotonic() - start, 2),
           "apk_sha256": json.loads((ROOT/'environment.json').read_text())['apk_sha256']}
    rows = [old for old in rows if old['case'] != name] + [row]
    (ROOT / "network-case-results.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    print(json.dumps(row), flush=True)
