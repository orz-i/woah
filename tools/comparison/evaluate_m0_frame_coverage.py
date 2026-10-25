#!/usr/bin/env python3
"""Check fixture frame-code pairs against actual ffprobe PTS coverage intervals.

Used for mixed CFR/VFR fixtures derived without changing their printed PTS.
A code identifies its original CFR reference index; the interval end comes from
actual next PTS, never average FPS. No physical panel/scanout proof is claimed.
"""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path
import subprocess


def frame_intervals(pts_us: list[int], duration_us: int, code_fps: int) -> dict:
    if not pts_us or pts_us != sorted(set(pts_us)) or pts_us[-1] >= duration_us:
        raise ValueError('Expected increasing video PTS within a finite duration')
    result = {}
    for start, end in zip(pts_us, pts_us[1:] + [duration_us]):
        code = round(start * code_fps / 1_000_000)
        if code < 0 or code in result or abs(start - code * 1_000_000 / code_fps) > 2:
            raise ValueError('Fixture code no longer identifies its printed source PTS')
        result[code] = (start, end)
    return result


def read_source(ffprobe: str, source: Path, code_fps: int) -> dict:
    r = subprocess.run([ffprobe, '-v', 'error', '-select_streams', 'v:0',
                        '-show_frames', '-show_entries',
                        'frame=best_effort_timestamp_time:stream=duration',
                        '-of', 'json', str(source)], capture_output=True, text=True, check=True)
    data = json.loads(r.stdout)
    pts = [round(float(x['best_effort_timestamp_time']) * 1_000_000)
           for x in data['frames']]
    duration = round(float(data['streams'][0]['duration']) * 1_000_000)
    return frame_intervals(pts, duration, code_fps)


def evaluate_coverage(samples: list[dict], teacher: dict, learner: dict,
                      offset_us: int) -> dict:
    gaps, errors, teacher_pts, learner_pts = [], [], [], []
    for row in samples:
        try:
            t0, t1 = teacher[row['teacherFrame']]
            l0, l1 = learner[row['learnerFrame']]
        except KeyError as e:
            raise ValueError('Captured code is absent from actual source PTS') from e
        gaps.append(max(0, max(t0, l0-offset_us)-min(t1, l1-offset_us)))
        errors.append(abs(l0-t0-offset_us))
        teacher_pts.append(t0); learner_pts.append(l0)
    def p95(values):
        return sorted(values)[math.ceil(.95*len(values))-1] if values else None
    enough = len(gaps) >= 60
    within = enough and max(gaps) <= 1  # ffprobe/codec integer-us quantization
    return {
        'schema': 'woah.comparison.m0.frame-coverage.v1',
        'status': ('FRAME_COVERAGE_WITHIN_TARGET' if within else
                   'FRAME_COVERAGE_OUTSIDE_TARGET') if enough else 'INSUFFICIENT_SCREEN_CAPTURE',
        'actualDisplayAcceptance': 'NOT_PROVEN_PHYSICAL_PANEL',
        'evidenceType': 'captured_fixture_codes_and_actual_source_pts_intervals',
        'samples': len(gaps), 'expectedOffsetUs': offset_us,
        'p95IntervalGapUs': p95(gaps), 'maxIntervalGapUs': max(gaps) if gaps else None,
        'intervalQuantizationToleranceUs': 1,
        'p95AbsFrameStartDeltaUs': p95(errors),
        'maxAbsFrameStartDeltaUs': max(errors) if errors else None,
        'teacherSourceSpanUs': max(teacher_pts)-min(teacher_pts) if teacher_pts else 0,
        'learnerSourceSpanUs': max(learner_pts)-min(learner_pts) if learner_pts else 0,
        'uniqueTeacherFrames': len(set(teacher_pts)),
        'uniqueLearnerFrames': len(set(learner_pts)),
        'limitation': ('Frame intervals may overlap or touch within 1us quantization. '
                       'Start-PTS deltas can differ naturally for mixed/VFR sources; '
                       'this checks captured frame coverage, not audio latency or panel scanout.'),
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('samples', type=Path)
    p.add_argument('--teacher-source', type=Path, required=True)
    p.add_argument('--learner-source', type=Path, required=True)
    p.add_argument('--teacher-code-fps', type=int, default=30, choices=(24,30,60))
    p.add_argument('--learner-code-fps', type=int, default=30, choices=(24,30,60))
    p.add_argument('--offset-us', type=int, default=3_000_000)
    p.add_argument('--ffprobe', default='ffprobe')
    p.add_argument('--out', type=Path)
    a = p.parse_args()
    result = evaluate_coverage(json.loads(a.samples.read_text(encoding='utf-8')),
        read_source(a.ffprobe,a.teacher_source,a.teacher_code_fps),
        read_source(a.ffprobe,a.learner_source,a.learner_code_fps),a.offset_us)
    output = json.dumps(result, indent=2)+'\n'
    if a.out: a.out.write_text(output,encoding='utf-8')
    print(output)
    return 0 if result['status']=='FRAME_COVERAGE_WITHIN_TARGET' else 2

if __name__ == '__main__': raise SystemExit(main())
