"""Render actual known partial-face crops for all11 full4K non-solid modes.
No semantic acceptance is inferred. Visually inspect every generated page.
"""
import argparse,cv2,hashlib,json,numpy as np
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
for name in ['run','sources','spec','destination']:p.add_argument(name,type=Path)
p.add_argument('--expected-source',required=True)
a=p.parse_args();a.destination.mkdir(exist_ok=False,parents=True)
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for block in iter(lambda:f.read(1048576),b''):h.update(block)
 return h.hexdigest()
spec=json.loads(a.spec.read_bytes());source=a.sources/spec['source'];assert sha(source)==spec['source_sha256']
points={int(k):v for k,v in spec['points'].items()};assert len(points)==8
api=json.loads((a.run/'results.json').read_bytes());assert api['status']=='finished' and api['compiled_source_commit']==a.expected_source
rows=[c for c in api['cases'] if c['input']==spec['source'] and c['expected_frames']==662 and c['start_ms']==0 and c['fill']!='solid']
assert len(rows)==11

def crop(im,p):
 x,y=p;radius=160;assert x-radius>=0 and y-radius>=0 and x+radius<=im.shape[1] and y+radius<=im.shape[0]
 return cv2.resize(im[y-radius:y+radius,x-radius:x+radius],(180,180))

src={};cap=cv2.VideoCapture(str(source))
for n in range(max(points)+1):
 ok,im=cap.read();assert ok
 if n in points:src[n]=crop(im,points[n])
cap.release();items=[];pages=[]
for row in rows:
 name=row['case'];assert row['state']=='completed' and row['pid']==api['process_id'] and row['current_frame']==662
 assert (row['output_width'],row['output_height'])==(spec['width'],spec['height'])
 movie=a.run/'outputs'/(name+'.mp4');mh=sha(movie);cap=cv2.VideoCapture(str(movie));tiles=[]
 for n in range(max(points)+1):
  ok,im=cap.read();assert ok
  if n not in points:continue
  pair=np.hstack([src[n],crop(im,points[n])]);label=np.zeros((24,360,3),np.uint8)
  cv2.putText(label,f'{len(items):02d} {name} frame{n}',(3,16),cv2.FONT_HERSHEY_SIMPLEX,.34,(0,255,255),1)
  tiles.append(np.vstack([label,pair]));items.append({'index':len(items),'case':name,'source_frame':n,'source_point':points[n],'source_pts_us':n*spec['frame_period_us'],'movie_sha256':mh})
 cap.release();assert len(tiles)==8
 board=np.vstack([np.hstack(tiles[k:k+2]) for k in range(0,8,2)]);f=a.destination/(name+'.jpg');assert cv2.imwrite(str(f),board,[cv2.IMWRITE_JPEG_QUALITY,88])
 pages.append({'file':f.name,'sha256':sha(f)});print('KNOWN_EFFECT_PAGE',name,flush=True)
v={'run_id':a.run.name,'compiled_source_commit':api['compiled_source_commit'],'source_sha256':sha(source),'spec_sha256':sha(a.spec),'scope':'All11 full4K non-solid modes at the existing independently reviewed partial-face points556-563 omitted by the source face scan. Actual source/output crops; requires visual decisions and does not substitute for complete62 acceptance.','items':items,'pages':pages}
(a.destination/'index.json').write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8');print('KNOWN_EFFECT_DETAILS',len(items),len(pages),flush=True)
