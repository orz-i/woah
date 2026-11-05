"""Reuse the previous run's exact regression entrypoints in a separate result directory."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
old = ROOT.parent / "privacy-regression-2026-11-04"
candidate = json.loads((ROOT / "final-candidate.json").read_text(encoding="utf-8"))
app = json.loads((ROOT / "app-candidate.json").read_text(encoding="utf-8"))
folder = ROOT / ("legacy_" + candidate["test_apk_sha256"][:8])
folder.mkdir(exist_ok=True)
for name in ("run_network_cases.py", "run_group_profiles.py", "run_regression_media.py",
             "run_existing_device_cases.py", "remaining-device-cases.json"):
    shutil.copyfile(old / name, folder / name)
environment = {"apk_sha256": app["app_apk_sha256"], "test_apk_sha256": candidate["test_apk_sha256"],
               "source_sha256": candidate["source_sha256"], "reuses_unchanged_previous_entrypoints": True}
path = folder / "environment.json"
if path.exists():
    assert json.loads(path.read_text(encoding="utf-8")) == environment, "Do not mix candidate builds"
path.write_text(json.dumps(environment, indent=2) + "\n", encoding="utf-8")
for filename, expected in candidate["source_sha256"].items():
    assert hashlib.sha256((REPO / filename).read_bytes()).hexdigest() == expected, filename
print(folder)
