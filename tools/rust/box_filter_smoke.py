#!/usr/bin/env python3
"""Cross-host state/covariance/PTS/gating corpus for the shared box filter."""
import argparse
import ctypes as c
import hashlib
import json
import struct
from abi_smoke import Rect

EXPECTED='e6d1cb740e6c0cf00ae01d954401505b1c8f8351eb771e1398bcf3969e1923fd'
class State(c.Structure):
    _fields_=[('mean',c.c_float*8),('covariance',c.c_float*64),('timestamp_us',c.c_int64)]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('library');lib=c.CDLL(p.parse_args().library)
    lib.frame_kernel_box_filter_step.argtypes=[c.POINTER(State),c.c_uint32,Rect,c.c_int64,c.c_float,c.POINTER(Rect),c.POINTER(c.c_float)]
    lib.frame_kernel_invert_matrix4.argtypes=[c.POINTER(c.c_float),c.POINTER(c.c_float)]
    assert c.sizeof(State)==296
    digest=hashlib.sha256()
    for scenario in range(2):
        state=State();output=Rect();distance=c.c_float();pts=1
        def step(op,box,pts,factor=1):
            assert lib.frame_kernel_box_filter_step(c.byref(state),op,box,pts,factor,c.byref(output),c.byref(distance))==0
        step(0,Rect(10,20,90,180),pts)
        for i in range(240):
            pts += [0,16667,33333,40000,100000,500000][i%6]
            if i==140:pts=0
            x=10+(i%120)*3 if scenario==0 else 500-(i%120)*2
            target=Rect(x,20+i%17,x+80+(i%5)/4,180+i%17+(i%7)/2)
            if i==200:step(0,target,pts)
            step(1,target,pts)
            step(4,target,pts);gate=distance.value
            if not 60<=i<=89:step(2,target,pts)
            if i%29==0:step(3,target,pts,.7)
            step(5,target,pts)
            digest.update(struct.pack('<77f',output.left,output.top,output.right,output.bottom,gate,*state.mean,*state.covariance))
        before=bytes(state);output.left=42;distance.value=42
        assert lib.frame_kernel_box_filter_step(c.byref(state),2,Rect(float('nan'),0,1,1),pts,1,c.byref(output),c.byref(distance))==-1
        assert bytes(state)==before and output.left==42 and distance.value==42
    matrix=(c.c_float*16)();inverse=(c.c_float*16)(*([42]*16))
    assert lib.frame_kernel_invert_matrix4(matrix,inverse)==1 and list(inverse)==[42]*16
    result=digest.hexdigest();assert not EXPECTED or result==EXPECTED,result
    print(json.dumps({'box_filter_frames':480,'box_filter_vector_sha256':result}))

if __name__=='__main__':main()
