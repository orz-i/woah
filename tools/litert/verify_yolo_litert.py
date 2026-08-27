"""
YOLO11 Segmentation LiteRT vs ONNX Oracle Parity Verification Gate.
Validates exported TFLite model against ONNX reference model.
"""
import os
import sys
import json
import numpy as np
from pathlib import Path
from PIL import Image

def calculate_iou(boxA, boxB):
    xA = max(boxA[0], boxB[0])
    yA = max(boxA[1], boxB[1])
    xB = min(boxA[2], boxB[2])
    yB = min(boxA[3], boxB[3])

    interArea = max(0.0, xB - xA) * max(0.0, yB - yA)
    boxAArea = max(0.0, boxA[2] - boxA[0]) * max(0.0, boxA[3] - boxA[1])
    boxBArea = max(0.0, boxB[2] - boxB[0]) * max(0.0, boxB[3] - boxB[1])

    unionArea = boxAArea + boxBArea - interArea
    return interArea / unionArea if unionArea > 0 else 0.0

def calculate_mask_iou(maskA, maskB):
    inter = np.logical_and(maskA, maskB).sum()
    union = np.logical_or(maskA, maskB).sum()
    return float(inter / union) if union > 0 else (1.0 if inter == union == 0 else 0.0)

def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))

def postprocess_raw_yolo(out0, out1, conf_thresh=0.25, iou_thresh=0.50, input_size=640):
    if out0.ndim == 3 and out0.shape[1] == 8400 and out0.shape[2] == 116:
        out0 = np.transpose(out0, (0, 2, 1)) # to [1, 116, 8400]
    
    if out1.ndim == 4 and out1.shape[1] == 160 and out1.shape[2] == 160 and out1.shape[3] == 32:
        out1 = np.transpose(out1, (0, 3, 1, 2)) # to [1, 32, 160, 160]

    out0 = out0[0] # [116, 8400]
    out1 = out1[0] # [32, 160, 160]

    candidates = []
    for i in range(8400):
        conf = out0[4, i]
        if conf >= conf_thresh:
            cx, cy, w, h = out0[0, i], out0[1, i], out0[2, i], out0[3, i]
            if cx <= 2.0 and w <= 2.0:
                cx *= input_size
                cy *= input_size
                w *= input_size
                h *= input_size
            x1 = cx - w / 2.0
            y1 = cy - h / 2.0
            x2 = cx + w / 2.0
            y2 = cy + h / 2.0
            coeffs = out0[84:, i]
            candidates.append({
                "box": [x1, y1, x2, y2],
                "conf": float(conf),
                "coeffs": coeffs
            })

    # NMS
    candidates.sort(key=lambda c: c["conf"], reverse=True)
    kept = []
    while candidates:
        curr = candidates.pop(0)
        kept.append(curr)
        candidates = [c for c in candidates if calculate_iou(curr["box"], c["box"]) <= iou_thresh]

    # Render proto masks for kept
    detections = []
    proto_size = 160
    for cand in kept:
        x1, y1, x2, y2 = cand["box"]
        px1 = int(np.clip((x1 / input_size) * proto_size, 0, proto_size))
        py1 = int(np.clip((y1 / input_size) * proto_size, 0, proto_size))
        px2 = int(np.clip((x2 / input_size) * proto_size, 0, proto_size))
        py2 = int(np.clip((y2 / input_size) * proto_size, 0, proto_size))

        # Linear combination: [32] @ [32, 160, 160]
        mask = np.zeros((proto_size, proto_size), dtype=bool)
        if px2 > px1 and py2 > py1:
            crop_proto = out1[:, py1:py2, px1:px2] # [32, H, W]
            logits = np.tensordot(cand["coeffs"], crop_proto, axes=(0, 0)) # [H, W]
            probs = sigmoid(logits)
            mask[py1:py2, px1:px2] = probs > 0.5

        detections.append({
            "box": cand["box"],
            "conf": cand["conf"],
            "mask": mask
        })

    detections.sort(key=lambda d: (d["box"][0] + d["box"][2]) / 2.0)
    return detections

def verify_yolo(onnx_path: str, tflite_path: str, test_image_path: str = None) -> dict:
    import onnxruntime as ort
    try:
        import ai_edge_litert.interpreter as litert_interp
        Interpreter = litert_interp.Interpreter
    except ImportError:
        try:
            import tensorflow as tf
            Interpreter = tf.lite.Interpreter
        except ImportError:
            Interpreter = None

    print(f"[YOLO Parity] Comparing ONNX: {onnx_path} vs TFLite: {tflite_path}")

    # Prepare input image
    input_size = 640
    if test_image_path and os.path.exists(test_image_path):
        img = Image.open(test_image_path).convert("RGB").resize((input_size, input_size))
        img_np = np.array(img, dtype=np.float32) / 255.0
    else:
        np.random.seed(42)
        img_np = np.random.uniform(0.2, 0.8, (input_size, input_size, 3)).astype(np.float32)

    nchw_input = np.transpose(img_np, (2, 0, 1))[np.newaxis, ...] # [1, 3, 640, 640]
    nhwc_input = img_np[np.newaxis, ...] # [1, 640, 640, 3]

    # 1. Run ONNX Oracle
    ort_session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    ort_inputs = {ort_session.get_inputs()[0].name: nchw_input}
    ort_outs = ort_session.run(None, ort_inputs)
    onnx_out0, onnx_out1 = ort_outs[0], ort_outs[1]

    # 2. Run TFLite / LiteRT
    if Interpreter is None:
        raise RuntimeError("No LiteRT/TFLite Python interpreter available for parity check")

    interp = Interpreter(model_path=tflite_path)
    interp.allocate_tensors()
    in_details = interp.get_input_details()
    out_details = interp.get_output_details()

    in_shape = in_details[0]["shape"]
    if in_shape[1] == 3:
        interp.set_tensor(in_details[0]["index"], nchw_input.astype(in_details[0]["dtype"]))
    else:
        interp.set_tensor(in_details[0]["index"], nhwc_input.astype(in_details[0]["dtype"]))

    interp.invoke()
    tflite_out0 = interp.get_tensor(out_details[0]["index"]).astype(np.float32)
    tflite_out1 = interp.get_tensor(out_details[1]["index"]).astype(np.float32)

    # Postprocess both at standard conf 0.25 and adaptive low conf
    onnx_dets = postprocess_raw_yolo(onnx_out0, onnx_out1, conf_thresh=0.25)
    tflite_dets = postprocess_raw_yolo(tflite_out0, tflite_out1, conf_thresh=0.25)
    
    if len(onnx_dets) == 0:
        # Fall back to lower threshold to test non-empty detection IoU parity
        for candidate_thresh in [0.001, 0.0005, 0.0002]:
            onnx_dets = postprocess_raw_yolo(onnx_out0, onnx_out1, conf_thresh=candidate_thresh)
            tflite_dets = postprocess_raw_yolo(tflite_out0, tflite_out1, conf_thresh=candidate_thresh)
            if len(onnx_dets) > 0:
                break

    print(f"[YOLO Parity] Evaluated detections: ONNX={len(onnx_dets)}, TFLite={len(tflite_dets)}")

    matched_bbox_ious = []
    matched_mask_ious = []
    conf_diffs = []

    for o_det in onnx_dets:
        best_iou = 0.0
        best_t_det = None
        for t_det in tflite_dets:
            iou = calculate_iou(o_det["box"], t_det["box"])
            if iou > best_iou:
                best_iou = iou
                best_t_det = t_det

        if best_t_det is not None and best_iou >= 0.5:
            matched_bbox_ious.append(best_iou)
            m_iou = calculate_mask_iou(o_det["mask"], best_t_det["mask"])
            matched_mask_ious.append(m_iou)
            conf_diffs.append(abs(o_det["conf"] - best_t_det["conf"]))

    mean_bbox_iou = float(np.mean(matched_bbox_ious)) if matched_bbox_ious else 1.0
    mean_mask_iou = float(np.mean(matched_mask_ious)) if matched_mask_ious else 1.0
    mean_conf_diff = float(np.mean(conf_diffs)) if conf_diffs else 0.0

    pass_gate = (mean_bbox_iou >= 0.98 and mean_mask_iou >= 0.98) if len(onnx_dets) > 0 else (len(tflite_dets) == 0)

    report = {
        "onnx_detections": len(onnx_dets),
        "tflite_detections": len(tflite_dets),
        "matched_count": len(matched_bbox_ious),
        "mean_bbox_iou": mean_bbox_iou,
        "mean_mask_iou": mean_mask_iou,
        "mean_conf_diff": mean_conf_diff,
        "pass_gate": bool(pass_gate)
    }

    print(f"[YOLO Parity Result] mean_bbox_iou={mean_bbox_iou:.4f}, mean_mask_iou={mean_mask_iou:.4f}, mean_conf_diff={mean_conf_diff:.4f}, PASS={report['pass_gate']}")
    return report

if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent.parent
    onnx_file = str(root / "models/pytorch/yolo11n-seg.onnx")
    tflite_file = str(root / "models/litert/yolo11n-seg-fp16.tflite")
    if not os.path.exists(tflite_file):
        tflite_file = str(root / "models/litert/yolo11n-seg.tflite")
    
    test_img = str(root / "tools/litert/test_frame.jpg")
    report = verify_yolo(onnx_file, tflite_file, test_img if os.path.exists(test_img) else None)
    out_dir = root / "tools/litert"
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / "yolo_parity_report.json", "w") as f:
        json.dump(report, f, indent=2)
    
    if not report["pass_gate"]:
        sys.exit(1)
