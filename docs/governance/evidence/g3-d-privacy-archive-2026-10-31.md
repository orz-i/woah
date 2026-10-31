# G3-D：required-reason API 与真实 unsigned archive

PRIV-02 唯一切片，也是冻结 G3 的第四个／最后实现 PR。基线 `45cd5770d5ffff1d0e0f779f68105ff9d082712c`，PR16 head run 41203537334、merge run 41204821995 均三检查成功。无签名、商店权限、SDK 升级、桌面或新治理阶段。

## 用途 → API → 组件声明

| 自有调用 | 真实用途／路径归属 | 声明 |
| --- | --- | --- |
| IOSComparisonFiles.swift 的 contentModificationDateKey / contentModificationDate | ApplicationSupport/WoahComparisonMedia 中 app 自己导入副本的既有回收判断；不是读取外部原媒体时间戳 | FileTimestamp / C617.1 |
| IOSComparisonExportFiles.swift 同类 URL resourceValues | app 管理的 WoahComparisonExports 既有保留／替换判断，不触碰 Photos 条目 | 同上 |
| IOSComparisonProjectStore.swift 同类 URL resourceValues | app 容器内比较项目 JSON 按修改时间排序 | 同上 |
| IOSRegenerableCacheRegistry.swift 的 attributesOfItem / modificationDate | app Caches 白名单再生缓存的既有 orphan 年龄判断 | 同上 |

C617.1 匹配 app 容器内文件元数据用途。没有改变任何回收、保留或用户删除策略。Picker／合成 smoke 的 setAttributes 是写时间戳，不据此虚构额外读取用途。对自有 Swift／Dart 源码检索未发现 UserDefaults、disk-space、systemUptime、mach_absolute_time、activeInputModes 等明确必报调用；CACurrentMediaTime 未列于本次官方必报列表，不因猜测内部实现添加其他类别。这是本轮有针对性人工核查，不是全 SDK／二进制 API 合规扫描。

dance_native 为静态链接组件，其代码进入 Runner；故在 **Runner 根 manifest 与 dance_native_privacy.bundle 的组件 manifest** 均保留 FileTimestamp/C617.1。不能只靠 Flutter／TensorFlowLite／shared_preferences 等第三方声明替代自有声明。不改变 `NSPrivacyTracking=false`、空 tracking domains 和空 collected data；离线媒体处理不因此新增云端上传。

## 实际产物与门禁

- 原有完整 Phase6、Debug main.dart iPhoneOS build 保留，先保存原 Debug zip；再运行 `flutter build ipa --release --no-codesign --no-pub --target lib/main.dart`，产生真正 `build/ios/archive/Runner.xcarchive`，不是把 Debug.app 的 zip 改名。
- Flutter 3.44.2 在 no-codesign 模式跳过 IPA 导出。构建产物只能作为 **unsigned Release archive 材料**；没有签名、设备安装、TestFlight／App Store validation 结论。
- `tools/ci/check_ios_privacy_archive.py` 从 archive Info.plist 的受限 ApplicationPath 定位 app，检查 Woah bundle ID、iPhoneOS 平台和 Mach-O 文件头；逐一检查两个固定自有 manifest、已知键／精确原因、与源码 plist 语义一致；盘点所有第三方 manifest 的相对路径、SHA 和声明，不重写第三方内容。
- 文件头与 plist 检查只证明本项目材料约束，不能独立证明完整二进制有效、Release 编译参数、代码签名或 Apple 审核接受。Release 来源由实际构建命令／日志、commit 及 CI 共同关联。
- 13 项小型合成 fixture 测试覆盖缺 root/native、第三方不能替代、错误原因／未知键、坏 plist、缺归档 metadata／可执行文件、越界 ApplicationPath、错误 app／simulator、CI 实际归档接线。fixture 中的几字节文件仅测试材料分支，绝不称真实构建。
- 所有 fixture／生成测试使用临时小目录；本地真实 archive 和报告在完成记录记录路径／摘要，远端上传 archive zip、原 Debug zip、JSON 报告，保持原 14 天 artifact retention。实际 exact-head 与 merge CI 完成后追加 PR 记录，不为了记录最终 SHA 再开启第五 PR。

## 本地验收与磁盘

开工18GiB空闲；复用现有 Flutter3.44.2/Dart3.12.2、CocoaPods1.17.0、Xcode27.2。`resolve_ios_dependencies.py` deployment 通过，未更改依赖锁。本地生产入口 unsigned Release archive 已成功（171.7s，exit 0，约197MiB，Flutter明确跳过IPA），两个自有manifest与源码一致，全部10份manifest完成盘点；[实际JSON报告](g3-d-local-archive-report-2026-10-31.json)。可执行文件SHA-256为 `b2ac47f768301c8035e66e9f5613a7cd74c67ee717e4abdc0039d3aa2a455ea4`。报告基于上述基线加本PR工作树，明确 `working_tree_dirty=true`，不冒充最终提交产物；最终精确提交由CI重新构建并上传报告。57项CI Python回归（含13新增）、phase0–6/cloud静态检查、九锁无漂移均通过。

构建进程退出后，经DerivedData/info.plist确认WorkspacePath属于Woah，仅清本次 `ArchiveIntermediates/Runner`（417MiB）及G3创建的四个Flutter build hash目录（约269MiB）；不触碰观察到的其他工作区构建。全盘空闲当时约14GiB，受其他活动影响，不声称恢复到开工值。清理后真实archive材料复验和可执行文件SHA不变。最终 `mobile/app/build/ios/archive/Runner.xcarchive`、app/APK、报告、模型及共享SDK/pub/Gradle/Pods缓存保留。没有真实媒体导入／上传，也不删除历史诊断资料。

## 官方依据与发布边界

- [Apple API 类别及原因](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)
- [各 app/SDK 自身声明责任](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)
- [manifest 包装位置及静态组件资源](https://developer.apple.com/documentation/bundleresources/adding-a-privacy-manifest-to-your-app-or-third-party-sdk)
- [Flutter iOS archive](https://docs.flutter.dev/deployment/ios)

用户当前没有真实 iOS／Xcode 团队签名环境；该事实不阻止 unsigned archive 核验，但正式签名、分发验证与真实设备验收仍未执行。Android GPU/MediaStore 真机缺口、iOS17 既有 shader 限制、debug raw 质量媒体非匿名、服务器保护 G1-EX-01 延期均原样保留。本轮四 PR 后停止实施，不用这些外部缺口继续膨胀阶段。
