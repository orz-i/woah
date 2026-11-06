#!/usr/bin/env python3
"""Validate the common 62-case contract and optionally its actual material files."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / 'testdata/privacy/matrix.json'


def load_matrix():
    data = json.loads(MATRIX.read_text(encoding='utf-8'))
    assert data['schema'] == 'woah-privacy-matrix-v1'
    assert len(data['materials']) == 8
    assert [(s['id'], len(s['cases'])) for s in data['suites']] == [
        ('legacy22', 22), ('original4k', 27), ('focused13', 13)]
    names = set()
    for suite in data['suites']:
        for row in suite['cases']:
            key = suite['id'] + '/' + row['case']
            assert key not in names, key
            names.add(key)
            assert row['input'] in data['materials'], key
            assert row['endMs'] > row['startMs'] >= 0, key
            assert row['width'] > 0 and row['height'] > 0, key
            assert row['profile'] in ('quality', 'balanced', 'speed'), key
            if suite['id'] == 'original4k':
                assert (row['width'], row['height']) in ((3840,2160),(2160,4096)), key
            if 'cancelAtFrame' in row:
                assert row['cancelAtFrame'] >= 25 and row['expected_frames'] == 0, key
    assert sum(r['expected_frames'] for s in data['suites'] for r in s['cases']) == 17443
    follow = next(r for r in data['suites'][0]['cases'] if r['case'] == 'group_full_length_follow')
    assert follow['hero'] == 6 and follow['faceOnly'] and follow['expected_frames'] == 581
    assert (follow['width'],follow['height'],follow['startMs'],follow['endMs']) == (720,1280,0,23240)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--materials',type=Path)
    parser.add_argument('--suite',choices=('legacy22','original4k','focused13'))
    parser.add_argument('--output',type=Path,help='Write a native QA config from the common contract')
    args = parser.parse_args()
    data = load_matrix()
    if args.materials:
        for name, binding in data['materials'].items():
            path = args.materials / name
            assert path.stat().st_size == binding['bytes'], name
            digest = hashlib.sha256()
            with path.open('rb') as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    digest.update(chunk)
            assert digest.hexdigest() == binding['sha256'], name
    if args.output:
        assert args.suite, '--output requires --suite'
        suite = next(s for s in data['suites'] if s['id'] == args.suite)
        args.output.write_text(json.dumps({'run_id':'rust_'+args.suite,'cases':suite['cases'],
            'matrix_sha256':hashlib.sha256(MATRIX.read_bytes()).hexdigest()},indent=2)+'\n',encoding='utf-8')
    print('Shared privacy matrix: 22 + 27 + 13 = 62 cases, 17443 expected movie frames, 8 material bindings')


if __name__ == '__main__':
    main()
