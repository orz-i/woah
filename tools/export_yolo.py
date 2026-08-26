"""
YOLO11 Segmentation Model Export Script for LiteRT / TFLite / ONNX (FP32)
"""
import os
import sys
import shutil
import platform
from pathlib import Path

def export_models():
    try:
        from ultralytics import YOLO
    except ImportError:
        print("[ERROR] ultralytics package is not installed. Please run 'uv add ultralytics'")
        sys.exit(1)

    project_root = Path(__file__).resolve().parent.parent
    pytorch_dir = project_root / "models" / "pytorch"
    litert_dir = project_root / "models" / "litert"
    
    pytorch_dir.mkdir(parents=True, exist_ok=True)
    litert_dir.mkdir(parents=True, exist_ok=True)

    models_to_export = [
        "yolo11n-seg.pt",
        "yolo11s-seg.pt",
    ]

    for model_name in models_to_export:
        print(f"\n==========================================")
        print(f"Exporting {model_name} (FP32, 640x640)...")
        print(f"==========================================")
        
        # Load or download model
        model_path = pytorch_dir / model_name
        if model_path.exists():
            model = YOLO(str(model_path))
        else:
            model = YOLO(model_name)
            downloaded_pt = Path(model_name)
            if downloaded_pt.exists():
                shutil.copy(downloaded_pt, model_path)

        # 1. Export ONNX (Cross-platform universal format)
        try:
            onnx_path = model.export(format="onnx", imgsz=640, opset=18)
            print(f"✅ ONNX export completed: {onnx_path}")
            shutil.copy(onnx_path, litert_dir / f"{Path(model_name).stem}.onnx")
        except Exception as e:
            print(f"⚠️ ONNX export warning: {e}")

        # 2. Export LiteRT / TFLite (if supported on OS)
        try:
            export_path = model.export(
                format="tflite",
                imgsz=640,
                int8=False,
                half=False,
            )
            print(f"✅ TFLite export completed: {export_path}")
            dest_tflite = litert_dir / f"{Path(model_name).stem}-fp32.tflite"
            shutil.copy(export_path, dest_tflite)
            print(f"Successfully placed model at: {dest_tflite} ({dest_tflite.stat().st_size / 1024 / 1024:.2f} MB)")
        except Exception as e:
            print(f"ℹ️ Direct TFLite export note ({platform.system()}): {e}")
            print("   (Note: Use ONNX or run on Linux/macOS/CI to generate direct .tflite)")

import argparse

def export_single_model(model_name: str, output_path: Path | None = None) -> Path:
    try:
        from ultralytics import YOLO
    except ImportError:
        print("[ERROR] ultralytics package is not installed. Please run 'uv add ultralytics'")
        sys.exit(1)

    project_root = Path(__file__).resolve().parent.parent
    pytorch_dir = project_root / "models" / "pytorch"
    litert_dir = project_root / "models" / "litert"
    
    pytorch_dir.mkdir(parents=True, exist_ok=True)
    litert_dir.mkdir(parents=True, exist_ok=True)

    if not model_name.endswith(".pt"):
        pt_name = f"{model_name}.pt"
    else:
        pt_name = model_name

    print(f"\n==========================================")
    print(f"Exporting {pt_name} (FP32, 640x640 ONNX)...")
    print(f"==========================================")

    model_path = pytorch_dir / pt_name
    if model_path.exists():
        model = YOLO(str(model_path))
    else:
        model = YOLO(pt_name)
        downloaded_pt = Path(pt_name)
        if downloaded_pt.exists():
            shutil.copy(downloaded_pt, model_path)

    onnx_path = model.export(format="onnx", imgsz=640, opset=18)
    onnx_file = Path(onnx_path)
    dest_path = output_path if output_path is not None else (litert_dir / f"{Path(pt_name).stem}.onnx")
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(onnx_file, dest_path)
    print(f"✅ ONNX export completed: {dest_path}")
    return dest_path

def main():
    parser = argparse.ArgumentParser(description="YOLO11 ONNX export tool")
    parser.add_argument("--model", type=str, default=None, help="Model name, e.g. yolo11n-seg")
    parser.add_argument("--output", type=str, default=None, help="Output destination path")
    args = parser.parse_args()

    if args.model:
        out_path = Path(args.output) if args.output else None
        export_single_model(args.model, out_path)
    else:
        export_models()

if __name__ == "__main__":
    main()

