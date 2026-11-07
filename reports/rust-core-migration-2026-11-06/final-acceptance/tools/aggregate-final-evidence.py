"""Validate portable curated evidence; emit acceptance only after both complete62 cohorts.
Usage: python aggregate-final-evidence.py /absolute/path/to/repository
"""
import datetime
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
base = root / 'reports/rust-core-migration-2026-11-06/final-acceptance'
commit = 'ce25a0d38a3c2c34e0d65b3967c0b32e7e7b1e10'
models = {
    'ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340': 11799725,
    '3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b': 1083786,
}


def read(p):
    return json.loads(p.read_bytes())


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


matrix = read(root / 'testdata/privacy/matrix.json')
suites = {s['id']: s for s in matrix['suites']}
platforms = {}
rows = []
for platform in ['android', 'ios']:
    all_cases = []
    cohorts = []
    binaries = set()
    fingerprints = set()
    for name in ['legacy22', 'original4k', 'focused13']:
        d = base / platform / name
        required = d / 'cohort-summary.json'
        if not required.exists():
            raise SystemExit(f'INCOMPLETE: missing {platform}/{name}; no acceptance emitted')
        summary = read(required)
        api = read(d / 'results.json')
        meta = read(d / 'run-metadata.json')
        build = read(d / 'build-manifest.json')
        expected = suites[name]['cases']
        assert summary['status'] == 'cohort_complete_independently_audited_and_reviewed'
        assert api['status'] == 'finished' and meta['finished_utc']
        assert api['compiled_source_commit'] == build['source_commit'] == commit
        assert api['compiled_source_fingerprint'] == build['source_fingerprint']
        assert meta['binary_sha256'] == build['binary_sha256']
        assert meta['config']['cases'] == expected
        assert not meta['config']['partial_stage_check'] and not meta['config']['stage_export_only']
        assert [c['case'] for c in api['cases']] == [c['case'] for c in expected]
        assert summary['api_cases'] == len(expected)
        for filename, material in matrix['materials'].items():
            assert meta['sources'][filename]['sha256'] == material['sha256']
        faces = read(d / 'face-audit.json')
        assert all(c['status'].startswith('passed') or (c['status'] in ['failed_face_cores', 'needs_hero_visual_review'] and c.get('uncovered_face_cores')) for c in faces['cases'])
        raw_alerts = [a for c in faces['cases'] for a in c.get('uncovered_face_cores', [])]
        allowed = {'intentionally_exempt_hero6_teal', 'protected_features_covered_bbox_includes_hair_neck',
                   'protected_features_covered_bbox_includes_hair_ear_arm_neck', 'source_detector_non_face_candidate'}
        files = read(d / 'archived-file-sha256.json')
        media = read(d / 'media-audit.json')
        assert len(media['cases']) == summary['actual_movies']
        assert sum(c['decoded_frames'] for c in media['cases']) == summary['decoded_frames']
        for c in media['cases']:
            assert c['status'] == 'passed_media'
            assert c['decoded_frames'] == c['expected_frames']
            assert files['outputs'][c['case'] + '.mp4'] == c['sha256']
        for c, exp in zip(api['cases'], expected):
            assert c['pid'] == api['process_id']
            assert c['expected_frames'] == exp['expected_frames']
            assert c['start_ms'] == exp['startMs'] and c['end_ms'] == exp['endMs']
            assert c['result'] in ['output_created', 'cancelled_cleanly']
            if c['result'] == 'cancelled_cleanly':
                assert c['before_cancel']['current_frame'] >= exp['cancelAtFrame']
                assert c['file_bytes'] == 0 and not c['partial_files'] and c['state'] == 'cancelled'
            else:
                assert c['state'] == 'completed' and c['current_frame'] == c['expected_frames']
            rows.append({'platform': platform, 'suite': name, 'case': c['case'],
                         'result': c['result'], 'pid': c['pid'], 'dimensions': [c['output_width'], c['output_height']],
                         'expected_frames': c['expected_frames'], 'elapsed_seconds': c.get('elapsed_seconds'),
                         'output_sha256': files.get('outputs', {}).get(c['case'] + '.mp4')})
        if name == 'original4k':
            cancel, retry = api['cases'][3:5]
            assert cancel['case'] == 'solo_4k_cancel' and cancel['before_cancel']['current_frame'] >= 25
            assert retry['case'] == 'solo_4k_retry_sticker' and retry['current_frame'] == 662
            assert retry['pid'] == cancel['pid'] == api['process_id']
        for folder, index_name, review_name, hash_key in [
            ('manual-review', 'review-index.json', 'manual-board-review.json', 'review_index_sha256'),
            ('manual-candidates', 'candidate-index.json', 'manual-candidate-review.json', 'candidate_index_sha256'),
        ]:
            f = d / folder
            index = read(f / index_name)
            if folder == 'manual-candidates':
                assert len(index['cases']) == len(raw_alerts)
            if folder == 'manual-candidates' and not index['cases']:
                continue
            review = read(f / review_name)
            assert review[hash_key] == sha(f / index_name)
            if folder == 'manual-review':
                assert {c['case'] for c in review['cases']} == {c['case'] for c in index['cases']}
                for c in index['cases']:
                    assert sha(f / c['board']) == c['board_sha256']
                    assert c['movie_sha256'] == files.get('outputs', {}).get(c['case'] + '.mp4')
            else:
                assert review['raw_status_unchanged'] and len(review['decisions']) == len(index['cases'])
                assert {v['index'] for v in review['decisions']} == set(range(len(index['cases'])))
                by_case = {c['case']: c for c in api['cases']}
                for decision, candidate in zip(sorted(review['decisions'], key=lambda v: v['index']), index['cases']):
                    assert decision['decision'] in allowed
                    assert decision['case'] == candidate['case'] and decision['source_frame'] == candidate['raw_alert']['source_frame']
                    if decision['decision'] == 'intentionally_exempt_hero6_teal':
                        assert by_case[decision['case']]['hero_id'] == 6
                for page in index['pages']:
                    assert sha(f / page['file']) == page['sha256']
        preview = read(d / 'preview-audit.json')
        assert all(c['status'] == 'passed_preview_time' for c in preview['cases'])
        alerts = [v for c in preview['cases'] for p in c['previews'] for v in p.get('uncovered_face_candidates', [])]
        if alerts:
            r = read(d / 'manual-review/manual-preview-candidate-review.json')
            assert r['raw_preview_audit_sha256'] == sha(d / 'preview-audit.json')
            assert len(r['decisions']) == len(alerts)
            assert all(v['decision'] in allowed for v in r['decisions'])
        if name != 'legacy22':
            known = read(d / 'known-face-probes.json')
            assert known['status'] == 'passed' and len(known['probes']) == 16
        assert read(d / 'lifecycle-audit.json')['status'] == ('passed_api_chain' if name == 'legacy22' else 'not_requested')
        assert all(c['status'].startswith('passed') for c in read(d / 'gallery-audit.json')['cases'])
        all_cases.extend(api['cases'])
        cohorts.append(summary)
        binaries.add(meta['binary_sha256'])
        fingerprints.add(api['compiled_source_fingerprint'])
    assert len(all_cases) == 62 and len(binaries) == len(fingerprints) == 1
    assert sum(c['actual_movies'] for c in cohorts) == 61
    assert sum(c['decoded_frames'] for c in cohorts) == 17491
    assert sum(c['native_preview_pairs'] for c in cohorts) == 184
    proof = read(base / platform / 'installed-build-model-bytes.json')
    entries = proof['actual_apk_model_entries' if platform == 'android' else 'actual_installed_model_entries']
    assert {e['sha256']: e['bytes'] for e in entries} == models
    assert proof['apk_sha256' if platform == 'android' else 'runner_sha256'] in binaries
    platforms[platform] = {'matrix_cases': 62, 'movies_including_lifecycle_retry': 61,
                           'fully_decoded_frames': 17491, 'native_preview_pairs': 184,
                           'binary_sha256': next(iter(binaries)), 'source_fingerprint': next(iter(fingerprints)),
                           'cohorts': cohorts}
assert len(rows) == 124
out = {'status': 'complete_both_platforms_62_each', 'compiled_source_commit': commit,
       'created_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
       'scope': 'API, media, preview timing, gallery, lifecycle and manual semantics on the frozen eight-source matrix. Raw automatic face statuses are retained. Does not assert every pixel in every possible media file or iPhone hardware performance.',
       'platforms': platforms, 'cases': rows}
(base / 'acceptance-summary.json').write_text(json.dumps(out, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: {n: v for n, v in p.items() if n != 'cohorts'} for k, p in platforms.items()}, indent=2))
