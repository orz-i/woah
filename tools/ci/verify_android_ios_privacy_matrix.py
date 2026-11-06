#!/usr/bin/env python3
"""Guard iOS privacy QA matrix against silent Android fixture mode drift.

The Android 2026-11-04 full-length case is FACE_ONLY; a previous iOS fixture
accidentally ran FULL_BODY under the same case name, yielding a non-equivalent
safety failure. This verifier compares ALL 22 static test intentions with the
Android device reports, preserving stress-only FULL_BODY scenarios separately.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[2]
ANDROID=ROOT/'reports/privacy-regression-2026-11-04'
IOS=ROOT/'mobile/app/lib'
_ENTRIES=re.compile(r"'([A-Za-z][A-Za-z_0-9]*)'\s*:\s*('[^']*'|true|false|\d+)")


def parse_dart_cases(text: str) -> list[dict]:
    prefix='const _cases = <Map<String, Object>>['
    if text.count(prefix)!=1:raise ValueError('Cannot uniquely locate 22-case iOS QA matrix')
    block=text.split(prefix,1)[1].split('];',1)[0]
    sections=re.findall(r'\{([^{}]*)\}',block,re.DOTALL)
    if len(sections)!=22:raise ValueError(f'Expected exactly 22 QA cases, found {len(sections)}')
    rows=[]
    for part in sections:
        values={}
        for match in _ENTRIES.finditer(part):
            key,val=match.groups()
            if key in values:raise ValueError(f'Duplicate Dart fixture key: {key}')
            values[key]=val[1:-1] if val.startswith("'") else (val=='true' if val in ('true','false') else int(val))
        remainder=_ENTRIES.sub('',part).replace(',','').strip()
        if remainder:raise ValueError(f'Unrecognized or dynamic Dart QA fixture: {remainder}')
        if 'case' not in values:raise ValueError('A QA fixture has no case name')
        rows.append(values)
    names=[r['case'] for r in rows]
    if len(set(names))!=22:raise ValueError('Duplicate iOS QA case name')
    return rows


def normalize_qa_case(row: dict, observed_ids: list[int]) -> dict:
    case=row['case']
    accepted={'case','input','fill','startMs','endMs','faceOnly','hero','select','profile','width','height'}
    if not set(row).issubset(accepted):raise ValueError(f'Unknown iOS fixture fields: {sorted(set(row)-accepted)}')
    input_name=row.get('input','solo-hd-7570258.mp4')
    source_ids=observed_ids[:1] if row.get('select')=='first' else observed_ids
    hero=row.get('hero')
    chosen=[x for x in source_ids if x!=hero]
    if hero is not None and hero not in observed_ids:raise ValueError(f'Hero not detected in {case}')
    profile=row.get('profile','quality')
    if case.endswith('_balanced') and profile!='balanced':raise ValueError(f'Bad balanced label {case}')
    if case.endswith('_speed') and profile!='speed':raise ValueError(f'Bad speed label {case}')
    return {
      'case':case,'input':input_name,
      'face_only':row.get('faceOnly',True),
      'fill':row.get('fill','sticker'),
      'trim_start_ms':row.get('startMs',0),
      'trim_end_ms':row.get('endMs',1600),
      'hero':hero,
      'selected_ids':chosen,
      'profile':profile,
      'width':row.get('width',540),'height':row.get('height',1024),
    }


def verify(text: str, android_dir: Path=ANDROID) -> dict:
    cases=parse_dart_cases(text)
    rows=[]
    for item in cases:
        name=item['case']
        android_file=android_dir/(name+'.json')
        if not android_file.is_file():raise ValueError(f'Android fixture missing: {name}')
        fixture=json.loads(android_file.read_text())
        if fixture['case']!=name:raise ValueError(f'Android fixture case mismatch: {name}')
        ios=normalize_qa_case(item,fixture['detected_ids'])
        material=('input','face_only','fill','trim_start_ms','trim_end_ms','hero','selected_ids')
        differences=[{'field':k,'android':fixture.get(k),'ios':ios[k]}
                     for k in material if fixture.get(k)!=ios[k]]
        rows.append({'case':name,'status':'matched_android_mode_and_selection' if not differences else 'NON_EQUIVALENT',
                     'material_differences':differences,
                     'ios':ios, 'android_expected_frames':fixture.get('expected_frames'),
                     'android_device_state':fixture.get('state')})
    return {'schema':'woah-ios-android-privacy-22-case-parity-v1',
            'android_fixture_date':'2026-11-04',
            'total_cases':len(rows),
            'matched':sum(not x['material_differences'] for x in rows),
            'failed':sum(bool(x['material_differences']) for x in rows),
            'all_22_equivalent':all(not x['material_differences'] for x in rows),
            'scope':'mode/source/trim/hero/selected IDs; does not prove video codec, output resolution, pixels or actual iOS runtime',
            'rows':rows}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--dart',default=str(IOS/'ios_privacy_regression_20261104_main.dart'))
    parser.add_argument('--output')
    args=parser.parse_args()
    results=verify(Path(args.dart).read_text())
    print('PRIVACY_QA_MATRIX_PARITY',f"{results['matched']}/22",'missing_or_mismatch',results['failed'])
    for entry in results['rows']:
        if entry['material_differences']:
            print(entry['case'],'NON_EQUIVALENT',entry['material_differences'])
    if args.output:Path(args.output).write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
    if not results['all_22_equivalent']:raise SystemExit(1)

if __name__=='__main__':main()
