"""Detect interior holes in the opaque red face masks of these public fixtures."""
import hashlib
import json
import sys
from pathlib import Path
import cv2
import numpy as np

root=Path(__file__).resolve().parent
before='--before' in sys.argv
names=['face-before-composition.mp4'] if before else ['group_face_solid_follow.mp4','group_all_faces.mp4','solo_face_solid.mp4']
if not before and (root/'group_full_length_follow.mp4').exists():names.append('group_full_length_follow.mp4')
results=[]
for name in names:
    p=root/name;cap=cv2.VideoCapture(str(p));frames=0;holes=[]
    while True:
        ok,frame=cap.read()
        if not ok:break
        b,g,r=cv2.split(frame)
        mask=((r>180)&(g<70)&(b<70)&(r.astype(np.int16)-g>100)).astype(np.uint8)*255
        contours,hierarchy=cv2.findContours(mask,cv2.RETR_CCOMP,cv2.CHAIN_APPROX_SIMPLE)
        details=[]
        # AVC 4:2:0 chroma and antialiased mask edges can cross the red color
        # threshold within three pixels of the outer boundary. Check actual
        # interior exposure separately from that expected encoding fringe.
        if hierarchy is not None:
            for c,h in zip(contours,hierarchy[0]):
                if h[3]<0 or cv2.contourArea(c)<=5:continue
                outer=np.zeros_like(mask);cv2.drawContours(outer,[contours[h[3]]],-1,255,cv2.FILLED)
                distance=cv2.distanceTransform(outer,cv2.DIST_L2,5)
                hole=np.zeros_like(mask);cv2.drawContours(hole,[c],-1,255,cv2.FILLED)
                exposed=(hole>0)&(mask==0)&(distance>3.0)
                if np.count_nonzero(exposed)>5:
                    details.append({'area':cv2.contourArea(c),'interior_exposed_pixels':int(np.count_nonzero(exposed)),
                                    'bbox':cv2.boundingRect(c),'parent_bbox':cv2.boundingRect(contours[h[3]])})
        if details:holes.append({'frame':frames,'count':len(details),'max_hole_area_px':max(d['area'] for d in details),'details':details})
        frames+=1
    cap.release()
    results.append({'file':name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'frames':frames,'frames_with_holes':len(holes),'holes':holes})
target='privacy-mask-holes-before.json' if before else 'privacy-mask-hole-audit.json'
(root/target).write_text(json.dumps(results,indent=2),encoding='utf-8')
print(json.dumps([{'file':r['file'],'frames':r['frames'],'frames_with_holes':r['frames_with_holes']} for r in results]))
if not before:assert all(r['frames_with_holes']==0 for r in results), 'Opaque face privacy has interior holes'
