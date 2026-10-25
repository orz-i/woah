#!/usr/bin/env python3
"""Make local M0 frame-coded test copies of two explicitly selected real videos.

Adds a 64px footer; original picture rows and audio timing are retained before
video re-encoding. FPS variants are derived test inputs, not native recordings.
No source filename/URI or media bytes are committed by this tool.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from m0_pixels import rect, text, timecode

FOOTER_HEIGHT = 64

def frame_code_spec(width: int, height: int) -> dict:
    if width < 192 or height < 1:
        raise ValueError('Source is too small for an unambiguous frame-code footer')
    return {'bits':20,'x':24,'y':height+44,'cellWidth':(width-48)//20,
            'height':16,'order':'most_significant_bit_first'}

def annotate_frame(raw: bytes, width: int, height: int, index: int,
                   fps: int, role: str) -> bytearray:
    if len(raw)!=width*height*3 or not 0<=index<2**20 or fps not in (24,30,60):
        raise ValueError('Invalid RGB frame, code index, or reference FPS')
    code=frame_code_spec(width,height)
    frame=bytearray(raw);frame.extend(bytes(width*FOOTER_HEIGHT*3))
    scale=3 if width>=480 else 2 if width>=320 else 1
    text(frame,width,f'{role} F{index:06d} {timecode(index*1000000//fps)}',24,height+8,scale)
    for bit in range(20):
        color=b'\xff\xff\xff' if index & (1<<(19-bit)) else b'\0\0\0'
        rect(frame,width,code['x']+bit*code['cellWidth'],code['y'],code['cellWidth'],code['height'],color)
    return frame

def sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest()

def probe(path: Path) -> dict:
    r=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_type,codec_name,width,height,start_time,duration,avg_frame_rate,nb_frames','-of','json',str(path)],capture_output=True,text=True,check=True)
    data=json.loads(r.stdout);video=next(x for x in data['streams'] if x['codec_type']=='video')
    audio=next(x for x in data['streams'] if x['codec_type']=='audio')
    if float(video['start_time'])!=0 or float(audio['start_time'])!=0:
        raise ValueError('Nonzero stream origins need an explicit timing contract')
    return data

def render(source: Path, output: Path, fps: int, label: str, info: dict) -> dict:
    v=next(x for x in info['streams'] if x['codec_type']=='video');w,h=v['width'],v['height']
    with tempfile.TemporaryFile() as decode_errors,tempfile.TemporaryFile() as encode_errors:
        decoder=subprocess.Popen(['ffmpeg','-v','error','-i',str(source),'-map','0:v:0','-vf',f'fps={fps}', '-pix_fmt','rgb24','-f','rawvideo','pipe:1'],stdout=subprocess.PIPE,stderr=decode_errors)
        encoder=subprocess.Popen(['ffmpeg','-v','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s',f'{w}x{h+FOOTER_HEIGHT}','-framerate',str(fps),'-i','pipe:0','-i',str(source),'-map','0:v:0','-map','1:a:0','-c:v','libx265','-preset','veryfast','-pix_fmt','yuv420p','-crf','20','-x265-params',f'log-level=error:pools=2:frame-threads=2:keyint={fps*2}:bframes=3','-tag:v','hvc1','-c:a','copy','-video_track_timescale','90000','-movflags','+faststart',str(output)],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=encode_errors)
        count=0
        try:
            assert decoder.stdout and encoder.stdin
            while True:
                raw=decoder.stdout.read(w*h*3)
                if not raw:break
                encoder.stdin.write(annotate_frame(raw,w,h,count,fps,label));count+=1
            encoder.stdin.close()
            if decoder.wait() or encoder.wait():raise RuntimeError('Real fixture codec process failed')
        except Exception as e:
            decoder.kill();encoder.kill();decoder.wait();encoder.wait()
            decode_errors.seek(0);encode_errors.seek(0)
            raise RuntimeError((decode_errors.read()+encode_errors.read()).decode('utf-8',errors='replace')) from e
        finally:decoder.stdout.close()
    data=probe(output)
    encoded=next(x for x in data['streams'] if x['codec_type']=='video')
    if int(encoded['nb_frames'])!=count or [encoded['width'],encoded['height']]!=[w,h+FOOTER_HEIGHT]:
        raise ValueError('Encoded frame count or canvas differs from RGB input')
    frames=subprocess.run(['ffprobe','-v','error','-select_streams','v:0','-show_frames','-show_entries','frame=best_effort_timestamp_time','-of','json',str(output)],capture_output=True,text=True,check=True)
    pts=[round(float(x['best_effort_timestamp_time'])*1000000) for x in json.loads(frames.stdout)['frames']]
    if len(pts)!=count or any(abs(t-i*1000000/fps)>2 for i,t in enumerate(pts)):
        raise ValueError('Encoded PTS no longer matches the printed source frame code')
    subprocess.run(['ffmpeg','-v','error','-i',str(output),'-f','null','-'],check=True,stdout=subprocess.DEVNULL)
    return {'file':output.name,'referenceCodeFps':fps,'frames':count,'sha256':sha256(output),'videoSize':[w,h+FOOTER_HEIGHT],'originalPictureSize':[w,h],'frameCode':frame_code_spec(w,h),'probe':data}

def main() -> int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--teacher',type=Path,required=True);p.add_argument('--learner',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();out=a.out.resolve()
    if out.exists():p.error('Use a new output directory that does not exist')
    out.parent.mkdir(parents=True,exist_ok=True)
    sources={'teacher':a.teacher,'learner':a.learner};info={k:probe(v) for k,v in sources.items()}
    manifest={'schema':'woah.comparison.m0.real-fixtures.v1','scope':'derived_real_picture_inputs_not_native_fps_recordings','manualOffsetUs':0,'musicAlignment':'UNCALIBRATED_SOURCE_TIME_SCHEDULING_ONLY','footerHeight':FOOTER_HEIGHT,'sourceSha256':{k:sha256(v) for k,v in sources.items()},'variants':{}}
    with tempfile.TemporaryDirectory(prefix='real-m0-',dir=out.parent) as tmp:
        stage=Path(tmp).resolve();assert stage.is_relative_to(out.parent.resolve())
        for role,fps in [('teacher',30),('learner',30),('learner',24),('learner',60)]:
            key=f'{role}-{fps}';dest=stage/(key+'.mp4')
            manifest['variants'][key]=render(sources[role],dest,fps,'T' if role=='teacher' else 'L',info[role])
            print('PREPARED',key,manifest['variants'][key]['frames'],flush=True)
        dest=stage/'learner-vfr.mp4'
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(stage/'learner-30.mp4'),'-vf',"select='not(eq(mod(n,7),2))'",'-fps_mode','vfr','-c:v','libx265','-preset','veryfast','-pix_fmt','yuv420p','-x265-params','log-level=error:pools=2:frame-threads=2:keyint=60:bframes=3','-tag:v','hvc1','-c:a','copy','-video_track_timescale','90000',str(dest)],check=True)
        subprocess.run(['ffmpeg','-v','error','-i',str(dest),'-f','null','-'],check=True,stdout=subprocess.DEVNULL)
        item=dict(manifest['variants']['learner-30']);item.update(file=dest.name,sha256=sha256(dest),probe=probe(dest),vfr=True)
        item.pop('frames');manifest['variants']['learner-vfr']=item
        (stage/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
        if out.exists():raise FileExistsError('Output was created by another operation')
        stage.rename(out)
    print('REAL_FIXTURES_READY',out,flush=True);return 0

if __name__=='__main__':raise SystemExit(main())
