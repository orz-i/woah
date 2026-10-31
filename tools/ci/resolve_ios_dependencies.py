#!/usr/bin/env python3
"""Resolve the actual CocoaPods/local-SPM chain without updating its lock."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'mobile/app'


def main():
    lock = APP / 'ios/Podfile.lock'
    before = lock.read_bytes()  # Missing lock is an error, not bootstrap mode.
    version = subprocess.check_output(['pod', '--version'], text=True).strip()
    if version != '1.17.0':
        raise SystemExit('Expected CocoaPods 1.17.0; explicitly review resolver updates')
    subprocess.run(['flutter', 'pub', 'get', '--enforce-lockfile', '--no-example'], cwd=APP, check=True)
    subprocess.run(['pod', 'install', '--deployment'], cwd=APP / 'ios', check=True)
    if lock.read_bytes() != before:
        raise SystemExit('Podfile.lock changed during deployment resolution')
    print('IOS_DEPENDENCY_LOCK=PASS')


if __name__ == '__main__':
    main()
