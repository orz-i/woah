#!/usr/bin/env python3
"""
Static Model Graph Analyzer for LiteRT / TFLite Models.
Inspects operators, tensors, data types, shapes, and dynamic dimensions to diagnose
LiteRT GPU delegate compatibility differences between models.
"""

import os
import sys
import json
import hashlib
import argparse
from pathlib import Path


def _json_shape(value):
    if value is None:
        return []
    return [int(x) for x in value]


def _tensor_summary(tensor_by_index, index):
    if index is None or int(index) < 0:
        return {"index": int(index) if index is not None else -1, "optional": True}
    idx = int(index)
    detail = tensor_by_index.get(idx)
    if detail is None:
        return {"index": idx, "missing_detail": True}
    shape = _json_shape(detail.get("shape", []))
    shape_signature = _json_shape(detail.get("shape_signature", shape))
    dtype = detail.get("dtype")
    return {
        "index": idx,
        "name": detail.get("name", ""),
        "shape": shape,
        "shape_signature": shape_signature,
        "rank": len(shape),
        "dtype": str(dtype.__name__ if hasattr(dtype, "__name__") else dtype),
    }

def analyze_tflite_model(model_path: str, allocate_tensors: bool = False) -> dict:
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
        "gpu_delegate_risk_factors": [],
        "operators": [],
        "gpu_blocker_candidates": []
    }

    with open(model_path, "rb") as f:
        report["sha256"] = hashlib.sha256(f.read()).hexdigest()

    # Attempt analysis using a real TFLite/LiteRT interpreter. Static graph
    # inspection does not require allocate_tensors() or invoke(), which keeps
    # this tool lightweight even for the ~113 MB SAM image encoder.
    try:
        interpreter_backend = None
        try:
            import tensorflow as tf
            interpreter = tf.lite.Interpreter(model_path=model_path)
            interpreter_backend = "tensorflow.lite.Interpreter"
        except ImportError:
            import ai_edge_litert.interpreter as litert_interp
            interpreter = litert_interp.Interpreter(model_path=model_path)
            interpreter_backend = "ai_edge_litert.interpreter.Interpreter"

        if allocate_tensors:
            interpreter.allocate_tensors()
        report["interpreter_backend"] = interpreter_backend
        report["tensors_allocated"] = allocate_tensors

        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        tensor_details = interpreter.get_tensor_details()
        tensor_by_index = {int(t["index"]): t for t in tensor_details}

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

        # Private but stable-enough diagnostic API available in TensorFlow's
        # Lite interpreter. Unlike raw-byte string scanning this returns the
        # actual flatbuffer execution order and tensor indices.
        get_ops_details = getattr(interpreter, "_get_ops_details", None)
        if callable(get_ops_details):
            ops = get_ops_details()
            parsed_ops = []
            blockers = []
            for op_index, op in enumerate(ops):
                op_name = str(op.get("op_name", "UNKNOWN"))
                inputs = [_tensor_summary(tensor_by_index, i) for i in op.get("inputs", [])]
                outputs = [_tensor_summary(tensor_by_index, i) for i in op.get("outputs", [])]
                record = {
                    "op_index": op_index,
                    "opcode": op_name,
                    "inputs": inputs,
                    "outputs": outputs,
                }
                parsed_ops.append(record)

                max_rank = max(
                    [t.get("rank", 0) for t in inputs + outputs if not t.get("optional")],
                    default=0,
                )
                if op_name in {"BROADCAST_TO", "GATHER_ND"} or (op_name == "RESHAPE" and max_rank >= 5):
                    blockers.append(record)

            report["operators"] = parsed_ops
            report["total_operators"] = len(parsed_ops)
            op_counts = {}
            for op in parsed_ops:
                name = op["opcode"]
                op_counts[name] = op_counts.get(name, 0) + 1
            report["operator_counts"] = op_counts
            report["unique_operators"] = sorted(op_counts)
            report["gpu_blocker_candidates"] = blockers
        else:
            report["ops_details_note"] = "Interpreter._get_ops_details() unavailable"

    except Exception as e:
        report["tf_lite_interpreter_note"] = f"TF lite interpreter scan skipped/failed: {e}"

    report["operator_parser_available"] = bool(report["operators"])

    # Raw bytes are retained only as a format sanity check. Operator truth must
    # come from _get_ops_details() above, not string scanning.
    try:
        with open(model_path, "rb") as f:
            data = f.read()

        # Check TFL3 / TFLite magic identifier in bytes 4..7
        if len(data) >= 8:
            magic = data[4:8]
            report["format_magic"] = magic.decode("latin1", errors="ignore")

        # GPU delegate risk evaluation
        risks = []
        found_ops = report.get("operator_counts", {})
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
    parser = argparse.ArgumentParser(
        description="Statically inspect LiteRT/TFLite graph operators without executing the model."
    )
    parser.add_argument(
        "--model",
        choices=["sam2_image_features", "yolo11n_seg_fp16", "sam2_init_step", "sam2_temporal_step", "all"],
        default="all",
        help="Analyze only one model to keep local inspection lightweight.",
    )
    parser.add_argument(
        "--allocate-tensors",
        action="store_true",
        help="Allocate tensors before inspection. Not needed for normal static graph analysis.",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent.parent
    reports_dir = repo_root / "reports"
    reports_dir.mkdir(exist_ok=True)

    models_to_analyze = [
        ("sam2_image_features", repo_root / "models" / "litert" / "sam2_image_features.tflite"),
        ("yolo11n_seg_fp16", repo_root / "models" / "litert" / "yolo11n-seg-fp16.tflite"),
        ("sam2_init_step", repo_root / "models" / "litert" / "sam2_init_step.tflite"),
        ("sam2_temporal_step", repo_root / "models" / "litert" / "sam2_temporal_step.tflite")
    ]

    if args.model != "all":
        models_to_analyze = [item for item in models_to_analyze if item[0] == args.model]

    summary = {}
    parsed_model_count = 0
    for name, path in models_to_analyze:
        print(f"[Analyzing] {name} at {path}...")
        report = analyze_tflite_model(str(path), allocate_tensors=args.allocate_tensors)
        if not report.get("operator_parser_available"):
            print(
                "  -> SKIPPED report overwrite: no real TFLite operator parser is available "
                f"({report.get('tf_lite_interpreter_note', 'unknown reason')})"
            )
            summary[name] = {
                "sha256": report.get("sha256"),
                "file_size_mb": report.get("file_size_mb"),
                "analysis_status": "PARSER_UNAVAILABLE",
                "note": report.get("tf_lite_interpreter_note"),
            }
            continue
        out_file = reports_dir / f"{name}_graph_report.json"
        with open(out_file, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"  -> Saved report to {out_file}")

        blocker_file = reports_dir / f"{name}_gpu_blockers.json"
        blocker_report = {
            "model": report.get("file_name"),
            "sha256": report.get("sha256"),
            "file_size_bytes": report.get("file_size_bytes"),
            "total_operators": report.get("total_operators"),
            "operator_counts": report.get("operator_counts"),
            "gpu_blocker_candidate_count": len(report.get("gpu_blocker_candidates", [])),
            "gpu_blocker_candidates": report.get("gpu_blocker_candidates", []),
        }
        with open(blocker_file, "w", encoding="utf-8") as f:
            json.dump(blocker_report, f, indent=2)
        print(f"  -> Saved compact GPU blocker report to {blocker_file}")
        parsed_model_count += 1
        summary[name] = {
            "sha256": report.get("sha256"),
            "file_size_mb": report.get("file_size_mb"),
            "total_operators": report.get("total_operators"),
            "unique_operators": report.get("unique_operators"),
            "gpu_blocker_candidate_count": len(report.get("gpu_blocker_candidates", [])),
            "risk_factors": report.get("gpu_delegate_risk_factors")
        }

    if parsed_model_count > 0 and args.model == "all":
        summary_file = reports_dir / "litert_models_comparison_summary.json"
        with open(summary_file, "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)
        print(f"[Done] Summary written to {summary_file}")
    elif parsed_model_count > 0:
        print("[Done] Single-model analysis complete; global comparison summary was not modified.")
    else:
        print("[Done] No trusted reports were overwritten because no real operator parser was available.")

if __name__ == "__main__":
    main()
