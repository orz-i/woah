#!/usr/bin/env python3
"""Compare source-only Vision face cores with actual solid export pixels."""
import argparse, json, pathlib, hashlib
import cv2
import numpy as np
import sys
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.privacy.lifecycle_evidence import retry_case
from tools.privacy.review_frames import sequential_selected
from tools.privacy.source_time import bind_microsecond_frame
REPORT=ROOT/'reports/privacy-ios-22-and-4k-regression-2026-11-05'
SOURCES=ROOT/'reports/privacy-ios-regression-2026-11-04/artifacts/sources'

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1048576),b''):digest.update(chunk)
    return digest.hexdigest()

def red(frame):
    b,g,r=cv2.split(frame)
    return (r>180)&(g<70)&(b<70)&(r.astype(np.int16)-g.astype(np.int16)>100)

def read_jsonl(p):
    return [json.loads(line) for line in p.read_text().splitlines() if line.strip()]

def main(argv=None):
    global REPORT,SOURCES
    parser=argparse.ArgumentParser();parser.add_argument('run_id')
    parser.add_argument('--report-root',type=pathlib.Path,default=REPORT)
    parser.add_argument('--sources',type=pathlib.Path,default=SOURCES)
    parser.add_argument('--source-faces',type=pathlib.Path)
    parser.add_argument('--require-face-cache',action='store_true')
    args=parser.parse_args(argv);REPORT=args.report_root.resolve();SOURCES=args.sources.resolve()
    cache_root=args.source_faces or REPORT/'artifacts/source-faces'
    run=REPORT/'artifacts'/args.run_id
    data=json.loads((run/'results.json').read_text())
    additional=retry_case(run)
    if additional:data['cases']=data['cases']+[additional]
    meta=json.loads((run/'run-metadata.json').read_text())
    trace_names=list(dict.fromkeys(meta.get('crop_traces',[])+list(meta.get('additional_crop_traces',{}).values())))
    traces=[read_jsonl(run/'crop-traces'/p) for p in trace_names]
    trace_by_output={t[0]['output_basename']:(t,name) for t,name in zip(traces,trace_names) if t[0].get('output_basename')}
    rows=[];summary=REPORT/'face-audits'/args.run_id;summary.mkdir(parents=True,exist_ok=True)
    previous={}
    for prior in [REPORT/'face-audits'/(args.run_id+'_prefix')/'results.json',summary/'results.json']:
        if prior.exists():previous.update({r['case']:r for r in json.loads(prior.read_text())['cases']})
    revision=sha(pathlib.Path(__file__))
    for row_index,row in enumerate(data['cases']):
        if row.get('select')=='first' or row.get('result')!='output_created':continue
        planned_geometry = row.get('fill')!='solid'
        source_name=row.get('input','solo-hd-7570258.mp4')
        cache=cache_root/(pathlib.Path(source_name).stem+'.jsonl')
        if not cache.exists():
            if args.require_face_cache:raise ValueError('Missing independent source face scan: '+source_name)
            continue
        source_faces=read_jsonl(cache)
        if source_faces[-1].get('kind')!='finished':raise ValueError('Source face scan not complete')
        if source_faces[0]['sha256']!=meta['sources'][source_name]['sha256']:raise ValueError('Source face scan SHA mismatch')
        frames={round(f['pts']*1000000):f for f in source_faces if f['kind']=='frame'}
        if trace_by_output:
            trace_pair=trace_by_output.get(row['case']+'.mp4')
            if trace_pair is None:raise ValueError('Missing explicitly bound crop trace')
            trace,trace_name=trace_pair
        else:
            if row_index>=len(traces):raise ValueError('Missing crop trace')
            trace=traces[row_index];trace_name=meta['crop_traces'][row_index]
        header=trace[0];crops=trace[1:]
        if header['source_basename']!=source_name or header['output_width']!=row['output_width'] or header['output_height']!=row['output_height']:
            raise ValueError('Trace/output binding mismatch')
        output=run/'outputs'/(row['case']+'.mp4')
        binding=hashlib.sha256(json.dumps({'row':row,'binary_sha256':meta['binary_sha256'],
          'movie_sha256':sha(output),'trace_sha256':sha(run/'crop-traces'/trace_name),
          'source_faces_sha256':sha(cache),'auditor_revision':revision},sort_keys=True).encode()).hexdigest()
        prior=previous.get(row['case'])
        if prior and prior.get('input_binding_sha256')==binding:
            rows.append(prior)
            (summary/'results.json').write_text(json.dumps({'run_id':args.run_id,'cases':rows},indent=2)+'\n')
            print(row['case'],prior['status'],'reused_exact_inputs',flush=True)
            if prior.get('follow_hero_id') is not None and prior['uncovered_face_cores']:
                make_review(row,prior['uncovered_face_cores'],run,summary)
            continue
        cap=cv2.VideoCapture(str(output));n=0;tested=0;outside=0;exposed=[];quantized_pts=[]
        while True:
            ok,image=cap.read()
            if not ok:break
            if n>=len(crops):raise ValueError('Missing per-frame source crop')
            c=crops[n];key=c['t_us'];source,scan_key=bind_microsecond_frame(frames,key)
            if scan_key!=key:quantized_pts.append({'frame_index':n,'native_pts_us':key,'scan_pts_us':scan_key})
            if c['frame_index']!=n:raise ValueError('Crop frame index mismatch')
            height,width=image.shape[:2]
            mask=None if planned_geometry else red(image)
            sw,sh=source['width'],source['height'];cx1,cy1,cx2,cy2=c['source_crop_normalized']
            crop_left,crop_top=cx1*sw,cy1*sh;crop_width=(cx2-cx1)*sw;crop_height=(cy2-cy1)*sh
            for face in source['faces']:
                x1,y1,x2,y2=face['bbox'];fw,fh=x2-x1,y2-y1
                # The central 70% remains inside the face ellipse and away
                # from codec/antialias fringes. This is a feature-core check.
                px1=(x1+fw*.15-crop_left)/crop_width*width;px2=(x2-fw*.15-crop_left)/crop_width*width
                py1=(y1+fh*.15-crop_top)/crop_height*height;py2=(y2-fh*.15-crop_top)/crop_height*height
                a=max(0,min(width,int(np.ceil(px1))));b=max(a,min(width,int(np.floor(px2))))
                d=max(0,min(height,int(np.ceil(py1))));e=max(d,min(height,int(np.floor(py2))))
                if (b-a)*(e-d)<9:outside+=1;continue
                tested+=1
                if planned_geometry:
                    xs=np.linspace(a+.5,b-.5,min(11,b-a));ys=np.linspace(d+.5,e-.5,min(11,e-d))
                    grid_x,grid_y=np.meshgrid(xs,ys)
                    sx=grid_x/width*crop_width+crop_left;sy=grid_y/height*crop_height+crop_top
                    covered=np.zeros_like(sx,dtype=bool)
                    for region in c.get('face_regions',[]):
                        x,y=region['center'];rx,ry=region['radii']
                        covered |= ((sx-x)/max(1,rx))**2+((sy-y)/max(1,ry))**2<=1
                    fraction=float(covered.mean())
                else:
                    fraction=float(mask[d:e,a:b].mean())
                if fraction<.9:
                    exposed.append({'output_frame':n,'source_frame':source['frame'],'source_pts_us':key,
                      'source_bbox':face['bbox'],'output_core':[a,d,b,e],'coverage_fraction':fraction})
            n+=1
        cap.release()
        if n!=len(crops):raise ValueError(f'Decode/crop count mismatch {n} != {len(crops)}')
        hero=row.get('hero_id')
        result={'case':row['case'],'input_binding_sha256':binding,'decoded_frames':n,'tested_visible_face_cores':tested,
                'outside_or_tiny_cores':outside,'uncovered_face_cores':exposed,'follow_hero_id':hero,
                'microsecond_quantization_matches':quantized_pts,
                'status':'needs_hero_visual_review' if hero is not None else ('passed_all_detected_face_cores' if not exposed else 'failed_face_cores'),
                'coverage_measure':'recorded_source_ellipses' if planned_geometry else 'decoded_opaque_red_pixels',
                'scope':'Source-only macOS Vision observations; non-solid modes check recorded face protection geometry separately from visual effect review; undetected faces require separate visual/known-point checks.'}
        rows.append(result)
        (summary/'results.json').write_text(json.dumps({'run_id':args.run_id,'cases':rows},indent=2)+'\n')
        print(row['case'],result['status'],tested,'cores',len(exposed),'uncovered',flush=True)
        if hero is not None and exposed:
            make_review(row,exposed,run,summary)
    return 1 if any(r['status']=='failed_face_cores' for r in rows) else 0

def make_review(row,exposed,run,summary):
    source_name=row['input'];source=SOURCES/source_name
    # Random seeking is not reliable in the simulator's OpenCV/FFmpeg build.
    # Decode from frame zero and crop immediately; retain only review tiles.
    picked=exposed
    clips={}
    movie=run/'outputs'/(row['case']+'.mp4')
    for path,side,key in [(source,0,'source_frame'),(movie,1,'output_frame')]:
        indices={}
        for i,item in enumerate(picked):indices.setdefault(item[key],[]).append((i,item))
        cap=cv2.VideoCapture(str(path))
        try:
            for n,frame in sequential_selected(cap,indices):
                for i,item in indices[n]:
                    if side==0:
                        x1,y1,x2,y2=item['source_bbox'];fw,fh=x2-x1,y2-y1
                        a=max(0,int(x1-fw*.5));b=min(frame.shape[1],int(x2+fw*.5))
                        d=max(0,int(y1-fh*.35));e=min(frame.shape[0],int(y2+fh*2.5))
                    else:
                        x1,y1,x2,y2=item['output_core'];pad=max(x2-x1,y2-y1)
                        a=max(0,x1-pad);b=min(frame.shape[1],x2+pad)
                        d=max(0,y1-pad);e=min(frame.shape[0],y2+pad*3)
                    if b<=a or e<=d:raise ValueError('Review crop is empty')
                    clips[i,side]=cv2.resize(frame[d:e,a:b],(120,180))
        finally:cap.release()
    tiles=[]
    review=run/'face-review';review.mkdir(exist_ok=True)
    for i,item in enumerate(picked):
        tile=np.concatenate([clips[i,0],clips[i,1]],axis=1)
        label=np.full((25,240,3),255,np.uint8);cv2.putText(label,f"f{item['source_frame']} cov={item['coverage_fraction']:.2f}",(4,18),cv2.FONT_HERSHEY_SIMPLEX,.45,(0,0,0),1)
        tiles.append(np.concatenate([label,tile],axis=0))
    for start in range(0,len(tiles),30):
        batch=tiles[start:start+30];canvas=np.full((205*((len(batch)+5)//6),240*6,3),235,np.uint8)
        for i,tile in enumerate(batch):canvas[(i//6)*205:(i//6+1)*205,(i%6)*240:(i%6+1)*240]=tile
        cv2.imwrite(str(review/f"{row['case']}-{start//30}.jpg"),canvas)
    (summary/(row['case']+'-review-samples.json')).write_text(json.dumps(picked,indent=2)+'\n')
    binding={'decoding':'sequential_from_frame_zero','candidate_count':len(picked),
      'movie_sha256':sha(movie),'source_sha256':sha(source),
      'candidate_sha256':hashlib.sha256(json.dumps(picked,sort_keys=True).encode()).hexdigest(),
      'boards':{p.name:sha(p) for p in sorted(review.glob(row['case']+'-*.jpg'))}}
    (summary/(row['case']+'-review-binding.json')).write_text(json.dumps(binding,indent=2)+'\n')


if __name__=='__main__':raise SystemExit(main())
