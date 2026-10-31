#!/usr/bin/env python3
"""Check repository-owned lock inputs and resolve tiny fresh Dart roots twice."""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGES = ('mobile/packages/dance_domain', 'mobile/packages/dance_ui',
            'mobile/packages/dance_native', 'mobile/app')
LOCKS = tuple(f'{package}/pubspec.lock' for package in PACKAGES) + (
    'mobile/app/ios/Podfile.lock', 'mobile/app/android/app/gradle.lockfile',
    'mobile/packages/dance_native/android/gradle.lockfile', 'uv.lock')


def fingerprints(root: Path) -> dict[str, str]:
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in LOCKS}


def clean_dart_resolution(root: Path = ROOT) -> None:
    # No source, models, SDK or dependency caches are copied. The fresh roots
    # reuse Pub's content-addressed cache, and disappear even on failure.
    with tempfile.TemporaryDirectory(prefix='woah-dart-locks-') as scratch:
        fresh = Path(scratch)
        for package in PACKAGES:
            target = fresh / package
            target.mkdir(parents=True)
            for name in ('pubspec.yaml', 'pubspec.lock'):
                shutil.copy2(root / package / name, target / name)
        for attempt in range(2):
            for package in PACKAGES:
                subprocess.run(['flutter', 'pub', 'get', '--offline', '--enforce-lockfile', '--no-example'],
                               cwd=fresh / package, check=True)
                if (fresh / package / 'pubspec.lock').read_bytes() != (root / package / 'pubspec.lock').read_bytes():
                    raise RuntimeError(f'Lock drift in fresh resolution: {package}')
        # A fresh application without its lock must fail, not silently resolve.
        (fresh / 'mobile/app/pubspec.lock').unlink()
        missing = subprocess.run(['flutter', 'pub', 'get', '--offline', '--enforce-lockfile', '--no-example'],
                                 cwd=fresh / 'mobile/app', capture_output=True, text=True)
        if missing.returncode == 0:
            raise RuntimeError('Missing lock was silently accepted')
        print('FRESH_DART_LOCKS=PASS (two passes + missing-lock rejection, shared download cache)')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fresh-dart', action='store_true')
    parser.add_argument('--check-tracked', action='store_true')
    args = parser.parse_args()
    for path, digest in fingerprints(ROOT).items():
        print(f'LOCK_SHA256 {digest} {path}')
    if args.fresh_dart:
        clean_dart_resolution()
    if args.check_tracked:
        subprocess.run(['git', 'ls-files', '--error-unmatch', '--', *LOCKS], cwd=ROOT, check=True)
        subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', *LOCKS], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
