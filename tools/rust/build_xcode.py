#!/usr/bin/env python3
"""Build only the Rust slices needed by the current Xcode target."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

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
    if len(slices)==1: shutil.copyfile(slices[0],output)
    else: subprocess.run(['xcrun','lipo','-create',*[str(p) for p in slices],'-output',str(output)],check=True)


if __name__=='__main__': main()
