#!/usr/bin/env python3
"""Generate/check exactly two Pigeon schemas and six outputs without copying models."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import unquote, urljoin, urlparse

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / 'mobile/packages/dance_native'
VERSION = '29.0.4'
SCHEMAS = ('dance_api.dart', 'comparison_api.dart')
OUTPUTS = (
    'lib/src/bridge/dance_api.g.dart',
    'android/src/main/kotlin/art/gaoge/dance/engine/bridge/DanceApi.g.kt',
    'ios/dance_native/Sources/dance_native/DanceApi.g.swift',
    'lib/src/bridge/comparison_api.g.dart',
    'android/src/main/kotlin/art/gaoge/dance/engine/bridge/comparison/ComparisonApi.g.kt',
    'ios/dance_native/Sources/dance_native/ComparisonApi.g.swift',
)


def generator(native: Path = NATIVE) -> tuple[Path, Path]:
    config = native / '.dart_tool/package_config.json'
    data = json.loads(config.read_text())
    packages = [item for item in data['packages'] if item['name'] == 'pigeon']
    if len(packages) != 1:
        raise ValueError('Exactly one resolved Pigeon package is required')
    uri = urlparse(urljoin(config.as_uri(), packages[0]['rootUri']))
    if uri.scheme != 'file' or uri.netloc:
        raise ValueError('Expected a local resolved Pigeon package')
    package = Path(unquote(uri.path))
    version = re.search(r'^version:\s*([^\s]+)', (package / 'pubspec.yaml').read_text(), re.M)
    if not version or version.group(1) != VERSION:
        raise ValueError(f'Pigeon must resolve to {VERSION}')
    return config, package / 'bin/pigeon.dart'


def snapshot(root: Path) -> dict[str, bytes]:
    return {name: (root / name).read_bytes() for name in OUTPUTS}


def compare(expected: dict[str, bytes], actual: dict[str, bytes]) -> None:
    changed = [name for name in OUTPUTS if expected.get(name) != actual.get(name)]
    if changed:
        raise ValueError('Pigeon output drift: ' + ', '.join(changed))


def generate(destination: Path, schema_root: Path, config: Path, entry: Path) -> dict[str, bytes]:
    for schema in SCHEMAS:
        subprocess.run(['dart', f'--packages={config}', str(entry),
                        '--input', str(schema_root / schema), '--base_path', str(destination)], check=True)
    actual_files = {str(path.relative_to(destination)) for path in destination.rglob('*') if path.is_file()}
    if actual_files != set(OUTPUTS):
        raise ValueError(f'Unexpected generated file set: {sorted(actual_files)}')
    return snapshot(destination)


def negative_checks(baseline: dict[str, bytes], temp: Path, config: Path, entry: Path) -> None:
    missing = dict(baseline)
    del missing[OUTPUTS[0]]
    try:
        compare(baseline, missing)
    except ValueError:
        pass
    else:
        raise AssertionError('Missing generated output was accepted')
    schemas = temp / 'mutated-schemas'
    schemas.mkdir()
    for name in SCHEMAS:
        shutil.copy2(NATIVE / 'pigeons' / name, schemas / name)
    schema = schemas / 'comparison_api.dart'
    original = schema.read_text()
    token = 'class ComparisonSourceDto {'
    if original.count(token) != 1:
        raise ValueError('Negative schema fixture needs explicit maintenance')
    schema.write_text(original.replace(token, token + '\n  String? governanceProbe;'))
    mutated = generate(temp / 'mutated', schemas, config, entry)
    try:
        compare(baseline, mutated)
    except ValueError as error:
        if 'comparison_api.g.dart' not in str(error):
            raise
    else:
        raise AssertionError('A valid schema change without updated outputs was accepted')
    print('PIGEON_NEGATIVE_CHECKS=PASS (missing output + successfully generated schema change)')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='Update only the six outputs after two identical generations')
    parser.add_argument('--self-test', action='store_true', help='Also run real-generator negative fixtures in a temporary directory')
    args = parser.parse_args()
    config, entry = generator()
    with tempfile.TemporaryDirectory(prefix='woah-pigeon-') as directory:
        temp = Path(directory)
        first = generate(temp / 'first', NATIVE / 'pigeons', config, entry)
        second = generate(temp / 'second', NATIVE / 'pigeons', config, entry)
        compare(first, second)
        if args.self_test:
            negative_checks(first, temp, config, entry)
        if args.write:
            for name, content in first.items():
                target = NATIVE / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
        else:
            compare(first, snapshot(NATIVE))
        for name, content in first.items():
            print(f'PIGEON_SHA256 {hashlib.sha256(content).hexdigest()} {name}')
    print(f'PIGEON_REPRODUCIBILITY=PASS (version={VERSION}, two schemas, six outputs, two passes)')


if __name__ == '__main__':
    main()
