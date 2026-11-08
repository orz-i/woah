import cv2,json,hashlib,sys,numpy as np
from pathlib import Path
from review_movie_frames import review_movie_frames
run=Path(sys.argv[1]);dest=Path(sys.argv[2]);dest.mkdir(parents=True,exist_ok=True)
rows=json.loads((run/'results.json').read_text())['cases']
life=run/'qa_lifecycle_results.json'
if life.exists():
 v=json.loads(life.read_text());status=v.get('retry_status',{})
 if v.get('input') and status.get('state')=='completed':
  rows.append({'case':'retry_custom_sticker','audit_origin':'lifecycle_chain','output_width':v['target_width'],'output_height':v['target_height'],'expected_frames':status['current_frame'],'previews':[]})
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
media_path=run.parent.parent/'media-audits'/run.name/'results.json'
media={r['case']:r for r in json.loads(media_path.read_bytes())['cases']} if media_path.is_file() else {}
proof=[]
for row in rows:
 name=row['case'];previews=row.get('previews',[]);w,h=row['output_width'],row['output_height']
 tile_w=220;tile_h=min(420,round(h*tile_w/w));rh=tile_h+24;columns=max(3,len(previews))
 def tile(image,label):
  out=np.full((rh,tile_w,3),25,dtype=np.uint8)
  if image is not None:out[24:]=cv2.resize(image,(tile_w,tile_h),interpolation=cv2.INTER_AREA)
  cv2.putText(out,label[:32],(4,16),cv2.FONT_HERSHEY_SIMPLEX,.38,(0,255,255),1);return out
 source=[];protected=[];names=[]
 for p in previews:
  src=run/'previews'/p['control_file'];out=run/'previews'/p['file']
  source.append(tile(cv2.imread(str(src)),'source '+str(p['requested_ms'])+'ms'));protected.append(tile(cv2.imread(str(out)),'preview '+str(p['requested_ms'])+'ms'))
  names.append({'requested_ms':p['requested_ms'],'source_sha256':sha(src),'protected_sha256':sha(out)})
 while len(source)<columns:source.append(tile(None,''));protected.append(tile(None,''))
 movie=run/'outputs'/(name+'.mp4');frames=[];indices=[]
 movie_hash=None;selection_mode='cancelled_no_movie'
 if movie.exists():
  indices,selected,movie_hash,selection_mode=review_movie_frames(
      movie,row['expected_frames'],[w,h],media.get(name,{}))
  for n in indices:frames.append(tile(selected[n],'movie frame '+str(n)))
 else:frames=[tile(None,'cancelled')]*3
 while len(frames)<columns:frames.append(tile(None,''))
 board=np.vstack([np.hstack(source),np.hstack(frames),np.hstack(protected)])
 title=np.zeros((25,board.shape[1],3),np.uint8);cv2.putText(title,name,(4,18),cv2.FONT_HERSHEY_SIMPLEX,.47,(255,255,255),1)
 target=dest/(name+'.jpg');cv2.imwrite(str(target),np.vstack([title,board]),[cv2.IMWRITE_JPEG_QUALITY,76])
 proof.append({'case':name,'audit_origin':row.get('audit_origin','matrix'),'movie_sha256':movie_hash,'movie_frame_selection':selection_mode,'movie_frame_indices':indices,'previews':names,'board':target.name,'board_sha256':sha(target)})
(dest/'review-index.json').write_text(json.dumps({'run_id':run.name,'scope':'Manual first/middle/last decoded movie and every native source/protected preview; complements full decode, source-face cores and known-point probes.','cases':proof},indent=2)+'\n')
print('REVIEW_BOARDS',len(proof),dest)
