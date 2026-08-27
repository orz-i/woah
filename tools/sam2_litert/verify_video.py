import os
import sys
import time
import json
import csv
import cv2
import numpy as np
import ai_edge_litert.interpreter as litert_interp

ROOT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
if ROOT_PATH not in sys.path:
    sys.path.insert(0, ROOT_PATH)

from tools.sam2_onnx.runtime.state_manager import Sam2OnnxStateManager

NUM_MASKMEM = 7
MAX_OBJ_PTRS = 16
HIDDEN_DIM = 256
MEM_DIM = 64
TOTAL_MEM_TOKENS = NUM_MASKMEM * 4096 + MAX_OBJ_PTRS * 4  # 28736

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

def run_litert_40frame_video():
    root_dir = ROOT_PATH
    models_dir = os.path.join(root_dir, 'models/litert')
    ref_in_dir = os.path.join(root_dir, 'tools/sam2_onnx/reference_inputs')
    gold_dir = os.path.join(root_dir, 'tools/sam2_onnx/reference_golden')
    out_dir = os.path.join(root_dir, 'tools/sam2_litert/runtime_output')
    rep_dir = os.path.join(root_dir, 'tools/sam2_litert/reports')

    os.makedirs(os.path.join(out_dir, 'masks'), exist_ok=True)
    os.makedirs(rep_dir, exist_ok=True)

    with open(os.path.join(ref_in_dir, 'reference_meta.json'), 'r', encoding='utf-8') as f:
        meta = json.load(f)

    w, h = meta['video_width'], meta['video_height']
    num_frames = meta['frame_count']
    first_bbox = meta['first_bbox']

    print(f'[LiteRT SAM2 Video] Initializing LiteRT interpreters (40 frames, {w}x{h})...', flush=True)
    threads = max(1, os.cpu_count() or 4)
    interp_img = litert_interp.Interpreter(model_path=os.path.join(models_dir, 'sam2_image_features.tflite'), num_threads=threads)
    interp_img.allocate_tensors()
    img_in_details = interp_img.get_input_details()
    img_out_details = interp_img.get_output_details()

    interp_init = litert_interp.Interpreter(model_path=os.path.join(models_dir, 'sam2_init_step.tflite'), num_threads=threads)
    interp_init.allocate_tensors()
    init_in_details = interp_init.get_input_details()
    init_out_details = interp_init.get_output_details()

    interp_temp = litert_interp.Interpreter(model_path=os.path.join(models_dir, 'sam2_temporal_step.tflite'), num_threads=threads)
    interp_temp.allocate_tensors()
    temp_in_details = interp_temp.get_input_details()
    temp_out_details = interp_temp.get_output_details()

    state_mgr = Sam2OnnxStateManager(
        num_maskmem=NUM_MASKMEM,
        mem_dim=MEM_DIM,
        hidden_dim=HIDDEN_DIM,
        max_obj_ptrs_in_encoder=MAX_OBJ_PTRS,
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
        input_tensor = np.expand_dims(normalized.transpose(2, 0, 1), axis=0).astype(np.float32)  # [1, 3, 1024, 1024]

        # 1. Image features
        t_enc_start = time.perf_counter()
        interp_img.set_tensor(img_in_details[0]['index'], input_tensor)
        interp_img.invoke()
        top_feat = interp_img.get_tensor(img_out_details[0]['index'])
        top_pos = interp_img.get_tensor(img_out_details[1]['index'])
        high_0 = interp_img.get_tensor(img_out_details[2]['index'])
        high_1 = interp_img.get_tensor(img_out_details[3]['index'])
        t_enc_end = time.perf_counter()
        img_times.append((t_enc_end - t_enc_start) * 1000.0)

        # 2. Tracking Step
        t_step_start = time.perf_counter()
        if f_idx == 0:
            pts = np.array([[[first_bbox[0] / float(w) * 1024.0, first_bbox[1] / float(h) * 1024.0],
                             [first_bbox[2] / float(w) * 1024.0, first_bbox[3] / float(h) * 1024.0]]], dtype=np.float32)
            labels = np.array([[2, 3]], dtype=np.int32)

            interp_init.set_tensor(init_in_details[0]['index'], top_feat)
            interp_init.set_tensor(init_in_details[1]['index'], high_0)
            interp_init.set_tensor(init_in_details[2]['index'], high_1)
            interp_init.set_tensor(init_in_details[3]['index'], pts)
            interp_init.set_tensor(init_in_details[4]['index'], labels)
            interp_init.invoke()

            low_mask = interp_init.get_tensor(init_out_details[0]['index'])
            high_mask = interp_init.get_tensor(init_out_details[1]['index'])
            obj_score = interp_init.get_tensor(init_out_details[2]['index'])
            obj_ptr = interp_init.get_tensor(init_out_details[3]['index'])
            mem_feat = interp_init.get_tensor(init_out_details[4]['index'])
            mem_pos = interp_init.get_tensor(init_out_details[5]['index'])

            state_mgr.add_conditioning_frame(0, mem_feat, mem_pos, obj_ptr)

            curr_mem_count = 1
            curr_mem_frames = [0]
            curr_ptr_count = 1
            curr_ptr_frames = [0]
            curr_num_tokens = 4
        else:
            sel = state_mgr.select_for_frame(f_idx, num_frames=num_frames)

            # Build static padded arrays and attention mask
            padded_mem_feats = np.zeros((NUM_MASKMEM, 1, 64, 64, 64), dtype=np.float32)
            padded_mem_pos = np.zeros((NUM_MASKMEM, 1, 64, 64, 64), dtype=np.float32)
            padded_tpos_inds = np.zeros((NUM_MASKMEM,), dtype=np.int64)
            padded_obj_ptrs = np.zeros((MAX_OBJ_PTRS, 1, 256), dtype=np.float32)
            attn_mask = np.full((1, 1, 1, TOTAL_MEM_TOKENS), -10000.0, dtype=np.float32)

            num_mem = len(sel['memory_frame_indices'])
            if num_mem > 0:
                padded_mem_feats[:num_mem] = sel['memory_features']
                padded_mem_pos[:num_mem] = sel['memory_pos']
                padded_tpos_inds[:num_mem] = sel['memory_tpos_indices']
                attn_mask[0, 0, 0, :num_mem * 4096] = 0.0

            num_ptrs = len(sel['obj_ptr_frame_indices'])
            if num_ptrs > 0:
                padded_obj_ptrs[:num_ptrs] = sel['obj_ptrs']
                attn_mask[0, 0, 0, NUM_MASKMEM * 4096 : NUM_MASKMEM * 4096 + num_ptrs * 4] = 0.0

            interp_temp.set_tensor(temp_in_details[0]['index'], top_feat)
            interp_temp.set_tensor(temp_in_details[1]['index'], top_pos)
            interp_temp.set_tensor(temp_in_details[2]['index'], high_0)
            interp_temp.set_tensor(temp_in_details[3]['index'], high_1)
            interp_temp.set_tensor(temp_in_details[4]['index'], padded_mem_feats)
            interp_temp.set_tensor(temp_in_details[5]['index'], padded_mem_pos)
            interp_temp.set_tensor(temp_in_details[6]['index'], padded_tpos_inds)
            interp_temp.set_tensor(temp_in_details[7]['index'], padded_obj_ptrs)
            interp_temp.set_tensor(temp_in_details[8]['index'], attn_mask)
            interp_temp.invoke()

            low_mask = interp_temp.get_tensor(temp_out_details[0]['index'])
            high_mask = interp_temp.get_tensor(temp_out_details[1]['index'])
            obj_score = interp_temp.get_tensor(temp_out_details[2]['index'])
            obj_ptr = interp_temp.get_tensor(temp_out_details[3]['index'])
            mem_feat = interp_temp.get_tensor(temp_out_details[4]['index'])
            mem_pos = interp_temp.get_tensor(temp_out_details[5]['index'])

            state_mgr.add_non_conditioning_frame(f_idx, mem_feat, mem_pos, obj_ptr)

            curr_mem_count = num_mem
            curr_mem_frames = sel['memory_frame_indices']
            curr_ptr_count = num_ptrs
            curr_ptr_frames = sel['obj_ptr_frame_indices']
            curr_num_tokens = sel['num_obj_ptr_tokens']

        t_step_end = time.perf_counter()
        step_times.append((t_step_end - t_step_start) * 1000.0)

        # Postprocess mask
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

        cx_lit = (bbox[0] + bbox[2]) / 2.0
        cy_lit = (bbox[1] + bbox[3]) / 2.0
        cx_gold = (gold_bbox[0] + gold_bbox[2]) / 2.0
        cy_gold = (gold_bbox[1] + gold_bbox[3]) / 2.0
        center_err = float(np.sqrt((cx_lit - cx_gold) ** 2 + (cy_lit - cy_gold) ** 2))
        bbox_center_errors.append(center_err)

        per_frame_records.append({
            'frame': f_idx,
            'mask_iou': iou,
            'bbox_center_error': center_err,
            'litert_bbox': bbox,
            'gold_bbox': gold_bbox,
            'litert_area': area,
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
            print(f'  [Frame {f_idx:02d}] Mask IoU: {iou:.4f} | CenterErr: {center_err:.2f}px | MemCount: {curr_mem_count} | PtrCount: {curr_ptr_count} | Total: {img_times[-1] + step_times[-1]:.1f}ms', flush=True)

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
        'pass_gate_s3': mean_iou >= 0.90 and not has_3_frame_divergence,
        'worst_5_frames': [
            {
                'frame': w_item['frame'],
                'mask_iou': float(w_item['mask_iou']),
                'bbox_center_error': float(w_item['bbox_center_error']),
                'litert_bbox': w_item['litert_bbox'],
                'gold_bbox': w_item['gold_bbox'],
                'memory_frame_indices': w_item['memory_frame_indices']
            }
            for w_item in worst_5
        ]
    }

    metrics_path = os.path.join(rep_dir, 'litert_video_parity_report.json')
    with open(metrics_path, 'w', encoding='utf-8') as f:
        json.dump(metrics, f, indent=2)

    div_status = 'YES (FAIL)' if has_3_frame_divergence else 'NO (PASS)'
    print('=' * 60)
    print(f'[LiteRT SAM2 40-FRAME VIDEO GATE RESULTS]')
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

    assert mean_iou >= 0.90, f'LiteRT 40-frame Gate FAIL: mean_iou {mean_iou:.4f} < 0.90'
    assert not has_3_frame_divergence, 'LiteRT 40-frame Gate FAIL: 3-frame divergence detected'
    print('[LiteRT SAM2 Video] ALL 40-FRAME GATES PASSED!')
    return True

if __name__ == '__main__':
    if not run_litert_40frame_video():
        sys.exit(1)
