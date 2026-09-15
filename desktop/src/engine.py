"""视频追踪引擎抽象层 — Cutie 视频目标分割。"""
import os, sys
import cv2
import numpy as np
import torch
from typing import List, Tuple
from abc import ABC, abstractmethod

from .tracker import TrackResult, compute_foot_y, compute_bbox_from_mask


# ================================================================
# 辅助: 首帧检测结果从左到右排序 (与 /analyze 算法一致)
# ================================================================

def sort_detections_left_to_right(detections: List[TrackResult]) -> List[TrackResult]:
    """按 bbox 中心 X 升序排列, 重新分配 ID 0,1,2..."""
    detections.sort(key=lambda t: (t.bbox[0] + t.bbox[2]) / 2.0)
    sorted_dets = []
    for new_id, det in enumerate(detections):
        sorted_dets.append(TrackResult(
            track_id=new_id, bbox=det.bbox,
            confidence=det.confidence, mask=det.mask, foot_y=det.foot_y,
        ))
    return sorted_dets


# ================================================================
# 抽象基类
# ================================================================

class BaseVideoTracker(ABC):
    """视频目标追踪引擎接口"""

    @abstractmethod
    def initialize(self, first_frame: np.ndarray,
                   detections: List[TrackResult],
                   all_tracked_ids: List[int]) -> None:
        """用首帧和 YOLO 检测结果初始化追踪器"""
        ...

    @abstractmethod
    def step(self, frame: np.ndarray, frame_idx: int) -> List[TrackResult]:
        """处理一帧, 返回追踪结果列表"""
        ...

    @abstractmethod
    def reset(self):
        ...


# ================================================================
# Cutie 引擎
# ================================================================

class CutieTracker(BaseVideoTracker):
    """
    Cutie 视频目标分割引擎。
    Cutie: https://github.com/hkchengrex/Cutie
    已 clone 到 vendor/Cutie。
    注意: Cutie 原生仅支持 CUDA, macOS 上会自动尝试 MPS 或 CPU。
    """

    def __init__(self, model_path: str = None, device: str = None,
                 verbose: bool = True):
        self.model_path = model_path or "weights/cutie-base-mega.pth"
        self.device = device or ("cuda" if torch.cuda.is_available()
                                  else "mps" if torch.backends.mps.is_available()
                                  else "cpu")
        self.verbose = verbose
        self._processor = None
        self._model = None
        self._objects = []
        self._cutie_to_track = {}
        self._w, self._h = 0, 0
        self._first_mask = None

    def _ensure_cutie_path(self):
        vendor = os.path.join(os.path.dirname(__file__), "..", "vendor", "Cutie")
        if os.path.isdir(vendor) and vendor not in sys.path:
            sys.path.insert(0, vendor)

    def initialize(self, first_frame: np.ndarray,
                   detections: List[TrackResult],
                   all_tracked_ids: List[int]) -> None:
        self._ensure_cutie_path()

        from cutie.utils.get_default_model import get_default_model
        from cutie.inference.inference_core import InferenceCore

        self._h, self._w = first_frame.shape[:2]
        if self.verbose:
            print(f"[CutieTracker] 初始化, 设备: {self.device}")

        # ★ 引擎内部强制从左到右排序
        detections = sort_detections_left_to_right(detections)

        # 构建 index mask: 仅画入追踪目标, Cutie obj 从 1 开始连续编号
        combined_mask = np.zeros((self._h, self._w), dtype=np.int32)
        self._cutie_to_track = {}  # cutie_obj → real track_id
        track_set = set(all_tracked_ids)
        cutie_idx = 1
        for det in detections:
            tid = det.track_id
            if tid not in track_set:
                continue
            mask = det.mask
            binary = (mask > 0.15).astype(np.uint8)
            # ★ 不膨胀 + 不覆盖: 避免相邻人物 mask 互相侵蚀导致手臂被"夺走"
            unassigned = (combined_mask == 0) & (binary > 0)
            combined_mask[unassigned] = cutie_idx
            self._cutie_to_track[cutie_idx] = tid
            self._objects.append(cutie_idx)
            if self.verbose:
                print(f"[CutieTracker]   注册 ID:{tid} → Cutie obj:{cutie_idx}")
            cutie_idx += 1

        if not self._objects:
            raise ValueError("当前画面未检测到明显的人物轮廓，请更换视频重试！")

        # Cutie 硬编码 .cuda() + autocast + torch.load(CUDA), 需 monkey-patch
        if self.device != "cuda":
            _original_cuda = torch.nn.Module.cuda
            _original_autocast = torch.cuda.amp.autocast
            _original_load = torch.load

            def _patched_cuda(module, device=None):
                return module.to(self.device)
            torch.nn.Module.cuda = _patched_cuda
            torch.cuda.amp.autocast = lambda enabled=True: torch.no_grad()
            torch.load = lambda *a, **kw: _original_load(*a, **{**kw, 'map_location': 'cpu'})

            # 清除 Hydra 全局状态 (多次调用会冲突)
            from hydra.core.global_hydra import GlobalHydra
            if GlobalHydra.instance().is_initialized():
                GlobalHydra.instance().clear()

            try:
                self._model = get_default_model()
                self._model = self._model.to(self.device)
            finally:
                torch.nn.Module.cuda = _original_cuda
                torch.cuda.amp.autocast = _original_autocast
                torch.load = _original_load
        else:
            from hydra.core.global_hydra import GlobalHydra
            if GlobalHydra.instance().is_initialized():
                GlobalHydra.instance().clear()
            self._model = get_default_model()

        self._processor = InferenceCore(self._model, cfg=self._model.cfg)
        # 限制内部处理分辨率，大幅提速（768 对打码精度几乎无影响）
        self._processor.max_internal_size = min(max(self._h, self._w), 768)

        # 首帧: 与 step 保持一致的缩小策略
        frame_rgb = cv2.cvtColor(first_frame, cv2.COLOR_BGR2RGB)
        self._scale = 1.0
        if hasattr(self, '_max_input_dim'):
            h, w = frame_rgb.shape[:2]
            longest = max(h, w)
            if longest > self._max_input_dim:
                self._scale = self._max_input_dim / longest
                new_w, new_h = int(w * self._scale), int(h * self._scale)
                frame_rgb = cv2.resize(frame_rgb, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
                combined_mask = cv2.resize(combined_mask.astype(np.float32), (new_w, new_h),
                                           interpolation=cv2.INTER_NEAREST).astype(np.int32)

        frame_tensor = torch.from_numpy(frame_rgb).permute(2, 0, 1).float() / 255.0
        frame_tensor = frame_tensor.to(self.device)
        mask_tensor = torch.from_numpy(combined_mask).to(self.device)

        with torch.no_grad():
            self._processor.step(frame_tensor, mask_tensor,
                                  objects=self._objects)

    def step(self, frame: np.ndarray, frame_idx: int) -> List[TrackResult]:
        if self._processor is None:
            return []

        frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        # 与初始化保持一致的缩小策略
        if self._scale != 1.0:
            new_w, new_h = int(frame_rgb.shape[1] * self._scale), int(frame_rgb.shape[0] * self._scale)
            frame_rgb = cv2.resize(frame_rgb, (new_w, new_h), interpolation=cv2.INTER_LINEAR)

        frame_tensor = torch.from_numpy(frame_rgb).permute(2, 0, 1).float() / 255.0
        frame_tensor = frame_tensor.to(self.device)

        with torch.no_grad():
            prob = self._processor.step(frame_tensor)

        if prob is None:
            return []

        # prob: (num_objects, H, W) on device, 强制转为 float32 [0,1]
        prob_np = prob.cpu().numpy().astype(np.float32)

        track_results = []
        for obj_idx in self._objects:
            if obj_idx >= prob_np.shape[0]:
                continue
            mask = prob_np[obj_idx].astype(np.float32)
            mask = np.clip(mask, 0.0, 1.0)
            # 还原到原始分辨率
            if mask.shape[:2] != (self._h, self._w):
                mask = cv2.resize(mask, (self._w, self._h),
                                  interpolation=cv2.INTER_LINEAR)
                mask = np.clip(mask, 0.0, 1.0)
                mask = cv2.resize(mask, (self._w, self._h),
                                  interpolation=cv2.INTER_LINEAR)
                mask = np.clip(mask, 0.0, 1.0)
            if mask.max() < 0.01:
                continue

            track_id = self._cutie_to_track.get(obj_idx, 0)
            foot_y = compute_foot_y(mask)
            bbox = compute_bbox_from_mask(mask)
            track_results.append(TrackResult(
                track_id=track_id, bbox=bbox,
                confidence=0.85, mask=mask, foot_y=foot_y,
            ))

        track_results.sort(key=lambda t: t.track_id)
        return track_results

    def reset(self):
        self._processor = None
        self._model = None
        self._objects = []
        self._cutie_to_track = {}

    @property
    def step_name(self) -> str:
        return "处理中"


# ================================================================
# 工厂函数
# ================================================================

def create_tracker(engine_type: str, **kwargs) -> BaseVideoTracker:
    """根据配置创建追踪引擎实例"""
    engines = {
        "cutie": CutieTracker,
    }
    cls = engines.get(engine_type.lower())
    if cls is None:
        raise ValueError(f"未知引擎: {engine_type}, 可选: {list(engines.keys())}")
    return cls(**kwargs)
