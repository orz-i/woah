# YOLO LiteRT Model Setup Guide

本项目使用 YOLO11-seg 进行端侧人体检测与实例分割。

## 一键自动准备 (推荐)

在全新克隆仓库或清理模型后，只需运行：

```bash
uv run python tools/setup_models.py --android
```

该脚本会自动：
1. 校验仓库内 canonical 模型 `models/litert/yolo11n-seg-fp16.tflite`；
2. 按固定 SHA-256 校验模型身份；
3. 将模型原子同步到 Android 资源目录：
   `mobile/packages/dance_native/android/src/main/assets/models/litert/yolo11n-seg-fp16.tflite`；
4. 再次校验打包副本，避免构建使用错误或不完整模型。

## 手动导出模型 (高级)

如果需要定制导出不同尺寸或目标路径：

```bash
uv run python tools/litert/export_yolo_litert.py
```

导出脚本默认读取 `models/pytorch/yolo11n-seg.pt`，并生成
`models/litert/yolo11n-seg-fp16.tflite`。生成新模型后必须同步更新模型契约中的
SHA-256，并通过仓库现有模型与构建验证后再提交。

## 模型输入输出规范

- **Canonical 路径**: `models/litert/yolo11n-seg-fp16.tflite`
- **Android 路径**: `mobile/packages/dance_native/android/src/main/assets/models/litert/yolo11n-seg-fp16.tflite`
- **输入格式**: `1x3x640x640` (RGB float32, normalized [0.0, 1.0])
- **输出格式**:
  - `output0`: `[1, 116, 8400]` (检测框与 32 维 Mask 系数)
  - `output1`: `[1, 32, 160, 160]` (Proto Mask 原型张量)
