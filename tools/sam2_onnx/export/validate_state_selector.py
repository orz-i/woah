import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

import json
import torch
import numpy as np

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)
RUNTIME_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../runtime'))
if RUNTIME_PATH not in sys.path:
    sys.path.insert(0, RUNTIME_PATH)

from sam2.build_sam import build_sam2_video_predictor
from state_manager import Sam2OnnxStateManager

def validate_state_selector():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    model = predictor
    
    test_frames = [1, 2, 3, 6, 7, 8, 10, 20, 39]
    print(f'[StateSelector Validation] Validating selection semantics on frames: {test_frames}')
    
    num_frames = 40
    num_maskmem = model.num_maskmem
    stride = model.memory_temporal_stride_for_eval
    max_obj_ptrs = model.max_obj_ptrs_in_encoder
    
    state_mgr = Sam2OnnxStateManager(
        num_maskmem=num_maskmem,
        mem_dim=model.mem_dim,
        hidden_dim=model.hidden_dim,
        max_obj_ptrs_in_encoder=max_obj_ptrs,
        memory_temporal_stride_for_eval=stride,
        max_cond_frames_in_attn=model.max_cond_frames_in_attn
    )
    
    dummy_mem = np.zeros((1, 64, 64, 64), dtype=np.float32)
    dummy_pos = np.zeros((1, 64, 64, 64), dtype=np.float32)
    dummy_ptr = np.zeros((1, 256), dtype=np.float32)
    state_mgr.add_conditioning_frame(0, dummy_mem, dummy_pos, dummy_ptr)
    
    desktop_output_dict = {
        'cond_frame_outputs': {
            0: {
                'maskmem_features': torch.zeros(1, 64, 64, 64),
                'maskmem_pos_enc': [torch.zeros(1, 64, 64, 64)],
                'obj_ptr': torch.zeros(1, 256)
            }
        },
        'non_cond_frame_outputs': {}
    }
    
    for f in range(1, num_frames):
        if f in test_frames:
            cond_outputs = desktop_output_dict['cond_frame_outputs']
            selected_cond_outputs = cond_outputs
            
            desk_t_pos_and_prevs = [(0, out, 0) for k, out in selected_cond_outputs.items()]
            for t_pos in range(1, num_maskmem):
                t_rel = num_maskmem - t_pos
                if t_rel == 1:
                    prev_frame_idx = f - t_rel
                else:
                    prev_frame_idx = ((f - 2) // stride) * stride - (t_rel - 2) * stride
                out = desktop_output_dict['non_cond_frame_outputs'].get(prev_frame_idx, None)
                if out is not None:
                    desk_t_pos_and_prevs.append((t_pos, out, prev_frame_idx))
                    
            desk_mem_frame_indices = [item[2] for item in desk_t_pos_and_prevs]
            desk_tpos_indices = [num_maskmem - item[0] - 1 for item in desk_t_pos_and_prevs]
            
            desk_pos_and_ptrs = [(abs(f - t), out['obj_ptr'], t) for t, out in selected_cond_outputs.items() if t <= f]
            for t_diff in range(1, min(num_frames, max_obj_ptrs)):
                t = f - t_diff
                if t < 0:
                    break
                out = desktop_output_dict['non_cond_frame_outputs'].get(t, None)
                if out is not None:
                    desk_pos_and_ptrs.append((t_diff, out['obj_ptr'], t))
                    
            desk_ptr_frame_indices = [item[2] for item in desk_pos_and_ptrs]
            desk_num_obj_tokens = len(desk_pos_and_ptrs) * (model.hidden_dim // model.mem_dim)
            
            mgr_sel = state_mgr.select_for_frame(f, num_frames=num_frames)
            
            assert mgr_sel['memory_frame_indices'] == desk_mem_frame_indices, f"Frame {f} mem mismatch: {mgr_sel['memory_frame_indices']} != {desk_mem_frame_indices}"
            assert list(mgr_sel['memory_tpos_indices']) == desk_tpos_indices, f"Frame {f} tpos mismatch: {list(mgr_sel['memory_tpos_indices'])} != {desk_tpos_indices}"
            assert mgr_sel['obj_ptr_frame_indices'] == desk_ptr_frame_indices, f"Frame {f} ptr mismatch: {mgr_sel['obj_ptr_frame_indices']} != {desk_ptr_frame_indices}"
            assert mgr_sel['num_obj_ptr_tokens'] == desk_num_obj_tokens, f"Frame {f} token count mismatch: {mgr_sel['num_obj_ptr_tokens']} != {desk_num_obj_tokens}"
            
            print(f"  Frame {f:02d}: mem_frames={mgr_sel['memory_frame_indices']}, tpos_inds={list(mgr_sel['memory_tpos_indices'])}, ptr_frames={mgr_sel['obj_ptr_frame_indices']}, num_tokens={mgr_sel['num_obj_ptr_tokens']} -> EXACT MATCH")
            
        desktop_output_dict['non_cond_frame_outputs'][f] = {
            'maskmem_features': torch.zeros(1, 64, 64, 64),
            'maskmem_pos_enc': [torch.zeros(1, 64, 64, 64)],
            'obj_ptr': torch.zeros(1, 256)
        }
        state_mgr.add_non_conditioning_frame(f, dummy_mem, dummy_pos, dummy_ptr)
        
    print('[StateSelector Validation] ALL FRAMES PASSED WITH EXACT PARITY!')

if __name__ == '__main__':
    validate_state_selector()
