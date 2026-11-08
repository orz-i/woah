import argparse,datetime,hashlib,json,subprocess,sys,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('report',type=Path);p.add_argument('prefix');p.add_argument('media',type=Path);p.add_argument('faces',type=Path);p.add_argument('runner_log',type=Path);p.add_argument('helpers',type=Path);p.add_argument('--suites',nargs='+',choices=['legacy22','original4k','focused13'],default=['legacy22','original4k','focused13']);a=p.parse_args();assert len(a.suites)==len(set(a.suites))
a.report.mkdir(parents=True,exist_ok=True)
state={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Independent audits and review-board generation only. Raw audit results are retained. This process does not accept manual candidates or assert final regression success.','cohorts':[]}
status_path=a.report/'independent-audit-progress.json'
def save():
 tmp=status_path.with_suffix('.tmp');tmp.write_text(json.dumps(state,indent=2)+'\n',encoding='utf-8');tmp.replace(status_path)
def run(argv,log):
 print('AUDIT_COMMAND',argv,flush=True)
 with log.open('w',encoding='utf-8') as f:r=subprocess.run([str(v) for v in argv],cwd=a.root,stdout=f,stderr=subprocess.STDOUT)
 return r.returncode
save();deadline=time.monotonic()+16.5*3600
for suite,count in [('legacy22',22),('original4k',27),('focused13',13)]:
 if suite not in a.suites:continue
 rid=a.prefix+'_'+suite;folder=a.report/'artifacts'/rid
 while True:
  text=a.runner_log.read_text(encoding='utf-8',errors='replace') if a.runner_log.exists() else ''
  if 'QA_SUITE_FINISHED '+rid in text:break
  if 'QA_SUITE_FAILED '+rid in text:break
  if time.monotonic()>deadline:raise TimeoutError('Audit wait deadline exceeded')
  time.sleep(15)
 entry={'run_id':rid};state['cohorts'].append(entry)
 if 'QA_SUITE_FAILED '+rid in text:
  entry['status']='runner_failed_not_accepted';save();continue
 results=json.loads((folder/'results.json').read_text(encoding='utf-8'));meta=json.loads((folder/'run-metadata.json').read_text(encoding='utf-8'))
 assert results['status']=='finished' and len(results['cases'])==count and meta.get('finished_utc')
 assert not meta['config']['partial_stage_check'] and not meta['config']['stage_export_only']
 assert results['compiled_source_commit']=='14f95fe1cedcf638e9c59855a378bcd83623aa76'
 entry['status']='auditing';entry['api_cases']=count;entry['source_commit']=results['compiled_source_commit'];save()
 entry['audit_exit']=run([sys.executable,a.root/'tools/privacy/audit_regression.py',rid,'--report',a.report,'--media',a.media,'--source-faces',a.faces],a.report/(rid+'-audit.log'))
 if suite!='legacy22':entry['known_face_exit']=run([sys.executable,a.root/'tools/privacy/audit_known_faces.py',folder],a.report/(rid+'-known-faces.log'))
 entry['boards_exit']=run([sys.executable,a.helpers/'review-focused.py',folder,a.report/'manual-review'/rid],a.report/(rid+'-boards.log'))
 entry['candidates_exit']=run([sys.executable,a.helpers/'review-face-candidates.py',folder,a.report/'face-audits'/rid/'results.json',a.media,a.report/'manual-candidates'/rid],a.report/(rid+'-candidates.log'))
 entry['status']='audits_generated_manual_review_pending';entry['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat();save();print('AUDIT_COHORT_READY',rid,flush=True)
state['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat();save();print('INDEPENDENT_AUDIT_SEQUENCE_FINISHED',flush=True)
