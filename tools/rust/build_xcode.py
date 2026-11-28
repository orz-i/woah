#!/usr/bin/env python3
"""Build only the Rust slices needed by the current Xcode target."""
import filecmp
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[2]
CORE=ROOT/'crates/frame-kernel'


def main():
    platform=os.environ['PLATFORM_NAME']
    architectures=os.environ['ARCHS'].split()
    slices=[]
    for arch in architectures:
        if platform=='iphoneos' and arch=='arm64': target='aarch64-apple-ios'
        elif platform=='iphonesimulator' and arch=='arm64': target='aarch64-apple-ios-sim'
        elif platform=='iphonesimulator' and arch=='x86_64': target='x86_64-apple-ios'
        else: raise SystemExit('Unsupported Rust Apple slice: '+platform+'/'+arch)
        subprocess.run([sys.executable,str(ROOT/'tools/rust/run.py'),'build','--target',target],check=True)
        slices.append(CORE/'target'/target/'release/libframe_kernel.a')
    output=Path(os.environ['BUILT_PRODUCTS_DIR'])/'FrameKernel/libframe_kernel.a'
    output.parent.mkdir(parents=True,exist_ok=True)
    # Cargo already checked every Rust source and manifest for freshness.
    # Don't advance the Xcode archive mtime (and force a relink) if its bytes
    # are unchanged. Publish changed archives atomically, including fat slices.
    with tempfile.TemporaryDirectory(prefix='frame-kernel-', dir=output.parent) as directory:
        candidate=Path(directory)/output.name
        if len(slices)==1:
            if output.is_file() and filecmp.cmp(slices[0],output,shallow=False):
                return
            shutil.copyfile(slices[0],candidate)
        else:
            subprocess.run(['xcrun','lipo','-create',*[str(p) for p in slices],
                            '-output',str(candidate)],check=True)
        if not output.is_file() or not filecmp.cmp(candidate,output,shallow=False):
            os.replace(candidate,output)


if __name__=='__main__': main()
