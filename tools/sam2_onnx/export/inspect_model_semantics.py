import os
import sys
import json
import hashlib
import torch

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor

def compute_sha256(filepath):
    if not os.path.exists(filepath):
        return None
    h = hashlib.sha256()
    with open(filepath, 'rb') as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def inspect_and_write_manifest():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    cfg_path = os.path.join(VENDOR_PATH, 'sam2/configs/sam2/sam2_hiera_t.yaml')
    
    ckpt_sha = compute_sha256(ckpt_path)
    cfg_sha = compute_sha256(cfg_path)
    
    print(f'[Inspect] Checkpoint SHA256: {ckpt_sha}')
    print(f'[Inspect] Config SHA256: {cfg_sha}')
    
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    m = predictor
    
    manifest = {
        'model_name': 'sam2_hiera_tiny',
        'checkpoint_path': os.path.relpath(ckpt_path, root_dir).replace('\\', '/'),
        'checkpoint_sha256': ckpt_sha,
        'config_path': os.path.relpath(cfg_path, root_dir).replace('\\', '/'),
        'config_sha256': cfg_sha,
        'image_size': int(m.image_size),
        'num_maskmem': int(m.num_maskmem),
        'mem_dim': int(m.mem_dim),
        'hidden_dim': int(m.hidden_dim),
        'directly_add_no_mem_embed': bool(m.directly_add_no_mem_embed),
        'use_high_res_features_in_sam': bool(m.use_high_res_features_in_sam),
        'use_obj_ptrs_in_encoder': bool(m.use_obj_ptrs_in_encoder),
        'max_obj_ptrs_in_encoder': int(m.max_obj_ptrs_in_encoder),
        'add_tpos_enc_to_obj_ptrs': bool(m.add_tpos_enc_to_obj_ptrs),
        'proj_tpos_enc_in_obj_ptrs': bool(m.proj_tpos_enc_in_obj_ptrs),
        'use_signed_tpos_enc_to_obj_ptrs': bool(m.use_signed_tpos_enc_to_obj_ptrs),
        'only_obj_ptrs_in_the_past_for_eval': bool(m.only_obj_ptrs_in_the_past_for_eval),
        'pred_obj_scores': bool(m.pred_obj_scores),
        'pred_obj_scores_mlp': bool(m.pred_obj_scores_mlp),
        'fixed_no_obj_ptr': bool(m.fixed_no_obj_ptr),
        'soft_no_obj_ptr': bool(m.soft_no_obj_ptr),
        'no_obj_embed_spatial': m.no_obj_embed_spatial if getattr(m, 'no_obj_embed_spatial', None) is not None else None,
        'multimask_output_in_sam': bool(m.multimask_output_in_sam),
        'multimask_output_for_tracking': bool(m.multimask_output_for_tracking),
        'multimask_min_pt_num': int(m.multimask_min_pt_num),
        'multimask_max_pt_num': int(m.multimask_max_pt_num),
        'use_multimask_token_for_obj_ptr': bool(m.use_multimask_token_for_obj_ptr),
        'sigmoid_scale_for_mem_enc': float(m.sigmoid_scale_for_mem_enc),
        'sigmoid_bias_for_mem_enc': float(m.sigmoid_bias_for_mem_enc),
        'binarize_mask_from_pts_for_mem_enc': bool(m.binarize_mask_from_pts_for_mem_enc),
        'non_overlap_masks_for_mem_enc': bool(m.non_overlap_masks_for_mem_enc),
        'memory_temporal_stride_for_eval': int(m.memory_temporal_stride_for_eval),
        'max_cond_frames_in_attn': int(m.max_cond_frames_in_attn),
        'use_mask_input_as_output_without_sam': bool(m.use_mask_input_as_output_without_sam)
    }
    
    out_path = os.path.abspath(os.path.join(os.path.dirname(__file__), '../model_manifest.json'))
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2)
    print(f'[Inspect] Saved manifest to {out_path}')
    print(json.dumps(manifest, indent=2))

if __name__ == '__main__':
    inspect_and_write_manifest()
