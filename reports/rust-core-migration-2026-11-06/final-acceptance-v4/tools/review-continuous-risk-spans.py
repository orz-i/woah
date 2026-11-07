"""LOCAL ONLY source/output review of continuous known-risk spans; no auto acceptance."""
from pathlib import Path
import argparse,cv2,json,hashlib,numpy as np
p=argparse.ArgumentParser(description=__doc__)
for name in ['run','sources','targets','destination']:p.add_argument(name,type=Path)
p.add_argument('--expected-source',required=True);a=p.parse_args()
def read(p):return json.loads(p.read_bytes())
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for block in iter(lambda:f.read(1048576),b''):h.update(block)
 return h.hexdigest()
api=read(a.run/'results.json');assert api['compiled_source_commit']==a.expected_source
spec=read(a.targets);source=a.sources/spec['source'];assert sha(source)==spec['source_sha256']
def box_at(span,n):
 anchors=sorted(span['anchors'],key=lambda c:c['frame']);assert len(anchors)>=2
 if n<=anchors[0]['frame']:lo,hi=anchors[:2]
 elif n>=anchors[-1]['frame']:lo,hi=anchors[-2:]
 else:lo,hi=next((l,r) for l,r in zip(anchors,anchors[1:]) if l['frame']<=n<=r['frame'])
 t=(n-lo['frame'])/(hi['frame']-lo['frame']);l,top,r,bottom=[u+(v-u)*t for u,v in zip(lo['bbox'],hi['bbox'])]
 w,h=r-l,bottom-top;assert w>1 and h>1;cx,cy=(l+r)/2,(top+bottom)/2
 return [max(0,int(cx-w*1.5)),max(0,int(cy-h*1.25)),min(spec['width'],int(cx+w*1.5)),min(spec['height'],int(cy+h*1.25))]
items=[{'span':s['name'],'source_frame':n,'source_pts_us':n*spec['frame_period_us'],'crop':box_at(s,n)} for s in spec['spans'] for n in range(s['first'],s['last']+1)];assert len(items)==53
by_frame={i['source_frame']:i for i in items};last=max(by_frame);source_tiles={}
def crop(im,box):
 x1,y1,x2,y2=box;assert x2>x1 and y2>y1;assert im.shape[:2]==(spec['height'],spec['width'])
 return cv2.resize(im[y1:y2,x1:x2],(240,210),interpolation=cv2.INTER_LINEAR)
cap=cv2.VideoCapture(str(source))
for n in range(last+1):
 ok,im=cap.read();assert ok
 if n in by_frame:source_tiles[n]=crop(im,by_frame[n]['crop'])
cap.release();a.destination.mkdir(parents=True,exist_ok=True);closed=[]
for row in api['cases']:
 if row['input']!=spec['source'] or not row['case'].startswith('group_4k_') or row.get('result')!='output_created' or row['expected_frames']!=581 or row['start_ms']!=0:continue
 assert row['pid']==api['process_id'] and row['state']=='completed' and row['current_frame']==581 and (row['output_width'],row['output_height'])==(spec['width'],spec['height'])
 movie=a.run/'outputs'/(row['case']+'.mp4')
 if not movie.exists() or movie.stat().st_size!=row['file_bytes']:continue
 dest=a.destination/row['case'];mh=sha(movie)
 if dest.exists():
  old=read(dest/'index.json');assert old['movie_sha256']==mh and old['source_sha256']==spec['source_sha256'];closed.append(row['case']);continue
 dest.mkdir();cap=cv2.VideoCapture(str(movie));out_tiles={}
 for n in range(last+1):
  ok,im=cap.read();assert ok
  if n in by_frame:out_tiles[n]=crop(im,by_frame[n]['crop'])
 cap.release();boards=[]
 for i,item in enumerate(items):
  n=item['source_frame'];top=np.zeros((24,480,3),np.uint8);cv2.putText(top,f'{item["span"]} source{n} source / actual output',(3,16),cv2.FONT_HERSHEY_SIMPLEX,.38,(0,255,255),1);boards.append(np.vstack([top,np.hstack([source_tiles[n],out_tiles[n]])]))
 pages=[]
 for start in range(0,len(boards),8):
  selected=boards[start:start+8]
  while len(selected)<8:selected.append(np.zeros_like(boards[0]))
  board=np.vstack([np.hstack(selected[k:k+2]) for k in range(0,8,2)]);f=dest/f'risk-{start//8:02d}.jpg';assert cv2.imwrite(str(f),board,[cv2.IMWRITE_JPEG_QUALITY,88]);pages.append({'file':f.name,'sha256':sha(f),'first_index':start,'last_index':min(start+7,len(items)-1)})
 v={'scope':'LOCAL ONLY source/context plus actual encoded output at every frame in both known-risk spans. Requires visual inspection; no acceptance inferred. Partial cohort is allowed only for closed immutable movies.','run_id':a.run.name,'compiled_source_commit':api['compiled_source_commit'],'case':row['case'],'source_sha256':spec['source_sha256'],'movie_sha256':mh,'target_spec_sha256':sha(a.targets),'items':items,'pages':pages}
 (dest/'index.json').write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8');closed.append(row['case']);print('RISK_SPAN_CASE_READY',row['case'],len(items),len(pages),flush=True)
print('RISK_SPAN_CLOSED_CASES',len(closed),closed,flush=True)
