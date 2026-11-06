#!/usr/bin/env python3
"""Use the repository's exact Rust toolchain, independently of ambient PATH."""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / 'crates/frame-kernel'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('doctor', 'lock', 'check', 'test', 'build'))
    parser.add_argument('--target')
    parser.add_argument('--ndk', type=Path, help='Android NDK root for Android cross-compilation')
    args = parser.parse_args()
    pins = re.findall(r'^channel\s*=\s*"(\d+\.\d+\.\d+)"\s*$',
                      (ROOT / 'rust-toolchain.toml').read_text(encoding='utf-8'), re.M)
    if len(pins) != 1:
        raise SystemExit('Expected exactly one fixed stable Rust version in rust-toolchain.toml')
    version = pins[0]
    rustup = shutil.which('rustup')
    if not rustup:
        raise SystemExit('rustup is required; put its bin directory on PATH (see docs/rust-core-migration.md)')
    def locate(name):
        return subprocess.check_output([rustup, 'which', '--toolchain', version, name], text=True).strip()
    cargo, rustc = locate('cargo'), locate('rustc')
    env = dict(os.environ, RUSTC=rustc, RUSTUP_TOOLCHAIN=version)
    env['PATH'] = str(Path(cargo).parent) + os.pathsep + env.get('PATH', '')
    print(subprocess.check_output([rustc, '-vV'], text=True), flush=True)
    def run(*command):
        print('+ ' + ' '.join(str(x) for x in command), flush=True)
        subprocess.run(command, cwd=CORE, env=env, check=True)
    if args.action == 'doctor':
        run(rustup, 'target', 'list', '--toolchain', version, '--installed')
    elif args.action == 'lock':
        run(cargo, 'generate-lockfile')
    elif args.action == 'check':
        run(cargo, 'fmt', '--all', '--', '--check')
        run(cargo, 'clippy', '--locked', '--all-targets', '--', '-D', 'warnings')
        run(cargo, 'test', '--locked')
    elif args.action == 'test':
        run(cargo, 'test', '--locked')
    elif args.action == 'build':
        extra = ['--target', args.target] if args.target else []
        if args.target and 'android' in args.target:
            ndk = args.ndk or (Path(env['ANDROID_NDK_HOME']) if env.get('ANDROID_NDK_HOME') else None)
            if ndk is None:
                raise SystemExit('Android builds require --ndk or ANDROID_NDK_HOME')
            host = 'windows-x86_64' if sys.platform == 'win32' else ('darwin-x86_64' if sys.platform == 'darwin' else 'linux-x86_64')
            clang = ndk / 'toolchains/llvm/prebuilt' / host / 'bin' / ('clang.exe' if sys.platform == 'win32' else 'clang')
            if not clang.is_file():
                raise SystemExit('Missing NDK clang: ' + str(clang))
            triple = {'aarch64-linux-android':'aarch64-linux-android24',
                      'x86_64-linux-android':'x86_64-linux-android24',
                      'armv7-linux-androideabi':'armv7a-linux-androideabi24'}[args.target]
            env['CARGO_TARGET_' + args.target.upper().replace('-', '_') + '_LINKER'] = str(clang)
            flags = ['-C', 'link-arg=--target=' + triple, '-C', 'link-arg=-Wl,-z,max-page-size=16384']
            env['CARGO_ENCODED_RUSTFLAGS'] = '\x1f'.join(flags)
        run(cargo, 'build', '--release', '--locked', *extra)
    return 0


if __name__ == '__main__':
    sys.exit(main())
