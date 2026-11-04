from pathlib import Path
import cv2
import numpy as np

root=Path(__file__).resolve().parent
for name,rect,size,cols in [
    ('group_face_solid_follow',(100,200,520,400),(195,150),8),
    ('group_full_speed',(500,130,240,350),(120,175),10),
    ('solo_face_solid',(80,20,400,400),(150,150),8),
]:
    cap=cv2.VideoCapture(str(root/(name+'.mp4')));cells=[];i=0
    while True:
        ok,f=cap.read()
        if not ok:break
        x,y,w,h=rect;cell=cv2.resize(f[y:y+h,x:x+w],size)
        cv2.putText(cell,str(i),(4,14),cv2.FONT_HERSHEY_SIMPLEX,.43,(255,255,255),1,cv2.LINE_AA)
        cells.append(cell);i+=1
    cap.release()
    while len(cells)%cols:cells.append(np.full_like(cells[0],235))
    sheet=cv2.vconcat([cv2.hconcat(cells[r:r+cols]) for r in range(0,len(cells),cols)])
    cv2.imwrite(str(root/(name+'-every-frame.jpg')),sheet)
    print(name,i,'frames')
