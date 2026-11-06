#!/usr/bin/env python3
"""Build an isolated QA binary and bind its source inputs before real-media runs."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[2]
APP=ROOT/'mobile/app'

def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest()

def inputs():
    files=set()
    for name in ['crates/frame-kernel/src','crates/frame-kernel/include',
                 'mobile/app/android/app/src/main','mobile/app/android/app/src/debug','mobile/app/ios/Runner',
                 'mobile/packages/dance_native/android/src/main',
                 'mobile/packages/dance_native/android/src/debug',
                 'mobile/packages/dance_native/ios/dance_native/Sources',
                 'mobile/packages/dance_native/ios/dance_native/Resources',
                 'mobile/packages/dance_native/lib','mobile/packages/dance_domain/lib']:
        directory=ROOT/name
        if directory.exists():files.update(p for p in directory.rglob('*') if p.is_file())
    for name in ['rust-toolchain.toml','crates/frame-kernel/Cargo.toml','crates/frame-kernel/Cargo.lock',
                 'mobile/app/lib/privacy_regression_main.dart',
                 'mobile/app/lib/ios_privacy_regression_20261104_main.dart',
                 'mobile/app/pubspec.lock','mobile/app/pubspec.yaml',
                 'mobile/app/android/app/build.gradle.kts',
                 'mobile/packages/dance_native/android/build.gradle.kts',
                 'mobile/app/ios/Podfile','mobile/app/ios/Podfile.lock',
                 'tools/rust/build_jni.py','tools/rust/build_xcode.py','tools/rust/run.py']:
        p=ROOT/name
        if p.exists():files.add(p)
    files.update((ROOT/'mobile/packages/dance_native/ios').glob('*.podspec'))
    return {p.relative_to(ROOT).as_posix():sha(p) for p in sorted(files)}

def fingerprint(values):
    return hashlib.sha256(json.dumps(values,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def run(args,cwd=APP,env=None):
    print('+',' '.join(str(x) for x in args),flush=True)
    subprocess.run([str(x) for x in args],cwd=cwd,env=env,check=True)

def binary_hashes(binary):
    if binary.is_file():return {binary.name:sha(binary)}
    return {p.relative_to(binary).as_posix():sha(p) for p in sorted(binary.rglob('*')) if p.is_file()}

def android_package_id(binary):
    sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:raise RuntimeError('Android SDK is required to inspect the QA package before installation')
    candidates=sorted((Path(sdk)/'build-tools').glob('*/aapt2.exe' if os.name=='nt' else '*/aapt2'))
    if not candidates:raise RuntimeError('Android aapt2 is required to inspect the QA package')
    text=subprocess.check_output([str(candidates[-1]),'dump','badging',str(binary)],text=True)
    match=re.search(r"^package: name='([^']+)'",text,re.M)
    if not match:raise RuntimeError('APK package metadata is missing')
    return match.group(1)

def verify_ios_model(directory):
    resources=ROOT/'mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources'
    for name in ['yolo11n-seg-fp16.contract.json','dense-face-v1.contract.json']:
        contract=json.loads((resources/name).read_text(encoding='utf-8'))
        path=directory/contract['model']
        if not path.is_file() or path.stat().st_size!=contract['expected_size_bytes'] or sha(path)!=contract['expected_sha256']:
            raise RuntimeError('QA iOS model missing or different from pinned model contract: '+str(path)+
                               '. Provision with tools/release/sync_ios_yolo_model.py and sync_ios_face_model.py before building.')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('platform',choices=['android','ios'])
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--derived-data',type=Path)
    p.add_argument('--udid',default='EE228BE6-0F7F-48E1-9892-313B7CFFBB63')
    args=p.parse_args()
    flutter=shutil.which('flutter')
    if not flutter:raise RuntimeError('Flutter is missing from PATH')
    if args.platform=='ios':
        verify_ios_model(ROOT/'mobile/packages/dance_native/ios/dance_native/Sources/dance_native/Resources/InferenceAssets')
        run(['pod','install'],cwd=APP/'ios')
    before=inputs();digest=fingerprint(before)
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    destination=args.output.resolve()
    manifest_path=destination/'build-manifest.json'
    if destination.exists():
        if manifest_path.exists():
            old=json.loads(manifest_path.read_text(encoding='utf-8'))
            old_binary=destination/old['binary_name']
            if old['source_fingerprint']==digest and binary_hashes(old_binary)==old['binary_files']:
                print('QA_BINARY_REUSED',old_binary,flush=True);return 0
        raise FileExistsError('Preserve the previous binary; choose a new output directory')
    defines=['--dart-define=WOAH_QA_SOURCE_FINGERPRINT='+digest,'--dart-define=WOAH_QA_SOURCE_COMMIT='+head]
    if args.platform=='android':
        env=dict(os.environ,ORG_GRADLE_PROJECT_WOAH_PRIVACY_QA='true')
        run([flutter,'build','apk','--debug','--no-pub','--target','lib/privacy_regression_main.dart',
             '--dart-define=WOAH_PRIVACY_QA_DIRECTORY=/sdcard/Android/data/art.gaoge.dance.privacyqa/files',*defines],env=env)
        binary=APP/'build/app/outputs/flutter-apk/app-debug.apk'
        if android_package_id(binary)!='art.gaoge.dance.privacyqa':
            raise RuntimeError('Refuse to archive an APK without the isolated privacyqa package ID')
    else:
        run([flutter,'build','ios','--simulator','--debug','--no-codesign','--config-only','--no-pub',
             '--target','lib/privacy_regression_main.dart',*defines])
        derived=(args.derived_data or APP/'build/qa-ios').resolve()
        run(['xcodebuild','-workspace','ios/Runner.xcworkspace','-scheme','Runner','-configuration','Debug',
             '-sdk','iphonesimulator','-destination','platform=iOS Simulator,id='+args.udid,
             '-derivedDataPath',derived,'CODE_SIGNING_ALLOWED=NO','SWIFT_OPTIMIZATION_LEVEL=-O',
             'GCC_OPTIMIZATION_LEVEL=2','ONLY_ACTIVE_ARCH=YES','build'])
        binary=derived/'Build/Products/Debug-iphonesimulator/Runner.app'
        verify_ios_model(binary/'dance_native_models.bundle')
    after=inputs()
    if before!=after:
        changed=[name for name in sorted(before.keys()|after.keys()) if before.get(name)!=after.get(name)]
        raise RuntimeError('Build inputs changed during compilation; rebuild with stable inputs: '+repr(changed))
    destination.mkdir(parents=True)
    archived=destination/binary.name
    if binary.is_dir():shutil.copytree(binary,archived)
    else:shutil.copy2(binary,archived)
    files=binary_hashes(archived)
    value={'platform':args.platform,'source_commit':head,'source_fingerprint':digest,
           'source_files':before,'binary_name':binary.name,'binary_files':files,
           'binary_sha256':files.get(binary.name) if binary.is_file() else files['Runner'],
           'rust_toolchain':(ROOT/'rust-toolchain.toml').read_text(encoding='utf-8')}
    manifest_path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
    print('QA_BINARY_READY',archived,flush=True)
    return 0

if __name__=='__main__':raise SystemExit(main())
