"""Make source/output pixel crops for visual review of each full 4K mode."""
import argparse
import json
from pathlib import Path
import cv2

ROOT=Path(__file__).resolve().parent

def make_crops(input_name, path, target=None):
    target = target or path.with_name(path.stem + "-privacy-crops.jpg")
    group=input_name.startswith("group-")
    picks=[(145,1372,682,"side face"),(290,893,1275,"mirror face"),(580,1402,1322,"leg")] if group else [(0,1224,1112,"first face"),(330,1328,1416,"middle face"),(661,1048,1152,"last face")]
    size=280 if group else 512
    source=cv2.VideoCapture(str(ROOT.parent/"privacy-e2e-2026-11-04"/input_name))
    output=cv2.VideoCapture(str(path));rows=[]
    for frame,x,y,label in picks:
        images=[]
        for cap in (source,output):
            cap.set(cv2.CAP_PROP_POS_FRAMES,frame);ok,pixels=cap.read();assert ok,(path.name,frame)
            half=size//2
            images.append(pixels[y-half:y+half,x-half:x+half])
        row=cv2.hconcat(images)
        row=cv2.copyMakeBorder(row,28,0,0,0,cv2.BORDER_CONSTANT,value=(0,0,0))
        cv2.putText(row,f"{label}, frame {frame}: source | output",(6,20),cv2.FONT_HERSHEY_SIMPLEX,.5,(255,255,255),1)
        rows.append(row)
    source.release();output.release();assert cv2.imwrite(str(target),cv2.vconcat(rows))
    return target


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--run",required=True)
    a=parser.parse_args()
    folder=ROOT/a.run
    summary=json.loads((folder/"results.json").read_text(encoding="utf-8"))
    for case in summary["cases"]:
        if case.get("state")!="completed" or case.get("result")!="passed" or case.get("trim_start_ms",0)!=0:
            continue
        path=folder/(case["case"]+".mp4")
        target=path.with_name(path.stem+"-privacy-crops.jpg")
        if not path.exists() or target.exists():continue
        print(make_crops(case["input"],path,target).name,flush=True)
