import argparse,datetime,hashlib,json,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('report',type=Path);p.add_argument('destination',type=Path);p.add_argument('--platform',required=True,choices=['android','ios']);p.add_argument('--prefix',default='shared_policy_final_v2');p.add_argument('--suite',required=True,choices=['legacy22','original4k','focused13']);a=p.parse_args()
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def copy(p,d):d.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,d)
matrix=read(a.root/'testdata/privacy/matrix.json');suite=next(s for s in matrix['suites'] if s['id']==a.suite);rid=a.prefix+'_'+a.suite;run=a.report/'artifacts'/rid;api=read(run/'results.json');meta=read(run/'run-metadata.json');build=read(run/'build-manifest.json');dest=a.destination/a.suite
assert api['status']=='finished' and meta.get('finished_utc')
assert api['platform']==a.platform and api['compiled_source_commit']=='ce25a0d38a3c2c34e0d65b3967c0b32e7e7b1e10'
assert api['compiled_source_commit']==build['source_commit'] and api['compiled_source_fingerprint']==build['source_fingerprint']
assert meta['binary_sha256']==build['binary_sha256']
assert meta['config']['cases']==suite['cases'] and not meta['config']['partial_stage_check'] and not meta['config']['stage_export_only']
assert [c['case'] for c in api['cases']]==[c['case'] for c in suite['cases']]
for name,v in matrix['materials'].items():assert meta['sources'][name]['sha256']==v['sha256']
for c,expected in zip(api['cases'],suite['cases']):
 assert c['start_ms']==expected['startMs'] and c['end_ms']==expected['endMs'] and c['expected_frames']==expected['expected_frames']
 assert c['pid']==api['process_id'] and c['result'] in ['output_created','cancelled_cleanly']
 if c['result']=='cancelled_cleanly':
  assert c['before_cancel']['current_frame']>=expected['cancelAtFrame'] and c['state']=='cancelled'
  assert c['file_bytes']==0 and c['partial_files']==[] and not (run/'outputs'/(c['case']+'.mp4')).exists()
 if a.suite=='original4k' and c['case']=='solo_4k_cancel':assert c['before_cancel']['current_frame']>=25
media=read(a.report/'media-audits'/rid/'results.json');faces=read(a.report/'face-audits'/rid/'results.json');previews=read(a.report/'preview-audits'/rid/'results.json');gallery=read(a.report/'gallery-audits'/rid/'results.json');life=read(a.report/'lifecycle-audits'/rid/'results.json')
assert all(c['status']=='passed_media' for c in media['cases']) and all(c['decoded_frames']==c['expected_frames'] for c in media['cases'])
assert all(c['status']=='passed_preview_time' for c in previews['cases'])
assert all(c['status'].startswith('passed') or (c['status'] in ['failed_face_cores','needs_hero_visual_review'] and c.get('uncovered_face_cores')) for c in faces['cases'])
assert all(c['status'].startswith('passed') for c in gallery['cases'])
assert life['status']==('passed_api_chain' if a.suite=='legacy22' else 'not_requested')
boards=a.report/'manual-review'/rid;board_index=read(boards/'review-index.json');board_review=read(boards/'manual-board-review.json');assert board_review['review_index_sha256']==sha(boards/'review-index.json')
assert len(board_index['cases'])==len(api['cases'])+(1 if a.suite=='legacy22' else 0)
assert {c['case'] for c in board_review['cases']}=={c['case'] for c in board_index['cases']}
for c in board_index['cases']:assert sha(boards/c['board'])==c['board_sha256'] and sha(run/'outputs'/(c['case']+'.mp4'))==c['movie_sha256'] if c['movie_sha256'] else sha(boards/c['board'])==c['board_sha256']
candidates=a.report/'manual-candidates'/rid;candidate_index=read(candidates/'candidate-index.json');alerts=[v for c in faces['cases'] for v in c.get('uncovered_face_cores',[])];assert len(alerts)==len(candidate_index['cases'])
if alerts:
 review=read(candidates/'manual-candidate-review.json');assert review['candidate_index_sha256']==sha(candidates/'candidate-index.json') and review['raw_status_unchanged']
 assert len(review['decisions'])==len(alerts) and {v['index'] for v in review['decisions']}==set(range(len(alerts)))
 allowed={'intentionally_exempt_hero6_teal','protected_features_covered_bbox_includes_hair_neck','protected_features_covered_bbox_includes_hair_ear_arm_neck','source_detector_non_face_candidate'}
 assert all(v['decision'] in allowed for v in review['decisions'])
 by_case={c['case']:c for c in api['cases']}
 for decision,candidate in zip(sorted(review['decisions'],key=lambda v:v['index']),candidate_index['cases']):
  assert decision['case']==candidate['case'] and decision['source_frame']==candidate['raw_alert']['source_frame']
  if decision['decision']=='intentionally_exempt_hero6_teal':assert by_case[decision['case']]['hero_id']==6
for page in candidate_index['pages']:assert sha(candidates/page['file'])==page['sha256']
preview_alerts=[v for c in previews['cases'] for row in c['previews'] for v in row.get('uncovered_face_candidates',[])]
if preview_alerts:
 review=read(boards/'manual-preview-candidate-review.json');assert review['raw_preview_audit_sha256']==sha(a.report/'preview-audits'/rid/'results.json') and len(review['decisions'])==len(preview_alerts)
 assert all(v['decision'] in allowed for v in review['decisions'])
if a.suite!='legacy22':
 known=read(run/'known-face-review/results.json');assert known['status']=='passed' and known['probes'];copy(run/'known-face-review/results.json',dest/'known-face-probes.json')
 for f in (run/'known-face-review').glob('*.jpg'):copy(f,dest/'known-face-review'/f.name)
for name in ['results.json','run-metadata.json','build-manifest.json','qa_lifecycle_results.json','qa_negative_results.json']:
 if (run/name).exists():copy(run/name,dest/name)
for kind in ['media','face','preview','gallery','lifecycle']:copy(a.report/(kind+'-audits')/rid/'results.json',dest/(kind+'-audit.json'))
for folder,label in [(boards,'manual-review'),(candidates,'manual-candidates')]:
 for f in folder.iterdir():
  if f.is_file():copy(f,dest/label/f.name)
files={}
for directory in ['outputs','previews','crop-traces','gallery']:
 f=run/directory
 if f.exists():files[directory]={q.name:sha(q) for q in sorted(f.iterdir()) if q.is_file()}
(dest/'archived-file-sha256.json').write_text(json.dumps(files,indent=2)+'\n',encoding='utf-8')
summary={'platform':a.platform,'suite':a.suite,'run_id':rid,'compiled_source_commit':api['compiled_source_commit'],'compiled_source_fingerprint':api['compiled_source_fingerprint'],'binary_sha256':meta['binary_sha256'],'process_id':api['process_id'],'api_cases':len(api['cases']),'actual_movies':len(media['cases']),'decoded_frames':sum(c['decoded_frames'] for c in media['cases']),'native_preview_pairs':sum(len(c['previews']) for c in previews['cases']),'manually_resolved_export_alerts':len(alerts),'manually_resolved_preview_alerts':len(preview_alerts),'reviewed_case_boards':len(board_index['cases']),'status':'cohort_complete_independently_audited_and_reviewed','scope':'This cohort only. Overall62 acceptance requires all three cohorts from both platforms. Raw automatic face statuses are retained; separate manual semantic decisions are explicit.','collected_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(dest/'cohort-summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8');print(json.dumps(summary))
