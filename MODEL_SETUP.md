# YOLO ONNX Model Setup Guide

本项目使用 YOLO11-seg 进行端侧人体检测与实例分割。

## 一键自动准备 (推荐)

在全新克隆仓库或清理模型后，只需运行：

```bash
uv run python tools/setup_models.py --android
```

该脚本会自动：
1. 检查本地 `models/litert/yolo11n-seg.onnx` 缓存；
2. 若无缓存则自动调用导出脚本下载 PyTorch 权重并导出为 640x640 FP32 ONNX；
3. 将模型放置于 Android 资源目录：
   `mobile/packages/dance_native/android/src/main/assets/yolo11n-seg.onnx`
4. 校验文件存在性与大小，并输出 SHA256 校验和。

## 手动导出模型 (高级)

如果需要定制导出不同尺寸或目标路径：

```bash
uv run python tools/export_yolo.py --model yolo11n-seg --output mobile/packages/dance_native/android/src/main/assets/yolo11n-seg.onnx
```

## 模型输入输出规范

- **Android 路径**: `mobile/packages/dance_native/android/src/main/assets/yolo11n-seg.onnx`
- **输入格式**: `1x3x640x640` (RGB float32, normalized [0.0, 1.0])
- **输出格式**:
  - `output0`: `[1, 116, 8400]` (检测框与 32 维 Mask 系数)
  - `output1`: `[1, 32, 160, 160]` (Proto Mask 原型张量)
