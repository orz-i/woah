#!/usr/bin/env python3
"""Produce compact source/output boards; never declares a visual pass."""
import argparse,json,pathlib,hashlib
import cv2
import numpy as np
ROOT=pathlib.Path(__file__).resolve().parents[2]
REPORT=ROOT/'reports/privacy-ios-22-and-4k-regression-2026-11-05'
SOURCES=ROOT/'reports/privacy-ios-regression-2026-11-04/artifacts/sources'

def read_frame(cap,n):
    cap.set(cv2.CAP_PROP_POS_FRAMES,n)
    ok,image=cap.read()
    if not ok:raise ValueError(f'Missing frame {n}')
    return image

def fit(image,width=320,height=240):
    h,w=image.shape[:2];scale=min(width/w,height/h)
    resized=cv2.resize(image,(max(1,round(w*scale)),max(1,round(h*scale))))
    tile=np.full((height,width,3),235,np.uint8)
    y=(height-resized.shape[0])//2;x=(width-resized.shape[1])//2
    tile[y:y+resized.shape[0],x:x+resized.shape[1]]=resized
    return tile

def main():
    p=argparse.ArgumentParser();p.add_argument('run_id');args=p.parse_args()
    run=REPORT/'artifacts'/args.run_id;data=json.loads((run/'results.json').read_text())
    meta=json.loads((run/'run-metadata.json').read_text());dest=run/'visual-boards';dest.mkdir(exist_ok=True)
    records=[]
    previous={r['case']:r for r in json.loads((dest/'index.json').read_text())['cases']} if (dest/'index.json').exists() else {}
    for i,row in enumerate(data['cases']):
        if row['result']!='output_created':continue
        movie=run/'outputs'/(row['case']+'.mp4')
        movie_sha=hashlib.sha256(movie.read_bytes()).hexdigest()
        requires_crop_trace=row.get('hero_id') is not None
        trace_names=meta.get('crop_traces',[])
        trace_path=run/'crop-traces'/trace_names[i] if i<len(trace_names) else None
        if requires_crop_trace and (trace_path is None or not trace_path.exists()):
            print('WAITING_FOR_FINAL_CROP_TRACE',row['case'],flush=True)
            continue
        trace_sha=hashlib.sha256(trace_path.read_bytes()).hexdigest() if requires_crop_trace else None
        if (previous.get(row['case'],{}).get('movie_sha256')==movie_sha and
                previous[row['case']].get('crop_trace_sha256')==trace_sha):
            records.append(previous[row['case']]);continue
        cap=cv2.VideoCapture(str(movie));source=cv2.VideoCapture(str(SOURCES/row['input']))
        frames=int(cap.get(cv2.CAP_PROP_FRAME_COUNT));samples={0,frames//2,frames-1}
        if row['output_width']==3840:
            samples.update(f-round(row['start_ms']*.025) for f in [145,290,411,580] if 0<=f-round(row['start_ms']*.025)<frames)
        if row['output_height']==4096:
            samples.update(f-round(row['start_ms']*.025) for f in [0,217,330,556,557,558,559,560,561,562,563,661] if 0<=f-round(row['start_ms']*.025)<frames)
        trace=None
        if i<len(meta.get('crop_traces',[])):
            trace=[json.loads(s) for s in (run/'crop-traces'/meta['crop_traces'][i]).read_text().splitlines()][1:]
        tiles=[];primary=[]
        for n in sorted(samples):
            image=read_frame(cap,n);source_n=round(row['start_ms']*.025)+n
            original=read_frame(source,source_n);sh,sw=original.shape[:2]
            crop=trace[n]['source_crop_normalized'] if trace else [0,0,1,1]
            a,b,c,d=crop
            original=original[max(0,int(b*sh)):min(sh,int(d*sh)),max(0,int(a*sw)):min(sw,int(c*sw))]
            pair=np.concatenate([fit(original),fit(image)],axis=1)
            label=np.full((25,640,3),255,np.uint8)
            cv2.putText(label,f"{row['case']} f{n} src{source_n}",(5,18),cv2.FONT_HERSHEY_SIMPLEX,.43,(0,0,0),1)
            tile=np.concatenate([label,pair]);tiles.append(tile)
            if n in {0,frames//2,frames-1}:primary.append(tile)
            # Original-size 4K critical face detail, not a resized full-frame claim.
            if row['output_width']==3840 and source_n in [145,290,411,580]:
                x,y={145:(1372,682),290:(893,1275),411:(835,541),580:(1402,1322)}[source_n]
                raw=read_frame(source,source_n)
                pair=np.concatenate([fit(raw[y-110:y+150,x-130:x+130]),fit(image[y-110:y+150,x-130:x+130])],axis=1)
                label=np.full((25,640,3),255,np.uint8);cv2.putText(label,f"critical source f{source_n}",(5,18),cv2.FONT_HERSHEY_SIMPLEX,.5,(0,0,0),1)
                tile=np.concatenate([label,pair]);tiles.append(tile)
                if source_n in [0,145,217,290,330,411,557,580,661]:primary.append(tile)
            elif row['output_height']==4096 and source_n in [0,217,330,556,557,558,559,560,561,562,563,661]:
                x,y={0:(1224,1112),330:(1328,1416),217:(1375,1338),661:(1048,1152),556:(1320,1180),557:(1304,1180),558:(1288,1180),559:(1272,1176),560:(1243,1173),561:(1214,1177),562:(1187,1179),563:(1168,1186)}[source_n]
                raw=read_frame(source,source_n)
                pair=np.concatenate([fit(raw[y-140:y+180,x-160:x+160]),fit(image[y-140:y+180,x-160:x+160])],axis=1)
                label=np.full((25,640,3),255,np.uint8);cv2.putText(label,f"critical source f{source_n}",(5,18),cv2.FONT_HERSHEY_SIMPLEX,.5,(0,0,0),1)
                tile=np.concatenate([label,pair]);tiles.append(tile)
                if source_n in [0,145,217,290,330,411,557,580,661]:primary.append(tile)
        cap.release();source.release()
        for start in range(0,len(primary),4):
            cv2.imwrite(str(dest/f"{row['case']}-overview-{start//4}.jpg"),np.concatenate(primary[start:start+4]))
        for start in range(0,len(tiles),4):
            canvas=np.concatenate(tiles[start:start+4])
            name=f"{row['case']}-{start//4}.jpg";cv2.imwrite(str(dest/name),canvas)
        records.append({'case':row['case'],'movie_sha256':movie_sha,'crop_trace_sha256':trace_sha,'sampled_output_frames':sorted(samples),
          'boards':[str(x.relative_to(run)) for x in sorted(dest.glob(row['case']+'-*.jpg'))]})
    (dest/'index.json').write_text(json.dumps({'run_id':args.run_id,'cases':records},indent=2)+'\n')
    print('BOARDS_READY',args.run_id,len(records))

if __name__=='__main__':main()
