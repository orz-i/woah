#!/usr/bin/env python3
"""Stage the same pinned face model used by Android into the iOS resource bundle."""
import argparse,hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources'
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--verify-only',action='store_true');args=parser.parse_args()
    c=json.loads((RES/'dense-face-v1.contract.json').read_text(encoding='utf-8'))
    source=ROOT/c['source'];target=RES/'InferenceAssets'/c['model']
    def valid(path):
        return path.is_file() and path.stat().st_size==c['expected_size_bytes'] and hashlib.sha256(path.read_bytes()).hexdigest()==c['expected_sha256']
    if not valid(source):raise SystemExit('Pinned face model source is missing or has changed: '+str(source))
    if not args.verify_only:
        target.parent.mkdir(exist_ok=True,parents=True)
        if not valid(target):shutil.copyfile(source,target)
    if not valid(target):raise SystemExit('Packaged face model is missing or changed: '+str(target))
    print('IOS_FACE_MODEL_VERIFIED',c['expected_sha256'])
if __name__=='__main__':main()
