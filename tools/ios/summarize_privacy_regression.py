#!/usr/bin/env python3
"""Bind completed simulator runs, independent audits and explicit visual reviews."""
import argparse, datetime, hashlib, json, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
REPORT = ROOT / 'reports/privacy-ios-22-and-4k-regression-2026-11-05'

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1048576),b''): digest.update(chunk)
    return digest.hexdigest()

def load(path):
    return json.loads(path.read_text())

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--legacy',default='complete_22')
    parser.add_argument('--fourk',default='complete_4k_v2')
    parser.add_argument('--preflight',default='complete_preflight_v3')
    args=parser.parse_args()
    errors=[];runs=[];bindings=[]
    reviews=load(REPORT/'visual-reviews.json') if (REPORT/'visual-reviews.json').exists() else {'cases':[]}
    reviewed={(r['run_id'],r['case']):r for r in reviews['cases']}
    current={p.name:sha(p) for p in (ROOT/'mobile/packages/dance_native/ios/dance_native/Sources/dance_native').glob('*.swift')}
    harness=sha(ROOT/'mobile/app/lib/ios_privacy_regression_20261104_main.dart')
    for run_id,count in [(args.legacy,22),(args.fourk,27),(args.preflight,13)]:
        base=REPORT/'artifacts'/run_id
        data=load(base/'results.json');meta=load(base/'run-metadata.json')
        media=load(REPORT/'media-audits'/run_id/'results.json')
        faces=load(REPORT/'face-audits'/run_id/'results.json')
        if data['status']!='finished' or len(data['cases'])!=count: errors.append(run_id+': incomplete matrix')
        if meta['native_sources']!=current or meta['harness_sha256']!=harness: errors.append(run_id+': source binding changed')
        bindings.append((meta['binary_sha256'],meta['harness_sha256'],meta['sources']))
        audits={r['case']:r for r in media['cases']}
        if len(audits)!=count: errors.append(run_id+': incomplete media audit')
        expected_faces={r['case'] for r in data['cases'] if r.get('result')=='output_created' and r.get('select')!='first' and (REPORT/'artifacts/source-faces'/(pathlib.Path(r.get('input','solo-hd-7570258.mp4')).stem+'.jsonl')).exists()}
        if {r['case'] for r in faces['cases']}!=expected_faces:errors.append(run_id+': incomplete face audit')
        movies=[];cases=[]
        for row in data['cases']:
            case=row['case'];a=audits.get(case,{})
            if row['pid']!=data['process_id']: errors.append(run_id+': process changed '+case)
            if not a.get('status','').startswith('passed'): errors.append(run_id+': media '+case)
            if row['result']=='output_created':
                movie=base/'outputs'/(case+'.mp4')
                if a.get('sha256')!=sha(movie): errors.append(run_id+': movie binding '+case)
                movies.append(a)
                v=reviewed.get((run_id,case))
                if not v or v.get('movie_sha256')!=a.get('sha256') or v.get('status')!='passed':
                    errors.append(run_id+': visual review missing '+case)
                else:
                    if not v.get('boards'): errors.append(run_id+': visual boards missing '+case)
                    for board in v.get('boards',[]):
                        path=(REPORT/board['path']).resolve()
                        if not path.is_relative_to(REPORT.resolve()) or not path.is_file() or sha(path)!=board.get('sha256'):
                            errors.append(run_id+': visual board binding '+case)
                if run_id==args.fourk:
                    dims=[3840,2160] if row['input'].startswith('group') else [2160,4096]
                    if a.get('dimensions')!=dims: errors.append(run_id+': 4K size '+case)
            elif row['result']!='cancelled_cleanly':
                errors.append(run_id+': native result '+case)
            cases.append({'case':case,'native_result':row['result'],'media_status':a.get('status'),
              'frames':a.get('decoded_frames',0),'dimensions':a.get('dimensions'),
              'sha256':a.get('sha256'),'elapsed_seconds':row.get('elapsed_seconds'),
              'audio_waveform':a.get('audio_waveform'),'privacy_probes':a.get('privacy_probes',[]),
              'gallery_original_sha_matches':a.get('gallery_original_sha_matches',[])})
        for f in faces['cases']:
            if f['status']=='needs_hero_visual_review':
                v=reviewed.get((run_id,f['case']),{})
                if not v.get('hero_candidates_reviewed'): errors.append(run_id+': hero review '+f['case'])
            elif f['status']!='passed_all_detected_face_cores':
                errors.append(run_id+': face core '+f['case'])
        if run_id in [args.legacy,args.preflight]:
            core=load(base/'core-smokes.json')
            if core['status']!='passed' or len(core['checks'])!=6: errors.append(run_id+': native cores')
        audio=[r['audio_waveform'] for r in movies if r.get('audio_waveform') and r['audio_waveform'].get('alignment_observable',False)]
        runs.append({'run_id':run_id,'status':'checked','process_id':data['process_id'],
          'cases':cases,'case_count':len(cases),'movie_count':len(movies),
          'decoded_frames':sum(r['decoded_frames'] for r in movies),
          'face_core_count':sum(r['tested_visible_face_cores'] for r in faces['cases']),
          'resources':meta.get('resources'),'binary_sha256':meta['binary_sha256'],
          'started_utc':meta['started_utc'],'finished_utc':meta.get('finished_utc'),
          'silent_audio_movies':sum(1 for r in movies if r.get('audio_waveform') and not r['audio_waveform'].get('alignment_observable',False)),
          'minimum_audio_correlation':min((a['correlation'] for a in audio),default=None),
          'maximum_audio_absolute_lag_ms':max((abs(a['lag_ms']) for a in audio),default=None)})
    if any(b!=bindings[0] for b in bindings):errors.append('Run binary/harness/source mismatch')
    legacy=REPORT/'artifacts'/args.legacy
    life=load(legacy/'qa_lifecycle_results.json');negative=load(legacy/'qa_negative_results.json')
    if not (life.get('cancel_passed') and life.get('retry_passed') and life.get('custom_sticker_exists')):
        errors.append('API lifecycle regression')
    if not all(negative.get(k) for k in ['flower_no_person','corrupt_rejected','valid_reimport']):
        errors.append('Negative/reimport regression')
    gates=[
      ('complete-tracker-acceptance.log','IOS_TRACKER_HOST_REGRESSION=PASS'),
      ('complete-highres-acceptance.log','TILE_REPROJECTION_DEDUP_OCCLUSION=PASS'),
      ('complete-golden-acceptance.log','CASE_PASS selected_identity_long_gap_fails_closed'),
      ('flutter-test.log','+277: All tests passed!')]
    for log,marker in gates:
        if marker not in (REPORT/'artifacts/logs'/log).read_text():errors.append('Host gate: '+log)
    known=load(REPORT/'known-observations.json')['observations'] if (REPORT/'known-observations.json').exists() else []
    summary={'status':('passed_with_observations' if known else 'passed') if not errors else 'needs_attention',
      'known_observations':known,
      'generated_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'errors':errors,
      'primary_cases':sum(r['case_count'] for r in runs[:2]),
      'primary_movies':sum(r['movie_count'] for r in runs[:2]),
      'primary_decoded_frames':sum(r['decoded_frames'] for r in runs[:2]),
      'runs':runs,'lifecycle':life,'negative':negative,
      'limits':['iOS 17.0.1 simulator on Apple M4; no physical iPhone encoder/performance claim',
        'Native QA API journey; picker gestures and permission dialogs are not UI automated',
        'Source Vision misses are supplemented by known-point and visual checks, not a proof for arbitrary footage',
        'quality/balanced/speed requests run the current XNNPACK export backend']}
    (REPORT/'test-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps({k:summary[k] for k in ['status','primary_cases','primary_movies','primary_decoded_frames','errors']},indent=2))
    return 0 if not errors else 1

if __name__=='__main__':raise SystemExit(main())
