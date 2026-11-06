#!/usr/bin/env python3
"""Use the same independent media/face auditors for both platform archives."""
from __future__ import annotations
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/ios'))
import audit_privacy_regression as media
import audit_privacy_face_cores as faces

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
    return 1 if media_result or face_result else 0

if __name__=='__main__':raise SystemExit(main())
