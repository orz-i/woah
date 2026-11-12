"""Independently validate every retained movie from a shared native matrix run."""
import argparse
import hashlib
import json
from pathlib import Path

from verify_comparison_export import validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'corpus', 'movies', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--allow-partial', action='store_true')
    args = parser.parse_args()
    runtime = json.loads(args.runtime.read_text(encoding='utf-8'))
    manifest_bytes = (args.corpus / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    args.report.mkdir(parents=True, exist_ok=True)
    for source in manifest['sources'].values():
        assert hashlib.sha256((args.corpus / source['file']).read_bytes()).hexdigest() == source['sha256']
    rows = []
    for record in runtime['exports']:
        row = {'id': record['id']}
        video = args.movies / record['file']
        try:
            validate(video, record, args.corpus / manifest['sources'][record['reference']]['file'],
                     args.corpus / manifest['sources'][record['target']]['file'],
                     args.report / (record['id'] + '.json'), record['coded30'])
            row.update(status='passed', bytes=video.stat().st_size,
                       sha256=hashlib.sha256(video.read_bytes()).hexdigest())
        except Exception as error:
            row.update(status='failed', error=str(error))
        rows.append(row)
        print(json.dumps(row), flush=True)
    complete = runtime['finished'] and runtime['passed']
    passed = bool(rows) and all(r['status'] == 'passed' for r in rows) and (complete or args.allow_partial)
    result = dict(schema='woah.comparison.export-audit.v1', platform=runtime['platform'],
                  corpusManifestSha256=hashlib.sha256(manifest_bytes).hexdigest(),
                  runtimeComplete=complete, partial=args.allow_partial, passed=passed, results=rows)
    (args.report / 'results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
