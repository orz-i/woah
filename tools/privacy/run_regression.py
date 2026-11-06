#!/usr/bin/env python3
"""Run the frozen suites through one Dart harness on device and simulator."""
from __future__ import annotations
import argparse
from contextlib import contextmanager, nullcontext
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
try:
    from .build_qa import inputs, fingerprint, binary_hashes, android_package_id
except ImportError:
    from build_qa import inputs, fingerprint, binary_hashes, android_package_id

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / 'testdata/privacy/matrix.json'
ANDROID_PACKAGE = 'art.gaoge.dance.privacyqa'
ANDROID_DOCUMENTS = f'/sdcard/Android/data/{ANDROID_PACKAGE}/files'

def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for part in iter(lambda: stream.read(1048576), b''): h.update(part)
    return h.hexdigest()

def command(*args, required=True):
    result = subprocess.run([str(x) for x in args], text=True, capture_output=True)
    if required and result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr[-1500:]}')
    return result.stdout.strip() if result.returncode == 0 else None

def atomic(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    temporary.replace(path)

def utc(): return dt.datetime.now(dt.timezone.utc).isoformat()


def wait_for_android_process(adb, timeout=30):
    deadline=time.monotonic()+timeout
    while True:
        pid=command(*adb, 'shell', 'pidof', ANDROID_PACKAGE, required=False)
        if pid:return pid
        if time.monotonic()>=deadline:raise TimeoutError('QA process did not start within the launch deadline')
        time.sleep(.2)

class Android:
    def __init__(self, args):
        self.args = args
        if android_package_id(args.binary) != ANDROID_PACKAGE:
            raise RuntimeError('Refuse to install an APK without the isolated privacyqa package ID')
        self.adb = ['adb', '-s', args.serial]
        if command(*self.adb, 'get-state') != 'device': raise RuntimeError('ADB device is not ready')
        if args.install: command(*self.adb, 'install', '-r', args.binary)
        apk = command(*self.adb, 'shell', 'pm', 'path', ANDROID_PACKAGE)
        if not apk or not apk.startswith('package:'): raise RuntimeError('Install the isolated privacyqa APK')
        installed = command(*self.adb, 'shell', 'sha256sum', apk.splitlines()[0][8:]).split()[0]
        if installed.lower() != sha(args.binary): raise RuntimeError('Installed APK differs from the supplied binary')
        self.installed_sha = installed
        command(*self.adb, 'shell', 'mkdir', '-p', ANDROID_DOCUMENTS+'/qa_sources')

    @contextmanager
    def awake(self):
        original=command(*self.adb,'shell','settings','get','global','stay_on_while_plugged_in')
        if original not in ['null','0','1','2','3','4','5','6','7']:
            raise RuntimeError('Cannot snapshot Android wake setting')
        try:
            command(*self.adb,'shell','svc','power','stayon','usb')
            command(*self.adb,'shell','input','keyevent','KEYCODE_WAKEUP')
            command(*self.adb,'shell','wm','dismiss-keyguard')
            command(*self.adb,'shell','cmd','statusbar','collapse')
            policy=command(*self.adb,'shell','dumpsys','window','policy')
            if re.search(r'\bshowing=true\b',policy):
                raise RuntimeError('Android requires manual unlock before foreground-service export tests')
            yield
        finally:
            if original=='null': command(*self.adb,'shell','settings','delete','global','stay_on_while_plugged_in')
            else: command(*self.adb,'shell','settings','put','global','stay_on_while_plugged_in',original)

    def read(self, relative):
        value = command(*self.adb, 'shell', 'cat', ANDROID_DOCUMENTS+'/'+relative, required=False)
        try: return json.loads(value) if value else None
        except ValueError: return None

    def traces(self):
        value = command(*self.adb, 'shell', 'find', ANDROID_DOCUMENTS,
                        '-maxdepth', '1', '-name', 'qa_pixel_crops_*.jsonl')
        return set(value.splitlines())

    def sources(self, materials):
        for name, expected in materials.items():
            path = self.args.media/name
            remote = ANDROID_DOCUMENTS+'/qa_sources/'+name
            existing = command(*self.adb, 'shell', 'sha256sum', remote, required=False)
            if not existing or existing.split()[0] != expected['sha256']:
                command(*self.adb, 'push', path, remote)
            actual = command(*self.adb, 'shell', 'sha256sum', remote).split()[0]
            if actual != expected['sha256']: raise RuntimeError(f'Device fixture hash mismatch: {name}')

    def run(self, config, dest, materials, deadline):
        run_id = config['run_id']; relative = 'qa_ios_runs/'+run_id
        exists = command(*self.adb, 'shell', 'ls', ANDROID_DOCUMENTS+'/'+relative, required=False)
        if exists is not None: raise FileExistsError('Preserve the old device run; choose a new prefix')
        before = self.traces()
        config_file = dest/'configuration.json'; atomic(config_file, config)
        command(*self.adb, 'push', config_file, ANDROID_DOCUMENTS+'/qa_ios_cases.json')
        mode = dest/'qa_mode.txt'; mode.write_text('configured', encoding='utf-8')
        command(*self.adb, 'push', mode, ANDROID_DOCUMENTS+'/qa_mode.txt')
        metadata = {'started_utc':utc(), 'platform':'android', 'serial':self.args.serial,
                    'config':config, 'sources':materials, 'git_head':command('git','rev-parse','HEAD'),
                    'binary_sha256':self.installed_sha, 'binary_path':str(self.args.binary.resolve()),
                    'harness_sha256':sha(ROOT/'mobile/app/lib/ios_privacy_regression_20261104_main.dart'),
                    'matrix_sha256':sha(MATRIX), 'application_id':ANDROID_PACKAGE,
                    'device':{key:command(*self.adb,'shell','getprop',key) for key in
                              ['ro.product.model','ro.build.version.release','ro.product.cpu.abi']}}
        atomic(dest/'run-metadata.json', metadata)
        command(*self.adb, 'shell', 'am', 'force-stop', ANDROID_PACKAGE)
        command(*self.adb, 'shell', 'am', 'start', '-W', '-n', ANDROID_PACKAGE+'/art.gaoge.dance.MainActivity')
        wait_for_android_process(self.adb)
        completed = set(); last = None; advanced = time.monotonic(); data = None
        try:
            while time.monotonic() < deadline:
                data = self.read(relative+'/results.json')
                progress = self.read('qa_current_progress.json')
                if data:
                    if data.get('compiled_source_fingerprint')!=config['build_source_fingerprint']:raise RuntimeError('Runtime/source fingerprint mismatch')
                    atomic(dest/'results.json', data)
                    state = (len(data['cases']), data['status'],
                             (progress or {}).get('case'), (progress or {}).get('current_frame'))
                    if state != last:
                        advanced = time.monotonic(); last = state
                        print(utc(),run_id,state,flush=True)
                    for row in data['cases']:
                        name = row['case']
                        if name in completed: continue
                        if row['result'] == 'output_created':
                            (dest/'outputs').mkdir(exist_ok=True)
                            command(*self.adb,'pull',ANDROID_DOCUMENTS+'/'+relative+'/outputs/'+name+'.mp4',dest/'outputs'/(name+'.mp4'))
                        original = row.get('gallery_original')
                        if original:
                            gallery = dest/'gallery'; gallery.mkdir(exist_ok=True)
                            target = gallery/Path(original['path']).name
                            command(*self.adb,'pull',original['path'],target)
                            if sha(target) != original['sha256']: raise RuntimeError('Gallery copy hash mismatch')
                        completed.add(name)
                    if data['status'] == 'finished': break
                if progress: atomic(dest/'progress.json',progress)
                if time.monotonic()-advanced > 12*60: raise TimeoutError('No QA case/frame progress for twelve minutes')
                if not command(*self.adb,'shell','pidof',ANDROID_PACKAGE,required=False): raise RuntimeError('QA process exited')
                time.sleep(5)
            else: raise TimeoutError('Run deadline exceeded; evidence preserved')
        finally:
            crop_dir = dest/'crop-traces'; crop_dir.mkdir(exist_ok=True)
            by_output = {}
            for remote in sorted(self.traces()-before):
                target = crop_dir/Path(remote).name
                command(*self.adb,'pull',remote,target)
                with target.open(encoding='utf-8') as stream: header=json.loads(stream.readline())
                output = header.get('output_basename')
                if not output or output in by_output: raise RuntimeError('Crop trace requires a unique output binding')
                by_output[output] = target.name
            planned={c['case']+'.mp4' for c in config['cases']}
            metadata['additional_crop_traces']={k:v for k,v in by_output.items() if k not in planned}
            by_output={k:v for k,v in by_output.items() if k in planned}
            metadata['crop_traces']=[by_output[c['case']+'.mp4'] for c in config['cases'] if c['case']+'.mp4' in by_output]
            metadata['crop_traces_by_output']=by_output
            metadata['finished_utc']=utc()
            atomic(dest/'run-metadata.json',metadata)
            command(*self.adb,'pull',ANDROID_DOCUMENTS+'/'+relative+'/previews',dest/'previews',required=False)
            for name in ['qa_lifecycle_results.json','qa_negative_results.json']:
                value=self.read(name)
                if config.get('lifecycle') and value: atomic(dest/name,value)
        if not data or data['status']!='finished' or len(data['cases'])!=len(config['cases']): raise RuntimeError('Incomplete matrix')
        if any(r['result'] not in ['output_created','cancelled_cleanly'] for r in data['cases']): raise RuntimeError('Matrix contains failures')
        if len(by_output)!=len(config['cases']): raise RuntimeError('Missing per-case crop traces')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('platform',choices=['android','ios'])
    p.add_argument('--suite',choices=['all','legacy22','original4k','focused13'],default='all')
    p.add_argument('--prefix',required=True)
    p.add_argument('--media',type=Path,required=True)
    p.add_argument('--report',type=Path,required=True)
    p.add_argument('--binary',type=Path,required=True,help='Android APK or iOS Runner.app')
    p.add_argument('--serial',default='334da6a3')
    p.add_argument('--udid',default='EE228BE6-0F7F-48E1-9892-313B7CFFBB63')
    p.add_argument('--install',action='store_true')
    p.add_argument('--case-filter',help='Explicit partial stage check; final acceptance omits this option')
    p.add_argument('--stage-export-only',action='store_true',help='Export diagnosis only; requires --case-filter and never qualifies as full acceptance')
    p.add_argument('--build-manifest',type=Path)
    p.add_argument('--deadline-hours',type=float,default=16)
    args=p.parse_args()
    if args.stage_export_only and not args.case_filter:
        p.error('Export-only diagnosis requires an explicit --case-filter; final acceptance must include previews')
    if not re.fullmatch(r'[A-Za-z0-9_-]+',args.prefix): p.error('Use a simple unique run prefix')
    build_path=args.build_manifest or args.binary.parent/'build-manifest.json'
    build=json.loads(build_path.read_text(encoding='utf-8'))
    if binary_hashes(args.binary)!=build['binary_files']:raise RuntimeError('QA binary differs from its build manifest')
    if fingerprint(inputs())!=build['source_fingerprint']:raise RuntimeError('Sources changed after the QA binary was built')
    matrix=json.loads(MATRIX.read_text(encoding='utf-8'))
    for name,expected in matrix['materials'].items():
        path=args.media/name
        if not path.is_file() or path.stat().st_size!=expected['bytes'] or sha(path)!=expected['sha256']:
            raise RuntimeError(f'Frozen fixture mismatch: {name}')
    selected=[s for s in matrix['suites'] if args.suite in ['all',s['id']]]
    deadline=time.monotonic()+args.deadline_hours*3600
    adapter=Android(args) if args.platform=='android' else None
    failed=[]
    with adapter.awake() if adapter else nullcontext():
        if adapter: adapter.sources(matrix['materials'])
        for suite in selected:
            cases=[c for c in suite['cases'] if not args.case_filter or re.fullmatch(args.case_filter,c['case'])]
            if not cases:continue
            run_id=args.prefix+'_'+suite['id']
            dest=args.report/'artifacts'/run_id
            if dest.exists(): raise FileExistsError(f'Preserve old evidence: {dest}')
            dest.mkdir(parents=True)
            config={'run_id':run_id,'cases':cases,'core_smokes':True,
                    'build_source_fingerprint':build['source_fingerprint'],
                    'partial_stage_check':args.case_filter is not None,
                    'verify_previews':not args.stage_export_only,'stage_export_only':args.stage_export_only,
                    'lifecycle':suite['id']=='legacy22' and args.case_filter is None}
            shutil.copyfile(build_path,dest/'build-manifest.json')
            if len({c['case'] for c in config['cases']})!=len(config['cases']): raise ValueError('Duplicate output names in a suite')
            print('QA_SUITE_START',args.platform,run_id,len(config['cases']),flush=True)
            try:
                if adapter: adapter.run(config,dest,matrix['materials'],deadline)
                else:
                    configuration=dest/'configuration.json';atomic(configuration,config)
                    cmd=[sys.executable,str(ROOT/'tools/ios/run_privacy_regression.py'),str(configuration),
                         '--app',str(args.binary),'--udid',args.udid,'--optimized-native',
                         '--report-root',str(args.report),'--sources',str(args.media),
                         '--deadline-hours',str(max(.1,(deadline-time.monotonic())/3600))]
                    if args.install:cmd.append('--install')
                    subprocess.run(cmd,check=True)
                print('QA_SUITE_FINISHED',run_id,flush=True)
            except (RuntimeError,TimeoutError,subprocess.CalledProcessError) as error:
                atomic(dest/'runner-error.json',{'error':str(error),'at_utc':utc()})
                failed.append(run_id);print('QA_SUITE_FAILED',run_id,str(error),flush=True)
    return 1 if failed else 0

if __name__=='__main__':raise SystemExit(main())
