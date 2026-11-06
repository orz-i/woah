#!/usr/bin/env python3
"""Use the same independent media/face auditors for both platform archives."""
from __future__ import annotations
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/ios'))
import audit_privacy_regression as media
import audit_privacy_face_cores as faces
from audit_previews import audit_run as audit_previews
from gallery_evidence import verify_saved_gallery
from lifecycle_evidence import verify_lifecycle

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('run_id');p.add_argument('--report',type=Path,required=True)
    p.add_argument('--media',type=Path,required=True)
    p.add_argument('--source-faces',type=Path,required=True)
    p.add_argument('--allow-partial-stage',action='store_true')
    a=p.parse_args();run=a.report/'artifacts'/a.run_id
    metadata=json.loads((run/'run-metadata.json').read_text(encoding='utf-8'))
    if metadata['config'].get('partial_stage_check') and not a.allow_partial_stage:
        raise RuntimeError('Stage subsets cannot be reported as full acceptance')
    args=[a.run_id,'--report-root',str(a.report),'--sources',str(a.media)]
    media_result=media.main(args)
    face_result=faces.main(args+['--source-faces',str(a.source_faces),'--require-face-cache'])
    if metadata['config'].get('stage_export_only') and metadata['config'].get('partial_stage_check'):
        marker=a.report/'preview-audits'/a.run_id/'results.json';marker.parent.mkdir(parents=True,exist_ok=True)
        marker.write_text(json.dumps({'run_id':a.run_id,'status':'not_run_export_only_stage',
            'scope':'Export-only diagnosis. Does not establish preview behavior or complete flow acceptance.'},indent=2)+'\n',encoding='utf-8')
        print('PREVIEWS_NOT_RUN_EXPORT_ONLY_STAGE',flush=True);preview_result=0
    else:
        preview_result=audit_previews(run,a.report,a.media,a.source_faces)
    gallery=verify_saved_gallery(run)
    destination=a.report/'gallery-audits'/a.run_id/'results.json';destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps({'run_id':a.run_id,'cases':gallery},indent=2)+'\n',encoding='utf-8')
    destination=a.report/'lifecycle-audits'/a.run_id/'results.json';destination.parent.mkdir(parents=True,exist_ok=True)
    try:lifecycle=verify_lifecycle(run)
    except RuntimeError as error:
        destination.write_text(json.dumps({'run_id':a.run_id,'status':'failed','error':str(error)},indent=2)+'\n',encoding='utf-8')
        return 1
    destination.write_text(json.dumps({'run_id':a.run_id,**lifecycle},indent=2)+'\n',encoding='utf-8')
    return 1 if media_result or face_result or preview_result else 0

if __name__=='__main__':raise SystemExit(main())
