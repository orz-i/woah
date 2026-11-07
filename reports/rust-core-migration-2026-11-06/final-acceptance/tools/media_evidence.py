"""Validate actual movie results, cancellation and explicitly reviewed topology alerts."""
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(Path(path).read_bytes())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_media_reviews(media, api, metadata, suite, manual_dir, media_path):
    by_api = {c['case']: c for c in api['cases']}
    cancelled = {c['case'] for c in api['cases'] if c['result'] == 'cancelled_cleanly'}
    expected_movies = set(by_api) - cancelled
    if suite == 'legacy22':
        expected_movies.add('retry_custom_sticker')
    assert len({c['case'] for c in media['cases']}) == len(media['cases'])
    assert {c['case'] for c in media['cases']} == expected_movies | cancelled
    assert {c['case'] for c in media['cases'] if c['status'] == 'passed_cancellation'} == cancelled
    for name in cancelled:
        c = by_api[name]
        assert c['state'] == 'cancelled' and c['file_bytes'] == 0 and c['partial_files'] == []
    movies = [c for c in media['cases'] if c['case'] in expected_movies]
    topology = []
    for c in movies:
        assert c['status'] in ['passed_media', 'needs_topology_review'] and not c.get('error')
        assert c['decoded_frames'] == c['expected_frames'] > 0
        assert c['max_pts_delta_seconds'] <= .002 and c['black_frames'] == c['green_frames'] == 0
        assert c['faststart']['moov_before_mdat'] and all(p['passed'] for p in c['privacy_probes'])
        if c['case'] in by_api:
            assert c['decoded_frames'] == by_api[c['case']]['expected_frames']
        if c['status'] == 'needs_topology_review':
            assert c['topology_candidates']
        for candidate in c['topology_candidates']:
            topology.append((c, candidate))
    if topology:
        review = read(manual_dir / 'manual-topology-review.json')
        assert review['raw_media_audit_sha256'] == sha(media_path) and review['raw_status_unchanged']
        assert len(review['decisions']) == len(topology)
        assert {v['index'] for v in review['decisions']} == set(range(len(topology)))
        for decision, (case, candidate) in zip(sorted(review['decisions'], key=lambda v: v['index']), topology):
            assert decision['case'] == case['case'] and decision['candidate'] == candidate
            assert decision['raw_case_sha256'] == hashlib.sha256(json.dumps(case, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            assert decision['movie_sha256'] == case['sha256']
            assert decision['source_sha256'] == metadata['sources'][by_api[case['case']]['input']]['sha256']
            assert decision['decision'] == 'mask_boundary_seam_without_identifying_features'
            assert len(decision['images']) >= 2
            for filename, digest in decision['images'].items():
                assert Path(filename).name == filename and sha(manual_dir / filename) == digest
    return movies, len(topology)
