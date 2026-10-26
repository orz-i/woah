# 对比页中央视图与参考/目标选片验收

日期：2026-10-26。仓库：`D:\dance-anonymizer`，分支：`feat/dance-comparison-m0`；本轮未推送。

## 完成内容

- 移除顶部“舞蹈对比”标题，以“上下 / 左右”纯文字与箭头作为居中视图入口，沿用隐私打码编辑页的字号、字重与排列方式。
- 移除角色下拉选片；整个视频画面、黑边及空槽均可点击选片。空槽显示加号和“选择参考视频 / 选择目标视频”。
- 页面、手动校准、更多菜单和相关无障碍名称统一为“参考 / 目标”。内部 teacher/learner 数据字段保持兼容。
- 历史、自动校准、手动双路滑条、布局和方向切换沿用现有流程。

代码提交：`1831a65`、`8319f20`。最终完整代码提交：`8319f205ef06a08ba49b708d1d994a2c50f5a236`。

## 检查与证据

| 检查 | 结果与证据 |
| --- | --- |
| Flutter 专项 | 17/17 通过，`docs/session/comparison-ux-20261025/flutter-tests-reference-target-centered.log` |
| 静态检查 | 无问题，`flutter-analyze-reference-target-centered.log` |
| 小屏、大字体 | 320×640 和 800×360、2 倍字号，入口与弹出菜单居中，无布局溢出；全区域点击角色映射及空槽自动校准通过 |
| 真机已加载画面 | 参考、目标各打开一次系统选片器并取消，恢复原片；`21-loaded-main.png`、`22-loaded-final.png` |
| 真机空槽 | 两个空槽各打开选片器并取消，不创建空历史记录；`21-empty-final.png`、`22-empty-final.png` |
| 手动校准 | 两路使用参考/目标标签，取消后保留原校准；`21-manual-labels.png` |
| 最终菜单位置 | 竖屏触发区中心 x=540px；菜单条目范围 x=240..840px，中心同为 540px。横屏安全区中心 x=1252px，菜单范围 x=952..1552px，两者一致；`22-centered-menu.png`、`22-landscape-menu.png` |
| 构建和安装 | Debug 构建退出 0，assembleDebug 112.6s；覆盖安装成功，本地与已安装 APK 哈希相同 |
| 记录保留 | 两条历史记录数量、ID、标题、offset、alignment、锚点、位置、倍速和布局与安装前一致，`22-reference-target-result.json` |
| 运行日志 | 当前应用进程日志未见 E/flutter 或 FATAL EXCEPTION，`22-reference-target-runtime.log` |

上述 PNG/XML/日志均位于本地忽略目录 `docs/session/comparison-ux-20261025/`，未提交原始视频或系统选片器内容。最终菜单宽度/偏移修正后只复测受影响的菜单和方向状态，沿用同轮已完成的选片/取消与手动校准检查。

## 设备和数据边界

设备为 KB2000（Android 14），DPR 3；竖屏 1080×2400，横屏 2400×1080。横屏标题以内容安全区居中，考虑左侧显示切口，不以整张截图的几何中心替代安全区中心。

本轮开始时用户已改为自动校准 +1.490s、共同位置 17.899s、1×、左右视图。原有两条记录均保留，不回写先前轮次的 +1.450s 手动锚点。最终停在新的未保存空槽页，与本轮初始状态一致；已有记录可从历史恢复。返回竖屏后系统 user_rotation=0。

参考源在结尾较暗，且自带播放器画面；目标源也与前轮不同。本轮只核对界面和操作，不把素材变化判作渲染回归，不重新评价音乐锚点或同步误差。

最终 APK：`mobile/app/build/app/outputs/flutter-apk/app-debug.apk`，272649896 字节。

SHA-256：`74a9de1bbd263a940f58df01b2ad6866bad4042da14c3ff601162b63287195a9`。

构建日志：`build-reference-target-centered-verified.log`；安装日志：`install-reference-target-centered.log`。

范围为本次 Android 页面调整；未执行全量 Release QA、多机型或完整 TalkBack 审计。
