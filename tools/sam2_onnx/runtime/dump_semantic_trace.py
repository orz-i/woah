import os
import sys
import json
import csv
import numpy as np
import torch

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor

def compute_bbox_from_mask(mask: np.ndarray, threshold: float = 0.15, expand_ratio: float = 0.05):
    h, w = mask.shape
    binary = (mask > threshold).astype(np.uint8)
    rows = np.any(binary, axis=1)
    cols = np.any(binary, axis=0)
    if not rows.any() or not cols.any():
        return [0, 0, w, h], 0.0, False
    y_indices = np.where(rows)[0]
    x_indices = np.where(cols)[0]
    y1, y2 = int(y_indices[0]), int(y_indices[-1])
    x1, x2 = int(x_indices[0]), int(x_indices[-1])
    bw, bh = x2 - x1, y2 - y1
    expand_w, expand_h = int(bw * expand_ratio), int(bh * expand_ratio)
    x1 = max(0, x1 - expand_w)
    y1 = max(0, y1 - expand_h)
    x2 = min(w, x2 + expand_w)
    y2 = min(h, y2 + expand_h)
    area = float(binary.sum())
    return [x1, y1, x2, y2], area, True

def dump_golden_trace():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    ref_dir = os.path.join(root_dir, 'tools/sam2_onnx/reference_inputs')
    out_dir = os.path.join(root_dir, 'tools/sam2_onnx/reference_golden')
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(os.path.join(out_dir, 'masks'), exist_ok=True)
    
    with open(os.path.join(ref_dir, 'reference_meta.json'), 'r', encoding='utf-8') as f:
        ref_meta = json.load(f)
        
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    
    state = predictor.init_state(video_path=ref_dir)
    first_bbox = ref_meta['first_bbox']
    
    # Run frame 0
    _, obj_ids, f0_mask_logits = predictor.add_new_points_or_box(
        state, frame_idx=0, obj_id=0, box=first_bbox
    )
    
    # Preflight consolidates Frame 0 into output_dict_per_obj
    predictor.propagate_in_video_preflight(state)
    
    # Save frame 0 mask
    f0_mask_np = f0_mask_logits[0, 0].cpu().numpy()
    np.save(os.path.join(out_dir, 'masks', 'frame_0000_mask.npy'), f0_mask_np)
    
    f0_prob = torch.sigmoid(f0_mask_logits[0, 0]).cpu().numpy()
    bbox, area, is_valid = compute_bbox_from_mask(f0_prob)
    
    f0_cond_out = state['output_dict_per_obj'][0]['cond_frame_outputs'][0]
    f0_obj_score = float(f0_cond_out['object_score_logits'].item())
    
    state_trace = [{
        'frame': 0,
        'maskArea': area,
        'bbox': bbox,
        'objectScore': f0_obj_score
    }]
    
    # Save Frame 0 intermediate tensors for component parity
    img_feat_0_dict = state['cached_features'][0][1]
    
    f0_trace = {
        'topVisionFeature': {
            'shape': list(img_feat_0_dict['backbone_fpn'][2].shape),
            'min': float(img_feat_0_dict['backbone_fpn'][2].min()),
            'max': float(img_feat_0_dict['backbone_fpn'][2].max()),
            'mean': float(img_feat_0_dict['backbone_fpn'][2].mean())
        },
        'highRes0': {
            'shape': list(img_feat_0_dict['backbone_fpn'][0].shape),
            'min': float(img_feat_0_dict['backbone_fpn'][0].min()),
            'max': float(img_feat_0_dict['backbone_fpn'][0].max()),
            'mean': float(img_feat_0_dict['backbone_fpn'][0].mean())
        },
        'highRes1': {
            'shape': list(img_feat_0_dict['backbone_fpn'][1].shape),
            'min': float(img_feat_0_dict['backbone_fpn'][1].min()),
            'max': float(img_feat_0_dict['backbone_fpn'][1].max()),
            'mean': float(img_feat_0_dict['backbone_fpn'][1].mean())
        },
        'obj_ptr': {
            'shape': list(f0_cond_out['obj_ptr'].shape),
            'min': float(f0_cond_out['obj_ptr'].min()),
            'max': float(f0_cond_out['obj_ptr'].max()),
            'mean': float(f0_cond_out['obj_ptr'].mean())
        },
        'maskmem_features': {
            'shape': list(f0_cond_out['maskmem_features'].shape),
            'min': float(f0_cond_out['maskmem_features'].min()),
            'max': float(f0_cond_out['maskmem_features'].max()),
            'mean': float(f0_cond_out['maskmem_features'].mean())
        },
        'maskmem_pos_enc': {
            'shape': list(f0_cond_out['maskmem_pos_enc'][-1].shape),
            'min': float(f0_cond_out['maskmem_pos_enc'][-1].min()),
            'max': float(f0_cond_out['maskmem_pos_enc'][-1].max()),
            'mean': float(f0_cond_out['maskmem_pos_enc'][-1].mean())
        }
    }
    with open(os.path.join(out_dir, 'f0_semantic_trace.json'), 'w', encoding='utf-8') as f:
        json.dump(f0_trace, f, indent=2)
        
    print('[Trace] Frame 0 traced.')
    
    # Track through frames 1..39
    for f_idx, obj_ids, f_mask_logits in predictor.propagate_in_video(state, start_frame_idx=1, max_frame_num_to_track=39):
        f_mask_np = f_mask_logits[0, 0].cpu().numpy()
        np.save(os.path.join(out_dir, 'masks', f'frame_{f_idx:04d}_mask.npy'), f_mask_np)
        
        prob_mask = torch.sigmoid(f_mask_logits[0, 0]).cpu().numpy()
        bbox, area, is_valid = compute_bbox_from_mask(prob_mask)
        
        f_out = state['output_dict_per_obj'][0]['non_cond_frame_outputs'].get(f_idx, {})
        obj_score = float(f_out.get('object_score_logits', torch.tensor(0.0)).item()) if 'object_score_logits' in f_out else 0.0
        
        state_trace.append({
            'frame': f_idx,
            'maskArea': area,
            'bbox': bbox,
            'objectScore': obj_score
        })
        if f_idx % 10 == 0 or f_idx == 39:
            print(f'[Trace] Frame {f_idx:02d} processed, maskArea={area:.1f}, bbox={bbox}')
            
    with open(os.path.join(out_dir, 'golden_state_trace.json'), 'w', encoding='utf-8') as f:
        json.dump(state_trace, f, indent=2)
        
    # Write golden bbox csv
    with open(os.path.join(out_dir, 'golden_bbox.csv'), 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(['frame', 'x1', 'y1', 'x2', 'y2', 'area', 'object_score'])
        for item in state_trace:
            b = item['bbox']
            writer.writerow([item['frame'], b[0], b[1], b[2], b[3], item['maskArea'], item['objectScore']])
            
    print(f'[Trace] Successfully dumped 40 golden frames to {out_dir}')

if __name__ == '__main__':
    dump_golden_trace()
