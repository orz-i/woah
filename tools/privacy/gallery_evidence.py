"""Verify saved originals without widening the app's Photos permission."""
from __future__ import annotations
import datetime as dt
import hashlib,json,shutil
from pathlib import Path

def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1048576),b''):h.update(chunk)
    return h.hexdigest()

def bound(root,relative):
    target=(root/relative).resolve()
    if not target.is_relative_to(root.resolve()):raise ValueError('Gallery evidence escapes the run')
    return target

def capture_simulator_gallery(run,dcim):
    run,dcim=Path(run),Path(dcim).resolve()
    data=json.loads((run/'results.json').read_text(encoding='utf-8'))
    start=dt.datetime.fromisoformat(data['started_at_utc'].replace('Z','+00:00')).timestamp()
    end=dt.datetime.fromisoformat(data['finished_at_utc'].replace('Z','+00:00')).timestamp()
    proof=[]
    for row in data['cases']:
        if not row.get('saveToGallery'):continue
        if row['result']!='output_created' or row.get('gallery_save_completed') is not True:
            raise ValueError('Gallery save did not complete')
        source=bound(run,'outputs/'+row['case']+'.mp4');digest=sha(source);size=source.stat().st_size
        matches=[]
        for file in dcim.rglob('*'):
            if file.is_file() and file.resolve().is_relative_to(dcim):
                stat=file.stat()
                if stat.st_size==size and start-5<=stat.st_mtime<=end+30 and sha(file)==digest:matches.append(file)
        if len(matches)!=1:raise ValueError(f'Expected one time/hash-bound Photos original for {row["case"]}; found {len(matches)}')
        target=run/'gallery'/matches[0].name;target.parent.mkdir(exist_ok=True)
        if target.exists() and sha(target)!=digest:raise FileExistsError('Preserve different prior gallery evidence')
        shutil.copy2(matches[0],target)
        proof.append({'case':row['case'],'status':'passed_photos_saved_original',
            'photos_original_relative_to_simulator_media':str(matches[0].relative_to(dcim.parent)),
            'sha256':digest,'bytes':size,'photos_original_mtime_utc':dt.datetime.fromtimestamp(matches[0].stat().st_mtime,dt.timezone.utc).isoformat(),
            'copied_file':str(target.relative_to(run))})
    result={'scope':'Host inspection of this QA simulator only; exact byte hash and run-time window. No added app Photos read permission.','cases':proof}
    (run/'gallery-proof.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    return result

def verify_saved_gallery(run):
    run=Path(run);data=json.loads((run/'results.json').read_text(encoding='utf-8'))
    wanted=[r for r in data['cases'] if r.get('saveToGallery')]
    proofs={r['case']:r for r in json.loads((run/'gallery-proof.json').read_text(encoding='utf-8'))['cases']} if (run/'gallery-proof.json').exists() else {}
    rows=[]
    for row in wanted:
        if row['result']!='output_created' or row.get('gallery_save_completed') is not True:raise ValueError('Gallery save did not complete')
        source=bound(run,'outputs/'+row['case']+'.mp4');expected=sha(source)
        original=row.get('gallery_original')
        if original:
            target=bound(run,'gallery/'+Path(original['path']).name);declared=original['sha256']
        else:
            proof=proofs.get(row['case'])
            if not proof:raise ValueError('Missing independently copied Photos original')
            target=bound(run,proof['copied_file']);declared=proof['sha256']
        if declared!=expected or sha(target)!=expected or target.stat().st_size!=source.stat().st_size:
            raise ValueError('Saved gallery original differs from the exported movie')
        rows.append({'case':row['case'],'status':'passed_saved_original_bytes','sha256':expected,'bytes':source.stat().st_size})
    return rows
