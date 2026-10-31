#!/usr/bin/env python3
"""Fail closed unless every required CI dependency completed successfully."""

from __future__ import annotations

import json
import os
import sys

REQUIRED_JOBS = frozenset({"validate-and-test", "ios"})


def validate_results(needs: object) -> list[str]:
    if not isinstance(needs, dict):
        return ["needs must be an object"]
    errors = []
    if set(needs) != REQUIRED_JOBS:
        errors.append(f"Expected dependencies {sorted(REQUIRED_JOBS)}, got {sorted(needs)}")
    for name in sorted(REQUIRED_JOBS):
        job = needs.get(name)
        result = job.get("result") if isinstance(job, dict) else None
        if result != "success":
            errors.append(f"{name}: expected success, got {result!r}")
    return errors


def main() -> int:
    try:
        needs = json.loads(os.environ["CI_NEEDS_JSON"])
    except (KeyError, json.JSONDecodeError) as error:
        print(f"Invalid CI dependency results: {error}", file=sys.stderr)
        return 1
    errors = validate_results(needs)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("All required CI jobs succeeded.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
