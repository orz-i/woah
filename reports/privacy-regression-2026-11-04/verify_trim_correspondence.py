import json
from pathlib import Path
import cv2
import numpy as np

root=Path(__file__).resolve().parent
source=root.parent/'privacy-e2e-2026-11-04/solo-hd-7570258.mp4'
cap=cv2.VideoCapture(str(source))
source_regions=[]
for i in range(70):
    ok, frame=cap.read()
    if not ok: break
    frame=cv2.resize(frame,(540,1024))
    source_regions.append(frame[450:850,140:400].astype(np.float32))
cap.release()
results=[]
for name, offset in [('solo_face_sticker.mp4',0),('solo_nonzero_trim.mp4',25)]:
    cap=cv2.VideoCapture(str(root/name)); indices=[]; errors=[]
    while True:
        ok,frame=cap.read()
        if not ok: break
        region=frame[450:850,140:400].astype(np.float32)
        diffs=[float(np.mean(np.abs(region-s))) for s in source_regions]
        best=int(np.argmin(diffs));indices.append(best);errors.append(round(diffs[best],3))
    cap.release()
    assert indices==list(range(offset,offset+40)), (name,indices)
    results.append({'file':name,'source_indices':indices,'body_region_mae':errors,'tail_source_frames_preserved':indices[-3:]})
(root/'trim-frame-correspondence.json').write_text(json.dumps(results,indent=2))
print(json.dumps([{'file':r['file'],'frames':len(r['source_indices']),'tail':r['tail_source_frames_preserved'],'max_mae':max(r['body_region_mae'])} for r in results]))
