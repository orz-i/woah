import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PACKAGE = "art.gaoge.dance.engine.test"
sys.stdout.reconfigure(encoding="utf-8")
remaining = json.loads((ROOT / "remaining-device-cases.json").read_text())
result_path = ROOT / "final-device-results.json"
rows = json.loads(result_path.read_text()) if result_path.exists() else []
done = {row["case"] for row in rows}
cases = [s for s in remaining if s not in done and ("--all" in sys.argv or not any(x in s for x in ("Reduces", "benchmarkOes")))]
for case in cases:
    index = len(rows)
    subprocess.run(["adb", "-s", "334da6a3", "shell", "am", "force-stop", PACKAGE], check=True, capture_output=True)
    start = time.monotonic()
    log = ROOT / f"final-device-{index:02d}.txt"
    try:
        proc = subprocess.run(["adb", "-s", "334da6a3", "shell", "am", "instrument", "-w", "-r", "-e", "class", case, PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, timeout=90)
        output = proc.stdout.decode("utf-8", errors="replace")
        log.write_text(output, encoding="utf-8")
        status = "passed" if "OK (1 test)" in output and "INSTRUMENTATION_STATUS_CODE: 0" in output else "failed"
    except subprocess.TimeoutExpired as error:
        log.write_bytes(error.stdout or b"")
        status = "timeout"
        subprocess.run(["adb", "-s", "334da6a3", "shell", "am", "force-stop", PACKAGE], check=True, capture_output=True)
    row = {"case": case, "result": status, "log": log.name, "elapsed_seconds": round(time.monotonic() - start, 2)}
    rows.append(row)
    result_path.write_text(json.dumps(rows, indent=2), encoding="utf-8")
    print(json.dumps(row), flush=True)
