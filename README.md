# Woah (DanceAnon) — AI 智能舞蹈视频打码与隐私匿名化工具

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Flutter](https://img.shields.io/badge/Flutter-3.x-02569B?logo=flutter)](https://flutter.dev)
[![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB?logo=python)](https://www.python.org)
[![Android](https://img.shields.io/badge/Android-MediaCodec%20%7C%20OpenGL%20ES-3DDC84?logo=android)](https://developer.android.com)

**Woah (DanceAnon)** 是一款基于 AI 视觉分割与多目标追踪技术的智能舞蹈视频打码与隐私保护工具。支持在**移动端（Flutter + 原生硬件加速）**与**桌面/Web 端（Python + PyTorch）**双端运行。能够自动识别人体轮廓、精准追踪多人动态，并提供全身打码、马赛克/模糊、渐变遮罩、边缘轮廓、智能镜头跟随等专业后期特效。

---

## ✨ 核心特性

- 🕺 **多人精准识别与实时分割**：基于 YOLOv11 实例分割网络，即使在跳舞动作幅度大、肢体交叠、旋转跳跃时也能精准抠出人体轮廓。
- 🎭 **多样化匿名打码特效**：
  - **纯色剪影 (Solid)**：自定义填充颜色与不透明度。
  - **轮廓光效 (Outline)**：提取人体边缘生成发光描边特效。
  - **动态模糊/马赛克 (Blur & Mosaic)**：保护隐私同时保留背景动态。
  - **渐变遮罩 (Gradient)**：双色空间插值，视觉效果极佳。
  - **美肤与塑形 (Whiten & Stretch)**：支持局部肤色提亮与美型拉腿。
- 🎥 **智能镜头跟随 (Camera Follow)**：锁定指定舞者，利用平滑阻尼算法自动运镜缩放与居中构图。
- ⚡ **移动端纯本地原生端侧引擎**：
  - **零云端依赖**：视频无需上传服务器，保护隐私且离线可用；
  - **硬件加速链路**：`MediaCodec` 硬件解码/编码 + `OpenGL ES 2.0` 定制着色器流水线 + `ONNX Runtime` 神经网络加速；
  - **实时渲染监看视窗**：在视频导出过程中支持静态帧实时监看与 FPS 性能显示。
- 🖥️ **桌面与 Web 高精度引擎**：
  - 基于 FastAPI + PyTorch + CUTIE / SAM2 的高精度分割追踪系统，支持交互式选人、首帧精修与批量渲染导出。

---

## 📁 目录结构

```text
dance-anonymizer/
├── mobile/                  # 📱 移动端工程 (Flutter + Android / iOS 原生插件)
│   ├── app/                 # Flutter 主应用 (UI、路由、状态管理)
│   └── packages/
│       ├── dance_domain/    # 核心业务实体与领域模型
│       ├── dance_native/    # Android (Kotlin/C++/GL) 与 iOS 原生能力桥接
│       └── dance_ui/        # 共享 UI 组件库与设计规范
├── desktop/                 # 🖥️ 桌面 & Web 端工程 (Python / FastAPI)
│   ├── app.py               # Web 后端服务主入口
│   ├── modules/             # 视频读取、推理追踪、特效渲染核心逻辑
│   ├── templates/           # Web 前端交互页面
│   └── vendor/              # 第三方算法库 (CUTIE 等)
├── models/                  # 🧠 模型权重与导出脚本
│   ├── pytorch/             # PyTorch 原生权重 (.pt)
│   └── litert/              # 移动端优化 ONNX / TFLite 模型
├── tools/                   # 🛠️ 辅助脚本 (模型导出、量化、基准测试)
├── testdata/                # 🧪 测试视频与 Golden 校验样本
└── LICENSE                  # 📄 MIT 开源许可证书
```

---

## 🚀 快速开始

### 📱 移动端 (Android / iOS)

#### 1. 环境准备
- 安装 [Flutter SDK](https://flutter.dev/docs/get-started/install) (≥ 3.22.0)
- 安装 [Android Studio](https://developer.android.com/studio) 及 Android SDK / NDK

#### 2. 安装依赖并准备模型
```bash
cd mobile/app
flutter pub get

# 确保移动端已置入 yolo11n-seg.onnx 资源
# 位置：mobile/packages/dance_native/android/src/main/assets/yolo11n-seg.onnx
```

#### 3. 运行与构建
```bash
# 连接真机或启动模拟器后运行
flutter run

# 构建 Android Debug APK
flutter build apk --debug

# 构建 Android Release APK
flutter build apk --release
```

---

### 🖥️ 桌面与 Web 端 (Python)

#### 1. 环境准备
- Python ≥ 3.10
- 推荐使用现代包管理器 [uv](https://github.com/astral-sh/uv)（或标准 pip / conda）

#### 2. 安装依赖
```bash
# 使用 uv (推荐)
uv venv
uv pip install -r requirements.txt

# 或使用标准 pip
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

#### 3. 启动 Web 服务
```bash
# 启动 FastAPI 服务
python desktop/app.py
```
浏览器访问：`http://localhost:8002`

---

## 🛠️ 技术栈

| 模块 | 技术选型 |
|---|---|
| **移动端界面** | Flutter 3.x, Dart 3.x, Riverpod / Bloc, Pigeon IPC |
| **移动端原生渲染** | Android MediaCodec, OpenGL ES 2.0 (GLSL), EGL, Kotlin Coroutines |
| **移动端端侧推理** | ONNX Runtime Mobile, YOLOv11 Nano Segmentation, Kalman Filter |
| **服务端 / 桌面端** | Python 3.10+, FastAPI, OpenCV, PyTorch, TorchVision |
| **高精度追踪算法** | CUTIE (Video Object Segmentation), SAM2 (Segment Anything 2) |

---

## 📄 开源许可证

本项目基于 [MIT License](file:///D:/dance-anonymizer/LICENSE) 协议开源。欢迎提交 Issue 与 Pull Request！
