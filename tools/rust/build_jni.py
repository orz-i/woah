#!/usr/bin/env python3
"""Build the JNI transport and link the pinned Rust static library."""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
CORE=ROOT/'crates/frame-kernel'
TARGETS={'arm64-v8a':'aarch64-linux-android','armeabi-v7a':'armv7-linux-androideabi','x86_64':'x86_64-linux-android'}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--abi',choices=('host',*TARGETS),default='host')
    p.add_argument('--ndk',type=Path)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    target=TARGETS.get(args.abi)
    command=[sys.executable,str(ROOT/'tools/rust/run.py'),'build']
    if target:
        if args.ndk is None: raise SystemExit('--ndk is required for Android')
        command+=['--target',target,'--ndk',str(args.ndk)]
    subprocess.run(command,check=True)
    library=CORE/'target'
    if target: library/=target
    library/='release'
    library/='frame_kernel.lib' if sys.platform=='win32' and not target else 'libframe_kernel.a'
    cmake=shutil.which('cmake')
    if not cmake:
        sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
                 str(Path(os.environ.get('LOCALAPPDATA',''))/'Android/Sdk'))
        candidates=sorted(sdk.glob('cmake/*/bin/cmake.exe'))
        if candidates: cmake=str(candidates[-1])
    if not cmake: raise SystemExit('CMake is required (Android SDK CMake is supported)')
    build=CORE/'target/bridge'/args.abi
    output=args.output.resolve()
    flags=['-DCMAKE_BUILD_TYPE=Release','-DFRAME_KERNEL_LIBRARY='+str(library), '-DFRAME_KERNEL_INCLUDE='+str(CORE/'include'),
           '-DCMAKE_LIBRARY_OUTPUT_DIRECTORY='+str(output),
           '-DCMAKE_RUNTIME_OUTPUT_DIRECTORY='+str(output),
           '-DCMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE='+str(output)]
    if sys.platform=='win32' and not target:
        compiler=shutil.which('cl')
        if not compiler:
            vswhere=Path(os.environ.get('ProgramFiles(x86)',r'C:\Program Files (x86)'))/'Microsoft Visual Studio/Installer/vswhere.exe'
            if vswhere.is_file():
                installation=subprocess.check_output([str(vswhere),'-latest','-products','*',
                    '-requires','Microsoft.VisualStudio.Component.VC.Tools.x86.x64','-property','installationPath'],text=True).strip()
                compilers=sorted(Path(installation).glob('VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe')) if installation else []
                if compilers: compiler=str(compilers[-1])
        if compiler:
            msvc=Path(compiler).parents[3]
            os.environ['PATH']=str(Path(compiler).parent)+os.pathsep+os.environ.get('PATH','')
            os.environ['LIB']=str(msvc/'lib/x64')+';'+os.environ.get('LIB','')
            os.environ['INCLUDE']=str(msvc/'include')+';'+os.environ.get('INCLUDE','')
        # NMake environments may expose cl/link without the Windows SDK tools.
        kits=Path(os.environ.get('WindowsSdkDir') or
                  str(Path(os.environ.get('ProgramFiles(x86)',r'C:\Program Files (x86)'))/'Windows Kits/10'))
        resource_compilers=sorted(kits.glob('bin/*/x64/rc.exe'))
        if resource_compilers:
            os.environ['PATH']=str(resource_compilers[-1].parent)+os.pathsep+os.environ.get('PATH','')
            sdk_version=resource_compilers[-1].parents[1].name
            os.environ['LIB']=';'.join(str(kits/'Lib'/sdk_version/p/'x64') for p in ('ucrt','um'))+';'+os.environ.get('LIB','')
            os.environ['INCLUDE']=';'.join(str(kits/'Include'/sdk_version/p) for p in ('ucrt','shared','um','winrt'))+';'+os.environ.get('INCLUDE','')
            # CMake writes these paths into generated CMake scripts. Forward
            # slashes prevent Windows path segments such as \Program Files
            # from becoming invalid CMake string escapes on a clean build.
            flags+=['-DCMAKE_RC_COMPILER='+resource_compilers[-1].as_posix(),
                    '-DCMAKE_MT='+resource_compilers[-1].with_name('mt.exe').as_posix()]
    if target:
        flags+=['-DCMAKE_TOOLCHAIN_FILE='+str(args.ndk/'build/cmake/android.toolchain.cmake'),
                '-DANDROID_ABI='+args.abi,'-DANDROID_PLATFORM=android-24']
        # Android SDK CMake includes Ninja, independently of the host VS version.
        ninja=Path(cmake).with_name('ninja.exe' if sys.platform=='win32' else 'ninja')
        flags+=['-G','Ninja']
        if ninja.is_file(): flags+=['-DCMAKE_MAKE_PROGRAM='+str(ninja)]
    subprocess.run([cmake,'-S',str(ROOT/'mobile/packages/dance_native/android/src/main/cpp/frame_kernel'),'-B',str(build),*flags],check=True)
    subprocess.run([cmake,'--build',str(build),'--config','Release'],check=True)


if __name__=='__main__': main()
