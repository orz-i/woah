import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

import time
import json
import csv
import cv2
import numpy as np
import onnxruntime as ort

from state_manager import Sam2OnnxStateManager

def compute_mask_iou(mask1: np.ndarray, mask2: np.ndarray, threshold: float = 0.0):
    b1 = mask1 > threshold
    b2 = mask2 > threshold
    inter = np.logical_and(b1, b2).sum()
    union = np.logical_or(b1, b2).sum()
    if union == 0:
        return 1.0 if inter == 0 else 0.0
    return float(inter / union)

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

def run_python_ort_video():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    gen_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated')
    ref_in_dir = os.path.join(root_dir, 'tools/sam2_onnx/reference_inputs')
    gold_dir = os.path.join(root_dir, 'tools/sam2_onnx/reference_golden')
    out_dir = os.path.join(root_dir, 'tools/sam2_onnx/runtime_output')
    rep_dir = os.path.join(root_dir, 'tools/sam2_onnx/reports')
    
    os.makedirs(os.path.join(out_dir, 'masks'), exist_ok=True)
    os.makedirs(rep_dir, exist_ok=True)
    
    with open(os.path.join(ref_in_dir, 'reference_meta.json'), 'r', encoding='utf-8') as f:
        meta = json.load(f)
        
    w, h = meta['video_width'], meta['video_height']
    num_frames = meta['frame_count']
    first_bbox = meta['first_bbox']
    
    print(f'[Python ORT] Initializing ONNX Runtime sessions (40 frames, {w}x{h})...')
    sess_img = ort.InferenceSession(os.path.join(gen_dir, 'sam2_image_features.onnx'), providers=['CPUExecutionProvider'])
    sess_init = ort.InferenceSession(os.path.join(gen_dir, 'sam2_init_step.onnx'), providers=['CPUExecutionProvider'])
    sess_temp = ort.InferenceSession(os.path.join(gen_dir, 'sam2_temporal_step.onnx'), providers=['CPUExecutionProvider'])
    
    state_mgr = Sam2OnnxStateManager(
        num_maskmem=7,
        mem_dim=64,
        hidden_dim=256,
        max_obj_ptrs_in_encoder=16,
        memory_temporal_stride_for_eval=1,
        max_cond_frames_in_attn=-1
    )
    
    per_frame_records = []
    ious = []
    bbox_center_errors = []
    
    img_times = []
    step_times = []
    
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    
    for f_idx in range(num_frames):
        jpg_path = os.path.join(ref_in_dir, f'{f_idx:05d}.jpg')
        frame_bgr = cv2.imread(jpg_path)
        frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        
        resized = cv2.resize(frame_rgb, (1024, 1024), interpolation=cv2.INTER_LINEAR)
        normalized = (resized.astype(np.float32) / 255.0 - mean) / std
        input_tensor = np.expand_dims(normalized.transpose(2, 0, 1), axis=0)  # [1, 3, 1024, 1024]
        
        t_enc_start = time.perf_counter()
        img_outs = sess_img.run(None, {'image': input_tensor})
        top_feat, top_pos, high_0, high_1 = img_outs
        t_enc_end = time.perf_counter()
        img_times.append((t_enc_end - t_enc_start) * 1000.0)
        
        t_step_start = time.perf_counter()
        if f_idx == 0:
            pts = np.array([[[first_bbox[0] / float(w) * 1024.0, first_bbox[1] / float(h) * 1024.0],
                             [first_bbox[2] / float(w) * 1024.0, first_bbox[3] / float(h) * 1024.0]]], dtype=np.float32)
            labels = np.array([[2, 3]], dtype=np.int32)
            
            init_outs = sess_init.run(None, {
                'top_vision_feature': top_feat,
                'high_res_feature_0': high_0,
                'high_res_feature_1': high_1,
                'point_coords': pts,
                'point_labels': labels
            })
            low_mask, high_mask, obj_score, obj_ptr, mem_feat, mem_pos = init_outs
            state_mgr.add_conditioning_frame(0, mem_feat, mem_pos, obj_ptr)
            
            curr_mem_count = 1
            curr_mem_frames = [0]
            curr_tpos_inds = [6]
            curr_ptr_count = 1
            curr_ptr_frames = [0]
            curr_num_tokens = 4
        else:
            sel = state_mgr.select_for_frame(f_idx, num_frames=num_frames)
            
            temp_outs = sess_temp.run(None, {
                'current_top_feature': top_feat,
                'current_top_pos': top_pos,
                'current_high_res_0': high_0,
                'current_high_res_1': high_1,
                'selected_memory_features': sel['memory_features'],
                'selected_memory_pos': sel['memory_pos'],
                'memory_tpos_indices': sel['memory_tpos_indices'],
                'selected_obj_ptrs': sel['obj_ptrs']
            })
            low_mask, high_mask, obj_score, obj_ptr, mem_feat, mem_pos = temp_outs
            state_mgr.add_non_conditioning_frame(f_idx, mem_feat, mem_pos, obj_ptr)
            
            curr_mem_count = len(sel['memory_frame_indices'])
            curr_mem_frames = sel['memory_frame_indices']
            curr_tpos_inds = list(sel['memory_tpos_indices'])
            curr_ptr_count = len(sel['obj_ptr_frame_indices'])
            curr_ptr_frames = sel['obj_ptr_frame_indices']
            curr_num_tokens = sel['num_obj_ptr_tokens']
            
        t_step_end = time.perf_counter()
        step_times.append((t_step_end - t_step_start) * 1000.0)
        
        # Postprocess mask to original resolution
        high_res_mask_1024 = high_mask[0, 0]  # [1024, 1024]
        mask_orig = cv2.resize(high_res_mask_1024, (w, h), interpolation=cv2.INTER_LINEAR)
        np.save(os.path.join(out_dir, 'masks', f'frame_{f_idx:04d}_mask.npy'), mask_orig)
        
        prob_mask = 1.0 / (1.0 + np.exp(-mask_orig))
        bbox, area, is_valid = compute_bbox_from_mask(prob_mask)
        
        gold_mask = np.load(os.path.join(gold_dir, 'masks', f'frame_{f_idx:04d}_mask.npy'))
        gold_prob = 1.0 / (1.0 + np.exp(-gold_mask))
        gold_bbox, gold_area, _ = compute_bbox_from_mask(gold_prob)
        
        iou = compute_mask_iou(mask_orig, gold_mask, threshold=0.0)
        ious.append(iou)
        
        cx_ort = (bbox[0] + bbox[2]) / 2.0
        cy_ort = (bbox[1] + bbox[3]) / 2.0
        cx_gold = (gold_bbox[0] + gold_bbox[2]) / 2.0
        cy_gold = (gold_bbox[1] + gold_bbox[3]) / 2.0
        center_err = float(np.sqrt((cx_ort - cx_gold) ** 2 + (cy_ort - cy_gold) ** 2))
        bbox_center_errors.append(center_err)
        
        per_frame_records.append({
            'frame': f_idx,
            'mask_iou': iou,
            'bbox_center_error': center_err,
            'ort_bbox': bbox,
            'gold_bbox': gold_bbox,
            'ort_area': area,
            'gold_area': gold_area,
            'object_score': float(obj_score[0, 0]),
            'memory_count': curr_mem_count,
            'memory_frame_indices': curr_mem_frames,
            'obj_ptr_count': curr_ptr_count,
            'obj_ptr_frame_indices': curr_ptr_frames,
            'num_obj_ptr_tokens': curr_num_tokens,
            'image_encoder_ms': img_times[-1],
            'step_ms': step_times[-1],
            'total_ms': img_times[-1] + step_times[-1]
        })
        
        if f_idx % 10 == 0 or f_idx == num_frames - 1:
            print(f'  [Frame {f_idx:02d}] IoU: {iou:.4f} | CenterErr: {center_err:.2f}px | MemCount: {curr_mem_count} | PtrCount: {curr_ptr_count} | Total: {img_times[-1] + step_times[-1]:.1f}ms')
            
    mean_iou = float(np.mean(ious))
    min_iou = float(np.min(ious))
    mean_center_err = float(np.mean(bbox_center_errors))
    max_center_err = float(np.max(bbox_center_errors))
    
    has_3_frame_divergence = False
    consec_count = 0
    for iou in ious:
        if iou < 0.50:
            consec_count += 1
            if consec_count >= 3:
                has_3_frame_divergence = True
                break
        else:
            consec_count = 0
            
    sorted_by_iou = sorted(per_frame_records, key=lambda r: r['mask_iou'])
    worst_5 = sorted_by_iou[:5]
    
    csv_path = os.path.join(rep_dir, 'python_video_parity.csv')
    with open(csv_path, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow([
            'frame', 'mask_iou', 'bbox_center_err',
            'ort_x1', 'ort_y1', 'ort_x2', 'ort_y2', 'ort_area',
            'gold_x1', 'gold_y1', 'gold_x2', 'gold_y2', 'gold_area',
            'object_score', 'memory_count', 'obj_ptr_count', 'total_ms'
        ])
        for r in per_frame_records:
            ob = r['ort_bbox']
            gb = r['gold_bbox']
            writer.writerow([
                r['frame'], f"{r['mask_iou']:.6f}", f"{r['bbox_center_error']:.2f}",
                ob[0], ob[1], ob[2], ob[3], r['ort_area'],
                gb[0], gb[1], gb[2], gb[3], r['gold_area'],
                f"{r['object_score']:.4f}", r['memory_count'], r['obj_ptr_count'], f"{r['total_ms']:.1f}"
            ])
            
    trace_csv_path = os.path.join(out_dir, 'state_trace.csv')
    with open(trace_csv_path, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow([
            'frame', 'memoryCount', 'memoryFrameIndices', 'objPtrCount', 'objPtrFrameIndices',
            'numObjPtrTokens', 'maskArea', 'bbox', 'objectScore'
        ])
        for r in per_frame_records:
            writer.writerow([
                r['frame'], r['memory_count'], str(r['memory_frame_indices']),
                r['obj_ptr_count'], str(r['obj_ptr_frame_indices']), r['num_obj_ptr_tokens'],
                r['ort_area'], str(r['ort_bbox']), f"{r['object_score']:.4f}"
            ])
            
    metrics = {
        'total_frames': num_frames,
        'mean_mask_iou': mean_iou,
        'min_mask_iou': min_iou,
        'has_3_frame_divergence': has_3_frame_divergence,
        'mean_bbox_center_error': mean_center_err,
        'max_bbox_center_error': max_center_err,
        'image_encoder_p50_ms': float(np.percentile(img_times, 50)),
        'image_encoder_p95_ms': float(np.percentile(img_times, 95)),
        'temporal_step_p50_ms': float(np.percentile(step_times, 50)),
        'temporal_step_p95_ms': float(np.percentile(step_times, 95)),
        'total_frame_p50_ms': float(np.percentile(np.array(img_times) + np.array(step_times), 50)),
        'total_frame_p95_ms': float(np.percentile(np.array(img_times) + np.array(step_times), 95)),
        'worst_5_frames': [
            {
                'frame': w_item['frame'],
                'mask_iou': float(w_item['mask_iou']),
                'bbox_center_error': float(w_item['bbox_center_error']),
                'ort_bbox': w_item['ort_bbox'],
                'gold_bbox': w_item['gold_bbox'],
                'memory_frame_indices': w_item['memory_frame_indices']
            }
            for w_item in worst_5
        ]
    }
    
    metrics_path = os.path.join(rep_dir, 'runtime_metrics.json')
    with open(metrics_path, 'w', encoding='utf-8') as f:
        json.dump(metrics, f, indent=2)
        
    div_status = 'YES (FAIL)' if has_3_frame_divergence else 'NO (PASS)'
    print('=' * 60)
    print(f'[Python ORT GATE RESULTS]')
    print(f'  Frames Tracked: {num_frames}/{num_frames}')
    print(f'  Mean Mask IoU:  {mean_iou:.6f} (Gate: >= 0.90)')
    print(f'  Min Mask IoU:   {min_iou:.6f}')
    print(f'  3-Frame Divergence: {div_status}')
    print(f'  Mean BBox Center Error: {mean_center_err:.2f} px')
    print(f'  Max BBox Center Error:  {max_center_err:.2f} px')
    print('Worst 5 frames:')
    for idx, wf in enumerate(worst_5):
        print(f"    #{idx+1}: Frame {wf['frame']:02d} | IoU: {wf['mask_iou']:.4f} | CenterErr: {wf['bbox_center_error']:.2f}px")
    print('=' * 60)
    
    assert mean_iou >= 0.90, f'Python 40-frame Gate FAIL: mean_iou {mean_iou} < 0.90'
    assert not has_3_frame_divergence, 'Python 40-frame Gate FAIL: 3-frame divergence detected'
    print('[Python ORT] ALL GATES PASSED!')

if __name__ == '__main__':
    run_python_ort_video()
