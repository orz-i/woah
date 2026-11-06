#!/usr/bin/env python3
"""Compare native unprotected preview controls with nearest decoded source frames."""
from __future__ import annotations
import functools,json
from pathlib import Path
import cv2
import numpy as np


def correlation(a,b):
    a=a.astype(np.float64).ravel();b=b.astype(np.float64).ravel()
    a-=a.mean();b-=b.mean()
    denominator=np.linalg.norm(a)*np.linalg.norm(b)
    return float(np.dot(a,b)/denominator) if denominator else float(np.array_equal(a,b))


def audit_run(run,report,sources):
    import audit_privacy_regression as media
    data=json.loads((run/'results.json').read_text(encoding='utf-8'))
    metadata=json.loads((run/'run-metadata.json').read_text(encoding='utf-8'))
    destination=report/'preview-audits'/run.name;destination.mkdir(parents=True,exist_ok=True)
    captures={}
    @functools.lru_cache(maxsize=16)
    def timestamps(name):return media.times(sources/name)
    @functools.lru_cache(maxsize=64)
    def reference(name,index,width,height):
        if name not in captures:captures[name]=cv2.VideoCapture(str(sources/name))
        cap=captures[name];cap.set(cv2.CAP_PROP_POS_FRAMES,index);ok,image=cap.read()
        if not ok:raise ValueError('Source preview reference decode failed')
        return cv2.cvtColor(cv2.resize(image,(width,height),interpolation=cv2.INTER_AREA),cv2.COLOR_BGR2GRAY)
    rows=[]
    try:
        for case in data['cases']:
            checks=[]
            try:
                source_name=case['input'];source=sources/source_name
                if media.sha(source)!=metadata['sources'][source_name]['sha256']:raise ValueError('Source preview binding changed')
                previews=case.get('previews') or []
                if not previews:raise ValueError('Missing protected previews')
                pts=timestamps(source_name)
                for preview in previews:
                    item={'requested_ms':preview['requested_ms']}
                    checks.append(item)
                    protected=run/'previews'/preview['file']
                    image=cv2.imread(str(protected))
                    if image is None or [image.shape[1],image.shape[0]]!=[preview['width'],preview['height']]:raise ValueError('Protected preview bytes/dimensions missing')
                    if 'control_file' not in preview:raise ValueError('Missing native time control')
                    control=run/'previews'/preview['control_file'];raw=cv2.imread(str(control))
                    if raw is None or control.stat().st_size!=preview['control_bytes']:raise ValueError('Native time control missing')
                    if [raw.shape[1],raw.shape[0]]!=[preview['control_width'],preview['control_height']]:raise ValueError('Native control dimensions differ')
                    width=min(256,raw.shape[1]);height=round(raw.shape[0]*width/raw.shape[1])
                    gray=cv2.cvtColor(cv2.resize(raw,(width,height),interpolation=cv2.INTER_AREA),cv2.COLOR_BGR2GRAY)
                    target=preview['requested_ms']/1000
                    distance=min(abs(t-target) for t in pts)
                    candidates=[i for i,t in enumerate(pts) if abs(t-target)<=distance+.000002]
                    score,index=max((correlation(gray,reference(source_name,i,width,height)),i) for i in candidates)
                    item.update(status='passed' if score>=.985 else 'failed_preview_time',
                        best_expected_frame=index,expected_pts_seconds=pts[index],
                        correlation=score,control_sha256=media.sha(control),protected_sha256=media.sha(protected))
                    if score<.985:
                        all_scores=[]
                        for i in range(len(pts)):
                            all_scores.append((correlation(gray,reference(source_name,i,width,height)),i))
                        best,matched=max(all_scores)
                        item.update(best_source_frame=matched,best_source_pts_seconds=pts[matched],best_source_correlation=best)
                        raise ValueError('Native preview does not match the nearest source frame')
                status='passed_preview_time'
            except Exception as error:
                status='failed_preview_time';checks.append({'error':str(error)})
            rows.append({'case':case['case'],'status':status,'previews':checks,
                'scope':'Raw native decode controls verify requested time; protected preview geometry/effect still requires visual review.'})
            (destination/'results.json').write_text(json.dumps({'run_id':run.name,'cases':rows},indent=2),encoding='utf-8')
            print(case['case'],status,flush=True)
    finally:
        for cap in captures.values():cap.release()
    return 0 if all(x['status']=='passed_preview_time' for x in rows) else 1
