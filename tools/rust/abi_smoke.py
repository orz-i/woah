#!/usr/bin/env python3
"""Exercise the actual C ABI and emit a host-independent geometry fingerprint."""
import argparse
import ctypes as c
import hashlib
import json


class Rect(c.Structure):
    _fields_ = [(name,c.c_float) for name in ('left','top','right','bottom')]


class Transform(c.Structure):
    _fields_ = [(name,c.c_float) for name in ('scale','pad_left','pad_top')] + [
        (name,c.c_uint32) for name in ('source_width','source_height','input_size','proto_size')]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library')
    args=parser.parse_args()
    lib=c.CDLL(args.library)
    lib.woah_core_abi_version.restype=c.c_uint32
    assert lib.woah_core_abi_version()==1
    assert (c.sizeof(Rect),c.sizeof(Transform))==(16,28)
    lib.woah_core_detail_views.argtypes=[c.c_uint32,c.c_uint32,c.POINTER(Rect)]
    lib.woah_core_detail_complete.argtypes=[Rect,Rect,c.c_uint32,c.c_uint32]
    lib.woah_core_reproject_mask.argtypes=[c.POINTER(c.c_uint8),c.c_size_t,Transform,
        Transform,Rect,c.POINTER(c.c_uint8),c.c_size_t]
    views=(Rect*4)()
    assert lib.woah_core_detail_views(3840,2160,views)==0
    assert (views[3].left,views[3].top,views[3].right,views[3].bottom)==(1536,864,3840,2160)
    assert lib.woah_core_detail_views(3840,2160,None)==-1
    assert lib.woah_core_detail_complete(Rect(100,0,500,1000),views[3],3840,2160)==0
    assert lib.woah_core_detail_complete(Rect(100,100,500,1000),views[3],3840,2160)==1
    def transform(w,h):
        scale=c.c_float(min(640/w,640/h)).value
        return Transform(scale,(640-c.c_float(w*scale).value)/2,
                         (640-c.c_float(h*scale).value)/2,w,h,640,160)
    source=(c.c_uint8*(160*160))(*([255]*(160*160)))
    result=(c.c_uint8*(160*160))()
    assert lib.woah_core_reproject_mask(source,len(source),transform(2304,1296),
        transform(3840,2160),views[2],result,len(result))==0
    assert lib.woah_core_reproject_mask(None,0,transform(2304,1296),
        transform(3840,2160),views[2],result,len(result))==-1
    print(json.dumps({'abi':1,'geometry_sha256':hashlib.sha256(bytes(result)).hexdigest()}))


if __name__=='__main__':
    main()
