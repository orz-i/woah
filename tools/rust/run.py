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
CORE = ROOT / 'mobile/packages/dance_native/core'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('doctor', 'lock', 'check', 'test', 'build'))
    parser.add_argument('--target')
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
        run(cargo, 'build', '--release', '--locked', *extra)
    return 0


if __name__ == '__main__':
    sys.exit(main())
