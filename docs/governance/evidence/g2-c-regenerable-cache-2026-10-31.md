# G2-C / PRIV-01：可再生缓存生命周期

日期：2026-10-31。基线为 PR11 合并提交 `694dcc8835084211a5f6ac7f8677137a1eccea86`，其 head／merge CI 已成功。本证据只覆盖冻结 G2 的第三个切片，不新增阶段、权限、遥测或持久数据删除功能。

## 冻结的保守策略

- 维护者授权按既定目标自主推进；24 小时孤儿 TTL、256 MiB **非活动**软预算由本次工程实现选择，不声称用户逐项指定这些数值，也不是产品持久保留承诺。
- 只拥有应用生成、可重新分析／生成的 analysis metadata/person thumbnails、精确关联 preview JPEG、trim JPEG。只按完整生产命名格式识别；未知文件／子目录／符号链接保守隔离。原始 `sourceUri` 只作来源元数据，绝不加入删除集合。
- **最终导出实际位于 cache/exports，仍永久排除于本清理器。** 同样排除 export_live_preview、原媒体、用户贴纸、profile、comparison 持久项目、模型、诊断和导出历史。没有“清空 cacheDir”或递归跨目录删除。
- 本次读码确认保护流程没有 `DanceProject.fromJson` 恢复调用、持久分析项目仓库或自动恢复导出 worker；SharedPreferences 只保存保护偏好，Android 历史任务在既有启动协调中标 interrupted。不改变这些语义。
- 正常 session release 在真实 operation lease 归零后及时清理；TTL 不能代替 release。启动与有限频率维护只扫描无 owner／无 lease 的 orphan。活动／retained 内容不强删，可超过软预算；预算不是整个应用缓存的硬上限。
- trim API 当前没有应用 UI 调用者。返回文件由 engine owner 保留至 detach，生成过程另持 lease；不新增未使用 UI／释放 API，不宣称存活 engine 的返回 trim 会被当作 inactive 清理。
- 删除失败／未知文件保留并在维护时重试；失败的 Flutter release 不标为完成，后续 close 可重试，native detach／重启孤儿处理是独立兜底。没有声称所有错误下已物理清空。

## 所有权与真实完成

1. Dart `AnalysisSession` 由单个 editor State 明确持有。editor 留在 export/result 下方，返回编辑、取消、失败重试不释放；最终回首页／放弃／成功替换仅关闭该 session 记录的 IDs。并不从共享 controller 当前 state 猜归属。
2. prepare/analyze 使用 generation、mounted 与 captured session 检查。晚成功只释放自己的结果，晚失败不覆盖新 state；editor await 后续操作也检查 accepted。原生 schema 不变。
3. 同一路由 `pushReplacement` 回归最初证实 GoRouter 可复用 editor State；生产路由补 `ValueKey(project.id)`，新视频获得新 State/session，而非增加导航架构。
4. Android `RegenerableCacheRegistry` 由进程级 ExportCoordinator 持有；既有 startup interruption 协调后才维护。analysis 写入前、preview 整个 render、export dispatch 前均有真实 lease；只在 OwnedExportJobs 的实际 completion/cleanup 路径释放导出 lease。cancelled 通知不释放。保留空 analysisCacheId 的既有导出路径。
5. iOS `IOSRegenerableCacheRegistry.shared` 跨 engine 共享，分析／预览最外层 defer、导出 Task 最外层 defer（先于 weak-self guard）持 lease；detach 只撤相应 owner，不销毁进程登记器或误伤其他 engine。
6. lease 归零后在清理队列、锁外通知弱 preview cleanup listener，再删除白名单文件；覆盖内存中的未打码预览，不跨 await 持锁。释放中的 entry 防止新借用，成功删除后移除 entry；absent acquire 同样拒绝。Owner 自身记 closed，避免永久 tombstone／closedOwners 账本增长。
7. Android CacheManager 的读取不再 mkdir；两端缓存路径、叶文件和 preview 完整名称匹配加强，不跟随 symlink。Android UUID 避免同毫秒分析碰撞。

## 确定性回归与本地证据

- Android：119 suites／541 tests 全通过，新增 11 项 registry 回归；其中实际 `OwnedExportJobs` 阻塞 worker 在 cancellation 后仍保留文件，真实 completion 才释放。100 次合成创建／放弃后白名单文件和登记项回到基线；未知文件、链接、排除目录／源文件 sentinel 保留；失败清理可重试。
- Android Release Kotlin、native/app Debug lint 通过：既有 70／19 warnings，0 errors；没有刷新 baseline 或全清警告。
- Flutter：新增 8 项纯 session/controller 回归与 2 项路由回归；包括乱序成功／失败、dispose 后回包、已有 cache 复用、同路径替换、export/result 往返及最终 home。现有真实取消回编辑回归新增未释放断言。focused 44 tests 通过；四包最终 analyze／format 与 299 tests（27／1／5／266）全部通过。
- Swift registry／100-cycle smoke 可注入独立临时根与时钟，已在 Mac Foundation 测试通过；这不冒充 iOS pipeline 验证。
- 20 项 CI Python 测试（新增 3 个接线守卫）与原有 10 项契约测试通过；iOS phase0／1／3／4／5／6／cloud 静态检查通过。Phase3 静态 verifier 改为验证 deferred cleanup callback 与零 lease 条件；Phase4 保留既有取消／成片断言并加强 cache lease 条件；Phase6 runner 强制要求新 cache smoke marker，未降低门槛。
- Debug APK 构建通过：273585473 bytes，SHA-256 `158430c217b2f1e7a6859b79e6d8376d4979ceb5e479f2f1b30f3abac4caa0bd`。
- 本地完整 Simulator build 通过（Xcode 27.2，显式 `DEVELOPER_DIR`，不改变全局 Xcode 选择）；Metal 两 SDK offline compile 通过。
- 本地日志：`/tmp/woah-g2-c/` 的 `native-fourth.log`、`flutter-focused-key.log`、`swift-smoke-second.log`、`ios-build-second.log`、`metal.log`、`shared-complete.log`；精确提交／远端 CI 链接在 PR 正文追加，不制造状态专用代码提交。

## 固定矩阵与实际限制

| 验证环境 | 结果／边界 |
| --- | --- |
| Android JVM＋synthetic temp roots | 541 测试通过；不代表真机 GPU／磁盘／进程强杀验收 |
| Android 真实设备 | 2026-10-31 06:16 UTC 证据无连接；本切片未操作真实设备／用户媒体，保留验收缺口 |
| Mac Foundation synthetic registry | 100 cycles 通过；非 iOS runtime 替代品 |
| 自建 iPhone 15 Pro／iOS 17.0.1 Simulator | Phase3 既有 Metal 入口失败：runtime 只支持 14 constant-buffer bindings，而原 shader 使用 17；尚未执行新 cache smoke，不记通过，不扩展修复 shader |
| 自建 iPhone 17／iOS 27 Simulator | 完整 Phase3／4／5／6 与 cache smoke 全通过；Phase4 真实 inference 阻塞→cancel/release→metadata 保留→worker drain→metadata 删除，报告 `cancel_cache_lease_drained=true`；cache 100 cycles PASS |
| 远端 Xcode 26 lane | 必须在最终 exact head 与 merge SHA 执行完整 Phase6＋production no-codesign build；本地 Xcode27 不替代该门禁 |
| iOS 真机／团队签名 | 无环境，未执行；不宣称商店／签名发布就绪 |

本地两次运行只新建并使用 `Woah-G2-C-Synthetic-20261031` 与 `Woah-G2-C-Synthetic27-20261031`，未使用／关闭既有模拟器。自建实例测试后 shutdown/delete；fixtures 均为新建合成内容，没有执行真实用户缓存清理测试。iOS27 完整通过日志为 `owned-simulator27-corrected.log`（第一次27启动 harness 曾在更新参数前读取旧已删除 UDID，未触及新设备；核验参数后正确执行）。

## 审查与关闭条件

Android、Swift、Dart 三路独立只读审查无剩余阻断；其中空 ID 兼容、内存预览延期清理、释放账本增长与同路由 State 复用均在原切片内修正并回归。仍须最终提交 exact-head 双平台三检查成功、正常 PR 合并、merge SHA 复验。硬件缺口与 iOS17 runtime 限制如实保留，不新增整改阶段填补。
