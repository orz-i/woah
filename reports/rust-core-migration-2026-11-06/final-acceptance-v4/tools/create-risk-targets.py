"""Create LOCAL source-only review crop targets; no production policy or acceptance."""
import argparse, json
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('source_vision_jsonl',type=Path);p.add_argument('destination',type=Path);a=p.parse_args()
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

spec={'scope':'LOCAL ONLY independent source-box trajectory for enlarged continuous risk-span review. No original native face observations or contours are published. Source context must be visually checked; interpolation does not infer acceptance.','source':'group-mirror-8956295.mp4','source_sha256':rows[0]['sha256'],'width':3840,'height':2160,'frame_period_us':40000,'spans':[{'name':'overlapping_neighbor','first':112,'last':123,'anchors':overlap},{'name':'clipped_top','first':176,'last':216,'anchors':clipped}]}
assert not a.destination.exists()
a.destination.parent.mkdir(parents=True,exist_ok=True)
a.destination.write_text(json.dumps(spec,indent=2)+'\n',encoding='utf-8')
print('Created local53-frame review targets from independent source observations only.')
