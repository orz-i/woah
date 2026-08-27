import numpy as np

class Sam2OnnxStateManager:
    def __init__(
        self,
        num_maskmem: int = 7,
        mem_dim: int = 64,
        hidden_dim: int = 256,
        max_obj_ptrs_in_encoder: int = 16,
        memory_temporal_stride_for_eval: int = 1,
        max_cond_frames_in_attn: int = -1
    ):
        self.num_maskmem = num_maskmem
        self.mem_dim = mem_dim
        self.hidden_dim = hidden_dim
        self.max_obj_ptrs_in_encoder = max_obj_ptrs_in_encoder
        self.stride = memory_temporal_stride_for_eval
        self.max_cond_frames_in_attn = max_cond_frames_in_attn
        
        self.cond_frame_outputs = {}      # frame_idx -> {'memory_features': np.ndarray, 'memory_pos_enc': np.ndarray}
        self.cond_obj_ptrs = {}           # frame_idx -> obj_ptr
        self.non_cond_frame_outputs = {}  # frame_idx -> {'memory_features': np.ndarray, 'memory_pos_enc': np.ndarray}
        self.non_cond_obj_ptrs = {}       # frame_idx -> obj_ptr
        
    def add_conditioning_frame(self, frame_idx: int, memory_features: np.ndarray, memory_pos_enc: np.ndarray, obj_ptr: np.ndarray):
        self.cond_frame_outputs[frame_idx] = {
            'memory_features': memory_features,
            'memory_pos_enc': memory_pos_enc,
        }
        self.cond_obj_ptrs[frame_idx] = obj_ptr
        
    def add_non_conditioning_frame(self, frame_idx: int, memory_features: np.ndarray, memory_pos_enc: np.ndarray, obj_ptr: np.ndarray):
        # Bound memory features to recent 6 frames (O(1) memory)
        self.non_cond_frame_outputs[frame_idx] = {
            'memory_features': memory_features,
            'memory_pos_enc': memory_pos_enc,
        }
        while len(self.non_cond_frame_outputs) > (self.num_maskmem - 1):
            oldest_key = next(iter(self.non_cond_frame_outputs.keys()))
            del self.non_cond_frame_outputs[oldest_key]
            
        # Bound obj pointers to recent 16 frames
        self.non_cond_obj_ptrs[frame_idx] = obj_ptr
        while len(self.non_cond_obj_ptrs) > self.max_obj_ptrs_in_encoder:
            oldest_key = next(iter(self.non_cond_obj_ptrs.keys()))
            del self.non_cond_obj_ptrs[oldest_key]
        
    def select_for_frame(self, frame_idx: int, num_frames: int = 40):
        # 1. Conditioning frames
        selected_cond_keys = set(self.cond_frame_outputs.keys())
        selected_cond_outputs = {k: self.cond_frame_outputs[k] for k in selected_cond_keys}
        
        # t_pos = 0 for selected conditioning frames
        selected_memories = []
        selected_memory_pos = []
        selected_tpos_indices = []
        selected_mem_frame_indices = []
        
        for k in sorted(selected_cond_outputs.keys()):
            out = selected_cond_outputs[k]
            selected_memories.append(out['memory_features'])
            selected_memory_pos.append(out['memory_pos_enc'])
            selected_tpos_indices.append(self.num_maskmem - 0 - 1)  # t_pos = 0 -> index = 6
            selected_mem_frame_indices.append(k)
            
        # 2. Non-conditioning frames (t_pos from 1 to num_maskmem - 1)
        for t_pos in range(1, self.num_maskmem):
            t_rel = self.num_maskmem - t_pos
            if t_rel == 1:
                prev_frame_idx = frame_idx - 1
            else:
                prev_frame_idx = ((frame_idx - 2) // self.stride) * self.stride
                prev_frame_idx = prev_frame_idx - (t_rel - 2) * self.stride
                
            out = self.non_cond_frame_outputs.get(prev_frame_idx, None)
            if out is not None:
                selected_memories.append(out['memory_features'])
                selected_memory_pos.append(out['memory_pos_enc'])
                selected_tpos_indices.append(self.num_maskmem - t_pos - 1)
                selected_mem_frame_indices.append(prev_frame_idx)
                
        # 3. Object pointers
        max_ptrs = min(num_frames, self.max_obj_ptrs_in_encoder)
        selected_ptrs = []
        selected_ptr_frame_indices = []
        
        # Add past/current conditioning pointers
        ptr_cond_keys = sorted([t for t in selected_cond_keys if t <= frame_idx])
        for t in ptr_cond_keys:
            selected_ptrs.append(self.cond_obj_ptrs[t])
            selected_ptr_frame_indices.append(t)
            
        # Add past non-conditioning pointers
        for t_diff in range(1, max_ptrs):
            t = frame_idx - t_diff
            if t < 0 or (num_frames is not None and t >= num_frames):
                break
            ptr = self.non_cond_obj_ptrs.get(t, None)
            if ptr is not None:
                selected_ptrs.append(ptr)
                selected_ptr_frame_indices.append(t)
                
        num_obj_ptr_tokens = len(selected_ptrs) * (self.hidden_dim // self.mem_dim)
        
        return {
            'memory_features': np.stack(selected_memories, axis=0) if len(selected_memories) > 0 else np.zeros((0, 1, 64, 64, 64), dtype=np.float32),
            'memory_pos': np.stack(selected_memory_pos, axis=0) if len(selected_memory_pos) > 0 else np.zeros((0, 1, 64, 64, 64), dtype=np.float32),
            'memory_tpos_indices': np.array(selected_tpos_indices, dtype=np.int64),
            'memory_frame_indices': selected_mem_frame_indices,
            'obj_ptrs': np.stack(selected_ptrs, axis=0) if len(selected_ptrs) > 0 else np.zeros((0, 1, 256), dtype=np.float32),
            'obj_ptr_frame_indices': selected_ptr_frame_indices,
            'num_obj_ptr_tokens': num_obj_ptr_tokens
        }
