# Woah — AI 智能舞蹈视频打码与隐私匿名化工具

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Flutter](https://img.shields.io/badge/Flutter-3.x-02569B?logo=flutter)](https://flutter.dev)
[![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB?logo=python)](https://www.python.org)
[![Android](https://img.shields.io/badge/Android-MediaCodec%20%7C%20OpenGL%20ES-3DDC84?logo=android)](https://developer.android.com)

**Woah** 是一款基于 AI 视觉分割与多目标追踪技术的智能舞蹈视频打码与隐私保护工具。当前交付范围为 **Android / iOS 移动端（Flutter + 原生硬件加速）**。桌面 / Web 产品自 2026-10-31 起废弃，历史代码保留但不再维护或提供部署支持。能够自动识别人体轮廓、精准追踪多人动态，并提供全身打码、马赛克/模糊、渐变遮罩、边缘轮廓、智能镜头跟随等专业后期特效。

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
  - **硬件加速链路**：`MediaCodec` 硬件解码/编码 + `OpenGL ES 2.0` 定制着色器流水线 + `LiteRT` 端侧推理；
  - **实时渲染监看视窗**：在视频导出过程中支持静态帧实时监看与 FPS 性能显示。
> 桌面 / Web 已过期，不应新部署。Python、模型导出及验证工具仍用于移动端构建，不属于被废弃的运行产品。详见[桌面退役说明](desktop/README.md)与[冻结治理阶段](docs/governance/governance-roadmap.md)。

---

## 📁 目录结构

```text
woah/
├── mobile/                  # 📱 移动端工程 (Flutter + Android / iOS 原生插件)
│   ├── app/                 # Flutter 主应用 (UI、路由、状态管理)
│   └── packages/
│       ├── dance_domain/    # 核心业务实体与领域模型
│       ├── dance_native/    # Android (Kotlin/C++/GL) 与 iOS 原生能力桥接
│       └── dance_ui/        # 共享 UI 组件库与设计规范
├── desktop/                 # 已废弃的桌面 / Web 历史代码，不再部署或维护
│   └── vendor/              # 保留第三方来源与许可证，不属于删除授权
├── models/                  # 🧠 模型权重与导出脚本
│   ├── pytorch/             # PyTorch 原生权重 (.pt)
│   └── litert/              # 移动端 LiteRT / TFLite 模型
├── tools/                   # 🛠️ 辅助脚本 (模型导出、量化、基准测试)
├── testdata/                # 🧪 测试视频与 Golden 校验样本
└── LICENSE                  # 📄 MIT 开源许可证书
```

---

## 🚀 快速开始

### 📱 移动端 (Android / iOS)

#### 1. 环境准备
- 安装 [Flutter SDK](https://flutter.dev/docs/get-started/install)（当前已验证基线 3.44.2 / Dart 3.12.2）
- 安装 [Android Studio](https://developer.android.com/studio) 及 Android SDK / NDK

#### 2. 安装依赖并准备模型
```bash
cd mobile/app
flutter pub get

# 将仓库中的 canonical YOLO LiteRT 模型同步到 Android assets
python ../../tools/setup_models.py --android
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

### 已废弃：桌面与 Web

本仓库不再提供桌面 / Web 安装或启动教程。`desktop/` 只保留历史源码与许可证；这不代表旧服务的安全问题已修复或部署已下线。已有实例的停用需要单独核实，不能以删除文档代替实际下线。

移动端仍使用 `tools/`、`models/`、`testdata/` 和根 Python 锁环境。不要因桌面废弃删除这些共享资产，也不要把 Linux CI 的 CUDA 解析策略直接当成 Mac 桌面安装指引。

---

## 🛠️ 技术栈

| 模块 | 技术选型 |
|---|---|
| **移动端界面** | Flutter 3.x, Dart 3.x, Riverpod / Bloc, Pigeon IPC |
| **移动端原生渲染** | Android MediaCodec, OpenGL ES 2.0 (GLSL), EGL, Kotlin Coroutines |
| **移动端端侧推理** | LiteRT, YOLOv11 Nano Segmentation, Kalman Filter |
| **移动模型与验证工具** | Python、OpenCV、PyTorch / TorchVision、模型导出与契约测试（非桌面运行产品） |

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 协议开源。欢迎提交 Issue 与 Pull Request！
