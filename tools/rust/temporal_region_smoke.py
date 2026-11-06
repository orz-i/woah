#!/usr/bin/env python3
"""C ABI corpus independently matched to frozen Kotlin temporal arithmetic."""
import argparse,ctypes as c,hashlib,json,struct
from abi_smoke import Rect
EXPECTED='905b408fc4b329704fcb6541e6370aadd77b3f0bc9acc5d3db6a0b2cf219f5a8'
class Ellipse(c.Structure):
    _fields_=[(n,c.c_float) for n in ['center_x','center_y','radius_x','radius_y']]
class State(c.Structure):
    _fields_=[('output',Ellipse)]+[(n,c.c_float) for n in ['reference_radius_x','reference_radius_y','reference_width','reference_height']]+[('anchor',Rect),('timestamp_us',c.c_int64),('flags',c.c_uint32)]
class Measurement(c.Structure):
    _fields_=[('region',Ellipse),('anchor',Rect),('timestamp_us',c.c_int64),('kind',c.c_uint32),('flags',c.c_uint32)]
def corpus():
    random=0x1d2e3f4
    def next_int(limit):
        nonlocal random
        random=(random*1664525+1013904223)&0xffffffff
        return (random>>8)%limit
    for sequence in range(32):
        left,top=next_int(8000)/4,next_int(4000)/4
        width,height=100+next_int(1000)/4,300+next_int(2000)/4
        previous=[left,top,left+width,top+height];pts=0
        for index in range(64):
            left+=(next_int(80)-40)/4;top+=(next_int(80)-40)/4
            anchor=[left,top,left+width+(next_int(200)-100)/4,top+height+(next_int(200)-100)/4]
            raw=[left+(next_int(400)-200)/4,top+(next_int(400)-200)/4,10+next_int(200)/4,10+next_int(200)/4]
            kind=next_int(3);observed=next_int(2)==0;trusted=next_int(5)==0
            pts+=-1000 if index>0 and index%31==0 else [0,8333,16667,33333,40000,100000,250000,500000][next_int(8)]
            flags=int(observed)|(int(trusted)<<1)|(int(sequence%2==0)<<2)
            yield sequence,index,Measurement(Ellipse(*raw),Rect(*anchor),pts,kind,flags),Rect(*previous)
            previous=anchor

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('library');a=p.parse_args();lib=c.CDLL(a.library)
    lib.frame_kernel_region_step.argtypes=[c.POINTER(State),Measurement,c.POINTER(Ellipse)]
    lib.frame_kernel_anchor_translation.argtypes=[Rect,Rect,c.POINTER(c.c_float)]
    digest=hashlib.sha256();count=0
    for sequence,index,m,previous in corpus():
        if index==0:state=State()
        output=Ellipse();assert lib.frame_kernel_region_step(c.byref(state),m,c.byref(output))==0
        digest.update(struct.pack('<4f',*[getattr(output,n) for n,_ in Ellipse._fields_]))
        delta=(c.c_float*2)();assert lib.frame_kernel_anchor_translation(previous,m.anchor,delta)==0
        digest.update(struct.pack('<2f',*delta));count+=1
    before=bytes(state);m.region.center_x=float('nan');output=Ellipse(42,42,42,42)
    assert lib.frame_kernel_region_step(c.byref(state),m,c.byref(output))==-1
    assert bytes(state)==before and output.center_x==42
    assert lib.frame_kernel_region_step(None,m,c.byref(output))==-1
    delta=(c.c_float*2)(42,99);assert lib.frame_kernel_anchor_translation(Rect(0,0,0,0),m.anchor,delta)==-1 and list(delta)==[42,99]
    result=digest.hexdigest();assert result==EXPECTED,result
    print(json.dumps({'temporal_cases':count,'translation_cases':count,'region_state_bytes':c.sizeof(State),'temporal_vector_sha256':result}))
if __name__=='__main__':main()
