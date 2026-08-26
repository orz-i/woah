# Woah / DanceAnon 视频端侧处理坐标系统规范

本文档详尽定义并推导 Woah (DanceAnon) 移动端视频处理管线中涉及的全部坐标空间、原点约定、Letterbox 变换以及零翻转 (Zero-Flip) 优化设计。

---

## 1. 坐标空间总览

| 坐标空间名称 | 尺寸 / 范围 | 原点位置 | 描述与典型用途 |
| :--- | :--- | :--- | :--- |
| **Video Coded Frame Space** | `codedWidth × codedHeight` | 左上角 `(0, 0)` | 视频解码器输出的原始编码像素网格（未应用旋转元数据）。 |
| **Visual Display Frame Space** | `displayWidth × displayHeight` | 左上角 `(0, 0)` | 考虑 `rotation` (0°, 90°, 180°, 270°) 后的视觉正向像素网格。 |
| **OpenGL NDC (Normalized Device Coordinates)** | `[-1.0, 1.0] × [-1.0, 1.0]` | 中心 `(0, 0)` | OpenGL 顶点着色器裁剪空间，`x ∈ [-1, 1]`, `y ∈ [-1, 1]`。 |
| **OpenGL FBO Framebuffer Space** | `FBO_Width × FBO_Height` | 左下角 `(0, 0)` | OpenGL 渲染缓冲物理内存空间。`glReadPixels()` 第一行从底部开始读取。 |
| **OpenGL Texture UV Space** | `[0.0, 1.0] × [0.0, 1.0]` | 左下角 `(0, 0)` 或 左上角（取决于采样来源） | 纹理采样归一化空间。标准 2D 纹理与 OES 外部纹理采样坐标。 |
| **Model Preprocessing Space** | `640 × 640` | 左上角 `(0, 0)` | 经等比例缩放与黑色 Letterbox 填充后的模型输入物理图像尺寸。 |
| **Model NCHW Tensor Space** | `[1, 3, 640, 640]` | 左上角 `(0, 0)` | ONNX Runtime 模型输入浮点张量，格式为 `[Batch, Channel, Height, Width]`，数值归一化至 `[0.0, 1.0]`。 |
| **YOLO Proto Mask Space** | `160 × 160` | 左上角 `(0, 0)` | YOLOv11-seg 模型输出的第 2 个输出头（Proto Mask）物理尺寸。 |
| **Bounding Box Space** | `[left, top, right, bottom]` | Visual Display 原图左上角 `(0, 0)` | 跟踪器与后处理输出的目标边界框像素绝对坐标。 |
| **Flutter UI Logical Space** | `Logical Width × Logical Height` | 屏幕/组件左上角 `(0, 0)` | Flutter 渲染层逻辑像素，与 Native 交互时基于归一化或屏幕比例自适应。 |

---

## 2. 核心坐标变换推导

### 2.1 Visual Display 与 Coded Frame 转换

根据视频流元数据 `rotation` 变换：

$$
\text{rotation} \in \{0^\circ, 90^\circ, 180^\circ, 270^\circ\}
$$

- 当 $\text{rotation} \in \{90^\circ, 270^\circ\}$ 时：
  $$
  \text{displayWidth} = \text{codedHeight}, \quad \text{displayHeight} = \text{codedWidth}
  $$
- 当 $\text{rotation} \in \{0^\circ, 180^\circ\}$ 时：
  $$
  \text{displayWidth} = \text{codedWidth}, \quad \text{displayHeight} = \text{codedHeight}
  $$

在 OpenGL 渲染阶段，通过 `SurfaceTexture.getTransformMatrix(stMatrix)` 或计算矩阵统一校正旋转，确保进入渲染管线的内容始终处于正向的视觉空间。

---

### 2.2 ModelCoordinateMapper: Source 到 Model / Proto 的映射

为了保持人体比例不变，模型输入（$640 \times 640$）采用等比例缩放 + Letterbox 黑边居中对齐策略：

$$
\text{scale} = \min\left(\frac{640}{\text{srcWidth}}, \frac{640}{\text{srcHeight}}\right)
$$

$$
\text{scaledW} = \text{srcWidth} \times \text{scale}, \quad \text{scaledH} = \text{srcHeight} \times \text{scale}
$$

$$
\text{padLeft} = \frac{640 - \text{scaledW}}{2}, \quad \text{padTop} = \frac{640 - \text{scaledH}}{2}
$$

#### 1) 原图坐标 $\to$ 模型输入空间 ($640 \times 640$)
$$
X_{\text{model}} = \text{padLeft} + X_{\text{src}} \times \text{scale}
$$
$$
Y_{\text{model}} = \text{padTop} + Y_{\text{src}} \times \text{scale}
$$

#### 2) 模型空间 $\to$ YOLO Proto 空间 ($160 \times 160$)
$$
X_{\text{proto}} = \frac{X_{\text{model}}}{640} \times 160
$$
$$
Y_{\text{proto}} = \frac{Y_{\text{model}}}{640} \times 160
$$

#### 3) 原图坐标直接映射至 Proto 空间
$$
X_{\text{proto}} = \frac{\text{padLeft} + X_{\text{src}} \times \text{scale}}{640} \times 160
$$
$$
Y_{\text{proto}} = \frac{\text{padTop} + Y_{\text{src}} \times \text{scale}}{640} \times 160
$$

---

## 3. 为什么 InferenceRenderer 与 GlShaders 之间没有垂直翻转开销 (Zero-Flip Architecture)

在常规移动端 OpenGL 视频处理中，常见性能瓶颈是：
1. OpenGL FBO 的物理内存原点在**左下角**；
2. `glReadPixels()` 从第 0 行开始读，读取出的是 FBO 的底部；
3. 传统做法在 CPU 或 GPU 上额外执行一次垂直反转（Vertical Flip），导致 1080p 图像出现数毫秒的内存复制或冗余 Pass。

### Woah 的零翻转设计：

1. **`InferenceRenderer` 顶点映射设计**：
   在离屏预处理 FBO 渲染时，顶点着色器的 Quad 顶点与纹理坐标映射如下：
   ```text
   NDC y = -1.0 (裁剪空间底部)  <---->  aTexCoord.y = 1.0 (原图视觉顶部)
   NDC y = +1.0 (裁剪空间顶部)  <---->  aTexCoord.y = 0.0 (原图视觉底部)
   ```
   这意味着：画面在 FBO 中渲染时，视觉顶部直接落在 FBO 的物理底部（即 row 0）。
2. **`glReadPixels()` 的直接对齐**：
   `glReadPixels()` 从 FBO 的物理 row 0 开始顺序读取至 `DirectByteBuffer`。
   因此，读出来的第 0 行内存字节**直接就是原图视觉顶部第一行**！
   完全不需要在 CPU 端做任何上下颠倒翻转。
3. **`GlShaders` 遮罩纹理采样对齐**：
   通过上述管线生成的 Proto Mask 上传为 OpenGL 2D 纹理后，其第 0 行即对齐画面视觉顶部。在 `GlShaders` 片元着色器中：
   ```glsl
   vec2 maskUv = vMaskTexCoord; // 正确对齐视觉顶部
   float maskVal = texture2D(uMaskTexture, maskUv).r;
   ```
   全管线各阶段物理方向严格自洽，消除了所有中间垂直拷贝与二次渲染。

---

## 4. TrackManager: warpMask 动态形变坐标映射

当某一帧检测漏检（`TrackState.LOST`）时，`TrackManager` 会使用卡尔曼滤波器预测的 `predBbox` 和上一帧 `prevBbox` 动态 Warp 旧的 `NativeMask`：

1. 必须使用 `sourceMask.mapper.sourceToProtoX()` 与 `sourceMask.mapper.sourceToProtoY()` 获取精确的 Proto 空间中心：
   $$
   (\text{cx}_{\text{prev}}, \text{cy}_{\text{prev}}) = \text{mapper.sourceToProto}(B_{\text{prev}}.\text{center})
   $$
   $$
   (\text{cx}_{\text{pred}}, \text{cy}_{\text{pred}}) = \text{mapper.sourceToProto}(B_{\text{pred}}.\text{center})
   $$
2. 逆向采样公式：
   $$
   X_{\text{src}} = \frac{X_{\text{dst}} - \text{cx}_{\text{pred}}}{\text{scaleX}} + \text{cx}_{\text{prev}}
   $$
   $$
   Y_{\text{src}} = \frac{Y_{\text{dst}} - \text{cy}_{\text{pred}}}{\text{scaleY}} + \text{cy}_{\text{prev}}
   $$
3. 在 16:9、9:16、1:1 等任意宽高比下，黑边（Letterbox padding）均在 `sourceToProto` 中被精确补偿，保证 Mask 在平移与形变过程中与目标人物轮廓始终严格贴合。
