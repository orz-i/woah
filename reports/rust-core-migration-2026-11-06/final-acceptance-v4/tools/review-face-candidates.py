import argparse,cv2,hashlib,json,math,numpy as np
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('run',type=Path);p.add_argument('audit',type=Path);p.add_argument('media',type=Path);p.add_argument('destination',type=Path);a=p.parse_args();a.destination.mkdir(parents=True,exist_ok=True)
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
class Reader:
 def __init__(self,path):self.cap=cv2.VideoCapture(str(path));self.at=-1;self.image=None
 def frame(self,n):
  assert n>=self.at
  while self.at<n:
   ok,self.image=self.cap.read()
   if not ok:raise ValueError('Missing sequential source/output review frame')
   self.at+=1
  return self.image
 def close(self):self.cap.release()
def tile(im,box):
 l,t,r,b=box;l=max(0,math.floor(l));t=max(0,math.floor(t));r=min(im.shape[1],math.ceil(r));b=min(im.shape[0],math.ceil(b))
 if r<=l or b<=t:raise ValueError('Empty candidate crop')
 return cv2.resize(im[t:b,l:r],(150,150),interpolation=cv2.INTER_LINEAR)
results={r['case']:r for r in json.loads((a.run/'results.json').read_text())['cases']}
items=[];images=[]
for case in json.loads(a.audit.read_text())['cases']:
 alerts=case.get('uncovered_face_cores',[])
 if not alerts:continue
 row=results[case['case']];movie=a.run/'outputs'/(case['case']+'.mp4');source=a.media/row['input'];src=Reader(source);out=Reader(movie)
 movie_sha=sha(movie);source_sha=sha(source)
 for index,c in enumerate(sorted(alerts,key=lambda c:(c['output_frame'],c['source_bbox'][0]))):
  frame=src.frame(c['source_frame']);protected=out.frame(c['output_frame'])
  l,t,r,b=c['source_bbox'];w=r-l;h=b-t;cx=(l+r)/2;cy=(t+b)/2
  x1,y1,x2,y2=c['output_core'];ow=(x2-x1)/.7;oh=(y2-y1)/.7;ox=(x1+x2)/2;oy=(y1+y2)/2
  board=np.vstack([np.zeros((34,450,3),np.uint8),np.hstack([tile(frame,(cx-w*.8,cy-h*.8,cx+w*.8,cy+h*.8)),tile(frame,(cx-w*1.5,cy-h,cx+w*1.5,cy+h*4)),tile(protected,(ox-ow*.8,oy-oh*.8,ox+ow*.8,oy+oh*.8))])])
  label=f"{len(items):03d} {case['case']} out{c['output_frame']} src{c['source_frame']}"
  cv2.putText(board,label[:68],(3,13),cv2.FONT_HERSHEY_SIMPLEX,.34,(0,255,255),1)
  cv2.putText(board,f"source face / source context / actual output; coverage={c['coverage_fraction']:.4f}",(3,29),cv2.FONT_HERSHEY_SIMPLEX,.32,(0,255,255),1)
  images.append(board);items.append({'index':len(items),'case':case['case'],'raw_status':case['status'],'raw_alert':c,'source_sha256':source_sha,'movie_sha256':movie_sha,'audit_input_binding_sha256':case.get('input_binding_sha256')})
 src.close();out.close()
pages=[]
for start in range(0,len(images),8):
 selected=images[start:start+8]
 while len(selected)<8:selected.append(np.zeros_like(images[0]))
 board=np.vstack([np.hstack(selected[j:j+2]) for j in range(0,8,2)])
 path=a.destination/f'candidates-{start//8:02d}.jpg';cv2.imwrite(str(path),board,[cv2.IMWRITE_JPEG_QUALITY,78]);pages.append({'file':path.name,'sha256':sha(path),'first_index':start,'last_index':min(start+7,len(images)-1)})
value={'run_id':a.run.name,'scope':'Unmodified raw face alerts. Source face/context and actual output decoded sequentially from frame zero. Requires a separate recorded manual decision; these boards do not change automatic audit status.','cases':items,'pages':pages,'audit_sha256':sha(a.audit)}
(a.destination/'candidate-index.json').write_text(json.dumps(value,indent=2)+chr(10));print('CANDIDATE_BOARDS',len(items),len(pages),a.destination)
