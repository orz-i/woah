"""Create LOCAL source-only review crop targets; no production policy or acceptance."""
import argparse, json
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('source_vision_jsonl',type=Path);p.add_argument('destination',type=Path);p.add_argument('--profile-anchor-index',required=True,type=int);a=p.parse_args()
rows=[json.loads(v) for v in a.source_vision_jsonl.read_text(encoding='utf-8').splitlines()]
assert rows[0]['sha256']=='376c2ae73c92c23dceb042d27450aaa45ba2f44a3d7b0a5f3c26e9bea6c2dd73'
by={v['frame']:v for v in rows if v.get('kind')=='frame'}
def item(n,selector):
    boxes=[v['bbox'] for v in by[n]['faces']]
    assert boxes,n
    return {'frame':n,'bbox':selector(boxes)}
# Source-only spatial selection, never production observations or rendered output.
overlap=[item(n,lambda boxes:max(boxes,key=lambda b:b[0])) for n in [116,119]]
clipped=[item(n,lambda boxes:min(boxes,key=lambda b:b[1])) for n in [185,186,188,189,190,193,194,201,202,203,204,205]]

# Anchors are chosen by inspecting independent source-only face detections.
def anchored(first,last,frame,index):
 boxes=[v['bbox'] for v in by[frame]['faces']]
 assert 0<=index<len(boxes)
 anchor=boxes[index];center=lambda b:((b[0]+b[2])/2,(b[1]+b[3])/2)
 ax,ay=center(anchor);limit=2.5*max(anchor[2]-anchor[0],anchor[3]-anchor[1]);result=[]
 for n in range(first,last+1):
  candidates=[v['bbox'] for v in by.get(n,{}).get('faces',[])]
  if not candidates:continue
  selected=min(candidates,key=lambda b:(center(b)[0]-ax)**2+(center(b)[1]-ay)**2)
  x,y=center(selected)
  if (x-ax)**2+(y-ay)**2<=limit**2:result.append({'frame':n,'bbox':selected})
 assert len(result)>=2
 return result
profile=anchored(94,110,102,a.profile_anchor_index)
early_chin=anchored(179,187,181,0)
late_chin=anchored(513,518,515,0)
end_chin=anchored(575,580,577,0)
spec={'scope':'LOCAL ONLY independent source-box trajectory for enlarged continuous risk-span review. No original native face observations or contours are published. Source context must be visually checked; interpolation does not infer acceptance.','source':'group-mirror-8956295.mp4','source_sha256':rows[0]['sha256'],'width':3840,'height':2160,'frame_period_us':40000,'spans':[{'name':'overlapping_neighbor','first':112,'last':123,'anchors':overlap},{'name':'clipped_top','first':176,'last':216,'anchors':clipped},{'name':'profile_transition','first':94,'last':110,'anchors':profile},{'name':'early_chin_margin','first':179,'last':187,'anchors':early_chin},{'name':'late_chin_margin','first':513,'last':518,'anchors':late_chin},{'name':'end_chin_margin','first':575,'last':580,'anchors':end_chin}]}
assert not a.destination.exists()
a.destination.parent.mkdir(parents=True,exist_ok=True)
a.destination.write_text(json.dumps(spec,indent=2)+'\n',encoding='utf-8')
print('Created local91 face-frame pair targets across82 source frames from independent source observations only.')
