import hashlib
import json
from pathlib import Path
from inspect_media import inspect, contact

root=Path(__file__).resolve().parent
cases=json.loads((root/'network-case-results.json').read_text())
assert len(cases)==19 and all(r['result']=='passed' for r in cases)
names=[r['case']+'.mp4' for r in cases]+['group_full_balanced.mp4','group_full_speed.mp4']
if (root/'group_full_length_follow.mp4').exists():names.append('group_full_length_follow.mp4')
if (root/'export-jobs.json').exists():
    cutoff=json.loads((root/'environment.json').read_text()).get('regression_started_ms',0)
    names += [Path(j['outputUri']).name for j in json.loads((root/'export-jobs.json').read_text()) if j['state']=='completed' and j['createdAt']>=cutoff]
previous=json.loads((root/'media-inspection.json').read_text()) if (root/'media-inspection.json').exists() else []
cached={r['file']:r for r in previous};results=[]
for name in dict.fromkeys(names):
    p=root/name;digest=hashlib.sha256(p.read_bytes()).hexdigest()
    r=cached.get(name)
    if not r or r['sha256']!=digest:
        r=inspect(p);contact(p)
    assert r['strictly_increasing_decoded_pts'] and r['black_frames']==0 and r['green_frames']==0, r
    assert r['atoms']['valid'] and r['atoms']['fast_start_ready'], r
    summary=root/(p.stem+'.json')
    if summary.exists():
        expected=json.loads(summary.read_text())
        assert r['decoded_frames']==expected['expected_frames']==expected['actual_frames'], name
    if r['audio_duration'] is not None:
        assert abs(r['av_duration_delta_ms']) <= 45, r
    results.append(r)
(root/'media-inspection.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'files':len(results),'decoded_frames':sum(r['decoded_frames'] for r in results),'black_frames':sum(r['black_frames'] for r in results),'green_frames':sum(r['green_frames'] for r in results),'all_fast_start':all(r['atoms']['fast_start_ready'] for r in results)}))
