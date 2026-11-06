#!/usr/bin/env python3
"""Common selection scope and explicit/fallback precedence contracts."""
import argparse
import ctypes as c
import hashlib
import json
import struct
from abi_smoke import Rect

EXPECTED='1b5ca65615939625878106d6f85088fab8cdef5b5ed45dbf4f10a58df33f2117'
VALUES=(-3,2,9,44)
def ids(mask):return [v for i,v in enumerate(VALUES) if mask&(1<<i)]
def array(values):return (c.c_int64*len(values))(*values)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('library');lib=c.CDLL(parser.parse_args().library)
    ptr=c.POINTER(c.c_int64)
    lib.frame_kernel_scene_scope.argtypes=[ptr,c.c_size_t,ptr,c.c_size_t,ptr,c.c_size_t,c.c_uint32,c.c_int64]
    lib.frame_kernel_compositor_targets.argtypes=[ptr,c.c_size_t,ptr,c.c_size_t,ptr,c.c_size_t,ptr,c.c_size_t]
    lib.frame_kernel_current_hero_index.argtypes=[c.POINTER(Rect),c.c_size_t,c.c_uint32,Rect]
    digest=hashlib.sha256();roots=array(VALUES[:3]);count=0
    for body_mask in range(16):
        for face_mask in range(16):
            body=ids(body_mask);face=ids(face_mask);b=array(body);f=array(face)
            for hero in (None,*VALUES):
                result=lib.frame_kernel_scene_scope(roots,3,b,len(b),f,len(f),hero is not None,hero or 0)
                expected=(4 if hero in VALUES[:3] and not body and set(face)==set(VALUES[:3])-{hero} else 0) if hero is not None else (
                    1 if not face and set(body)>=set(VALUES[:3]) else 2 if not body and set(face)>=set(VALUES[:3]) else 0)
                assert result==expected,(body_mask,face_mask,hero,result,expected)
                digest.update(struct.pack('<i',result));count+=1
    for selected_mask in range(16):
        for covered_mask in range(16):
            for explicit_mask in range(16):
                selected=ids(selected_mask);covered=ids(covered_mask);explicit=ids(explicit_mask)
                s=array(selected);co=array(covered);e=array(explicit);out=(c.c_int64*len(s))()
                n=lib.frame_kernel_compositor_targets(s,len(s),co,len(co),e,len(e),out,len(out))
                expected=sorted(set(selected)-(set(covered)-set(explicit)))
                assert n==len(expected) and list(out[:n])==expected
                digest.update(struct.pack('<I',n))
                for value in out[:n]:digest.update(struct.pack('<q',value))
    hero=Rect(1,2,3,4);boxes=(Rect*3)(Rect(9,8,10,12),hero,Rect(13,14,15,16))
    assert lib.frame_kernel_current_hero_index(boxes,3,1,hero)==1
    assert lib.frame_kernel_current_hero_index(boxes,3,0,hero)==-1
    boxes[2]=hero;assert lib.frame_kernel_current_hero_index(boxes,3,1,hero)==-1
    hero.left=float('nan');assert lib.frame_kernel_current_hero_index(boxes,3,1,hero)==-2
    result=digest.hexdigest();assert not EXPECTED or result==EXPECTED,result
    print(json.dumps({'scope_vectors':count,'compositor_vectors':4096,'selection_vector_sha256':result}))

if __name__=='__main__':main()
