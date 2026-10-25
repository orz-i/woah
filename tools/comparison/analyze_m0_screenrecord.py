#!/usr/bin/env python3
"""Measure rendered M0 frame codes in an Android compositor screen recording.

Requires FFmpeg/ffprobe, no OCR/fonts. Explicit video rectangles refer to capture
pixels (x,y,width,height). This proves captured pixels, not physical panel scanout.
"""
from __future__ import annotations
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import subprocess
import tempfile


def summarize(samples: list[dict], fps: int, offset_us: int) -> dict:
    if fps <= 0:
        raise ValueError('fps must be positive')
    measured = []
    for row in samples:
        row = dict(row)
        row['errorUs'] = round((row['learnerFrame'] - row['teacherFrame']) *
                               1_000_000 / fps - offset_us)
        measured.append(row)
    if not measured:
        return {'status': 'INSUFFICIENT_SCREEN_CAPTURE', 'samples': 0,
                'actualDisplayAcceptance': 'NOT_PROVEN_PHYSICAL_PANEL'}
    errors = sorted(abs(row['errorUs']) for row in measured)
    p95 = errors[math.ceil(.95 * len(errors)) - 1]
    groups = defaultdict(list)
    for row in measured:
        groups[int(row['captureSeconds'] // 5)].append(row['errorUs'])
    windows = [{'startSeconds': window * 5, 'samples': len(values),
                'meanSignedErrorUs': sum(values) / len(values),
                'maxAbsErrorUs': max(map(abs, values))}
               for window, values in sorted(groups.items())]
    one_frame = math.ceil(1_000_000 / fps)
    enough = len(measured) >= 60
    within = p95 <= one_frame and errors[-1] <= 2 * one_frame
    return {
        'schema': 'woah.comparison.m0.screen.v1',
        'status': ('SCREEN_CAPTURE_WITHIN_TARGET' if within else
                   'SCREEN_CAPTURE_OUTSIDE_TARGET') if enough else
                  'INSUFFICIENT_SCREEN_CAPTURE',
        'actualDisplayAcceptance': 'NOT_PROVEN_PHYSICAL_PANEL',
        'evidenceType': 'decoded_frame_codes_in_compositor_capture',
        'samples': len(measured), 'fps': fps, 'expectedOffsetUs': offset_us,
        'p95AbsMediaErrorUs': p95, 'maxAbsMediaErrorUs': errors[-1],
        'meanSignedMediaErrorUs': sum(r['errorUs'] for r in measured) / len(measured),
        'firstCaptureSeconds': measured[0]['captureSeconds'],
        'lastCaptureSeconds': measured[-1]['captureSeconds'],
        'windows': windows,
        'worstExamples': sorted(measured, key=lambda r: abs(r['errorUs']),
                                reverse=True)[:12],
        'limitation': ('Screen encoder/compositor can drop frames or sample the two '
                       'surfaces at different display phases. No panel/scanout proof; '
                       'thresholds apply only to equal-fps CFR sources.'),
    }


def rectangle(value: str) -> tuple[int, ...]:
    parts = tuple(map(int, value.split(',')))
    if len(parts) != 4 or min(parts) < 0 or min(parts[2:]) <= 0:
        raise argparse.ArgumentTypeError('Expected x,y,width,height')
    return parts


def decode_strip(raw: bytes) -> tuple[int, int] | None:
    result = []
    for side in range(2):
        code = 0
        for bit in range(20):
            # Average a central 5x4 patch in every large binary cell.
            pixels = [raw[y * 1040 + side * 520 + bit * 26 + x]
                      for y in range(4) for x in range(11, 16)]
            mean = sum(pixels) / len(pixels)
            if 55 < mean < 200:
                return None
            code = code * 2 + (mean >= 128)
        result.append(code)
    return tuple(result)


def analyze(args: argparse.Namespace) -> dict:
    probe = subprocess.run([args.ffprobe, '-v', 'error', '-select_streams', 'v:0',
                            '-show_frames', '-show_entries',
                            'frame=best_effort_timestamp_time', '-of', 'json',
                            str(args.recording)],capture_output=True,text=True,check=True)
    times = [float(row['best_effort_timestamp_time'])
             for row in json.loads(probe.stdout)['frames']]
    chains = ['[0:v]split=2[t][l]']
    for side, rect in zip(('t', 'l'), (args.teacher_rect, args.learner_rect)):
        x, y, width, height = rect
        chains.append(f'[{side}]crop=w={width}:h={height}:x={x}:y={y}:exact=1,'
                      f'scale=640:360:flags=area,crop=520:4:24:262[{side}b]')
    chains.append('[tb][lb]hstack=inputs=2[out]')
    command = [args.ffmpeg,'-v','error','-i',str(args.recording),'-filter_complex',
               ';'.join(chains),'-map','[out]','-fps_mode','passthrough',
               '-pix_fmt','gray','-f','rawvideo','pipe:1']
    samples, invalid, excluded = [], 0, 0
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command,stdout=subprocess.PIPE,stderr=errors)
        try:
            assert process.stdout is not None
            index = 0
            while True:
                raw = process.stdout.read(1040 * 4)
                if not raw: break
                if len(raw) != 1040 * 4 or index >= len(times):
                    raise ValueError('Truncated frame strip or ffprobe/frame count mismatch')
                pair = decode_strip(raw)
                seconds = times[index]
                index += 1
                if pair is None:
                    invalid += 1
                    continue
                teacher, learner = pair
                if not (args.start_source_seconds <= teacher / args.fps <
                        args.end_source_seconds):
                    excluded += 1
                    continue
                samples.append({'captureSeconds': seconds, 'teacherFrame': teacher,
                                'learnerFrame': learner})
            if process.wait():
                errors.seek(0)
                raise RuntimeError(errors.read().decode('utf-8', errors='replace'))
            if index != len(times): raise ValueError('Incomplete screen decode')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
    result = summarize(samples, args.fps, args.offset_us)
    result.update({'recording': str(args.recording), 'decodedCaptureFrames': index,
                   'invalidCodes': invalid, 'excludedFrames': excluded,
                   'teacherRect': args.teacher_rect, 'learnerRect': args.learner_rect})
    if args.samples_out:
        args.samples_out.write_text(json.dumps(samples, indent=2) + '\n', encoding='utf-8')
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording',type=Path)
    parser.add_argument('--teacher-rect',type=rectangle,required=True)
    parser.add_argument('--learner-rect',type=rectangle,required=True)
    parser.add_argument('--fps',type=int,choices=(24,30,60),default=30)
    parser.add_argument('--offset-us',type=int,default=3_000_000)
    parser.add_argument('--start-source-seconds',type=float,default=1.0)
    parser.add_argument('--end-source-seconds',type=float,default=11.0)
    parser.add_argument('--ffmpeg',default='ffmpeg')
    parser.add_argument('--ffprobe',default='ffprobe')
    parser.add_argument('--out',type=Path)
    parser.add_argument('--samples-out',type=Path)
    args=parser.parse_args()
    result=analyze(args)
    output=json.dumps(result,indent=2)+'\n'
    if args.out: args.out.write_text(output,encoding='utf-8')
    print(output)
    return 0 if result['status']=='SCREEN_CAPTURE_WITHIN_TARGET' else 2

if __name__=='__main__': raise SystemExit(main())
