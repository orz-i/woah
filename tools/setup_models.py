#!/usr/bin/env python3
"""
Model bootstrap and asset setup script for DanceAnon / Woah.
Ensures required ONNX model assets are staged properly for Android builds.

Usage:
    uv run python tools/setup_models.py --android
"""
import os
import sys
import shutil
import hashlib
import argparse
from pathlib import Path

# Ensure UTF-8 output on Windows consoles
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# Add project root to sys.path so we can import tools.export_yolo if needed
project_root = Path(__file__).resolve().parent.parent
if str(project_root) not in sys.path:
    sys.path.insert(0, str(project_root))

def compute_sha256(file_path: Path) -> str:
    hasher = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()

def setup_android_model(root: Path) -> bool:
    source_model = root / "models" / "litert" / "yolo11n-seg.onnx"
    target_asset_dir = (
        root
        / "mobile"
        / "packages"
        / "dance_native"
        / "android"
        / "src"
        / "main"
        / "assets"
    )
    target_model = target_asset_dir / "yolo11n-seg.onnx"

    target_asset_dir.mkdir(parents=True, exist_ok=True)

    if source_model.exists() and source_model.stat().st_size > 0:
        print(f"[CACHE] Found cached model at: {source_model}")
        shutil.copy2(source_model, target_model)
    elif target_model.exists() and target_model.stat().st_size > 0:
        print(f"[OK] Target model asset already present at: {target_model}")
        # Also sync back to models/litert/ if missing
        source_model.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target_model, source_model)
    else:
        print("[WARN] Model not found in cache or assets. Attempting on-demand export...")

        try:
            from tools.export_yolo import export_single_model
            export_single_model("yolo11n-seg", target_model)
            # Sync to source_model as well
            source_model.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target_model, source_model)
        except Exception as e:
            print(f"❌ Failed to export model: {e}")
            print("Please run: uv run python tools/export_yolo.py --model yolo11n-seg --output models/litert/yolo11n-seg.onnx")
            return False

    if not target_model.exists() or target_model.stat().st_size == 0:
        print(f"❌ Model verification failed: {target_model} is missing or empty.")
        return False

    file_size_mb = target_model.stat().st_size / (1024 * 1024)
    sha256_hash = compute_sha256(target_model)

    print("\n==========================================")
    print("SUCCESS: Android Model Asset Ready!")
    print(f"  Path:   {target_model}")
    print(f"  Size:   {file_size_mb:.2f} MB")
    print(f"  SHA256: {sha256_hash}")
    print("==========================================\n")
    return True


def main():
    parser = argparse.ArgumentParser(description="Model bootstrap script for Woah")
    parser.add_argument("--android", action="store_true", help="Prepare model asset for Android build")
    parser.add_argument("--all", action="store_true", help="Prepare all models")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent

    if args.android or args.all or len(sys.argv) == 1:
        success = setup_android_model(root)
        if not success:
            sys.exit(1)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
