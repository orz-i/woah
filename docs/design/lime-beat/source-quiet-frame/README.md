# Woah / Quiet Frame VI v1.1

Quiet Frame 品牌方向的 v1.1 生产候选交付，2026-10-29，修订2。

先阅读 `Woah-Quiet-Frame-VI-v1.1.pdf`。正式中文名“舞哇”，正式英文名“Woah”。Quiet Frame 为视觉方向名，不替代产品名称。

## 本次纠正

中文品牌名称定稿为舞哇，英文名称定稿为 Woah；产品同时覆盖人物隐私保护与舞蹈双视频同步对比。标识、字标及画面延展遵循主品牌，而非为每个功能重设 Logo。

当前人物显示标记是 segmentation 产生的 mask，按人物区域填充或叠加；不是线框、矩形框或仅描边。开放框线仅作为品牌排版与宣传延展。编辑器 PDF/PNG、VI 手册、Token 语义与本源文件已同步修正。示意中的人物与 mask 为人工绘制，薄荷色仅为 VI 示意，不代表当前实现的颜色、透明度或模型精度。

## 资产

- `assets/logo/`：SVG 均已转轮廓，无字体依赖。无裁切的 256×160 符号设计画布。
- `assets/icons/ios/`：1024 PNG 为平面预览，default/dark 共享主色（待独立系统外观验收）；mono PNG 保留透明前景。分层 SVG 为 Icon Composer 的源材料，并非已完成的 `.icon` 项目。
- `assets/icons/android/`：108×108 前景/背景/单色源。XML 为接入参考，需要平台真实编译；API26 文件放入 mipmap-anydpi-v26，API33 文件放入 mipmap-anydpi-v33，并统一重命名为 ic_launcher.xml，图层 XML 放入 drawable。先在隔离分支中检查平台效果。
- `assets/applications/`：品牌应用示意含保护与双视频对比，不是运行截图。原创抽象舞者不代表真实保护效果。海报 SVG 只含基础字标/框线；完整构图在 PDF。
- `assets/tokens/`：新规范建议值，已用于独立接入分支的语义色参考；不可全局替换视频效果色。
- `assets/fonts/`：随包仅分发许可证文件，不重新分发 TTF。文档内部已嵌入必要字体子集；完整字体可从用户原 v1.0 包或官方来源取得。
- `assets/reference/`：用户选定概念图原文件，未改动。
- `assets/checks/`：纯色 sRGB 对比值与导出检查。

## 重建

脚本位于 `source/build_vi.py`，需要 Python 3、reportlab、fonttools、Pillow、PyMuPDF。重建前请先从自有 v1.0 包或官方来源将 IBM Plex Sans 四个字重及 NotoSansSC-Variable.ttf 放入 `assets/fonts/`，然后运行 `python source/build_vi.py`；品牌源码不依赖这些字体在运行设备上存在。

## 待确认

待验收：iOS Icon Composer 外观、Android 多 OEM 系统遮罩、真机小尺寸、系统大字号、视频保护与双视频对比完整回归；不影响正式命名。
