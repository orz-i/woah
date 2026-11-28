"""Current anatomical face observation policy for the canonical replay."""
import ctypes as C
import numpy as np
from evaluate_pose_parts import pose_points
from evaluate_fragment_heads import intersects, square
from replay_face_geometry import P, S, U, MaskSpec, Ellipse, Rect, detect_face, ptr, vals
from replay_face_tracking import HeadSticker

class AnatomicalPolicy:
    """Per-frame model transport for the Rust anatomical ownership decision."""
    def __init__(self, lib, pose, face):
        self.lib, self.pose, self.face = lib, pose, face
        self.fn=lib.frame_kernel_research_anatomical_observation
        self.fn.argtypes=[P,S,S,U,MaskSpec,P,Ellipse,P]
        self.fn.restype=C.c_int32
        self.cap_fn=lib.frame_kernel_research_anatomical_head
        self.cap_fn.argtypes=[P,S,S,U,MaskSpec,P,P];self.cap_fn.restype=C.c_int32
        self.place_fn=lib.frame_kernel_research_anatomical_placement
        self.place_fn.argtypes=[P,S,S,U,MaskSpec,P,P,P,Ellipse,P,S,P];self.place_fn.restype=C.c_int32

    def prepare(self, im, tr, ds, masks, j, measured):
        # One scheduling decision for all targets; no source annotations.
        b=vals(ds[j].box)
        upper=[b[0],b[1],b[2],b[1]+.35*(b[3]-b[1])]
        if not any(k!=j and (intersects(upper,vals(d.box)) or
                (measured is not None and intersects(square(vals(measured)),vals(d.box))))
                for k,d in enumerate(ds)):
            return None
        kp=np.ascontiguousarray(np.stack([pose_points(self.pose,im,tr,ds[j],masks,j,flip=v)[:7]
                                          for v in (False,True)]),np.float32)
        return (MaskSpec(tr.proto,tr.proto,tr,ds[j].box),kp)

    def compare(self, masks, j, prepared, measured):
        if prepared is None or measured is None or measured.rx<=0 or measured.ry<=0:
            return 1,None
        spec,kp=prepared;cap=HeadSticker()
        rc=self.fn(ptr(masks),masks.size,len(masks),j,spec,ptr(kp),measured,C.byref(cap))
        if rc not in (0,1,2):raise RuntimeError("invalid anatomical observation")
        return rc,cap if rc!=1 else None

    def place(self, im, tr, ds, masks, j, prepared):
        if prepared is None:return None
        sp,kp=prepared
        cap=HeadSticker();rc=self.cap_fn(ptr(masks),masks.size,len(ds),j,sp,ptr(kp),C.byref(cap))
        if rc<0:raise RuntimeError("invalid cap")
        if rc:return None
        fs=[detect_face(self.lib,self.face,im,MaskSpec(tr.proto,tr.proto,tr,d.box),masks[k]) or Ellipse() for k,d in enumerate(ds)]
        # The cap supplies current coverage only. Do not renew face history
        # or union an untrusted face into the required rectangle.
        result=HeadSticker()
        rc=self.place_fn(ptr(masks),masks.size,len(ds),j,sp,ptr(kp),
            (Rect*len(ds))(*[d.box for d in ds]),(Ellipse*len(fs))(*fs),Ellipse(),
            (Rect*0)(),0,C.byref(result))
        if rc<0:raise RuntimeError("invalid anatomical placement")
        if rc:return None
        return result
