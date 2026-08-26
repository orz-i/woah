import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

import json
import cv2
import numpy as np
import torch
import onnxruntime as ort

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor
from export_image_features import Sam2ImageFeaturesExporter
from export_init_step import Sam2InitStepExporter
from export_temporal_step import Sam2TemporalStepExporter
from portable_rotary import patch_rotary_for_export

def compute_mask_iou(mask1: np.ndarray, mask2: np.ndarray, threshold: float = 0.0):
    b1 = mask1 > threshold
    b2 = mask2 > threshold
    inter = np.logical_and(b1, b2).sum()
    union = np.logical_or(b1, b2).sum()
    if union == 0:
        return 1.0 if inter == 0 else 0.0
    return float(inter / union)

def run_component_parity():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    gen_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated')
    ref_dir = os.path.join(root_dir, 'tools/sam2_onnx/reference_inputs')
    rep_dir = os.path.join(root_dir, 'tools/sam2_onnx/reports')
    os.makedirs(rep_dir, exist_ok=True)
    
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    predictor.eval()
    
    # 1. Load real frame 0
    f0_img = cv2.imread(os.path.join(ref_dir, '00000.jpg'))
    f0_img_rgb = cv2.cvtColor(f0_img, cv2.COLOR_BGR2RGB)
    f0_resized = cv2.resize(f0_img_rgb, (1024, 1024), interpolation=cv2.INTER_LINEAR)
    f0_norm = (f0_resized.astype(np.float32) / 255.0 - np.array([0.485, 0.456, 0.406])) / np.array([0.229, 0.224, 0.225])
    f0_tensor = torch.from_numpy(f0_norm.transpose(2, 0, 1)).unsqueeze(0).float()
    
    # ----------------------------------------------------
    # Component 1: Image Features Parity
    # ----------------------------------------------------
    print('[Parity 1/3] Testing sam2_image_features...')
    img_wrapper = Sam2ImageFeaturesExporter(predictor)
    img_wrapper.eval()
    with torch.no_grad():
        py_top, py_top_pos, py_high0, py_high1 = img_wrapper(f0_tensor)
        
    sess_img = ort.InferenceSession(os.path.join(gen_dir, 'sam2_image_features.onnx'), providers=['CPUExecutionProvider'])
    ort_outs = sess_img.run(None, {'image': f0_tensor.numpy()})
    ort_top, ort_top_pos, ort_high0, ort_high1 = ort_outs
    
    diff_top = np.abs(py_top.numpy() - ort_top)
    diff_high0 = np.abs(py_high0.numpy() - ort_high0)
    diff_high1 = np.abs(py_high1.numpy() - ort_high1)
    
    img_report = {
        'top_vision_feature': {
            'maxAbsError': float(diff_top.max()),
            'meanAbsError': float(diff_top.mean())
        },
        'high_res_feature_0': {
            'maxAbsError': float(diff_high0.max()),
            'meanAbsError': float(diff_high0.mean())
        },
        'high_res_feature_1': {
            'maxAbsError': float(diff_high1.max()),
            'meanAbsError': float(diff_high1.mean())
        }
    }
    print(f'  image_features top maxAbsError: {diff_top.max():.2e}, high0 maxAbsError: {diff_high0.max():.2e}')
    assert diff_top.max() <= 1e-3, f'image_features top maxAbsError {diff_top.max()} > 1e-3'
    
    # ----------------------------------------------------
    # Component 2: Init Step Parity
    # ----------------------------------------------------
    print('[Parity 2/3] Testing sam2_init_step...')
    with open(os.path.join(ref_dir, 'reference_meta.json'), 'r', encoding='utf-8') as f:
        ref_meta = json.load(f)
    first_bbox = ref_meta['first_bbox']
    pts = np.array([[[first_bbox[0] / 640.0 * 1024.0, first_bbox[1] / 480.0 * 1024.0],
                     [first_bbox[2] / 640.0 * 1024.0, first_bbox[3] / 480.0 * 1024.0]]], dtype=np.float32)
    labels = np.array([[2, 3]], dtype=np.int32)
    
    pts_t = torch.from_numpy(pts)
    labels_t = torch.from_numpy(labels)
    
    init_wrapper = Sam2InitStepExporter(predictor)
    init_wrapper.eval()
    with torch.no_grad():
        py_low_mask, py_high_mask, py_obj_score, py_obj_ptr, py_mem_feat, py_mem_pos = init_wrapper(
            py_top, py_high0, py_high1, pts_t, labels_t
        )
        
    sess_init = ort.InferenceSession(os.path.join(gen_dir, 'sam2_init_step.onnx'), providers=['CPUExecutionProvider'])
    ort_init_outs = sess_init.run(None, {
        'top_vision_feature': ort_top,
        'high_res_feature_0': ort_high0,
        'high_res_feature_1': ort_high1,
        'point_coords': pts,
        'point_labels': labels
    })
    ort_low_mask, ort_high_mask, ort_obj_score, ort_obj_ptr, ort_mem_feat, ort_mem_pos = ort_init_outs
    
    init_mask_iou = compute_mask_iou(py_high_mask.numpy()[0, 0], ort_high_mask[0, 0])
    diff_obj_ptr = np.abs(py_obj_ptr.numpy() - ort_obj_ptr)
    diff_mem_feat = np.abs(py_mem_feat.numpy() - ort_mem_feat)
    diff_mem_pos = np.abs(py_mem_pos.numpy() - ort_mem_pos)
    
    init_report = {
        'mask_iou': float(init_mask_iou),
        'obj_ptr': {
            'maxAbsError': float(diff_obj_ptr.max()),
            'meanAbsError': float(diff_obj_ptr.mean())
        },
        'memory_features': {
            'maxAbsError': float(diff_mem_feat.max()),
            'meanAbsError': float(diff_mem_feat.mean())
        },
        'memory_pos_enc': {
            'maxAbsError': float(diff_mem_pos.max()),
            'meanAbsError': float(diff_mem_pos.mean())
        }
    }
    print(f'  init_step Mask IoU: {init_mask_iou:.6f}, obj_ptr maxAbsError: {diff_obj_ptr.max():.2e}, mem_feat maxAbsError: {diff_mem_feat.max():.2e}')
    assert init_mask_iou >= 0.999, f'init_step Mask IoU {init_mask_iou} < 0.999'
    assert diff_obj_ptr.max() <= 1e-3, f'init_step obj_ptr maxAbsError {diff_obj_ptr.max()} > 1e-3'
    assert diff_mem_feat.max() <= 1e-3, f'init_step memory_features maxAbsError {diff_mem_feat.max()} > 1e-3'
    
    # ----------------------------------------------------
    # Component 3: Temporal Step Parity
    # ----------------------------------------------------
    print('[Parity 3/3] Testing sam2_temporal_step on Frame 1...')
    f1_img = cv2.imread(os.path.join(ref_dir, '0001.jpg') if os.path.exists(os.path.join(ref_dir, '0001.jpg')) else os.path.join(ref_dir, '00001.jpg'))
    f1_img_rgb = cv2.cvtColor(f1_img, cv2.COLOR_BGR2RGB)
    f1_resized = cv2.resize(f1_img_rgb, (1024, 1024), interpolation=cv2.INTER_LINEAR)
    f1_norm = (f1_resized.astype(np.float32) / 255.0 - np.array([0.485, 0.456, 0.406])) / np.array([0.229, 0.224, 0.225])
    f1_tensor = torch.from_numpy(f1_norm.transpose(2, 0, 1)).unsqueeze(0).float()
    
    with torch.no_grad():
        py_f1_top, py_f1_top_pos, py_f1_high0, py_f1_high1 = img_wrapper(f1_tensor)
        
    # Temporal step input: Frame 0 conditioning memory
    f0_mem_feats_t = py_mem_feat.unsqueeze(0)  # [1, 1, 64, 64, 64]
    f0_mem_pos_t = py_mem_pos.unsqueeze(0)    # [1, 1, 64, 64, 64]
    f0_tpos_indices_t = torch.tensor([6], dtype=torch.int64)  # t_pos=0 -> num_maskmem - 1 = 6
    f0_obj_ptrs_t = py_obj_ptr.unsqueeze(0)   # [1, 1, 256]
    
    temp_wrapper = Sam2TemporalStepExporter(predictor)
    temp_wrapper.eval()
    with patch_rotary_for_export(predictor):
        with torch.no_grad():
            py_f1_low_mask, py_f1_high_mask, py_f1_obj_score, py_f1_obj_ptr, py_f1_mem_feat, py_f1_mem_pos = temp_wrapper(
                py_f1_top,
                py_f1_top_pos,
                py_f1_high0,
                py_f1_high1,
                f0_mem_feats_t,
                f0_mem_pos_t,
                f0_tpos_indices_t,
                f0_obj_ptrs_t
            )
            
    sess_temp = ort.InferenceSession(os.path.join(gen_dir, 'sam2_temporal_step.onnx'), providers=['CPUExecutionProvider'])
    ort_f1_outs = sess_img.run(None, {'image': f1_tensor.numpy()})
    ort_f1_top, ort_f1_top_pos, ort_f1_high0, ort_f1_high1 = ort_f1_outs
    
    ort_temp_outs = sess_temp.run(None, {
        'current_top_feature': ort_f1_top,
        'current_top_pos': ort_f1_top_pos,
        'current_high_res_0': ort_f1_high0,
        'current_high_res_1': ort_f1_high1,
        'selected_memory_features': np.expand_dims(ort_mem_feat, axis=0),
        'selected_memory_pos': np.expand_dims(ort_mem_pos, axis=0),
        'memory_tpos_indices': np.array([6], dtype=np.int64),
        'selected_obj_ptrs': np.expand_dims(ort_obj_ptr, axis=0)
    })
    ort_f1_low_mask, ort_f1_high_mask, ort_f1_obj_score, ort_f1_obj_ptr, ort_f1_mem_feat, ort_f1_mem_pos = ort_temp_outs
    
    temp_mask_iou = compute_mask_iou(py_f1_high_mask.numpy()[0, 0], ort_f1_high_mask[0, 0])
    diff_temp_obj_ptr = np.abs(py_f1_obj_ptr.numpy() - ort_f1_obj_ptr)
    diff_temp_mem_feat = np.abs(py_f1_mem_feat.numpy() - ort_f1_mem_feat)
    
    temp_report = {
        'mask_iou': float(temp_mask_iou),
        'obj_ptr': {
            'maxAbsError': float(diff_temp_obj_ptr.max()),
            'meanAbsError': float(diff_temp_obj_ptr.mean())
        },
        'memory_features': {
            'maxAbsError': float(diff_temp_mem_feat.max()),
            'meanAbsError': float(diff_temp_mem_feat.mean())
        }
    }
    print(f'  temporal_step Mask IoU: {temp_mask_iou:.6f}, obj_ptr maxAbsError: {diff_temp_obj_ptr.max():.2e}, mem_feat maxAbsError: {diff_temp_mem_feat.max():.2e}')
    assert temp_mask_iou >= 0.999, f'temporal_step Mask IoU {temp_mask_iou} < 0.999'
    assert diff_temp_obj_ptr.max() <= 1e-3, f'temporal_step obj_ptr maxAbsError {diff_temp_obj_ptr.max()} > 1e-3'
    assert diff_temp_mem_feat.max() <= 1e-3, f'temporal_step memory_features maxAbsError {diff_temp_mem_feat.max()} > 1e-3'
    
    full_report = {
        'image_features': img_report,
        'init_step': init_report,
        'temporal_step': temp_report,
        'status': 'PASS'
    }
    out_rep_path = os.path.join(rep_dir, 'component_parity.json')
    with open(out_rep_path, 'w', encoding='utf-8') as f:
        json.dump(full_report, f, indent=2)
        
    print(f'[Parity SUCCESS] Component parity report written to {out_rep_path}')
    print(json.dumps(full_report, indent=2))

if __name__ == '__main__':
    run_component_parity()
