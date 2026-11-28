#!/usr/bin/env python3
"""Audit current canonical mask ownership at frozen source-only body landmarks.

Landmarks never enter tracking or face inference. They are sparse body points,
not semantic head coverage or dense identity ground truth. Re-decode the exact
hashed fixture frame and require the original raw detector boxes to match.
"""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image

from replay_face_geometry import ROOT, Rect, detect_instances, iou, load, model, vals


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--library', type=Path, required=True)
    ap.add_argument('--fixture', type=Path, required=True)
    ap.add_argument('--labels', type=Path, required=True)
    ap.add_argument('--replay', type=Path, required=True)
    ap.add_argument('--selected-id', type=int, required=True)
    ap.add_argument('--distractor-id', type=int, required=True)
    ap.add_argument('--output', type=Path, required=True)
    a = ap.parse_args()
    if a.selected_id == a.distractor_id:
        ap.error('identity IDs must differ')
    labels = json.loads(a.labels.read_text())
    manifest = json.loads(a.fixture.read_text())
    replay = json.loads(a.replay.read_text())
    if replay['fixture_sha256'] != sha(a.fixture) or replay['target_id'] != a.selected_id:
        raise ValueError('replay fixture or initial identity mismatch')
    if manifest['source_sha256'] != labels['sources']['reference']['testedMp4Sha256']:
        raise ValueError('source video hash mismatch')
    model_path = ROOT / 'models/litert/yolo11n-seg-fp16.tflite'
    if replay['model_sha256']['person'] != sha(model_path):
        raise ValueError('person model hash mismatch')
    lib, yolo = load(a.library), model(model_path)
    rows = []
    for label in labels['annotations']['reference']:
        frame = round(label['ptsUs'] * manifest['fps'] / 1e6)
        meta = manifest['frames'][frame]
        path = a.fixture.parent / meta['asset']
        if sha(path) != meta['sha256']:
            raise ValueError('source frame hash mismatch')
        image = Image.open(path).convert('RGB')
        t, ds, masks = detect_instances(lib, yolo, image)
        points = [label['selected'], label['distractor']]
        def support(mask, point):
            x, y = point
            u = int((x * t.width * t.scale + t.left) * t.proto / t.input)
            v = int((y * t.height * t.scale + t.top) * t.proto / t.input)
            if not (0 <= u < t.proto and 0 <= v < t.proto):
                raise ValueError('landmark outside mask grid')
            return int(mask[v, u])
        raw_support = [[support(m, point) for point in points] for m in masks]
        result = dict(index=frame, source_sha256=meta['sha256'], raw_support=raw_support)
        for mode in ('baseline', 'candidate'):
            record = replay[mode]['frames'][frame]
            if record['raw_boxes'] != [vals(d.box) for d in ds]:
                raise ValueError('detector evidence mismatch')
            mode_rows = []
            for i, ident in enumerate((a.selected_id, a.distractor_id)):
                track = next((t for t in record['tracks'] if t['id'] == ident), None)
                matched = [j for j, d in enumerate(ds) if track and track['observed']
                           and iou(d.box, Rect(*track['bounds'])) > .99999]
                if len(matched) != 1:
                    mode_rows.append(dict(id=ident, outcome='unobserved_or_ambiguous'))
                    continue
                j = matched[0]
                own, other = raw_support[j][i], raw_support[j][1-i]
                outcome = ('correct' if own >= 128 and other < 128 else
                           'wrong' if own < 128 and other >= 128 else 'ambiguous')
                mode_rows.append(dict(id=ident, raw_index=j, own_support=own,
                                      other_support=other, outcome=outcome))
            result[mode] = mode_rows
        rows.append(result)
    summary = {mode: {str(ident): {status: sum(
        x['id'] == ident and x['outcome'] == status for r in rows for x in r[mode])
        for status in ('correct', 'wrong', 'ambiguous', 'unobserved_or_ambiguous')}
        for ident in (a.selected_id, a.distractor_id)} for mode in ('baseline', 'candidate')}
    out = dict(scope=__doc__, labels_sha256=sha(a.labels), fixture_sha256=sha(a.fixture),
               replay_sha256=sha(a.replay), summary=summary, rows=rows)
    a.output.write_text(json.dumps(out, indent=2)+'\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
