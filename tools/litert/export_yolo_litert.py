"""
YOLO11 Segmentation Model Export Script for LiteRT (.tflite)
Exports raw segmentation head (no embedded end-to-end NMS) for strict GPU/CPU LiteRT execution.
"""
import os
import sys
import shutil
from pathlib import Path

def export_yolo_litert(pt_path: str, out_tflite_path: str, half: bool = True):
    from ultralytics import YOLO
    
    print(f"[YOLO Export] Loading YOLO model from {pt_path}...")
    model = YOLO(pt_path)
    
    print(f"[YOLO Export] Exporting to TFLite (imgsz=640, half={half}, int8=False, nms=False)...")
    # Export using ultralytics tflite exporter
    export_res = model.export(
        format="tflite",
        imgsz=640,
        half=half,
        int8=False,
        nms=False
    )
    
    export_path = Path(export_res)
    if export_path.is_dir():
        # Look for .tflite inside saved_model directory
        tflite_files = list(export_path.glob("*.tflite"))
        if not tflite_files:
            raise FileNotFoundError(f"No .tflite file found in export directory {export_path}")
        # Prefer float16 or float32 file
        if half:
            candidates = [f for f in tflite_files if "float16" in f.name or "fp16" in f.name]
            actual_tflite = candidates[0] if candidates else tflite_files[0]
        else:
            candidates = [f for f in tflite_files if "float32" in f.name or "fp32" in f.name]
            actual_tflite = candidates[0] if candidates else tflite_files[0]
    else:
        actual_tflite = export_path
        
    out_path = Path(out_tflite_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(str(actual_tflite), str(out_path))
    
    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"[YOLO Export] SUCCESS: Saved {out_path} ({size_mb:.2f} MB)")
    
    # Inspect tensor layout
    import ai_edge_litert.interpreter as litert_interp
    interp = litert_interp.Interpreter(model_path=str(out_path))
    interp.allocate_tensors()
    
    in_details = interp.get_input_details()
    out_details = interp.get_output_details()
    
    print("\n--- Tensor Details ---")
    for i, inp in enumerate(in_details):
        print(f"  Input[{i}]: name='{inp['name']}', shape={inp['shape'].tolist()}, dtype={inp['dtype']}")
    for i, out in enumerate(out_details):
        print(f"  Output[{i}]: name='{out['name']}', shape={out['shape'].tolist()}, dtype={out['dtype']}")
    print("----------------------\n")
    
    return {
        "path": str(out_path),
        "size_bytes": out_path.stat().st_size,
        "inputs": [{"name": inp["name"], "shape": inp["shape"].tolist(), "dtype": str(inp["dtype"])} for inp in in_details],
        "outputs": [{"name": out["name"], "shape": out["shape"].tolist(), "dtype": str(out["dtype"])} for out in out_details]
    }

if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent.parent
    pt_file = str(root / "models/pytorch/yolo11n-seg.pt")
    dest_tflite = str(root / "models/litert/yolo11n-seg-fp16.tflite")
    export_yolo_litert(pt_file, dest_tflite, half=True)
