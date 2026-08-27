#!/usr/bin/env python3
"""
Static Model Graph Analyzer for LiteRT / TFLite Models.
Inspects operators, tensors, data types, shapes, and dynamic dimensions to diagnose
LiteRT GPU delegate compatibility differences between models.
"""

import os
import sys
import json
from pathlib import Path

def analyze_tflite_model(model_path: str) -> dict:
    if not os.path.exists(model_path):
        return {"error": f"Model file not found: {model_path}"}

    file_size_bytes = os.path.getsize(model_path)
    report = {
        "model_path": os.path.abspath(model_path),
        "file_name": os.path.basename(model_path),
        "file_size_bytes": file_size_bytes,
        "file_size_mb": round(file_size_bytes / (1024 * 1024), 2),
        "subgraphs": [],
        "operator_counts": {},
        "unique_operators": [],
        "tensor_types": {},
        "has_dynamic_shapes": False,
        "dynamic_tensor_count": 0,
        "total_tensors": 0,
        "total_operators": 0,
        "gpu_delegate_risk_factors": []
    }

    # Attempt analysis using tensorflow/tflite schema if available
    try:
        import tensorflow as tf
        interpreter = tf.lite.Interpreter(model_path=model_path)
        interpreter.allocate_tensors()

        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        tensor_details = interpreter.get_tensor_details()

        report["input_tensors"] = [
            {
                "name": inp["name"],
                "index": int(inp["index"]),
                "shape": [int(x) for x in inp["shape"]],
                "dtype": str(inp["dtype"].__name__ if hasattr(inp["dtype"], "__name__") else inp["dtype"])
            }
            for inp in input_details
        ]

        report["output_tensors"] = [
            {
                "name": out["name"],
                "index": int(out["index"]),
                "shape": [int(x) for x in out["shape"]],
                "dtype": str(out["dtype"].__name__ if hasattr(out["dtype"], "__name__") else out["dtype"])
            }
            for out in output_details
        ]

        report["total_tensors"] = len(tensor_details)
        for t in tensor_details:
            dt = str(t["dtype"].__name__ if hasattr(t["dtype"], "__name__") else t["dtype"])
            report["tensor_types"][dt] = report["tensor_types"].get(dt, 0) + 1
            shape = [int(x) for x in t.get("shape", [])]
            if any(dim < 0 for dim in shape):
                report["has_dynamic_shapes"] = True
                report["dynamic_tensor_count"] += 1

    except Exception as e:
        report["tf_lite_interpreter_note"] = f"TF lite interpreter scan skipped/failed: {e}"

    # Also parse raw flatbuffer / byte inspect for known opcode names and markers
    try:
        with open(model_path, "rb") as f:
            data = f.read()

        # Check TFL3 / TFLite magic identifier in bytes 4..7
        if len(data) >= 8:
            magic = data[4:8]
            report["format_magic"] = magic.decode("latin1", errors="ignore")

        # Scan for common TFLite operator strings in the schema string table
        known_ops = [
            "CONV_2D", "DEPTHWISE_CONV_2D", "TRANSPOSE_CONV", "FULLY_CONNECTED",
            "BATCH_MATMUL", "GELU", "SOFTMAX", "LOGISTIC", "RELU", "RELU6",
            "ADD", "SUB", "MUL", "DIV", "POW", "SQUARED_DIFFERENCE",
            "RESHAPE", "TRANSPOSE", "STRIDED_SLICE", "SLICE", "PACK", "UNPACK",
            "CONCATENATION", "SPLIT", "SPLIT_V", "RESIZE_BILINEAR", "RESIZE_NEAREST_NEIGHBOR",
            "MEAN", "SUM", "REDUCE_MAX", "REDUCE_MIN", "PAD", "PADV2",
            "CUSTOM", "SELECT", "SELECT_V2", "CAST", "EXP", "LOG", "SQRT", "RSQRT"
        ]

        found_ops = {}
        for op in known_ops:
            count = data.count(op.encode("utf-8"))
            if count > 0:
                found_ops[op] = count

        report["operator_counts"] = found_ops
        report["unique_operators"] = sorted(list(found_ops.keys()))
        report["total_operators"] = sum(found_ops.values())

        # GPU delegate risk evaluation
        risks = []
        if "BATCH_MATMUL" in found_ops:
            risks.append("BATCH_MATMUL: Multi-head attention MatMul may require specific FP16 texture layout on Qualcomm Adreno")
        if "RESIZE_BILINEAR" in found_ops:
            risks.append("RESIZE_BILINEAR: Check half_pixel_centers / align_corners compatibility on OpenCL/Vulkan delegate")
        if "GELU" in found_ops:
            risks.append("GELU: Custom or composite op might decompose into elementary ops during GPU delegate compile")
        if "STRIDED_SLICE" in found_ops:
            risks.append("STRIDED_SLICE: Dynamic or non-constant stride slicing requires CPU fallback or graph rewrite")
        if "SELECT_V2" in found_ops or "SELECT" in found_ops:
            risks.append("SELECT/SELECT_V2: Conditional ops often fail strict GPU delegate partitioning")

        report["gpu_delegate_risk_factors"] = risks

    except Exception as e:
        report["flatbuffer_scan_error"] = str(e)

    return report

def main():
    repo_root = Path(__file__).resolve().parent.parent.parent
    reports_dir = repo_root / "reports"
    reports_dir.mkdir(exist_ok=True)

    models_to_analyze = [
        ("sam2_image_features", repo_root / "models" / "litert" / "sam2_image_features.tflite"),
        ("yolo11n_seg_fp16", repo_root / "models" / "litert" / "yolo11n-seg-fp16.tflite"),
        ("sam2_init_step", repo_root / "models" / "litert" / "sam2_init_step.tflite"),
        ("sam2_temporal_step", repo_root / "models" / "litert" / "sam2_temporal_step.tflite")
    ]

    summary = {}
    for name, path in models_to_analyze:
        print(f"[Analyzing] {name} at {path}...")
        report = analyze_tflite_model(str(path))
        out_file = reports_dir / f"{name}_graph_report.json"
        with open(out_file, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"  -> Saved report to {out_file}")
        summary[name] = {
            "file_size_mb": report.get("file_size_mb"),
            "total_operators": report.get("total_operators"),
            "unique_operators": report.get("unique_operators"),
            "risk_factors": report.get("gpu_delegate_risk_factors")
        }

    summary_file = reports_dir / "litert_models_comparison_summary.json"
    with open(summary_file, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    print(f"[Done] Summary written to {summary_file}")

if __name__ == "__main__":
    main()
