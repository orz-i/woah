#!/usr/bin/env python3
"""Check the compiled projection C ABI before native consumers use it."""
import argparse
import ctypes as c
from abi_smoke import Rect
from temporal_region_smoke import Ellipse

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('library');a=p.parse_args()
    lib=c.CDLL(a.library)
    lib.frame_kernel_project_face_region.argtypes=[Rect,Rect,Ellipse,c.c_int64,c.POINTER(Ellipse)]
    lib.frame_kernel_project_face_region.restype=c.c_int32
    previous=Rect(10,20,110,320);current=Rect(30,30,130,330);seed=Ellipse(60,65,20,30);out=Ellipse()
    assert lib.frame_kernel_project_face_region(current,previous,seed,150000,c.byref(out))==0
    assert [getattr(out,n) for n,_ in Ellipse._fields_]==[80,75,22,33]
    saved=bytes(out)
    assert lib.frame_kernel_project_face_region(Rect(),previous,seed,0,c.byref(out))==-1 and bytes(out)==saved
    assert lib.frame_kernel_project_face_region(current,previous,seed,0,None)==-1
    print('TRUSTED_REGION_PROJECTION_C_ABI=PASS success=0 invalid=-1 unchanged_failure_output=true')
if __name__=='__main__':main()
