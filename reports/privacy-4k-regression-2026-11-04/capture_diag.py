"""Keep this test's native diagnostics locally and extract relevant leak-frame evidence."""
import json
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent
ADB = ["adb", "-s", "334da6a3"]
PKG = "art.gaoge.dance.engine.test"


def adb(*args):
    return subprocess.check_output(ADB + list(args))


names = adb("shell", "run-as", PKG, "ls", "-t", "files/diagnostics").decode().splitlines()
logs = [n.strip() for n in names if n.startswith("session_") and n.endswith(".jsonl")]
prefix = logs[0].rsplit("_", 1)[0]
folder = ROOT / ".raw-diag"
folder.mkdir(exist_ok=True)
events = []
for name in sorted(n for n in logs if n.startswith(prefix + "_")):
    raw = adb("exec-out", "run-as", PKG, "cat", "files/diagnostics/" + name)
    (folder / name).write_bytes(raw)
    for line in raw.splitlines():
        try:
            events.append(json.loads(line))
        except (ValueError, UnicodeDecodeError):
            pass  # Active writer may have an incomplete final record; retain its raw bytes.
parser = argparse.ArgumentParser()
parser.add_argument("--job", default="job_2150a440-b2c6-4878-98d5-6169bb38c773")
parser.add_argument("--output", default="baseline-leak-native-samples.json")
args = parser.parse_args()
job = args.job
selected = [e for e in events if e.get("fields", {}).get("job_id") == job
            and e.get("fields", {}).get("pts_us") in (0, 5_800_000, 8_680_000, 11_600_000, 20_280_000, 23_200_000)]
(ROOT / args.output).write_text(json.dumps(selected, indent=2), encoding="utf-8")
try:
    raw = adb("exec-out", "run-as", PKG, "cat", f"files/diagnostics/pipeline_summary_{job}.json")
    summary = json.loads(raw)
    (ROOT / args.output.replace("-samples.json", "-summary.json")).write_text(
        json.dumps(summary, indent=2), encoding="utf-8")
except (subprocess.CalledProcessError, ValueError):
    pass  # A still-running or explicitly stopped job need not have a completion summary.
print(json.dumps({"session": prefix, "events": len(events), "sample_events": len(selected),
                  "schema": list(events[0]) if events else [],
                  "event_types": sorted({e.get("event", "") for e in selected})}, ensure_ascii=False))
