#!/usr/bin/env python3
"""Cross-host PTS camera corpus shared with the native reference tests."""
import argparse
import ctypes as c
import hashlib
import json
import struct
from abi_smoke import Rect

EXPECTED='8f276242a6773df19a18a4b7c63af042da1f5d2b1eea8f84d397ce3b1bb6a511'
FIELDS=('camera_x','camera_y','target_x','target_y','velocity_x','velocity_y')

class State(c.Structure):
    _fields_=[(name,c.c_float) for name in FIELDS]+[('last_pts_us',c.c_int64),('initialized',c.c_uint32),('has_time',c.c_uint32)]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('library');args=p.parse_args()
    lib=c.CDLL(args.library)
    lib.frame_kernel_follow_reset.argtypes=[c.POINTER(State)]
    lib.frame_kernel_follow_step.argtypes=[c.POINTER(State),Rect,c.c_int32,c.c_int64,*([c.c_float]*4),c.POINTER(Rect)]
    lib.frame_kernel_fit_even_ratio.argtypes=[*([c.c_uint32]*5),c.POINTER(c.c_uint32)]
    assert c.sizeof(State)==40
    f=lambda x:c.c_float(x).value
    digest=hashlib.sha256()
    for scenario in range(2):
        state=State();assert lib.frame_kernel_follow_reset(c.byref(state))==0
        pts=0
        for i in range(240):
            pts+=[0,16667,33333,40000,100000,500000][i%6]
            if i==140:pts=0
            if i==200:assert lib.frame_kernel_follow_reset(c.byref(state))==0
            x=f(.2 if i<20 else .8 if i<110 else .15 if i<180 else .9)
            missing=60<=i<=89 or (scenario==1 and i<3)
            target=Rect(f(x-f(.04)),.3,f(x+f(.04)),.7);output=Rect()
            assert lib.frame_kernel_follow_step(c.byref(state),target,not missing,pts,
                f(16/9) if scenario==0 else f(.4),f(9/16),1 if scenario==0 else f(1.9),
                f(.1) if scenario==0 else f(.15),c.byref(output))==0
            digest.update(struct.pack('<10f',output.left,output.top,output.right,output.bottom,
                *[getattr(state,name) for name in FIELDS]))
        before=bytes(state);output=Rect(42,42,42,42)
        assert lib.frame_kernel_follow_step(c.byref(state),target,1,pts,0,1,1,.1,c.byref(output))==-1
        assert bytes(state)==before and output.left==42
    size=(c.c_uint32*2)(42,42)
    assert lib.frame_kernel_fit_even_ratio(1920,1080,1280,9,16,size)==0 and list(size)==[594,1056]
    assert lib.frame_kernel_fit_even_ratio(17,31,1280,9,16,size)==-1 and list(size)==[594,1056]
    assert digest.hexdigest()==EXPECTED,digest.hexdigest()
    print(json.dumps({'camera_frames':480,'follow_vector_sha256':digest.hexdigest()}))

if __name__=='__main__':main()
