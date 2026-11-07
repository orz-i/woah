"""Combine validated local counts-only platform summaries. Never reads raw face data."""
import argparse, datetime, hashlib, json
from pathlib import Path
p = argparse.ArgumentParser(description=__doc__)
for name in ['android', 'ios', 'destination']:
    p.add_argument(name, type=Path)
a = p.parse_args()
out = {'status': 'complete_both_platforms_62_each', 'created_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'scope': 'Frozen eight-source Android real-device and iOS-simulator regression. Local raw audits and manually reviewed images stay on their originating hosts. Summary hashes bind locally validated counts, movies and review evidence; this is not a claim about every possible media file or iPhone hardware performance.', 'platforms': {}, 'cases': [], 'validated_platform_summary_sha256': {}}
for platform in ['android', 'ios']:
    f = getattr(a, platform)
    d = json.loads(f.read_bytes())
    assert d['status'] == 'complete_' + platform + '_62'
    assert set(d['platforms']) == {platform} and len(d['cases']) == 62
    for key in ['compiled_source_commit', 'source_materials', 'model_bytes']:
        if key in out:
            assert out[key] == d[key]
        else:
            out[key] = d[key]
    assert len(d['source_materials']) == 8 and len(d['model_bytes']) == 2
    v = d['platforms'][platform]
    assert v['matrix_cases'] == 62 and v['movies_including_lifecycle_retry'] == 61
    assert v['fully_decoded_frames'] == 17491 and v['native_preview_pairs'] == 186
    assert v['supplemental_reviews']['known_non_solid_pairs'] == 88
    assert v['supplemental_reviews']['continuous_risk_pairs'] == 636
    assert v['supplemental_reviews']['enlarged_native_preview_pairs'] == 2
    assert all(c['status'] == 'cohort_complete_independently_audited_and_reviewed' for c in v['cohorts'])
    assert all(c['platform'] == platform for c in d['cases'])
    out['platforms'][platform] = v
    out['cases'].extend(d['cases'])
    out['validated_platform_summary_sha256'][platform] = hashlib.sha256(f.read_bytes()).hexdigest()
assert len(out['cases']) == 124
assert {(c['suite'], c['case']) for c in out['cases'] if c['platform'] == 'android'} == {(c['suite'], c['case']) for c in out['cases'] if c['platform'] == 'ios'}
a.destination.parent.mkdir(parents=True, exist_ok=True)
a.destination.write_text(json.dumps(out, indent=2) + '\n', encoding='utf-8')
print('Validated both platform summaries: 124 matrix cases, 34982 decoded frames, 372 native preview pairs.')
