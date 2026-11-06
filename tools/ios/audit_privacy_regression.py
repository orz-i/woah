#!/usr/bin/env python3
"""Independent decode, timestamps, audio, original dimensions and privacy probes."""
import argparse, hashlib, json, math, pathlib, struct, subprocess
from fractions import Fraction
import cv2
import numpy as np
from audio_regression_check import compare_audio_samples

ROOT=pathlib.Path(__file__).resolve().parents[2]
REPORT=ROOT/'reports/privacy-ios-22-and-4k-regression-2026-11-05'
SOURCES=ROOT/'reports/privacy-ios-regression-2026-11-04/artifacts/sources'

def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1048576),b''):h.update(chunk)
    return h.hexdigest()

def run(*args):
    p=subprocess.run(args,text=True,capture_output=True,check=True,timeout=240)
    if p.stderr.strip():raise ValueError(p.stderr[-2000:])
    return p.stdout

def probe(path):
    return json.loads(run('ffprobe','-v','error','-show_streams','-show_format','-of','json',str(path)))

def times(path,packet=False):
    kind='packet' if packet else 'frame'
    text=run('ffprobe','-v','error','-select_streams','v:0','-show_entries',
             kind+('=pts_time' if packet else '=best_effort_timestamp_time'),
             '-of','csv=p=0',str(path))
    result=[]
    for line in text.splitlines():
        try:result.append(float(line.split(',')[0]))
        except ValueError:pass
    return sorted(result) if packet else result

def faststart(path):
    boxes=[];offset=0;end=path.stat().st_size
    with path.open('rb') as f:
        while offset+8<=end:
            f.seek(offset);header=f.read(16);n=struct.unpack('>I',header[:4])[0]
            if n==1:n=struct.unpack('>Q',header[8:16])[0]
            if n==0:n=end-offset
            if n<8 or offset+n>end:raise ValueError('Invalid MP4 atom')
            boxes.append(header[4:8].decode('ascii',errors='replace'));offset+=n
    return {'atoms':boxes,'moov_before_mdat':boxes.index('moov')<boxes.index('mdat')}

@__import__('functools').lru_cache(maxsize=8)
def source_pcm(path,start,duration):
    return decoded_pcm(path,start,duration)

def decoded_pcm(path,start=0,duration=None):
    args=['ffmpeg','-hide_banner','-nostdin','-v','error','-xerror','-i',str(path)]
    if start:args+=['-ss',str(start)]
    if duration is not None:args+=['-t',str(duration)]
    args+=['-map','0:a:0','-ac','1','-ar','16000','-f','f32le','-']
    p=subprocess.run(args,capture_output=True,check=True,timeout=120)
    if p.stderr.strip():raise ValueError(p.stderr.decode(errors='replace')[-2000:])
    return np.frombuffer(p.stdout,dtype='<f4')

def red(frame):
    b,g,r=cv2.split(frame)
    return (r>180)&(g<70)&(b<70)&(r.astype(np.int16)-g.astype(np.int16)>100)

def audit(row,path,dest,metadata):
    name=row['case'];source=SOURCES/row.get('input','solo-hd-7570258.mp4')
    result={'case':name,'status':'failed'}
    if row['result']=='cancelled_cleanly':
        result.update(status='passed_cancellation' if not path.exists() and not row.get('partial_files') else 'failed',
                      cancelled_at_frame=row['before_cancel']['current_frame'])
        return result
    if row['result']!='output_created' or not path.exists():
        result['native_failure']=row
        return result
    try:
        info=probe(path);original=probe(source)
        video=next(s for s in info['streams'] if s['codec_type']=='video')
        audio=[s for s in info['streams'] if s['codec_type']=='audio']
        source_audio=[s for s in original['streams'] if s['codec_type']=='audio']
        width,height=row['output_width'],row['output_height']
        if (video['width'],video['height'])!=(width,height):raise ValueError('Output size changed')
        start=row['start_ms']/1000;end=row['end_ms']/1000
        source_pts=times(source,True)
        expected=[p-start for p in source_pts if start-0.00001<=p<end-0.00001]
        # Read muxed presentation timestamps without a second full decode.
        # The OpenCV pass below independently checks each decoded frame PTS.
        pts=times(path,True)
        if len(pts)!=len(expected) or not pts:raise ValueError(f'Frame count {len(pts)} != {len(expected)}')
        if any(b<=a for a,b in zip(pts,pts[1:])):raise ValueError('Non-increasing PTS')
        max_delta=max(abs(a-b) for a,b in zip(pts,expected))
        if max_delta>0.002:raise ValueError(f'Source/output PTS drift {max_delta}')
        if bool(audio)!=bool(source_audio):raise ValueError('Audio presence mismatch')
        if audio and abs(float(audio[0]['duration'])-(expected[-1]+1/float(Fraction(video['r_frame_rate']))))>0.1:
            raise ValueError('Audio duration mismatch')
        audio_waveform=None
        if audio:
            audio_waveform=compare_audio_samples(source_pcm(source,start,end-start),decoded_pcm(path))
            audio_waveform['alignment_observable']=audio_waveform['reference_rms']>=1e-7
            if audio_waveform['correlation']<.95 or abs(audio_waveform['lag_ms'])>1000/float(Fraction(video['r_frame_rate']))+1 or not .8<=audio_waveform['gain_ratio']<=1.25:
                raise ValueError('Audio waveform/alignment mismatch: '+str(audio_waveform))
        atoms=faststart(path)
        if not atoms['moov_before_mdat']:raise ValueError('MP4 is not fast-start')
        cap=cv2.VideoCapture(str(path));count=0;black=0;green=0;holes=[]
        solid=row['fill']=='solid';face_only=row['face_only']
        privacy_probes=[]
        group='group' in name and width==3840 and height==2160
        # Exact source points from the Android regression. Followed output
        # requires a separate crop mapping and is not compared to these points.
        points={145:(1372,682,'side_face',True),290:(893,1275,'mirror_face',True),411:(835,541,'body_omitted_face_411',True),580:(1402,1322,'foreground_leg',False)} if group else {}
        if 'solo' in name and width==2160 and height==4096:
            points={0:(1224,1112,'face_start',True),330:(1328,1416,'face_middle',True),217:(1375,1338,'body_omitted_face_217',True),661:(1048,1152,'face_last',True),
              556:(1320,1180,'occluded_face_556',True),557:(1304,1180,'occluded_face_557',True),
              558:(1288,1180,'occluded_face_558',True),559:(1272,1176,'occluded_face_559',True),
              560:(1243,1173,'raised_arm_face_560',True),561:(1214,1177,'raised_arm_face_561',True),
              562:(1187,1179,'raised_arm_face_562',True),563:(1168,1186,'raised_arm_face_563',True)}
        select_samples={0,len(expected)//2,len(expected)-1}
        select_samples.update(i for i,p in enumerate(expected) if round((p+start)*25) in points)
        while True:
            ok,frame=cap.read()
            if not ok:break
            if count>=len(expected) or abs(cap.get(cv2.CAP_PROP_POS_MSEC)/1000-expected[count])>0.002:
                raise ValueError(f'Decoded frame PTS mismatch at {count}')
            if frame.max()<12:black+=1
            tiny=cv2.resize(frame,(96,54),interpolation=cv2.INTER_AREA)
            if np.mean((tiny[:,:,0]<30)&(tiny[:,:,1]>180)&(tiny[:,:,2]<30))>0.98:green+=1
            source_index=round((pts[count]+start)*25) if count<len(pts) else -1
            if solid and source_index in points:
                x,y,label,protected=points[source_index];patch=red(frame[y-12:y+12,x-12:x+12])
                total=int(patch.size);n=int(patch.sum())
                must_cover = protected or not face_only
                passed=(n>=math.ceil(total*.9)) if must_cover else (n<=math.floor(total*.1))
                privacy_probes.append({'source_frame':source_index,'label':label,'red_pixels':n,'pixels':total,'passed':passed})
            if solid and face_only:
                mask=(red(frame).astype(np.uint8)*255)
                contours,hierarchy=cv2.findContours(mask,cv2.RETR_CCOMP,cv2.CHAIN_APPROX_SIMPLE)
                if hierarchy is not None:
                    for contour,h in zip(contours,hierarchy[0]):
                        if h[3]<0 or cv2.contourArea(contour)<=5:continue
                        outer=np.zeros_like(mask);cv2.drawContours(outer,[contours[h[3]]],-1,255,cv2.FILLED)
                        distance=cv2.distanceTransform(outer,cv2.DIST_L2,5)
                        hole=np.zeros_like(mask);cv2.drawContours(hole,[contour],-1,255,cv2.FILLED)
                        exposed=int(np.count_nonzero((hole>0)&(mask==0)&(distance>3.0)))
                        if exposed>5:holes.append({'frame':count,'interior_pixels':exposed,'bbox':list(cv2.boundingRect(contour))})
            if count in select_samples:
                # Preview only; encoded media is checked at full resolution.
                thumb=cv2.resize(frame,(min(960,width),round(height*min(960,width)/width)))
                cv2.imwrite(str(dest/f'{name}-frame-{count}.jpg'),thumb)
            count+=1
        cap.release()
        if count!=len(pts) or black or green:raise ValueError(f'Decode/color failure count={count} black={black} green={green}')
        result.update(status='passed_media',sha256=sha(path),bytes=path.stat().st_size,
          dimensions=[width,height],decoded_frames=count,expected_frames=len(expected),
          max_pts_delta_seconds=max_delta,audio_streams=len(audio),audio_waveform=audio_waveform,faststart=atoms,
          black_frames=black,green_frames=green,privacy_probes=privacy_probes,
          topology_candidates=holes,topology_scope='FACE_ONLY solid interior holes; not semantic face coverage')
        if row.get('saveToGallery'):
            media=pathlib.Path('/Users/mini/Library/Developer/CoreSimulator/Devices')/metadata['udid']/'data/Media'
            since=__import__('datetime').datetime.fromisoformat(metadata['started_utc']).timestamp()-1
            matches=[]
            for candidate in media.rglob('*'):
                if not candidate.is_file() or candidate.suffix.lower() not in ['.mp4','.mov']: continue
                stat=candidate.stat()
                if stat.st_size==path.stat().st_size and stat.st_mtime>=since and sha(candidate)==result['sha256']:
                    matches.append(str(candidate.relative_to(media)))
            result['gallery_original_sha_matches']=matches
            if not row.get('gallery_save_completed') or not matches: result['status']='failed_gallery_original_check'
        if any(not p['passed'] for p in privacy_probes):result['status']='failed_privacy_probe'
        # Topology candidates require inspection, not an invented automatic pass.
        if holes and result['status']=='passed_media':result['status']='needs_topology_review'
    except Exception as error:result['error']=str(error)
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('run_id');args=p.parse_args()
    root=REPORT/'artifacts'/args.run_id
    data=json.loads((root/'results.json').read_text())
    metadata=json.loads((root/'run-metadata.json').read_text()) if (root/'run-metadata.json').exists() else {}
    out=REPORT/'media-audits'/args.run_id;out.mkdir(parents=True,exist_ok=True)
    samples=root/'review-frames';samples.mkdir(exist_ok=True)
    results=[]
    previous={r['case']:r for r in json.loads((out/'results.json').read_text())['cases']} if (out/'results.json').exists() else {}
    revision=sha(pathlib.Path(__file__))+';'+sha(pathlib.Path(__file__).with_name('audio_regression_check.py'))
    for row in data['cases']:
        movie=root/'outputs'/(row['case']+'.mp4')
        source=SOURCES/row.get('input','solo-hd-7570258.mp4')
        source_sha=sha(source)
        if source_sha!=metadata['sources'][source.name]['sha256']: raise ValueError('Source changed since run')
        binding=hashlib.sha256(json.dumps({'row':row,'source_sha256':source_sha,
          'binary_sha256':metadata['binary_sha256'],'auditor_revision':revision},sort_keys=True).encode()).hexdigest()
        old=previous.get(row['case'],{})
        if old.get('input_binding_sha256')==binding and old.get('status','').startswith('passed') and old.get('sha256')==(sha(movie) if movie.exists() else None):
            result=old
        else:
            result=audit(row,movie,samples,metadata)
            result['input_binding_sha256']=binding
        results.append(result)
        (out/'results.json').write_text(json.dumps({'run_id':args.run_id,'cases':results},indent=2)+'\n')
        print(row['case'],result['status'],result.get('decoded_frames'),result.get('error',''),flush=True)
    return 0 if all(r['status'].startswith('passed') for r in results) else 1

if __name__=='__main__':
    import fcntl,sys
    lock_dir=REPORT/'media-audits'/sys.argv[1]
    lock_dir.mkdir(parents=True,exist_ok=True)
    with (lock_dir/'.audit.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        raise SystemExit(main())
