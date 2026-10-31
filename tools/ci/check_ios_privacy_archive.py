#!/usr/bin/env python3
"""Verify owned privacy declarations and actual unsigned iPhoneOS archive materials.

This is a project packaging check, not signing/App Store validation or an API scanner.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OWNED = {
    'PrivacyInfo.xcprivacy': 'mobile/app/ios/Runner/PrivacyInfo.xcprivacy',
    'dance_native_privacy.bundle/PrivacyInfo.xcprivacy':
        'mobile/packages/dance_native/ios/dance_native/Sources/dance_native/PrivacyInfo.xcprivacy',
}
EXPECTED_APIS = [{'NSPrivacyAccessedAPIType': 'NSPrivacyAccessedAPICategoryFileTimestamp',
                  'NSPrivacyAccessedAPITypeReasons': ['C617.1']}]


def load(path: Path) -> dict:
    with path.open('rb') as stream:
        value = plistlib.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f'Expected plist dictionary: {path.name}')
    return value


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def owned_manifest(path: Path) -> dict:
    value = load(path)
    if set(value) != {'NSPrivacyAccessedAPITypes', 'NSPrivacyTracking',
                      'NSPrivacyTrackingDomains', 'NSPrivacyCollectedDataTypes'}:
        raise ValueError('Unexpected owned privacy keys')
    if value.get('NSPrivacyAccessedAPITypes') != EXPECTED_APIS:
        raise ValueError('Owned FileTimestamp API reason must be exactly C617.1')
    if (value.get('NSPrivacyTracking') is not False
            or value.get('NSPrivacyTrackingDomains') != []
            or value.get('NSPrivacyCollectedDataTypes') != []):
        raise ValueError('Owned tracking/data collection declaration changed')
    return value


def source_manifests(root: Path = ROOT) -> dict[str, dict]:
    return {target: owned_manifest(root / source) for target, source in OWNED.items()}


def inside(path: Path, root: Path) -> Path:
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root.resolve(strict=True)):
        raise ValueError('Archive path escapes its expected bundle')
    return resolved


def verify_archive(archive: Path, source_root: Path = ROOT) -> dict:
    expected = source_manifests(source_root)
    if archive.suffix != '.xcarchive' or not archive.is_dir():
        raise ValueError('An actual .xcarchive directory is required, not a zipped Debug app')
    metadata = load(archive / 'Info.plist')
    app_path = metadata.get('ApplicationProperties', {}).get('ApplicationPath')
    if not isinstance(app_path, str) or Path(app_path).is_absolute() or '..' in Path(app_path).parts:
        raise ValueError('Archive has no safe ApplicationPath')
    app = inside(archive / 'Products' / app_path, archive / 'Products/Applications')
    if app.suffix != '.app' or not app.is_dir():
        raise ValueError('Archive does not contain an application product')
    info = load(app / 'Info.plist')
    if info.get('CFBundleIdentifier') != 'art.gaoge.dance':
        raise ValueError('Archive is not the Woah application product')
    if info.get('CFBundleSupportedPlatforms') != ['iPhoneOS']:
        raise ValueError('Expected iPhoneOS archive, not a simulator bundle')
    executable_name = info.get('CFBundleExecutable')
    if not isinstance(executable_name, str) or Path(executable_name).name != executable_name:
        raise ValueError('Invalid app executable name')
    executable = inside(app / executable_name, app)
    if not executable.is_file():
        raise ValueError('Missing app executable')
    with executable.open('rb') as stream:
        if stream.read(4) not in (b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xca\xfe\xba\xbe', b'\xbe\xba\xfe\xca'):
            raise ValueError('App executable is not a Mach-O material')
    for target, source in expected.items():
        actual = owned_manifest(inside(app / target, app))
        if actual != source:
            raise ValueError(f'Bundled manifest differs from source: {target}')
    inventory = []
    for path in sorted(app.rglob('PrivacyInfo.xcprivacy')):
        inside(path, app)
        value = load(path)
        inventory.append({'path': str(path.relative_to(app)), 'sha256': digest(path),
                          'accessed_api_types': value.get('NSPrivacyAccessedAPITypes', []),
                          'tracking': value.get('NSPrivacyTracking'),
                          'collected_data_types': value.get('NSPrivacyCollectedDataTypes', [])})
    return {'archive': archive.name, 'application_path': app_path,
            'bundle_identifier': info.get('CFBundleIdentifier'),
            'platform': 'iPhoneOS', 'executable_sha256': digest(executable),
            'owned_manifests': list(OWNED), 'manifest_inventory': inventory,
            'validation_scope': 'unsigned archive materials only; no signing, device or App Store acceptance'}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    source_manifests()
    print('IOS_PRIVACY_SOURCE=PASS (owned container FileTimestamp/C617.1)')
    if args.archive:
        report = verify_archive(args.archive)
        report['git_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        report['working_tree_dirty'] = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
        payload = json.dumps(report, indent=2, ensure_ascii=False) + '\n'
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(payload)
        print(payload)
        print('IOS_PRIVACY_ARCHIVE=PASS (unsigned materials, not distribution approval)')
    elif args.report:
        parser.error('--report requires --archive')


if __name__ == '__main__':
    main()
