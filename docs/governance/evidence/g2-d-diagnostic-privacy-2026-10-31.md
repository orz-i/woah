# G2-D / PRIV-03：诊断文本与异常出口

范围版本：2026-10-31；G2 第四个、最后一个实现 PR。基线为 G2-C merge `c707c0fff0fd6ae7638992cfd1bb7e579957d53d`，其 [push CI 41189542467](https://github.com/orz-i/woah/actions/runs/41189542467) 三检查成功。阶段总预算仍为四个 PR，不新增第五个切片。

## 实现边界

- Dart 日志仅输出五个既有组件、明确 event 和错误码集合；未知 tag/event/code 使用稳定兜底。任何构建模式都不调用任意 error/stack 的 toString；导入、预览、导出错误文案与 clipboard 只含固定文案／stage／安全码。
- ExportState 保留独立安全 errorCode，code-only 错误可诊断；正常后续进度、取消、重试同时清理旧错误字段，不改变正常 source/output/gallery/share 参数。
- Android 在非生成的两个 Pigeon API wrapper 捕获未知异常并转安全 FlutterError；Swift 在非生成 API 方法内捕获并转安全 PigeonError。生成文件与 schema 未修改。
- 原生日志不再输出媒体 URI、异常 message/cause/stack；已知业务错误码和安全类别保留。Swift comparison 与两平台旧导出状态的 error 出口使用精确固定恢复提示／安全兜底；不删除或重写历史用户项目和成片。
- Android debug 诊断字段按固定源码词表、枚举、受限内部 ID、哈希、artifact 名称及可信运行时设备／codec 信息处理。循环／深层字段有界，未知对象不调用 toString；嵌套异常仅输出安全 code/type。
- ZIP 打包前清理 staging 副本中的旧 JSON/JSONL/text，坏行不原文回退。源文件在复制前须是 canonical diagnostics root 的直系非链接文件，不能先跟随链接再靠 staging 检查。文件名完整匹配当前 session/job，包括既有 cpu_probe/cpu_mt4_probe；未知后缀排除。
- 不新增网络上传、遥测、权限、分享模式或 UI。成功业务 URI／路径保持原样，脱敏只施于诊断／失败出口。

## 必须保留的非匿名 debug 边界

现有诊断包创建／分享仅 debug UI 与 Android debug bridge 提供；release bridge 不实现它，iOS 也未新增该功能。现有跨设备质量工具实际依赖 RGBA、YUV 和 tensor 原始证据，故本切片**保留合法当前 job 的原始二进制证据**，不把日志脱敏扩大为删除验收输入。manifest 明示包可能含可识别媒体，**不宣称诊断包或媒体全部匿名**。测试只使用自建合成数据，没有上传真实内容。

历史原诊断文件不会被此 PR 回溯删除；最终打包处理其副本。日志文本目标与 debug 原始媒体风险是不同边界，后者仍须由开发者审慎控制分享对象和数据来源，不被标成已匿名。

## 确定性验证

- Dart 回归：Unicode／空格／编码文件名、content/file URI、Unix/Windows/UNC 路径、嵌套 PlatformException/FileSystemException/StackTrace、禁止未知对象字符串化；真实 repository 包装 fake client 验证业务 URI 字节保真。
- 导出回归：code-only 错误、旧错误清理、取消失败仍回到正常失败处理、成功状态不误留 isFailed、实际 clipboard platform handler 只收到安全 stage/code。
- Android 实际 createBundle 回归：旧敏感 JSON/JSONL、坏 JSON、恶意文件后缀、指向合成外部 sentinel 的源链接、循环字段；解压扫描文本和文件名不含 sentinel，合法 tensor/RGBA/YUV bytes 不变，原始历史／外部 fixture 不被清理。
- `tools/ci/check_diagnostic_privacy_fixture.py` 调用既有 `analyze_cross_device_batch.read_bundle`、Face/FullBody `extract_bundle(include_fingerprints=True)`，比较非空 production/cpu_probe/cpu_mt4_probe 检测、分析／ROI／adaptive 证据、quality/runtime/performance/observability/fingerprints 与二进制哈希。已通过合成前后 bundle 对比；CI 在原生测试后同样强制执行。不是硬件、golden 或 promotion 验收替代。
- Swift 新 smoke 包含嵌套 NSError、合法 code 携带敏感 message/details、未知 code、comparison 真实错误类型、恢复提示、真实 plugin.probeVideo 原生 API 方法失败边界以及路径／jobId 保真。它不是完整 Pigeon 编解码往返或所有成功媒体 API 的动态覆盖。
- 保留原 Phase3/4/5/6、cache marker，新增 diagnostic marker 为必需。独立 iPhone17/iOS27 实例 `6EF94D7F-C846-4DE3-985B-903D97A1A90E` 已完整通过并删除；没有动现有设备。Xcode27.2 Simulator build 通过。
- 首轮日志测试暴露旧弱断言（保留 MediaStore ID、任意消息）和 fixture session 格式不实，已更新为新隐私契约；真实 ZIP 测试还发现 source canonical alias 与测试 singleton 清理问题，均修复后重测。不是忽略／删除原数值与快照完整性断言。

## 验收记录与收尾

最终本地四包 analyze 与 303 tests（27／1／5／270）、120 native suites／545 tests（0 failure/error/skip）、Release Kotlin、lint、APK、iOS Phase0–6/cloud 静态及共享导出契约、26 CI Python tests＋10 原契约 tests 均通过。三位只读 reviewer 分别核对 Android/CI、Swift、Dart；所有阻断及收口建议已修复，最新旧记录安全写出测试亦通过。APK 241448970 bytes，SHA-256 `f7ee3428df7f2edcb2941175bf242c1ba7eb5a11310541204151b6f9b6dfabd4`。保持既有 70 native + 19 app lint warnings、0 error/fatal；不清零历史警告。head CI 和 merge-SHA CI 附于本 PR 完成记录，不用状态专用提交重启所有检查。

按照维护者新增磁盘要求，每次验证后仅清理本轮、无活动使用的可再生中间产物及自建模拟器；保留源码、模型、原始媒体、最终交付物、测试报告／日志摘要及共享 SDK/Gradle/pub 缓存。G2-C 已清理约 2.8GiB 本地中间产物（可用 16→19GiB）；本切片清理事实另附 PR 完成记录。

Android 真机仍未连接（最近明确无设备证据 06:16 UTC），没有真实 iOS／团队签名环境；iOS17 既有 shader 差异仍按 G2-C 证据保留。模拟器／JVM／合成质量对比不能替代这些缺口。G2 软件交付通过不等于所有硬件／正式发布验收关闭；四项完成后移交冻结 G3，不扩张阶段。
