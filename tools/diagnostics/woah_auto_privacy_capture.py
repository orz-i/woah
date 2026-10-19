#!/usr/bin/env python3
"""Offline automatic capture for the Woah 39.733s privacy-overlap investigation.

Requires Python 3.9+, ffmpeg, ffprobe. Does NOT require OpenCV, Pillow, or an APK.
Reads the original local video, optional final exports, and Woah's numeric report;
packages only small selected risk-frame crops and a manifest. No networking,
no uploads, no audio, no full source frames, and no automatic privacy verdict.

CAUTION: the ZIP can contain recognizable people in the original-video crops.
Share it only with a recipient authorized to see those faces/images.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile
from fractions import Fraction

WINDOW_START_US = 39_633_333
WINDOW_END_US = 39_966_667
REQUIRED_BBOX_KEYS = ('protected_mask_proto_bbox', 'selected_bbox_proto')
DEFAULT_CONTEXT_PADDING = (90, 180, 75, 155)  # left, right, top, bottom, source px
MAX_TOTAL_CAPTURE_FRAMES = 18


def require_executable(name: str) -> str:
    found = shutil.which(name)
    if not found:
        raise ValueError(f'Cannot find {name}; install FFmpeg and add ffmpeg/ffprobe to PATH')
    return found


def run(cmd: list[str]) -> str:
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding='utf-8', errors='replace', check=False)
    if result.returncode:
        raise ValueError(f'{Path(cmd[0]).name} returned {result.returncode}: '
                         + result.stderr[-1400:].strip())
    return result.stdout


def sha256(path: Path) -> str:
    dig = hashlib.sha256()
    with path.open('rb') as fd:
        while True:
            piece = fd.read(2 * 1024 * 1024)
            if not piece:
                break
            dig.update(piece)
    return dig.hexdigest()


def read_numeric_report(path: Path) -> dict:
    if path.stat().st_size > 2_500_000:
        raise ValueError('Numeric JSON is unexpectedly large (>2.5 MB)')
    obj = json.loads(path.read_text(encoding='utf-8'))
    frames = obj.get('focus_frames')
    if not isinstance(frames, list) or not 3 <= len(frames) <= 15:
        raise ValueError('Expect 3-15 overlap risk frames in numeric-report.focus_frames')
    entries = []
    for item in frames:
        pts = item.get('pts_us')
        rect = next((item[name] for name in REQUIRED_BBOX_KEYS if name in item), None)
        if not isinstance(pts, int) or not 39_600_000 <= pts <= 39_910_000:
            raise ValueError('Risk-frame PTS is outside the audited window')
        if (not isinstance(rect, list) or len(rect) != 4 or
            any(type(v) not in (float, int) or not math.isfinite(v) for v in rect) or
            not (0 <= rect[0] < rect[2] <= 160 and 0 <= rect[1] < rect[3] <= 160)):
            raise ValueError('Invalid 160x160 prototype mask bbox')
        entries.append({'pts_us': pts, 'selected_bbox_proto': rect})
    entries.sort(key=lambda x: x['pts_us'])
    if len(set(x['pts_us'] for x in entries)) != len(entries):
        raise ValueError('Duplicated risk-frame timestamps')
    commit = obj.get('build_commit')
    if commit is not None and (not isinstance(commit, str) or len(commit) != 40 or
                               any(c not in '0123456789abcdefABCDEF' for c in commit)):
        raise ValueError('Invalid Git commit in numeric report')
    return {'focus': entries, 'numeric_report_commit': commit,
            'numeric_report_sha256': sha256(path)}


def bbox_to_source(bbox: list, width: int = 1280, height: int = 720) -> list[float]:
    scale = min(640 / width, 640 / height)
    pad_x = (640 - width * scale) / 2
    pad_y = (640 - height * scale) / 2
    return [((bbox[0] * 4 - pad_x) / scale),
            ((bbox[1] * 4 - pad_y) / scale),
            ((bbox[2] * 4 - pad_x) / scale),
            ((bbox[3] * 4 - pad_y) / scale)]


def choose_roi(focus: list[dict], width=1280, height=720) -> tuple[int, int, int, int]:
    rects = [bbox_to_source(f['selected_bbox_proto'], width, height) for f in focus]
    lp, rp, tp, bp = DEFAULT_CONTEXT_PADDING
    left = max(0, int(math.floor(min(r[0] for r in rects) - lp)))
    top = max(0, int(math.floor(min(r[1] for r in rects) - tp)))
    right = min(width, int(math.ceil(max(r[2] for r in rects) + rp)))
    bottom = min(height, int(math.ceil(max(r[3] for r in rects) + bp)))
    if not (160 <= right - left <= 700 and 200 <= bottom - top <= 700):
        raise ValueError('Calculated source ROI outside expected safety bounds')
    return left, top, right - left, bottom - top


def probe(path: Path, ffprobe: str) -> dict:
    obj = json.loads(run([ffprobe, '-v', 'error', '-select_streams', 'v:0',
        '-show_streams', '-show_frames',
        '-show_entries', 'stream=width,height,avg_frame_rate:frame=best_effort_timestamp_time',
        '-of', 'json', str(path)]))
    streams = obj.get('streams', [])
    if len(streams) != 1 or not obj.get('frames'):
        raise ValueError('Video contains no decodable primary video frames')
    stream = streams[0]
    try:
        pts = [float(it['best_effort_timestamp_time']) for it in obj['frames']]
        fps = float(Fraction(stream['avg_frame_rate']))
        dims = (int(stream['width']), int(stream['height']))
    except (TypeError, KeyError, ValueError, ZeroDivisionError) as exc:
        raise ValueError('Video lacks reliable frame timestamps or format') from exc
    if (not math.isfinite(fps) or not (15 <= fps <= 120) or
        any(not math.isfinite(t) for t in pts) or
        any(y <= x for x, y in zip(pts, pts[1:]))):
        raise ValueError('Video frame rate or PTS are invalid/non-monotonic')
    return {'fps': fps, 'dimensions': dims, 'duration_s': pts[-1] - pts[0],
            'pts': [t - pts[0] for t in pts], 'num_frames': len(pts),
            'first_frame_original_pts_s': pts[0]}


def nearest_frame(pts_list: list[float], target: float, fps: float) -> tuple[int, float, float]:
    # bisect picks the nearest presentation-order frame. We extract by n, not -ss.
    from bisect import bisect_left
    pos = bisect_left(pts_list, target)
    choices = [i for i in (pos - 1, pos) if 0 <= i < len(pts_list)]
    if not choices:
        raise ValueError('No candidate video frame')
    index = min(choices, key=lambda i: abs(pts_list[i] - target))
    delta = abs(pts_list[index] - target)
    if delta > max(0.018, 0.55 / fps):
        raise ValueError(f'No source frame near {target:.6f}s (nearest {pts_list[index]:.6f})')
    return index, pts_list[index], delta


def select_indices(probe_data: dict, focus: list[dict], *, offset: float) -> list[int]:
    if not math.isfinite(offset) or offset < 0:
        raise ValueError('--source-offset must be finite and nonnegative')
    times = [f['pts_us'] / 1_000_000 for f in focus]
    fps = probe_data['fps']
    # Six risk frames plus adjacent source frames with ±~0.1 sec context.
    # Matches the prior final-video 39.633..39.966s review window.
    start = WINDOW_START_US / 1_000_000
    end = WINDOW_END_US / 1_000_000
    if end + offset > probe_data['duration_s'] + 1 / fps:
        raise ValueError('Source video does not extend to the overlap review window')
    frames = [i for i, t in enumerate(probe_data['pts'])
              if (start + offset - 1e-5) <= t <= (end + offset + 1e-5)]
    for t in times:
        idx, _, _ = nearest_frame(probe_data['pts'], t + offset, fps)
        frames.append(idx)
    indices = sorted(set(frames))
    # At 60/120fps avoid leaking a longer clip; retain each exact target and
    # a small before/after sample and fill only up to the fixed 18-frame cap.
    if len(indices) > MAX_TOTAL_CAPTURE_FRAMES:
        required = set()
        for pts_us in [WINDOW_START_US, WINDOW_END_US] + [f['pts_us'] for f in focus]:
            idx, _, _ = nearest_frame(probe_data['pts'], pts_us / 1_000_000 + offset, fps)
            required.add(idx)
        for idx in indices:
            if len(required) >= MAX_TOTAL_CAPTURE_FRAMES:
                break
            if idx not in required:
                required.add(idx)
        indices = sorted(required)
    if not 6 <= len(indices) <= MAX_TOTAL_CAPTURE_FRAMES:
        raise ValueError('Unexpected number of candidate risk frames: ' + str(len(indices)))
    return indices


def make_filter(indices: list[int], crop: tuple[int, int, int, int] | None) -> str:
    if not indices or indices != sorted(set(indices)):
        raise ValueError('Frame indices must be sorted and unique')
    select = 'select=' + '+'.join(f'eq(n\\,{i})' for i in indices)
    if crop:
        x, y, w, h = crop
        select += f',crop={w}:{h}:{x}:{y}'
    return select


def ffmpeg_extract(video: Path, indices: list[int], dst: Path, ffmpeg: str,
                   crop: tuple[int,int,int,int] | None) -> list[Path]:
    dst.mkdir(parents=True, exist_ok=False)
    cmd = [ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
           '-i', str(video), '-map', '0:v:0', '-an', '-sn', '-dn',
           '-vf', make_filter(indices, crop), '-vsync', '0', '-start_number', '0',
           '-f', 'image2', str(dst / 'frame_%03d.png')]
    run(cmd)
    files = sorted(dst.glob('frame_*.png'))
    if len(files) != len(indices):
        raise ValueError(f'FFmpeg extracted {len(files)} frames, expected {len(indices)}; refusing misaligned evidence')
    if any(file.stat().st_size < 60 for file in files):
        raise ValueError('Empty or corrupt PNG output')
    return files


def generate_index(manifest: dict) -> str:
    blocks = []
    for item in manifest['frames']:
        imgs = ''.join('<figure><img src="'+html.escape(data['file'],quote=True)+'" loading="lazy">'
                       '<figcaption>'+html.escape(role)+' · #'+str(data['frame_index'])+' · '
                       +f"{data['source_timeline_s']:.6f}"+'s</figcaption></figure>'
                       for role,data in item['images'].items())
        blocks.append('<section><h2>PTS '+f"{item['target_pts_us']/1e6:.6f}"+' s</h2><div class="pics">'+imgs+'</div></section>')
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<title>Woah 最终隐私交叠复核材料</title><style>'
            'body{background:#181818;color:#eee;font:14px system-ui;margin:22px}'
            'section{border-top:1px solid #555;margin:24px 0;padding:14px 0}'
            '.pics{display:flex;flex-wrap:wrap;gap:16px}figure{margin:0;max-width:100%}'
            'img{max-width:min(94vw,850px);height:auto;border-radius:6px}'
            'figcaption{margin:8px;color:#bbb}</style><h1>Woah 自动采集：39.733s 交叠</h1>'
            '<p>原视频只保留局部裁剪，仍可能含可识别人物。'
            '本材料仅用于人工像素审查，未自动判断隐私通过。</p>'
            +''.join(blocks)+'</html>')


def prepare(source: Path, landscape: Path | None, portrait: Path | None,
            numeric: Path, output: Path, source_offset: float, ffmpeg: str, ffprobe: str) -> dict:
    if not source.is_file() or source.suffix.lower() not in {'.mp4','.mov','.m4v','.mkv'}:
        raise ValueError('Original --source must be a local video file, not a ZIP')
    if not (0 <= source_offset <= 24 * 3600) or not math.isfinite(source_offset):
        raise ValueError('--source-offset must be a nonnegative finite number')
    if output.suffix.lower() != '.zip':
        raise ValueError('--output must end with .zip')
    if output.exists():
        raise ValueError('Output ZIP exists; choose a new name to avoid stale evidence')
    for mode, path in [('landscape',landscape),('portrait',portrait)]:
        if path is not None and (not path.is_file() or path.suffix.lower() not in {'.mp4','.mov','.m4v'}):
            raise ValueError(mode + ' must be an existing full exported video')
    paths = [p.resolve() for p in (source, landscape, portrait) if p]
    if len(paths) != len(set(paths)):
        raise ValueError('Source and exported videos must be different files')
    root = Path(__file__).resolve().parent
    # No source crops should be produced within Woah's repository.
    try:
        git_root = Path(run(['git','-C',str(root),'rev-parse','--show-toplevel']).strip()).resolve()
    except ValueError:
        git_root = None
    if git_root and (output.resolve() == git_root or git_root in output.resolve().parents):
        raise ValueError('Write the sensitive review ZIP outside the Git repository')
    identity = read_numeric_report(numeric)
    original = probe(source, ffprobe)
    if original['dimensions'] != (1280,720):
        raise ValueError('Original source video must be the 1280x720 tracking input; got '+str(original['dimensions']))
    exports = {}
    for name, video, dims in [('landscape',landscape,(1280,720)),('portrait',portrait,(810,1440))]:
        if video:
            meta = probe(video, ffprobe)
            if meta['dimensions'] != dims:
                raise ValueError(name+' output dimensions mismatch: '+str(meta['dimensions']))
            if meta['num_frames'] != 1391 or abs(meta['fps']-30)>0.2:
                raise ValueError(name+' export is not the 1391-frame/30fps diagnostic job')
            exports[name] = (video,meta)
    focus = identity['focus']
    roi = choose_roi(focus)
    source_indices = select_indices(original,focus,offset=source_offset)
    timeline = []
    for f in focus:
        pts_s = f['pts_us']/1e6
        source_i,real_source_t,source_delta = nearest_frame(
            original['pts'],pts_s+source_offset,original['fps'])
        images = {'source': {'frame_index':source_i,
                             'source_timeline_s':round(real_source_t-source_offset,6),
                             'alignment_delta_s':round(source_delta,6)}}
        for name,(file,meta) in exports.items():
            idx,real_t,delta = nearest_frame(meta['pts'],pts_s,meta['fps'])
            images[name]={'frame_index':idx,'source_timeline_s':round(real_t,6),
                          'alignment_delta_s':round(delta,6)}
        timeline.append({'target_pts_us':f['pts_us'], 'selected_bbox_proto':f['selected_bbox_proto'],
                         'selected_bbox_source_px': [round(x,3) for x in bbox_to_source(f['selected_bbox_proto'])],
                         'images':images})
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='woah_private_auto_capture_') as folder:
        root_tmp=Path(folder)
        all_inputs={'source':(source,original,source_indices,roi)}
        for name,(file,meta) in exports.items():
            indices=select_indices(meta,focus,offset=0.)
            # Portrait uses the full protected final frame to avoid guessing the
            # time-varying crop rect. Landscape is source-coordinate comparable.
            all_inputs[name]=(file,meta,indices,roi if name=='landscape' else None)
        lookups={}
        for mode,(file,meta,indices,crop) in all_inputs.items():
            images=ffmpeg_extract(file,indices,root_tmp/mode,ffmpeg,crop)
            lookups[mode]={index: path for index,path in zip(indices,images)}
        for item in timeline:
            for mode,info in item['images'].items():
                file=lookups[mode][info['frame_index']]
                info['file']=file.relative_to(root_tmp).as_posix()
        provenance={mode:{'sha256':sha256(file),'dimensions':list(meta['dimensions']),
                          'fps':round(meta['fps'],6),'frames':meta['num_frames']}
                    for mode,(file,meta,_indices,_crop) in all_inputs.items()}
        manifest={
            'schema':1,'purpose':'woah_private_source_vs_final_video_overlap_capture',
            'privacy_acceptance':'NOT_REVIEWED',
            'pixel_ownership_proof':'NOT_INFERRED_FROM_DIAGNOSTICS',
            'warning':'INCLUDES_UNPROTECTED_SOURCE_VIDEO_CROPS_WITH_POTENTIALLY_IDENTIFIABLE_PEOPLE',
            'capture_scope':'ONLY_39_633_TO_39_967_SOURCE_CROPS_AND_OPTIONAL_PROTECTED_FINAL_FRAMES',
            'audio_included':False,'raw_mask_included':False,'full_original_video_included':False,
            'numeric_report_sha256':identity['numeric_report_sha256'],
            'numeric_report_commit_operator_asserted':identity['numeric_report_commit'],
            'source_timeline_offset_seconds_operator_asserted':source_offset,
            'source_roi_pixels_xywh':list(roi),
            'alignment_status':'NEAREST_PRESENTATION_PTS_ONLY_SCENE_IDENTITY_NOT_CRYPTOGRAPHICALLY_PROVEN',
            'inputs':provenance,
            'frames':timeline,
            'review_focus_pts_us':[f['pts_us'] for f in focus],
        }
        (root_tmp/'manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False,sort_keys=True)+'\n',encoding='utf-8')
        (root_tmp/'index.html').write_text(generate_index(manifest),encoding='utf-8')
        (root_tmp/'README.txt').write_text(
            'Woah privacy-overlap evidence (LOCAL automatic capture).\n'
            'This ZIP contains small crops from an UNPROTECTED original video.\n'
            'People in these PNGs may be identifiable. Share only when permitted.\n'
            'No audio, full original video, or raw masks are included.\n'
            'Video source/export matching and human pixel ownership remain unproven.\n'
            'Original files were not modified.\n',encoding='utf-8')
        pending=output.with_suffix(output.suffix+'.partial')
        try:
            with zipfile.ZipFile(pending,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
                for path in sorted(root_tmp.rglob('*')):
                    if path.is_file():
                        z.write(path,arcname=path.relative_to(root_tmp).as_posix())
            with zipfile.ZipFile(pending) as test:
                failed=test.testzip()
                if failed is not None:
                    raise ValueError('ZIP CRC check failed: '+failed)
            os.replace(pending,output)
        finally:
            if pending.exists():
                pending.unlink()
        print('WOAH_AUTO_PRIVACY_CAPTURE=READY_NOT_ACCEPTED')
        print('Archive:',output)
        print('Source crop:',list(roi),'samples',len(source_indices))
        print('Modes:',','.join(all_inputs))
        print('Sensitive source crops: review sharing permissions before uploading.')
        return manifest


def main(argv: list[str] | None = None) -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True,help='1280x720 original UNPROTECTED MP4')
    parser.add_argument('--landscape',type=Path,help='Optional 1280x720 final protected Woah export')
    parser.add_argument('--portrait',type=Path,help='Optional 810x1440 final protected Woah export')
    parser.add_argument('--numeric-report',type=Path,required=True,help='Previously supplied six-frame numeric JSON')
    parser.add_argument('--source-offset',type=float,default=0.,help='Source trim offset in seconds (default 0)')
    parser.add_argument('--output',type=Path,required=True,help='New ZIP outside the Git repository')
    args=parser.parse_args(argv)
    try:
        if not args.numeric_report.is_file():
            raise ValueError('Numeric report does not exist')
        prepare(args.source,args.landscape,args.portrait,args.numeric_report,args.output,
                args.source_offset,require_executable('ffmpeg'),require_executable('ffprobe'))
    except (OSError,ValueError,KeyError,TypeError,json.JSONDecodeError,zipfile.BadZipFile) as e:
        print('WOAH_AUTO_PRIVACY_CAPTURE=FAIL: '+str(e),file=sys.stderr)
        return 1
    return 0


if __name__=='__main__':
    raise SystemExit(main())
