"""Verify a comparison MP4 against its frozen export spec and original sources.

Requires ffmpeg/ffprobe on PATH and the project's NumPy/OpenCV/SciPy environment.
--coded-fixtures additionally checks the 640x360, 30fps binary source-frame strips
created with tools/comparison/m0_pixels.py, after independent mirror/zoom/pan mapping.
"""
import argparse,json,subprocess,math,bisect
from fractions import Fraction
from pathlib import Path
import numpy as np,cv2
from scipy import signal

def probe(path):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_frames',
      '-show_format','-show_entries','format=duration:stream=codec_name,codec_type,width,height,duration,sample_rate,channels,start_time,time_base:frame=media_type,best_effort_timestamp,best_effort_timestamp_time,pkt_duration_time',
      '-of','json',str(path)]))
def frame_times(info,kind='video'):
    time_base=Fraction(next(s['time_base'] for s in info['streams'] if s['codec_type']==kind))
    return [int(f['best_effort_timestamp'])*time_base for f in info['frames'] if f['media_type']==kind]

def pts(info,kind='video'):
    return [round(t*1_000_000) for t in frame_times(info,kind)]

def source_correspondence(reference_pts,target_pts,source_seconds,time_scale,offset_us):
    # Frame lookup must precede quantization: 11/6 * .8 + 1.2 is exactly
    # 8/3, whereas rounding the reference first falsely selects its predecessor.
    reference=reference_pts[bisect.bisect_right(reference_pts,source_seconds)-1]
    target_seconds=source_seconds*Fraction(str(time_scale))+Fraction(offset_us,1_000_000)
    target=target_pts[bisect.bisect_right(target_pts,target_seconds)-1]
    return reference,target

def panes(spec):
    w,h,g=spec['width'],spec['height'],spec['gap']
    if spec['sideBySide']:
        first=(w-g)//2;return [(0,0,first,h),(first+g,0,w-first-g,h)]
    first=(h-g)//2;return [(0,0,w,first),(0,first+g,w,h-first-g)]

def transform(spec,index):
    x,y,w,h=panes(spec)[index];fw=min(w,int(h*640/360));fh=min(h,int(w*360/640))
    fx=x+(w-fw)//2;fy=y+(h-fh)//2
    p=spec[['teacherPicture','learnerPicture'][index]];z=p['zoom']
    cx=(2*(fx-x+fw/2)/w-1)*z;cy=(1-2*(fy-y+fh/2)/h)*z
    tx=0 if z==1 else abs(fw/w*z-1);ty=0 if z==1 else abs(fh/h*z-1)
    sx=cx if z==1 else max(-tx,min(tx,cx+p['panX']*tx))
    sy=cy if z==1 else max(-ty,min(ty,cy-p['panY']*ty))
    return x,y,w,h,fw/w*z,fh/h*z,sx,sy

def code_pixels(image,spec,index,expected):
    x,y,w,h,zx,zy,sx,sy=transform(spec,index);mirror=spec[['mirrorTeacher','mirrorLearner'][index]]
    checked=0;errors=[]
    for bit in range(20):
        u=(24+(bit+.5)*26)/640;v=264/360
        if mirror:u=1-u
        px=round(x+(1+sx+(2*u-1)*zx)*w/2-.5)
        py=round(y+(1-sy+(2*v-1)*zy)*h/2-.5)
        if x+2<=px<x+w-2 and y+2<=py<y+h-2:
            value=float(np.median(image[py-1:py+2,px-1:px+2]));want=bool(expected & (1<<(19-bit)))
            if (value>128)!=want:errors.append(dict(bit=bit,value=value,expected=want,x=px,y=py))
            checked+=1
    assert checked>=12,(index,checked)
    assert not errors,(index,expected,errors)
    return checked

def pcm(path,rate):
    return np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-map','0:a:0','-ac','1','-ar',str(rate),'-f','f32le','pipe:1']),dtype=np.float32)

def validate(video,record,ref,target,report_path,coded_fixtures=False,snapshot=None):
    label=video.stem;s=record.get('spec',record)
    ri,ti,oi=map(probe,[ref,target,video]);rp,tp,op=map(pts,[ri,ti,oi])
    reference_pts,target_pts=map(frame_times,[ri,ti])
    a,b=s['timeScale'],s['offsetUs'];origin=max(rp[0],math.ceil((tp[0]-b)/a))
    start=origin+s['startUs'];end=origin+s['endUs'];duration=end-start
    exact_start,exact_end=Fraction(start,1_000_000),Fraction(end,1_000_000)
    exact_expect=[Fraction(0)]+[t-exact_start for t in reference_pts if exact_start<t<exact_end]
    expect=[round(t*1_000_000) for t in exact_expect]
    assert len(expect)==len(op),(len(expect),len(op))
    error=max(abs(a-b) for a,b in zip(expect,op));assert error<=20,error
    v=next(v for v in oi['streams'] if v['codec_type']=='video');audio=next(v for v in oi['streams'] if v['codec_type']=='audio')
    assert v['codec_name']=='h264' and audio['codec_name']=='aac';assert (v['width'],v['height'])==(s['width'],s['height'])
    duration_error=abs(round(float(v['duration'])*1e6)-duration);assert duration_error<=20,duration_error
    audio_duration_error=abs(round(float(audio['duration'])*1e6)-duration);assert audio_duration_error<=100,audio_duration_error
    container_error=abs(round(float(oi['format']['duration'])*1e6)-duration);assert container_error<=100,container_error
    indices=sorted(set([0,len(op)-1]+[round((len(op)-1)*i/10) for i in range(1,10)])) if coded_fixtures else ([len(op)//2] if snapshot else [])
    selector='+'.join('eq(n,'+str(i)+')' for i in indices)
    raw=b'' if not indices else subprocess.check_output(['ffmpeg','-v','error','-i',str(video),'-map','0:v:0','-vf','select='+selector.replace(',',r'\,'),'-fps_mode','vfr','-f','rawvideo','-pix_fmt','rgb24','pipe:1'])
    frames=np.frombuffer(raw,dtype=np.uint8).reshape(len(indices),s['height'],s['width'],3)
    checks=[]
    for n,frame in zip(indices,frames):
        if not coded_fixtures: continue
        rt,tt=source_correspondence(reference_pts,target_pts,exact_start+exact_expect[n],a,b)
        rc,tc=round(rt*30),round(tt*30)
        bits=[code_pixels(frame,s,0,rc),code_pixels(frame,s,1,tc)]
        checks.append(dict(outputUs=op[n],referencePtsUs=round(rt*1_000_000),targetPtsUs=round(tt*1_000_000),codes=[rc,tc],checkedBits=bits))
    if snapshot: cv2.imwrite(str(snapshot),cv2.cvtColor(frames[len(frames)//2],cv2.COLOR_RGB2BGR))
    rate=int(audio['sample_rate']);out=pcm(video,rate)
    assert len(out)>=round(duration*rate/1e6)-math.ceil(rate*.0001),(len(out),duration,rate)
    if any(v['codec_type']=='audio' for v in ri['streams']):
        reference=pcm(ref,rate);first=math.ceil(start*rate/1e6);segment=reference[first:first+min(len(out),round(duration*rate/1e6))]
        # A second lossy AAC generation may change samples; position must remain within 2ms.
        x=out[:len(segment)];corr=signal.correlate(x,segment,mode='full',method='fft');lag=int(signal.correlation_lags(len(x),len(segment))[np.argmax(corr)])
        normalized=float(np.max(corr)/(np.linalg.norm(x)*np.linalg.norm(segment)))
        assert abs(lag)<=round(rate*.002),(lag,rate)
        assert normalized>.97,normalized
        audio_check=dict(sampleRate=rate,lagSamples=lag,lagUs=round(lag*1e6/rate),correlation=normalized,decodedSamples=len(out))
    else:
        rms=float(np.sqrt(np.mean(out**2)));assert rms<1e-4,rms
        audio_check=dict(sampleRate=rate,silentRms=rms,decodedSamples=len(out))
    report=dict(label=label,width=s['width'],height=s['height'],durationUs=duration,frameCount=len(op),
       ptsMaxErrorUs=error,durationErrorUs=duration_error,audioDurationErrorUs=audio_duration_error,containerDurationErrorUs=container_error,frameIntervalsUs=sorted(set(b-a for a,b in zip(op,op[1:]))),
       sourceOriginUs=origin,timeScale=a,offsetUs=b,sideBySide=s['sideBySide'],frames=checks,audio=audio_check)
    report_path.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='frames'},ensure_ascii=False))
    return report
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('output','spec','reference','target','report'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--coded-fixtures',action='store_true')
    parser.add_argument('--snapshot',type=Path)
    args=parser.parse_args()
    validate(args.output,json.loads(args.spec.read_text(encoding='utf-8')),args.reference,args.target,
        args.report,args.coded_fixtures,args.snapshot)
