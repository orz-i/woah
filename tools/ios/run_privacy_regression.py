#!/usr/bin/env python3
"""Run the simulator-only real-media harness, preserving each run and its binary."""
import argparse, datetime, hashlib, json, os, pathlib, plistlib, re, shutil, subprocess, time

ROOT = pathlib.Path(__file__).resolve().parents[2]
REPORT = ROOT / 'reports/privacy-ios-22-and-4k-regression-2026-11-05'
SOURCES = ROOT / 'reports/privacy-ios-regression-2026-11-04/artifacts/sources'
BUNDLE = 'art.gaoge.dance'
ENV = dict(os.environ, DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer')

def command(*args):
    return subprocess.run(args, env=ENV, text=True, capture_output=True, check=True).stdout.strip()

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1048576), b''): h.update(chunk)
    return h.hexdigest()

def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, indent=2) + '\n')
    temp.replace(path)

def main():
    global REPORT, SOURCES
    parser = argparse.ArgumentParser()
    parser.add_argument('config', type=pathlib.Path)
    parser.add_argument('--udid', default='EE228BE6-0F7F-48E1-9892-313B7CFFBB63')
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--app', type=pathlib.Path, default=ROOT/'mobile/app/build/ios/iphonesimulator/Runner.app')
    parser.add_argument('--optimized-native', action='store_true')
    parser.add_argument('--report-root', type=pathlib.Path, default=REPORT)
    parser.add_argument('--sources', type=pathlib.Path, default=SOURCES)
    parser.add_argument('--deadline-hours', type=float, default=5)
    args = parser.parse_args()
    REPORT, SOURCES = args.report_root.resolve(), args.sources.resolve()
    config = json.loads(args.config.read_text())
    run_id = config['run_id']
    if not run_id.replace('_', '').replace('-', '').isalnum(): raise ValueError('Invalid run ID')
    dest = REPORT / 'artifacts' / run_id
    dest.mkdir(parents=True, exist_ok=True)
    app = args.app.resolve()
    # No inference trace or raw RGB dumps: long 4K exports must stay bounded.
    subprocess.run(['xcrun','simctl','terminate',args.udid,BUNDLE],env=ENV,capture_output=True)
    if args.install: command('xcrun','simctl','install',args.udid,str(app))
    container = pathlib.Path(command('xcrun','simctl','get_app_container',args.udid,BUNDLE,'data'))
    docs = container / 'Documents'
    live = docs / 'qa_ios_runs' / run_id
    if live.exists(): raise FileExistsError(f'Preserve old run, choose a new run_id: {live}')
    if any(c.get('saveToGallery') for c in config.get('cases',[])):
        with (app/'Info.plist').open('rb') as f: info=plistlib.load(f)
        if not info.get('NSPhotoLibraryAddUsageDescription'): raise ValueError('Missing production Photos add usage description')
        command('xcrun','simctl','privacy',args.udid,'grant','photos-add',BUNDLE)
    source_dest = docs / 'qa_sources'
    source_dest.mkdir(parents=True, exist_ok=True)
    sources = {}
    for path in sorted(SOURCES.iterdir()):
        if not path.is_file(): continue
        sources[path.name] = {'sha256': sha(path), 'bytes':path.stat().st_size}
        target = source_dest / path.name
        if not target.exists(): shutil.copyfile(path,target)
        if sha(target) != sources[path.name]['sha256']: raise ValueError(f'Simulator source mismatch: {path.name}')
    atomic(docs / 'qa_ios_cases.json', config)
    (docs / 'qa_mode.txt').write_text('configured')
    production = ROOT / 'mobile/packages/dance_native/ios/dance_native/Sources/dance_native'
    meta = {
        'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'udid':args.udid, 'config':config, 'sources':sources,
        'git_head':command('git','rev-parse','HEAD'),
        'native_sources':{p.name:sha(p) for p in sorted(production.glob('*.swift'))},
        'harness_sha256':sha(ROOT / 'mobile/app/lib/ios_privacy_regression_20261104_main.dart'),
        'binary_sha256':sha(app / 'Runner'),
        'native_binary_images':{p.name:sha(p) for p in [app/'Runner',app/'Runner.debug.dylib'] if p.exists()},
        'app_bundle':str(app),
        'container':str(container),
        'native_optimization':'-O; Flutter debug simulator' if args.optimized_native else '-Onone; Flutter debug simulator',
    }
    atomic(dest / 'run-metadata.json',meta)
    (dest / 'outputs').mkdir(exist_ok=True)
    results = live / 'results.json'
    crop_started = time.time()
    launch_env = dict(ENV, SIMCTL_CHILD_WOAH_PIXEL_CROP_AUDIT='1')
    with (dest / 'console.log').open('w') as log:
        process = subprocess.Popen(['xcrun','simctl','launch','--terminate-running-process','--console',
            args.udid,BUNDLE],env=launch_env,stdout=log,stderr=subprocess.STDOUT)
        last = None
        resources = []
        deadline = time.monotonic() + args.deadline_hours*3600
        while time.monotonic() < deadline:
            if results.exists():
                try: data = json.loads(results.read_text())
                except (ValueError,OSError): data = None
                if data:
                    atomic(dest / 'results.json',data)
                    sample={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'completed_cases':len(data['cases']), 'disk_free_bytes':shutil.disk_usage(ROOT).free}
                    swap=subprocess.run(['sysctl','vm.swapusage'],text=True,capture_output=True).stdout
                    for key,value in re.findall(r'(total|used|free) = ([0-9.]+)M',swap):
                        sample['system_swap_'+key+'_bytes']=round(float(value)*1024*1024)
                    app_pid=data.get('process_id')
                    if app_pid:
                        measured=subprocess.run(['ps','-p',str(app_pid),'-o','rss=,%cpu='],text=True,capture_output=True).stdout.split()
                        if len(measured)==2: sample.update(pid=app_pid,rss_kib=int(measured[0]),cpu_percent=float(measured[1]))
                    journal=container/'Library/Application Support/WoahPrivacyExportJobs'
                    try:
                        latest=max(journal.glob('*.json'),key=lambda p:p.stat().st_mtime)
                        state=json.loads(latest.read_text())
                        sample['latest_job']={k:state.get(k) for k in ['jobId','state','currentFrame','totalFrames','fps']}
                    except (OSError,ValueError): pass
                    resources.append(sample)
                    with (dest/'resource-samples.jsonl').open('a') as f: f.write(json.dumps(sample)+'\n')
                    summary = (len(data['cases']),data['status'])
                    if summary != last:
                        print(datetime.datetime.now().isoformat(),run_id,summary,flush=True); last=summary
                    for row in data['cases']:
                        name=row['case']+'.mp4'; source=live/'outputs'/name; target=dest/'outputs'/name
                        if source.exists() and not target.exists():
                            try: os.link(source,target)
                            except OSError: shutil.copyfile(source,target)
                    if data['status']=='finished':
                        preview_source=live/'previews'
                        if preview_source.exists():shutil.copytree(preview_source,dest/'previews',dirs_exist_ok=True)
                        preview_files={}
                        for row in data['cases']:
                            for preview in row.get('previews',[]):
                                image=dest/'previews'/preview['file']
                                if not image.is_file() or image.stat().st_size!=preview['bytes']:
                                    raise RuntimeError('Missing or incomplete archived preview: '+preview['file'])
                                preview_files[preview['file']]=sha(image)
                                if preview.get('control_file'):
                                    control=dest/'previews'/preview['control_file']
                                    if not control.is_file() or control.stat().st_size!=preview['control_bytes']:
                                        raise RuntimeError('Missing or incomplete native preview control')
                                    preview_files[preview['control_file']]=sha(control)
                        meta['preview_files_sha256']=preview_files
                        for name in ['qa_lifecycle_results.json','qa_negative_results.json']:
                            if config.get('lifecycle') and (docs/name).exists(): shutil.copyfile(docs/name,dest/name)
                        if (live/'core-smokes.json').exists(): shutil.copyfile(live/'core-smokes.json',dest/'core-smokes.json')
                        traces = sorted((p for p in docs.glob('qa_pixel_crops_*.jsonl') if p.stat().st_mtime >= crop_started
                          and json.loads(p.open().readline()).get('source_basename') in sources), key=lambda p:p.stat().st_birthtime)
                        (dest/'crop-traces').mkdir(exist_ok=True)
                        for trace in traces: shutil.copyfile(trace,dest/'crop-traces'/trace.name)
                        meta['crop_traces']=[p.name for p in traces]
                        meta['resources']={'samples':len(resources),'peak_rss_kib':max((s.get('rss_kib',0) for s in resources),default=0),'minimum_free_disk_bytes':min((s['disk_free_bytes'] for s in resources),default=0),'peak_system_swap_used_bytes':max((s.get('system_swap_used_bytes',0) for s in resources),default=0)}
                        meta['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
                        atomic(dest/'run-metadata.json',meta)
                        if data.get('core_smoke_failure'):
                            raise RuntimeError('Native core smoke failed; preserve this run and inspect core-smokes.json')
                        expected_count=22 if config.get('legacy_22') else len(config.get('cases',[]))
                        if len(data['cases'])!=expected_count:
                            raise RuntimeError(f'Incomplete matrix: {len(data["cases"])} != {expected_count}')
                        if any(r['result'] not in ['output_created','cancelled_cleanly'] for r in data['cases']):
                            raise RuntimeError('Native matrix contains failures; preserve results for diagnosis')
                        print('QA_RUN_FINISHED',run_id,flush=True)
                        return 0
            if process.poll() is not None: raise RuntimeError(f'Simulator runner exited: {process.returncode}; see {dest}/console.log')
            time.sleep(5)
        raise TimeoutError('Run deadline exceeded; simulator evidence preserved')

if __name__ == '__main__': raise SystemExit(main())
