# G2-A：Android preview / export 原生资源交接

日期：2026-10-31 UTC。范围：NATIVE-01，G2 最多四个 PR 中的第一个；基线为 G1 合并提交 `e21c6c7165c969463f23e812f33b35eecf8016af`。不修改 iOS 实现、Pigeon schema、模型、依赖、CI 或仓库保护。

## 行为契约与实现

- `PreviewExportResourceGate` 为 `ExportCoordinator` 持有的进程级 GPU lane 所有权。preview 的整个 suspend render（含预处理、模型输入写入、推理及输出读取）在 coroutine Mutex 内；export 先预留 owner，再等待已有 preview drain，后来的 preview 不会饿死交接。
- gate 登记所有使用过的 preview engine owner；交接会关闭全部已登记 preview 资源，而非只关闭发起 export 的实例。detach 立即拒绝该 owner 新调用，异步 drain / close，不在主线程等待长推理。
- export 同时只接受一个原生 owner；第二个 export 明确失败，不再让多个 worker 不安全地共享一个 segmenter。UI 常规单导出路径不变，不新增排队系统。
- `PreviewAnalysisCache.copyForUse` 在 cache 锁内复制 Bitmap；调用者拥有副本，LRU / clear 只回收原件，消除取裸引用后并发 recycle 的窗口。
- `OwnedExportJobs` 是实际 service 使用的可测生命周期：LAZY 创建 → 先登记 → start → body 真正退出 → model cleanup → completion。即使取消使 body 从未进入，也完成一次空资源 cleanup；取消通知或 stop deadline 不是资源已释放的证据。
- `ExportForegroundService` 的 segmenter 归该 worker 所有，onDestroy 仅取消本实例的 jobs，不提前 close，也不取消另一实例 / 尚未派发 Intent 的新 reservation。快速完成、重复 Intent、progress 更新 startId、旧超时与新任务的竞争均有回归。
- 观察到 native cleanup 抛错时 fail closed：preview cleanup poison 或 export reservation 阻止再次使用该 lane，需重启进程恢复。等待 drain 时被取消则撤回自己的 reservation。此处不承诺底层运行库吞掉的错误均可被外层侦测。
- 当前 `releaseProject` 与 preview 资源修改序列化；export 占用期间明确拒绝删除。G2-C 将按真实 lease 实现延期释放，本 PR 不提前实施缓存策略。

## 确定性回归

新增 15 项：

1. gate 6 项：暂停 preview 后 export drain / 排队 preview 拒绝；取消状态不释放 owner；等待 drain 取消回滚；多 engine 清理与失败 poison；detach 等待真实退出；export 期间禁止缓存修改。
2. service registry 7 项：快速完成无幽灵 map 条目；父 scope 先取消；非协作 native 阻塞期间取消不提前 close；close 失败不放开 lane；旧 service 不取消新 reservation；重复 start 不替换 worker；duplicate / progress 最新 startId 与旧 timeout 不误停新 worker。
3. Bitmap 2 项：副本在 eviction 后仍归调用者；暂停复制时 clear 不能提前 recycle。

使用 latch / deferred 固定竞争窗口，没有依赖随机 sleep 的通过条件。

## 本地验证

环境：Mac mini，Flutter 3.44.2 / Dart 3.12.2，Temurin JDK 17.0.21，Android SDK 36。最终实现的日志前缀为 `/tmp/woah-g2-a/`（本机临时证据，不是用户下载链接）。

- 手写 Dart format：通过；四包 analyze：通过。
- Flutter：domain 27、UI 1、native DTO 5、app 256，共 289 tests 通过。
- `:dance_native:testDebugUnitTest`：116 suites / 505 tests，0 failure / error / skipped（原 490 + 新 15）。
- `:dance_native:compileReleaseKotlin`：通过。
- `:dance_native:lintDebug :app:lintDebug`：0 error，native 70 / app 19 warnings，与原台账一致；未新增 suppression。
- Python CI contracts：14 tests；shared export + iOS phase4 contracts：4 + 6 tests，均通过。system Python 起初缺 PyYAML；改用现有 Python 3.12 与隔离 `uv run --no-project --with PyYAML==6.0.3`（沿用仓库锁定版本）验证，未在 Mac 同步根 CUDA 环境。
- iOS 静态 phase0/1/3/4/5/6 与 cloud verifier：通过；完整 iOS gate 仍由最终 head 的远端 CI 验收。
- Debug APK：构建成功（exit 0），241352342 bytes，SHA-256 `00f9eb6594745d2b0b0f63aebe0089af578ed94a2030a93617a876ece07ce1db`。未安装到用户设备。

最终 PR / merge CI 证据追加在该 PR 正文，避免仅为状态记录额外提交并重启 CI；未运行的检查不记为通过。

## 保留的验收边界

2026-10-31 06:16 UTC `adb devices` 无已连接设备。本 PR 的 JVM 并发 / 故障注入与构建成功不代替 Android GPU / MediaStore 真机验收。没有安装 / 操作用户媒体或重置其他项目模拟器。NATIVE-01 软件切片可交付，设备快速切换 / 导出 / 取消仍待既定 Android 硬件验证；不宣称整个 G2 已完成。

仓库保护依 G1-EX-01 本轮延期；89 条历史 warnings、不返回 job ID 的外部 RPC、不可观测底层清理故障并非本切片扩展目标。
