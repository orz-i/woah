#!/usr/bin/env python3
"""Capture only completed WoahExportPerf Release scalar events without clearing logcat.

One foreground capture invocation per installed Release variant (OFF or ON).
Waits for the user to finish 3 otherwise identical exports in the app.
Never reads the video, sends network traffic, installs APKs, changes controls,
writes raw logcat or touches model/video binaries.

Arm the stream using a new unique logcat marker so preexisting/older records
can never count toward the fresh measured run. In-app exports remain manual.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import queue
import re
import secrets
import subprocess
import sys
import threading
import time
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.diagnostics.crop_clarity_release_perf import (  # noqa: E402
    FLOAT_FIELDS, MATCH_FIELDS, TAG, validate_release_record,
)

MARKER_TAG = "WoahPerfMarker"
MARKER_PREFIX = "WOAH_RELEASE_PERF_CAPTURE_"
MARKER_FORMAT = re.compile(r"[0-9a-f]{24}")
ALLOWED_FIELDS = frozenset({
    "schema", "build_mode", "git_commit", "device_model",
    "source_width", "source_height", "source_fps",
    "trim_start_ms", "trim_end_ms",
    "target_width", "target_height", "target_fps",
    "video_bitrate", "profile", "follow_enabled", "privacy_target_count",
    "clarity_scale", "clarity_state",
    "decoded_frames", "rendered_frames", "encoded_frames",
    "yolo_accelerator", "yolo_fallback",
    "elapsed_ms", "throughput_fps",
    "render_cpu_dispatch_count", "render_cpu_dispatch_p50_ms",
    "render_cpu_dispatch_p95_ms", "thermal_status_end", "pss_end_kb",
    "ab_capture_possible", "state",
})
COMPARABLE_FIELDS = MATCH_FIELDS + FLOAT_FIELDS + (
    "clarity_scale", "clarity_state", "decoded_frames", "rendered_frames",
    "encoded_frames",
)


def ensure_output_outside_checkout(path: Path) -> None:
    resolved, repo = path.resolve(), ROOT.resolve()
    if resolved == repo or repo in resolved.parents:
        raise ValueError("Release performance capture must be outside Git checkout")
    if path.exists() or path.with_name(path.name + ".partial").exists():
        raise ValueError("Release performance capture exists; choose a fresh output name")


def adb_prefix(adb: str, serial: str | None) -> list[str]:
    if not adb or any(ch in adb for ch in "\r\n\0"):
        raise ValueError("invalid adb executable")
    if serial and not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", serial):
        raise ValueError("invalid Android serial")
    return [adb] + (["-s", serial] if serial else [])


class CaptureSession:
    def __init__(
        self, mode: str, marker_token: str, *,
        repeats: int = 3, expected_commit: str | None = None
    ) -> None:
        if mode not in {"off", "on"}:
            raise ValueError("mode must be off or on")
        if not 2 <= repeats <= 20:
            raise ValueError("capture requires 2–20 complete exports")
        if not isinstance(marker_token, str) or not MARKER_FORMAT.fullmatch(marker_token):
            raise ValueError("capture marker token must be 24 lowercase hex characters")
        if expected_commit is not None and (
            not re.fullmatch(r"[0-9a-fA-F]{40}", expected_commit)
        ):
            raise ValueError("expected commit must be the full Git SHA-1")
        self.mode = mode
        self.marker = MARKER_PREFIX + marker_token
        self.repeats = repeats
        self.expected_commit = expected_commit.lower() if expected_commit else None
        self.armed = False
        self.records: list[dict[str, Any]] = []
        self._seen_payloads: set[str] = set()

    def ingest(self, line: str) -> str:
        """Return 'armed', 'sample', or 'ignored'; fail closed on bad new events."""
        if not self.armed:
            if MARKER_TAG in line and self.marker in line:
                self.armed = True
                return "armed"
            return "ignored"
        if TAG not in line or "{" not in line:
            return "ignored"
        try:
            value = json.loads(line[line.index("{"):])
        except json.JSONDecodeError as exc:
            raise ValueError("malformed tagged Release export JSON") from exc
        if not isinstance(value, dict) or set(value) != ALLOWED_FIELDS:
            raise ValueError("unknown, missing or potentially private Release payload fields")
        data = validate_release_record(value, source="captured Release export")
        if data["clarity_state"] != self.mode:
            raise ValueError("captured wrong Release clarity variant")
        if self.expected_commit is not None and data["git_commit"].lower() != self.expected_commit:
            raise ValueError("captured export does not match the requested Git commit")
        if self.records:
            base = self.records[0]
            for key in COMPARABLE_FIELDS:
                if data[key] != base[key]:
                    raise ValueError("non-comparable repeated Release export field: " + key)
        payload = json.dumps(data, sort_keys=True, separators=(",", ":"), allow_nan=False)
        if payload in self._seen_payloads:
            raise ValueError("duplicate identical Release export record; not an independent run")
        self._seen_payloads.add(payload)
        self.records.append(data)
        return "sample"

    @property
    def complete(self) -> bool:
        return len(self.records) == self.repeats

    def scalar_log(self) -> str:
        if not self.armed or not self.complete:
            raise ValueError("capture must verify exactly the required number of fresh exports")
        # Only the exact known native scalar fields, never raw logcat lines.
        return "".join(
            "I/WoahExportPerf: " + json.dumps(
                record, sort_keys=True, separators=(",", ":"), allow_nan=False
            ) + "\n" for record in self.records
        )


def capture_device(
    output: Path, *, mode: str, repeats: int, timeout_minutes: int,
    adb: str, serial: str | None, expected_commit: str | None,
) -> None:
    ensure_output_outside_checkout(output)
    if not 5 <= timeout_minutes <= 240:
        raise ValueError("capture timeout must be between 5 and 240 minutes")
    prefix = adb_prefix(adb, serial)
    session = CaptureSession(
        mode, secrets.token_hex(12), repeats=repeats,
        expected_commit=expected_commit,
    )
    proc = subprocess.Popen(
        prefix + [
            "logcat", "-v", "brief", "-s",
            f"{TAG}:I", f"{MARKER_TAG}:I", "*:S",
        ],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    events: queue.Queue[str | None] = queue.Queue(maxsize=512)
    overflow = threading.Event()

    def read_tag_lines() -> None:
        try:
            assert proc.stdout is not None
            for line in proc.stdout:
                try:
                    events.put(line, timeout=2)
                except queue.Full:
                    overflow.set()
                    break
        finally:
            try:
                events.put_nowait(None)
            except queue.Full:
                overflow.set()

    thread = threading.Thread(target=read_tag_lines, name="woah-release-logcat", daemon=True)
    thread.start()
    try:
        if proc.poll() is not None:
            raise RuntimeError("ADB logcat exited before Release capture could start")
        # Wait briefly for the subprocess to attach before emitting the unique
        # logcat event; neither read nor clear global device log buffers.
        time.sleep(0.25)
        marker = subprocess.run(
            prefix + ["shell", "log", "-p", "i", "-t", MARKER_TAG, session.marker],
            capture_output=True, text=True, timeout=15, check=False,
        )
        if marker.returncode != 0:
            raise RuntimeError("ADB device cannot emit Release capture marker; log utility unavailable")
        deadline = time.monotonic() + timeout_minutes * 60
        print(f"WOAH_RELEASE_CAPTURE_MODE={mode.upper()}; expected_exports={repeats}", flush=True)
        while not session.complete:
            if overflow.is_set():
                raise RuntimeError("filtered logcat queue overflowed; capture invalid")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(
                    f"Release capture timed out after {timeout_minutes} minutes "
                    f"with {len(session.records)}/{repeats} valid completed exports"
                )
            try:
                line = events.get(timeout=min(remaining, 2))
            except queue.Empty:
                if proc.poll() is not None:
                    raise RuntimeError("ADB logcat exited during Release capture")
                continue
            if line is None:
                raise RuntimeError("ADB logcat closed before required Release exports")
            signal = session.ingest(line)
            if signal == "armed":
                print(
                    "WOAH_RELEASE_CAPTURE_ARMED=YES; "
                    "now complete the matching exports in the installed Release app",
                    flush=True,
                )
            elif signal == "sample":
                print(
                    f"WOAH_RELEASE_EXPORT_CAPTURED={len(session.records)}/{repeats}",
                    flush=True,
                )
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_name(output.name + ".partial")
        temporary.write_text(session.scalar_log(), encoding="utf-8")
        temporary.replace(output)
        print(f"WOAH_RELEASE_CAPTURE=SCALAR_ONLY_COMPLETE; output={output}", flush=True)
        print("RELEASE_VISUAL_AND_PRIVACY_ACCEPTANCE=NOT_PROVEN", flush=True)
    finally:
        if proc.poll() is None:
            proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        if proc.stdout:
            proc.stdout.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["off", "on"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--timeout-minutes", type=int, default=90)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--expected-commit")
    args = parser.parse_args()
    try:
        capture_device(
            args.output, mode=args.mode, repeats=args.repeats,
            timeout_minutes=args.timeout_minutes, adb=args.adb,
            serial=args.serial, expected_commit=args.expected_commit,
        )
    except (ValueError, OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
        print("WOAH_RELEASE_CAPTURE=FAIL: " + str(exc), file=sys.stderr)
        print("NO_PRIVACY_OR_PERFORMANCE_PASS_CLAIM", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("WOAH_RELEASE_CAPTURE=CANCELLED_NO_REPORT", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
