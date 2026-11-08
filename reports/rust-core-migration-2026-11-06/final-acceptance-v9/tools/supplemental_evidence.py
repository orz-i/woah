"""Validate locally retained supplemental source/output reviews; never infer decisions."""
import hashlib
import json
from pathlib import Path

def read(p):
    return json.loads(p.read_bytes())

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def validate_supplemental(base, commit):
    base = Path(base)
    run = read(base / 'original4k/results.json')
    files = read(base / 'original4k/archived-file-sha256.json')['outputs']
    expected_effects = {v['case'] for v in run['cases'] if v['input'] == 'solo-portrait-7570258.mp4' and v['expected_frames'] == 662 and v['start_ms'] == 0 and v['fill'] != 'solid'}
    expected_risk = {v['case'] for v in run['cases'] if v['case'].startswith('group_4k_') and v['expected_frames'] == 581 and v['start_ms'] == 0}
    assert len(expected_effects) == 11 and len(expected_risk) == 12
    folder = base / 'known-effect-review'
    index = read(folder / 'index.json')
    review = read(folder / 'manual-review.json')
    assert index['compiled_source_commit'] == commit
    assert index['run_id'] == run['run_id'] if 'run_id' in run else index['run_id'].endswith('_original4k')
    assert review['index_sha256'] == sha(folder / 'index.json')
    assert len(index['items']) == len(review['decisions']) == 88 and len(index['pages']) == 11
    assert {v['case'] for v in index['items']} == expected_effects
    assert {v['index'] for v in index['items']} == set(range(88))
    for case in expected_effects:
        assert {v['source_frame'] for v in index['items'] if v['case'] == case} == set(range(556, 564))
    for item, decision in zip(index['items'], review['decisions']):
        assert item['index'] == decision['index'] and item['case'] == decision['case'] and item['source_frame'] == decision['source_frame']
        assert item['movie_sha256'] == files[item['case'] + '.mp4']
        assert decision['decision'] == 'protected_features_covered'
    for page in index['pages']:
        assert Path(page['file']).name == page['file'] and sha(folder / page['file']) == page['sha256']
    safe = {'known_non_solid_pairs': 88, 'known_non_solid_modes': 11, 'known_effect_index_sha256': sha(folder / 'index.json'), 'known_effect_review_sha256': sha(folder / 'manual-review.json')}
    folder = base / 'continuous-risk-review'
    assert {v.name for v in folder.iterdir() if v.is_dir()} == expected_risk
    safe['continuous_risk_pairs'] = 0
    safe['continuous_risk_modes'] = len(expected_risk)
    safe['continuous_risk_review_hashes'] = {}
    expected_spans = [('overlapping_neighbor',112,123),('clipped_top',176,216),('profile_transition',94,110),('early_chin_margin',179,187),('late_chin_margin',513,518),('end_chin_margin',575,580)]
    expected_pairs = [(name,frame) for name,first,last in expected_spans for frame in range(first,last+1)]
    expected_frames = [frame for _,frame in expected_pairs]
    for case in sorted(expected_risk):
        f = folder / case
        index = read(f / 'index.json')
        review = read(f / 'manual-review.json')
        assert index['compiled_source_commit'] == commit and index['case'] == case
        assert index['movie_sha256'] == files[case + '.mp4']
        assert review['index_sha256'] == sha(f / 'index.json')
        assert [(v['span'],v['source_frame']) for v in index['items']] == expected_pairs
        assert [(v['span'],v['source_frame']) for v in review['decisions']] == expected_pairs
        assert len(index['pages']) == 12
        assert all(v['decision'] == 'protected_features_covered' for v in review['decisions'])
        for page in index['pages']:
            assert Path(page['file']).name == page['file'] and sha(f / page['file']) == page['sha256']
        safe['continuous_risk_review_hashes'][case] = {'index_sha256': sha(f / 'index.json'), 'manual_review_sha256': sha(f / 'manual-review.json')}
        safe['continuous_risk_pairs'] += len(expected_frames)
    assert safe['continuous_risk_pairs'] == 1092
    folder = base / 'native-edge-preview-review'
    index = read(folder / 'index.json')
    review = read(folder / 'manual-review.json')
    assert index['compiled_source_commit'] == commit and index['case'] == 'group_4k_face_solid'
    assert index['results_sha256'] == sha(base / 'original4k/results.json')
    assert review['index_sha256'] == sha(folder / 'index.json')
    assert [v['requested_ms'] for v in index['items']] == [7400, 8120]
    assert [v['source_frame'] for v in index['items']] == [185, 203]
    assert len(review['decisions']) == 2
    archived = read(base / 'original4k/archived-file-sha256.json')['previews']
    for item, decision in zip(index['items'], review['decisions']):
        assert item['control_sha256'] == archived[item['control_file']]
        assert item['protected_sha256'] == archived[item['protected_file']]
        assert Path(item['page']).name == item['page'] and sha(folder / item['page']) == item['page_sha256']
        assert decision['requested_ms'] == item['requested_ms'] and decision['decision'] == 'protected_features_covered'
    safe['enlarged_native_preview_pairs'] = 2
    safe['enlarged_native_preview_index_sha256'] = sha(folder / 'index.json')
    safe['enlarged_native_preview_review_sha256'] = sha(folder / 'manual-review.json')
    return safe
