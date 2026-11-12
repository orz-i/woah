"""Check captured Flutter texture pixels against independent source PTS and images."""
import argparse
import hashlib
import json
from fractions import Fraction
from pathlib import Path

import cv2
from verify_comparison_export import probe, frame_times, source_correspondence, code_pixels, source_image, pane_similarity


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'corpus', 'images', 'report'):
        p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args()
    run = json.loads(a.runtime.read_bytes())
    manifest = json.loads((a.corpus / 'manifest.json').read_bytes())
    sessions = {s['id']: s for s in manifest['sessions']}
    infos = {}
    rows = []
    for record in run['previews']:
        row = dict(session=record['session'], positionUs=record['positionUs'])
        try:
            s = sessions[record['session']]
            paths = [a.corpus / manifest['sources'][s[k]]['file'] for k in ('reference', 'target')]
            for path in paths:
                if path not in infos:
                    infos[path] = probe(path)
            ri, ti = [infos[path] for path in paths]
            clock = Fraction(s['startUs'] + record['positionUs'], 1_000_000)
            rt, tt = source_correspondence(frame_times(ri), frame_times(ti), clock, s['scale'], s['offset'])
            image_path = a.images / record['file']
            image = cv2.cvtColor(cv2.imread(str(image_path)), cv2.COLOR_BGR2RGB)
            assert image.shape == (240, 320, 3), image.shape
            spec = dict(width=320, height=240, gap=8, sideBySide=True, mirrorTeacher=False,
                        mirrorLearner=False, teacherPicture=dict(zoom=1, panX=0, panY=0),
                        learnerPicture=dict(zoom=1, panX=0, panY=0))
            if all(manifest['sources'][s[k]]['coded30'] for k in ('reference', 'target')):
                row['checkedBits'] = [code_pixels(image, spec, i, round(t * 30)) for i, t in enumerate((rt, tt))]
            else:
                row['visual'] = [pane_similarity(image, spec, i, source_image(path, info, t))
                                 for i, (path, info, t) in enumerate(zip(paths, (ri, ti), (rt, tt)))]
            row.update(status='passed', referencePtsUs=round(rt * 1_000_000), targetPtsUs=round(tt * 1_000_000),
                       imageSha256=hashlib.sha256(image_path.read_bytes()).hexdigest())
        except Exception as error:
            row.update(status='failed', error=str(error))
        rows.append(row)
        print(json.dumps(row), flush=True)
    passed = run['finished'] and run['passed'] and len(rows) == 24 and all(r['status'] == 'passed' for r in rows)
    result = dict(schema='woah.comparison.texture-audit.v1', platform=run['platform'], passed=passed,
                  scope='Actual Flutter texture snapshots at three source clocks in eight sessions; independent of playback DTO frame labels.',
                  corpusManifestSha256=hashlib.sha256((a.corpus / 'manifest.json').read_bytes()).hexdigest(), results=rows)
    a.report.write_text(json.dumps(result, indent=2), encoding='utf-8')
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
