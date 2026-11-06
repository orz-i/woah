#!/usr/bin/env python3
"""Portable pixel corpus and an independent fixed-point sampling oracle."""
import argparse
import ctypes as c
import hashlib
import json
import math
import struct

EXPECTED = '3fb3f8996f0cf401e8b9d707d51798c90774c5880d143dc3d558ea48c1783d51'
class Plane(c.Structure):
    _fields_=[('data',c.POINTER(c.c_uint8)),('len',c.c_size_t),('row_stride',c.c_uint32),('pixel_stride',c.c_uint32)]
class YuvSpec(c.Structure):
    _fields_=[(name,c.c_uint32) for name in ('width','height','crop_left','crop_top','crop_right','crop_bottom',
        'display_width','display_height','rotation','output_width','output_height','flip_rows','matrix','full_range','fill')]
class PixelSpec(c.Structure):
    _fields_=[(name,c.c_uint32) for name in ('width','height','row_stride','channels','red','green','blue','flip_rows','flip_columns','interleaved')]+[('scale',c.c_float),('bias',c.c_float)]
f=lambda x:c.c_float(x).value

def reference(planes,s):
    scale=min(f(16/s.display_width),f(16/s.display_height))
    w=f(s.display_width*scale);h=f(s.display_height*scale)
    left=f(f(16-w)/2);top=f(f(16-h)/2)
    swap=s.rotation in (90,270)
    dw=(s.crop_bottom-s.crop_top) if swap else (s.crop_right-s.crop_left)
    dh=(s.crop_right-s.crop_left) if swap else (s.crop_bottom-s.crop_top)
    def sample(plane,x,y,width,height):
        x=max(0,min(width-1,x));y=max(0,min(height-1,y))
        x0=math.floor(x);y0=math.floor(y);x1=min(x0+1,width-1);y1=min(y0+1,height-1)
        wx=math.floor((x-x0)*256+.5);wy=math.floor((y-y0)*256+.5)
        buf,stride,step=plane
        a=buf[y0*stride+x0*step]*(256-wx)+buf[y0*stride+x1*step]*wx
        b=buf[y1*stride+x0*step]*(256-wx)+buf[y1*stride+x1*step]*wx
        return (a*(256-wy)+b*wy+32768)>>16
    out=bytearray(16*16*4)
    for y in range(16):
        for x in range(16):
            dest=((15-y if s.flip_rows else y)*16+x)*4
            if not(left<=x+.5<f(left+w) and top<=y+.5<f(top+h)):
                out[dest:dest+4]=bytes([114,114,114,255]);continue
            dx=((x+.5-left)/w)*dw-.5;dy=((y+.5-top)/h)*dh-.5
            if s.rotation==90:sx,sy=dy+s.crop_left,s.crop_bottom-1-dx
            elif s.rotation==180:sx,sy=s.crop_right-1-dx,s.crop_bottom-1-dy
            elif s.rotation==270:sx,sy=s.crop_right-1-dy,dx+s.crop_top
            else:sx,sy=dx+s.crop_left,dy+s.crop_top
            sx=max(s.crop_left,min(s.crop_right-1,sx));sy=max(s.crop_top,min(s.crop_bottom-1,sy))
            yy=sample(planes[0],sx,sy,7,5)
            u=sample(planes[1],sx*.5,sy*.5,4,3)-128
            v=sample(planes[2],sx*.5,sy*.5,4,3)-128
            rv,gu,gv,bu=(((359,-88,-183,454),(403,-48,-120,475),(377,-42,-146,482)) if s.full_range else
                ((409,-100,-208,516),(459,-55,-136,541),(430,-48,-167,548)))[s.matrix]
            yy=yy*256 if s.full_range else 298*max(0,yy-16)
            rgb=[(yy+rv*v+128)>>8,(yy+gu*u+gv*v+(127 if s.full_range else 128))>>8,(yy+bu*u+128)>>8]
            out[dest:dest+4]=bytes([max(0,min(255,x)) for x in rgb]+[255])
    return bytes(out)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('library');lib=c.CDLL(p.parse_args().library)
    lib.frame_kernel_yuv420_rgba.argtypes=[Plane,Plane,Plane,YuvSpec,c.POINTER(c.c_uint8),c.c_size_t]
    lib.frame_kernel_normalize_pixels.argtypes=[c.POINTER(c.c_uint8),c.c_size_t,PixelSpec,c.POINTER(c.c_float),c.c_size_t]
    arrays=[(c.c_uint8*n)(*[(i*mult+add)&255 for i in range(n)]) for n,mult,add in [(51,17,3),(31,11,17),(31,23,71)]]
    planes=[Plane(a,len(a),11 if i==0 else 12,1 if i==0 else 2) for i,a in enumerate(arrays)]
    oracle=[(bytes(a),p.row_stride,p.pixel_stride) for p,a in zip(planes,arrays)]
    digest=hashlib.sha256();count=0
    for crop in (False,True):
        l,t,r,b=(1,1,6,5) if crop else (0,0,7,5)
        for rotation in (0,90,180,270):
            dw,dh=((b-t,r-l) if rotation in (90,270) else (r-l,b-t))
            for matrix in range(3):
                for full in (0,1):
                    for flip in (0,1):
                        s=YuvSpec(7,5,l,t,r,b,dw,dh,rotation,16,16,flip,matrix,full,114)
                        output=(c.c_uint8*1024)()
                        assert lib.frame_kernel_yuv420_rgba(*planes,s,output,len(output))==0
                        assert bytes(output)==reference(oracle,s),(crop,rotation,matrix,full,flip)
                        digest.update(bytes(output));count+=1
    result=digest.hexdigest();assert not EXPECTED or result==EXPECTED,result
    # Every byte value, layout and row/column orientation, including face-model
    # [-1,1] normalization, is checked as Float32 bits against a scalar oracle.
    rgba=(c.c_uint8*1024)(*[(i*37+11)&255 for i in range(1024)])
    for flip_y in (0,1):
        for flip_x in (0,1):
            for interleaved in (0,1):
                for scale,bias in ((f(1/255),0),(f(1/127.5),-1)):
                    output=(c.c_float*768)();s=PixelSpec(16,16,64,4,0,1,2,flip_y,flip_x,interleaved,scale,bias)
                    assert lib.frame_kernel_normalize_pixels(rgba,1024,s,output,768)==0
                    expected=[0.]*768
                    for y in range(16):
                        for x in range(16):
                            src=((15-y if flip_y else y)*16+(15-x if flip_x else x))*4
                            for channel in range(3):
                                expected[(y*16+x)*3+channel if interleaved else channel*256+y*16+x]=f(f(rgba[src+channel]*scale)+bias)
                    assert bytes(output)==struct.pack('<768f',*expected)
    out=(c.c_uint8*1024)(*([42]*1024));bad=Plane(arrays[0],1,11,1)
    assert lib.frame_kernel_yuv420_rgba(bad,planes[1],planes[2],YuvSpec(7,5,0,0,7,5,7,5,0,16,16,0,1,0,114),out,1024)==-1
    assert bytes(out)==bytes([42]*1024)
    print(json.dumps({'yuv_frames':count,'pixel_vector_sha256':result,'normalization_layouts':16}))

if __name__=='__main__':main()
