#!/usr/bin/env python3
"""Cross-host C ABI corpus also exercised by Kotlin and Swift frozen references."""
import argparse
import ctypes as c
import hashlib
import json
import struct
from abi_smoke import Rect, Transform

EXPECTED = 'f70e4b84ef18dea594bda49968cdaec9ef52a72e55976b4d776b6974986db8e9'

class Spec(c.Structure):
    _fields_ = [(name,c.c_uint32) for name in ('anchors','attributes','score_index','coefficient_index',
        'channels','head_transposed','proto_nhwc','coordinate_units')]

class Thresholds(c.Structure):
    _fields_ = [(name,c.c_float) for name in ('confidence','bbox_iou','mask_iou')]

class Detection(c.Structure):
    _fields_ = [('bounds',Rect),('confidence',c.c_float)]

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('library'); args=parser.parse_args()
    lib=c.CDLL(args.library)
    lib.frame_kernel_decode_instances.argtypes=[c.POINTER(c.c_float),c.c_size_t,c.POINTER(c.c_float),c.c_size_t,
        Spec,Transform,Thresholds,c.POINTER(Detection),c.c_size_t,c.POINTER(c.c_uint8),c.c_size_t,c.c_void_p]
    lib.frame_kernel_decode_instances.restype=c.c_int32
    assert c.sizeof(Spec)==32 and c.sizeof(Detection)==20
    anchors,pixels=8400,160*160
    f=lambda v:c.c_float(v).value
    head=(c.c_float*(116*anchors))()
    for a in range(4):
        for attr,value in [(0,f(f(.4)+f(a*f(.005)))),(1,.5),(2,.22),(3,.4),(4,f(f(.9)-f(a*f(.1))))]:
            head[attr*anchors+a]=value
        for channel in range(32): head[(84+channel)*anchors+a]=f(((channel*11%19)-9)/13)*(-1 if a==3 else 1)
    proto=(c.c_float*(32*pixels))(*(((i*17%41)-20)/23 for i in range(32*pixels)))
    spec=Spec(anchors,116,4,84,32,0,0,2)
    transform=Transform(f(640/3840),0,140,3840,2160,640,160)
    thresholds=Thresholds(.25,.5,.5)
    def decode(detections,capacity,masks,mask_capacity):
        return lib.frame_kernel_decode_instances(head,len(head),proto,len(proto),spec,transform,thresholds,
            detections,capacity,masks,mask_capacity,None)
    status=decode(None,0,None,0)
    assert status<=-2,status
    count=-status-1
    detections=(Detection*count)(); masks=(c.c_uint8*(count*pixels))()
    assert decode(detections,count,masks,len(masks))==count
    digest=hashlib.sha256()
    for i,d in enumerate(detections):
        digest.update(struct.pack('<5f',d.bounds.left,d.bounds.top,d.bounds.right,d.bounds.bottom,d.confidence))
        digest.update(bytes(masks[i*pixels:(i+1)*pixels]))
    assert digest.hexdigest()==EXPECTED,digest.hexdigest()
    print(json.dumps({'instances':count,'instance_vector_sha256':digest.hexdigest()}))

if __name__=='__main__': main()
