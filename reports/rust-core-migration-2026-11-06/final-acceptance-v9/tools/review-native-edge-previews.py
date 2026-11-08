"""Private local enlargement of two actual source/protected native preview pairs."""
from pathlib import Path
import argparse,cv2,json,hashlib,numpy as np
p=argparse.ArgumentParser()
for n in ['run','targets','destination']:p.add_argument(n,type=Path)
p.add_argument('--expected-source',required=True)
a=p.parse_args()
def read(p):return json.loads(p.read_bytes())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
api=read(a.run/'results.json')
assert api['status']=='finished' and api['compiled_source_commit']==a.expected_source
row=next(r for r in api['cases'] if r['case']=='group_4k_face_solid')
assert row['face_only'] and row['fill']=='solid' and row['hero_id'] is None
assert set(row['selected_ids'])==set(row['detected_ids']) and row['input']=='group-mirror-8956295.mp4'
spec=read(a.targets)
anchors={v['frame']:v['bbox'] for s in spec['spans'] if s['name']=='clipped_top' for v in s['anchors']}
a.destination.mkdir(parents=True,exist_ok=False)
items=[]
for ms in [7400,8120]:
    frame=ms*1000//spec['frame_period_us'];box=anchors[frame]
    q=next(v for v in row['previews'] if v['requested_ms']==ms)
    assert q['dto_timestamp_ms']==ms
    control=a.run/'previews'/q['control_file'];protected=a.run/'previews'/q['file']
    assert control.stat().st_size==q['control_bytes'] and protected.stat().st_size==q['bytes']
    raw=cv2.imread(str(control));out=cv2.imread(str(protected));assert raw is not None and out is not None and raw.shape==out.shape
    h,w=raw.shape[:2];assert (w,h)==(q['width'],q['height']) and abs(w/h-spec['width']/spec['height'])<.01
    l,t,r,b=box;fw,fh=r-l,b-t
    source_crop=[max(0,int(l-fw)),max(0,int(t-fh)),min(spec['width'],int(r+fw)),min(spec['height'],int(b+fh))]
    l,t,r,b=source_crop;x1,y1,x2,y2=int(l*w/spec['width']),int(t*h/spec['height']),int(np.ceil(r*w/spec['width'])),int(np.ceil(b*h/spec['height']))
    assert x2>x1 and y2>y1
    tile=lambda im:cv2.resize(im[y1:y2,x1:x2],(480,420),interpolation=cv2.INTER_NEAREST)
    title=np.zeros((35,960,3),np.uint8);cv2.putText(title,f'actual native source / protected, {ms}ms, source{frame}',(5,24),cv2.FONT_HERSHEY_SIMPLEX,.6,(0,255,255),1)
    image=np.vstack([title,np.hstack([tile(raw),tile(out)])])
    f=a.destination/f'preview-{ms}.jpg';assert cv2.imwrite(str(f),image,[cv2.IMWRITE_JPEG_QUALITY,92])
    items.append({'requested_ms':ms,'source_frame':frame,'control_file':q['control_file'],'protected_file':q['file'],'control_sha256':sha(control),'protected_sha256':sha(protected),'source_crop':source_crop,'preview_crop':[x1,y1,x2,y2],'preview_dimensions':[w,h],'page':f.name,'page_sha256':sha(f)})
index={'scope':'LOCAL ONLY. Actual native PNG pairs enlarged using independently obtained source-face bounds scaled to preview dimensions. Both source crops and protected features require actual visual inspection.','run_id':a.run.name,'case':row['case'],'compiled_source_commit':api['compiled_source_commit'],'results_sha256':sha(a.run/'results.json'),'target_spec_sha256':sha(a.targets),'items':items}
(a.destination/'index.json').write_text(json.dumps(index,indent=2)+'\n')
print('ENLARGED_NATIVE_PAIRS_REQUIRE_ACTUAL_REVIEW',len(items))
