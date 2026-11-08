#!/usr/bin/env python3
"""Probe frozen source face points missed by the independent face detector.

Checks decoded opaque-red output pixels and exact-time native previews; preserves
movie/trace/preview/source/spec/auditor hashes. Complements full media audits and
visual review. Non-solid effects require their separate geometry/visual audits.
"""
import argparse,hashlib,json,sys
from pathlib import Path
import cv2
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
SPEC=ROOT/'testdata/privacy/known-face-points.json'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def projection(point,source_size,crop,output_size):
    sw,sh=source_size;ow,oh=output_size
    left,top,right,bottom=crop
    if right<=left or bottom<=top:raise ValueError('Invalid crop')
    x,y=point[0]/sw,point[1]/sh
    if not(left<=x<right and top<=y<bottom):return None
    return ((x-left)/(right-left)*ow,(y-top)/(bottom-top)*oh,ow/((right-left)*sw))
def probe(image,position,radius,minimum):
    x,y,scale=position;x,y=round(x),round(y);r=max(1,round(radius*scale))
    if x-r<0 or y-r<0 or x+r>image.shape[1] or y+r>image.shape[0]:raise ValueError('Probe patch clipped')
    b,g,red=cv2.split(image[y-r:y+r,x-r:x+r]);mask=(red>180)&(g<70)&(b<70)
    fraction=float(mask.mean())
    return {'red_pixels':int(mask.sum()),'pixels':int(mask.size),'fraction':fraction,'passed':fraction>=minimum}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
    run=a.run;dest=a.output or run/'known-face-review';dest.mkdir(parents=True,exist_ok=True)
    spec=json.loads(SPEC.read_text());meta=json.loads((run/'run-metadata.json').read_text());data=json.loads((run/'results.json').read_text())
    if meta['sources'][spec['source']]['sha256']!=spec['source_sha256']:raise ValueError('Frozen source differs')
    points={int(k)*spec['frame_period_us']:v for k,v in spec['points'].items()};rows=[];boards=[]
    trace_by_output={}
    for name in meta['crop_traces']:
        path=run/'crop-traces'/name
        with path.open() as stream:header=json.loads(stream.readline())
        output=header['output_basename']
        if output in trace_by_output:raise ValueError('Duplicate trace output binding')
        trace_by_output[output]=name
    for row in data['cases']:
        if row.get('input')!=spec['source'] or row.get('fill')!='solid' or row.get('result')!='output_created':continue
        movie=run/'outputs'/(row['case']+'.mp4')
        trace_name=trace_by_output[movie.name];trace=run/'crop-traces'/trace_name
        records=[json.loads(s) for s in trace.read_text().splitlines()];header=records[0]
        if header['source_basename']!=spec['source'] or header['output_basename']!=movie.name:raise ValueError('Trace binding differs')
        movie_sha=sha(movie);trace_sha=sha(trace)
        targets={c['frame_index']:c for c in records[1:] if c['t_us'] in points}
        if targets:
            cap=cv2.VideoCapture(str(movie))
            for i in range(max(targets)+1):
                ok,im=cap.read()
                if not ok:raise ValueError('Missing decoded frame')
                if i not in targets:continue
                c=targets[i];point=points[c['t_us']];position=projection(point,(spec['width'],spec['height']),c['source_crop_normalized'],(im.shape[1],im.shape[0]))
                result={'case':row['case'],'kind':'export','output_frame':i,'source_pts_us':c['t_us'],'source_point':point,'movie_sha256':movie_sha,'trace_sha256':trace_sha}
                if position is None:result['status']='outside_crop'
                else:
                    result.update(probe(im,position,spec['radius_source_pixels'],spec['minimum_red_fraction']));result['status']='passed' if result['passed'] else 'failed'
                    x,y,scale=position;x,y=round(x),round(y);r=max(1,round(160*scale));tile=im[max(0,y-r):min(im.shape[0],y+r),max(0,x-r):min(im.shape[1],x+r)]
                    tile=cv2.resize(tile,(180,180));cv2.putText(tile,str(c['t_us']//40000)+' export',(3,17),cv2.FONT_HERSHEY_SIMPLEX,.4,(0,255,255),1);boards.append(tile)
                rows.append(result)
            cap.release()
        for preview in row.get('previews',[]):
            at=preview['requested_ms']*1000
            if at not in points:continue
            if preview['dto_timestamp_ms']!=preview['requested_ms']:raise ValueError('Preview timestamp differs')
            path=run/'previews'/preview['file'];control=run/'previews'/preview['control_file'];im=cv2.imread(str(path))
            if im is None:raise ValueError('Missing native preview')
            pos=projection(points[at],(spec['width'],spec['height']),(0,0,1,1),(im.shape[1],im.shape[0]))
            result={'case':row['case'],'kind':'preview','source_pts_us':at,'source_point':points[at],'preview_sha256':sha(path),'control_sha256':sha(control)}
            result.update(probe(im,pos,spec['radius_source_pixels'],spec['minimum_red_fraction']));result['status']='passed' if result['passed'] else 'failed';rows.append(result)
    if not rows:raise ValueError('No known source points exercised')
    for i in range(0,len(boards),12):
        page=boards[i:i+12]
        while len(page)%4:page.append(np.zeros((180,180,3),dtype=np.uint8))
        board=np.vstack([np.hstack(page[j:j+4]) for j in range(0,len(page),4)])
        cv2.imwrite(str(dest/('known-faces-'+str(i//12)+'.jpg')),board,[cv2.IMWRITE_JPEG_QUALITY,75])
    value={'run_id':run.name,'binary_sha256':meta['binary_sha256'],'source_sha256':spec['source_sha256'],'spec_sha256':sha(SPEC),'auditor_sha256':sha(Path(__file__)),
      'scope':'Known source points omitted by automatic face scan; actual opaque-red pixels only, complements media and non-solid visual audits.','probes':rows,
      'status':'failed' if any(r['status']=='failed' for r in rows) else 'passed'}
    (dest/'results.json').write_text(json.dumps(value,indent=2)+'\n');print(value['status'],len(rows),'known face probes',dest)
    return value['status']=='failed'
if __name__=='__main__':sys.exit(main())
