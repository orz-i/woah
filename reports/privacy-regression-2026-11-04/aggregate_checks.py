import json
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

root = Path(__file__).resolve().parent
def read(name):
    return json.loads((root/name).read_text(encoding='utf-8'))

jvm = []
for p in (root/'jvm-results').glob('TEST-*.xml'):
    suite = ET.parse(p).getroot()
    for case in suite.findall('testcase'):
        jvm.append({'case': suite.get('name')+'#'+case.get('name'),
                    'passed': case.find('failure') is None and case.find('error') is None
                    and case.find('skipped') is None})
if not jvm:
    jvm = read('jvm-test-results.json')['cases']
assert len(jvm) == 555 and all(c['passed'] for c in jvm)
flutter = (root/'flutter-tests.txt').read_text(encoding='utf-8')
assert '+272: All tests passed!' in flutter
device = read('final-device-results.json')
assert len(device) == 40 and all(r['result'] == 'passed' for r in device)
baseline = json.loads((root.parent/'privacy-fixes-2026-11-04/test-summary.json').read_text(encoding='utf-8'))
resolved = [r['case'] for r in baseline['device']['failed_cases']]
assert len(resolved) == 8 and set(resolved) <= {r['case'] for r in device}
matrix = read('network-case-results.json')
assert len(matrix) == 19 and all(r['result'] == 'passed' for r in matrix)
for name in ('group_full_balanced', 'group_full_speed', 'group_full_length_follow'):
    result = read(name+'.json')
    assert result['state'] == 'completed' and result['expected_frames'] == result['actual_frames']
    matrix.append({'case': name, 'result': 'passed', 'expected_frames': result['expected_frames']})
media = read('media-inspection.json')
assert len(media) == 24 and all(r['strictly_increasing_decoded_pts'] and r['black_frames'] == 0
                             and r['green_frames'] == 0 and r['atoms']['fast_start_ready'] for r in media)
ui = read('ui-lifecycle.json')
core = [r for r in ui if not r['tag'].startswith('interrupted-')]
assert len({r['pid'] for r in core}) == 1
jobs = read('export-jobs.json')
states = Counter(j['state'] for j in jobs)
assert states == {'completed': 2, 'cancelled': 1, 'interrupted': 1}, states
assert all(j['outputUri'] is None for j in jobs if j['state'] in ('cancelled', 'interrupted'))
interrupted = read('interruption-after.json')
assert interrupted['state'] == 'interrupted' and interrupted['errorCode'] == 'EXPORT_INTERRUPTED'
assert interrupted['outputUri'] is None
assert read('lifecycle-final.json')['state'] == 'passed'
assert all(r['result'] == 'passed' and r['no_new_export_job'] for r in read('ui-negative-results.json'))
assert len(read('ui-negative-results.json')) == 2
assert len(read('ui-auxiliary-results.json')) == 2
assert all(r['result'] == 'passed' for r in read('ui-auxiliary-results.json'))
assert 'isForeground=true' in (root/'final-ui-background-service.txt').read_text(encoding='utf-8')
holes = read('privacy-mask-hole-audit.json')
assert len(holes) == 4 and sum(r['frames'] for r in holes) == 773
assert all(r['frames_with_holes'] == 0 for r in holes)
media_hashes = {r['file']: r['sha256'] for r in media}
assert all(r['sha256'] == media_hashes[r['file']] for r in holes)
metrics = (root/'device-performance-metrics.txt').read_text(encoding='utf-8')
p95 = float(re.findall(r'detector_p95_ms=([0-9.]+)', metrics)[-1])
assert p95 <= 40
summary = {
    'flutter': {'total': 272, 'passed': 272},
    'android_jvm': {'total': len(jvm), 'passed': len(jvm), 'failed': []},
    'device': {'total': len(device), 'results': dict(Counter(r['result'] for r in device)),
               'resolved_previous_failures': resolved, 'new_failures': []},
    'production_stress': {'frames': 300, 'detector_p95_ms': p95, 'budget_ms': 40},
    'real_media': {'total': len(matrix), 'passed': len(matrix), 'cases': matrix},
    'media_inspection': {'files': len(media), 'decoded_frames': sum(r['decoded_frames'] for r in media),
                        'black_frames': 0, 'green_frames': 0, 'all_fast_start': True},
    'ui_continuous_process_pid': core[0]['pid'], 'ui_job_states': dict(states),
    'ui_negative_checks': read('ui-negative-results.json'),
    'ui_auxiliary_checks': read('ui-auxiliary-results.json'),
    'face_mask_interior_holes': holes,
    'apk_sha256': read('environment.json')['apk_sha256'],
}
(root/'test-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in summary.items() if k not in ('real_media','face_mask_interior_holes','device')}, ensure_ascii=False))
