# YOLO ONNX Model Setup Guide

本项目使用 YOLO11-seg 进行人体检测与实例分割。

## 模型导出

可以使用项目提供的 	ools/export_yolo.py 脚本自动下载 PyTorch 权重并导出为 ONNX 模型：

`ash
uv run python tools/export_yolo.py --model yolo11n-seg --output mobile/packages/dance_native/android/src/main/assets/yolo11n-seg.onnx
`

## 模型放置路径

- Android: mobile/packages/dance_native/android/src/main/assets/yolo11n-seg.onnx
- 规范输入尺寸：1x3x640x640 (RGB float32, normalized [0.0, 1.0])
- 输出格式：
  - output0: [1, 116, 8400] (检测框与 32 维 Mask 系数)
  - output1: [1, 32, 160, 160] (Proto Mask 原型张量)
