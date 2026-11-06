#!/usr/bin/env python3
"""Fetch byte-identical privacy E2E fixtures and verify source SHA-256."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import hashlib
import json
import os
import urllib.request

ROOT = Path(__file__).resolve().parent
SOURCE_INDEX = ROOT.parent / 'privacy-e2e-2026-11-04' / 'sources.json'
TARGET = ROOT / 'artifacts' / 'sources'
TARGET.mkdir(parents=True, exist_ok=True)


def fetch(entry):
    name = entry['file']
    expected = entry['sha256']
    file = TARGET / name
    if file.exists() and hashlib.sha256(file.read_bytes()).hexdigest() == expected:
        return f'REUSE {name}'
    urls = [entry['download_url']] if 'download_url' in entry else []
    if name == 'no-person-flower.mp4':
        urls = [entry['source']]
    if name == 'custom-sticker.png':
        urls = ['https://raw.githubusercontent.com/twitter/twemoji/master/assets/72x72/1f600.png',
                'https://raw.githubusercontent.com/twitter/twemoji/v14.0.2/assets/72x72/1f600.png']
    last_error = None
    for url in urls:
        part = file.with_suffix(file.suffix+'.part')
        try:
            req = urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0 (Woah iOS QA)'})
            with urllib.request.urlopen(req, timeout=120) as response, part.open('wb') as target:
                while chunk := response.read(1024 * 1024):
                    target.write(chunk)
            digest = hashlib.sha256(part.read_bytes()).hexdigest()
            size = part.stat().st_size
            if digest != expected or size != entry['size']:
                raise ValueError(f'SHA/size mismatch: {digest}/{size}; expected {expected}/{entry["size"]}')
            os.replace(part, file)
            return f'OK {name} {size} bytes SHA-256={digest}'
        except Exception as exc:
            last_error = str(exc)
            part.unlink(missing_ok=True)
    raise RuntimeError(f'{name}: {last_error}')


entries = json.loads(SOURCE_INDEX.read_text())
failed = []
with ThreadPoolExecutor(max_workers=3) as pool:
    futures = {pool.submit(fetch,e): e for e in entries}
    for task in as_completed(futures):
        try:
            print(task.result(), flush=True)
        except Exception as exc:
            print(f'FAILED: {exc}', flush=True)
            failed.append(str(exc))
if not failed:
    pair = TARGET / 'duo-dance-6940536.mp4'
    corrupted = TARGET / 'corrupt-video-1024bytes.mp4'
    corrupted.write_bytes(pair.read_bytes()[:1024])
    print(f'OK {corrupted.name}: 1024 bytes, sha256={hashlib.sha256(corrupted.read_bytes()).hexdigest()}', flush=True)
print(f'SOURCES_VALIDATED={len(entries)-len(failed)}/{len(entries)}', flush=True)
raise SystemExit(bool(failed))
