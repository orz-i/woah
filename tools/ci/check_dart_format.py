#!/usr/bin/env python3
"""Check all tracked handwritten mobile Dart files with the pinned Dart SDK.

Generated Pigeon outputs are governed by regeneration, not hand-formatting.
Use --write for a dedicated formatting change; the default never edits files.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
GENERATED = frozenset({
    "mobile/packages/dance_native/lib/src/bridge/dance_api.g.dart",
    "mobile/packages/dance_native/lib/src/bridge/comparison_api.g.dart",
})


def handwritten_files(paths: list[str]) -> list[str]:
    return sorted(path for path in paths if path.startswith("mobile/")
                  and path.endswith(".dart") and path not in GENERATED)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
    files = handwritten_files(tracked.decode("utf-8").split("\0"))
    if not files:
        print("No tracked handwritten Dart files found.", file=sys.stderr)
        return 1
    flags = [] if args.write else ["--output=none", "--set-exit-if-changed"]
    return subprocess.call(["dart", "format", *flags, *files], cwd=ROOT)


if __name__ == "__main__":
    sys.exit(main())
