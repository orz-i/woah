#!/usr/bin/env python3
"""Freeze existing comparison media and explicit 4K derivatives for both devices."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import bisect
import math
from fractions import Fraction

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def probe(path):
    raw = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams',
        '-show_frames', '-show_entries', 'stream=codec_type,width,height,duration,time_base:frame=media_type,best_effort_timestamp',
        '-of', 'json', str(path)]))
    video = next(s for s in raw['streams'] if s['codec_type'] == 'video')
    tick = Fraction(video['time_base'])
    pts = [round(int(f['best_effort_timestamp'])*tick*1000000)
           for f in raw['frames'] if f['media_type'] == 'video']
    ticks = [int(f['best_effort_timestamp']) for f in raw['frames'] if f['media_type'] == 'video']
    return {'durationUs': round(float(video['duration'])*1000000), 'width': video['width'],
            'height': video['height'], 'hasAudio': any(s['codec_type'] == 'audio' for s in raw['streams']),
            'ptsUs': pts, 'ptsTicks': ticks, 'timeBase': video['time_base']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--comparison-source', type=Path,
        default=ROOT/'docs/session/comparison-music-rate-20261026/fixtures')
    parser.add_argument('--privacy-source', type=Path,
        default=ROOT/'.qa-runtime/rust-migration/woah-rust-media')
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    sources = {}; recipes = {}

    def copy(key, path, coded=False):
        target = out/(key+'.mp4')
        if target.exists():
            assert digest(target) == digest(path), ('Existing source differs', target)
        else:
            shutil.copyfile(path, target)
        sources[key] = {'file': target.name, 'sha256': digest(target), 'bytes': target.stat().st_size,
                        'coded30': coded, **probe(target)}

    def derive(key, inputs, filters, extra, coded=False):
        target = out/(key+'.mp4')
        command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y']
        for path in inputs:
            command += ['-i', str(path)]
        command += filters+extra+['-movflags', '+faststart', str(target.with_suffix('.staging.mp4'))]
        recipes[key] = command[:-1]+[target.name]
        if not target.exists():
            subprocess.run(command, check=True)
            target.with_suffix('.staging.mp4').replace(target)
        sources[key] = {'file': target.name, 'sha256': digest(target), 'bytes': target.stat().st_size,
                        'coded30': coded, 'derived': True, **probe(target)}

    base = ROOT/'testdata/comparison/generated'
    copy('base_reference', base/'teacher.mp4', True)
    copy('base_target', base/'learner.mp4', True)
    for key, name in [('rate_reference','teacher'),('rate_target','learner'),('pitch_target','pitched'),
                      ('real_reference','real-reference'),('real_target','real-target')]:
        copy(key, args.comparison_source/(name+'.mp4'), key.startswith(('rate_', 'pitch_')))
    derive('silent_reference', [base/'teacher.mp4'], [], ['-map','0:v:0','-an','-c:v','copy'], True)
    derive('short_reference', [base/'teacher.mp4'], [], ['-t','0.5','-c','copy'], True)
    derive('reference_24fps', [base/'teacher.mp4'], [], ['-vf','fps=24','-c:v','libx264','-preset','veryfast','-crf','20','-c:a','copy'])
    derive('reference_60fps', [base/'teacher.mp4'], [], ['-vf','fps=60','-c:v','libx264','-preset','veryfast','-crf','20','-c:a','copy'])
    # Full-duration 4K encoded files, derived from exactly the existing dancers.
    # Input originals are HD. Do not describe these as native-camera 4K footage.
    for prefix, name, width, height in [('landscape4k','group-hd-8956295.mp4',3840,2160),
                                      ('portrait4k','solo-hd-7570258.mp4',2160,4096)]:
        original = args.privacy_source/name
        duration = probe(original)['durationUs']/1000000
        audio = args.comparison_source/'teacher.wav'
        derive(prefix+'_reference', [original,audio],
            ['-filter_complex', f'[0:v]scale={width}:{height}[v];[1:a]apad,atrim=duration={duration}[a]'],
            ['-map','[v]','-map','[a]','-c:v','libx264','-preset','veryfast','-crf','23','-pix_fmt','yuv420p',
             '-c:a','aac','-b:a','128k','-ar','44100','-ac','2','-t',str(duration)])
        derive(prefix+'_target', [out/(prefix+'_reference.mp4'),audio],
            ['-filter_complex', f'[1:a]adelay=700:all=1,apad,atrim=duration={duration}[a]'],
            ['-map','0:v:0','-map','[a]','-c:v','copy','-c:a','aac','-b:a','128k','-ar','48000','-ac','1','-t',str(duration)])
        assert sources[prefix+'_reference']['width'] == width and sources[prefix+'_reference']['height'] == height
    (out/'corrupt.mp4').write_bytes((args.privacy_source/'corrupt-video-1024bytes.mp4').read_bytes())
    alignments = [
        dict(id='positive_offset', reference='base_reference', target='base_target', scale=1, offset=3000000),
        dict(id='negative_offset', reference='base_target', target='base_reference', scale=1, offset=-3000000),
        dict(id='preserved_pitch', reference='rate_reference', target='rate_target', scale=.8, offset=1200000),
        dict(id='shifted_pitch', reference='rate_reference', target='pitch_target', scale=1/.75, offset=round(2000000-1000000/.75)),
        dict(id='real_music', reference='real_reference', target='real_target', scale=.8, offset=700000),
        dict(id='source_rates_4k', reference='landscape4k_reference', target='landscape4k_target', scale=1, offset=700000),
        dict(id='no_audio', reference='silent_reference', target='base_target', status='no_audio'),
        dict(id='too_short', reference='short_reference', target='short_reference', status='no_match'),
    ]
    sessions = [
        dict(id='base', reference='base_reference', target='base_target', scale=1, offset=3000000),
        dict(id='negative', reference='base_target', target='base_reference', scale=1, offset=-3000000),
        dict(id='vfr_affine', reference='rate_reference', target='rate_target', scale=.8, offset=1200000),
        dict(id='silent', reference='silent_reference', target='base_target', scale=1, offset=3000000),
        dict(id='24fps', reference='reference_24fps', target='base_target', scale=1, offset=3000000),
        dict(id='60fps', reference='reference_60fps', target='base_target', scale=1, offset=3000000),
        dict(id='landscape4k', reference='landscape4k_reference', target='landscape4k_target', scale=1, offset=700000),
        dict(id='portrait4k', reference='portrait4k_reference', target='portrait4k_target', scale=1, offset=700000),
    ]
    exports = [
        dict(id='base_full', session='base', width=1280, height=720),
        dict(id='base_trim', session='base', start=123456, end=4987654, width=640, height=360),
        dict(id='base_picture_slow_loop', session='base', start=1000000, end=3000000, picture=True, width=640, height=360),
        dict(id='base_repeat', session='base', start=1000000, end=3000000, width=640, height=360),
        dict(id='negative_full', session='negative', width=640, height=360),
        dict(id='vfr_boundary', session='vfr_affine', start=1000000, end=2000000, picture=True, width=320, height=240),
        dict(id='vfr_irregular_trim', session='vfr_affine', start=123456, end=4987654, width=640, height=360),
        dict(id='vfr_affine_tail', session='vfr_affine', tail=True, width=640, height=360),
        dict(id='silent_full', session='silent', width=640, height=360),
        dict(id='24fps_full', session='24fps', width=640, height=360),
        dict(id='60fps_full', session='60fps', width=640, height=360),
        dict(id='landscape4k_full', session='landscape4k', width=1920, height=1080),
        dict(id='landscape4k_tail', session='landscape4k', tail=True, width=1920, height=1080),
        dict(id='landscape4k_repeat', session='landscape4k', start=1000000, end=3000000, picture=True, width=1920, height=1080),
        dict(id='portrait4k_full', session='portrait4k', width=1080, height=1920, horizontal=False),
        dict(id='portrait4k_tail', session='portrait4k', tail=True, width=1080, height=1920, horizontal=False),
    ]
    # Independent preview pairs cover the requested continuous music time.
    # Export cadence is checked separately from exact encoded source PTS.
    for session in sessions:
        ref, target = sources[session['reference']], sources[session['target']]
        scale, offset = Fraction(str(session['scale'])), session['offset']
        start = max(ref['ptsUs'][0], math.ceil(Fraction(target['ptsUs'][0]-offset)/scale))
        end = min(ref['durationUs'], math.floor(Fraction(target['durationUs']-1-offset)/scale)+1,
                  math.floor(Fraction(target['durationUs']-offset)/scale))
        session['startUs'], session['durationUs'] = start, end-start
        session['probes'] = []
        for position in [280000,1250000,end-start-1]:
            if position >= end-start:
                continue
            i = bisect.bisect_right(ref['ptsUs'],start+position)-1
            frame = ref['ptsUs'][i]
            mapped = Fraction(start+position)*scale+offset
            j = bisect.bisect_right(target['ptsUs'],math.floor(mapped+Fraction(1,2)))-1
            session['probes'].append({'positionUs':position,'referenceFrameUs':frame,'targetFrameUs':target['ptsUs'][j],
                'nextReferenceFrameUs':ref['ptsUs'][min(i+1,len(ref['ptsUs'])-1)],
                'previousReferenceFrameUs':max(start,ref['ptsUs'][max(0,i-1)])})
    manifest = {'schema':'woah.comparison.core-corpus.v1', 'sources':sources, 'alignments':alignments,
                'sessions':sessions, 'exports':exports, 'recipes':recipes,
                'ffmpeg':subprocess.check_output(['ffmpeg','-version'],text=True).splitlines()[0],
                'fourKProvenance':'Encoded 4K derivatives of the existing HD group/solo materials; original video content is unchanged.'}
    staged=out/'manifest.staging.json'; staged.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    staged.replace(out/'manifest.json')
    print(json.dumps({'sources':len(sources), 'alignments':len(alignments),'sessions':len(sessions),'exports':len(exports),
                      'manifest_sha256':digest(out/'manifest.json')},indent=2))


if __name__ == '__main__':
    main()
