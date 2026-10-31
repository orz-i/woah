# G2-B：Android 相册保存事务

日期：2026-10-31 UTC。范围 NATIVE-02，G2 四个有限 PR 中的第二个。基线 `0a30e589dde0dfbade35f205bf5e7b4649adecec`；前一 G2-A [PR10](https://github.com/orz-i/woah/pull/10) 的 PR head 与 merge [run 41181276227](https://github.com/orz-i/woah/actions/runs/41181276227) 三检查均成功。没有更改权限、minSdk、Pigeon schema、依赖、iOS 保存路径或仓库保护。

## 已实现的边界

- MethodChannel 的 save handler 只提交到该 plugin attachment 拥有的单线程 `GallerySaveWorker`。文件读取、MediaStore insert、copy、close、publish 与 rollback 均在 worker；回包使用创建时捕获的主线程 dispatcher，不读取 detach 后置空的 handler。
- `GallerySaveTransaction` 只持有本次 insert 返回的目标 URI。源文件只读，既有相册内容不进入删除接口。已移除对 app 源文件的 MediaScanner 调用，避免另行扫描原路径。
- 输入 / 输出 close 与 flush 成功且实际复制字节数大于零后，才提交 pending 状态。API 29+ `IS_PENDING=1 → 0`；publish update 不等于 1 视作失败。空源不发布为空媒体。
- detach 与 commit 通过 CAS 线性化。commit 尚未开始的操作取消并回滚；commit 已开始则完成保存，不因 UI / engine 随后消失删除已发布资产。detach 不等待 provider I/O 锁。
- 操作先登记再 execute，submit / shutdown 竞争、排队取消和重复 close 均恰好产生一次回包尝试。shutdown 不丢弃队列；旧 attachment 只取消自己的操作。
- callback 失败在事务之外，不触发已提交资产回滚。engine 已销毁时不保证 Dart 能收到回包，不能把一次发送尝试当确认送达。
- rollback 只针对本次新 URI；删除未确认或 provider 抛错时返回独立 `SAVE_ROLLBACK_FAILED`，保留内部原始与回滚原因，不对外输出路径 / URI / exception 文本，也不伪称已清理。

## 验证

本机日志：`/tmp/woah-g2-b/`，仅为本机复核位置。

- 新增 25 JVM 用例：17 个事务测试、8 个 worker 测试。覆盖 source open、insert / 权限、output open、read、磁盘满 write、flush、双流 close、零字节、publish、rollback 自身失败；取消发生在 insert 后 / copy 后 / commit 后；排队 detach、submit 拒绝、旧 engine 隔离、慢 I/O 与回包线程、回包抛错；合成临时源文件字节不变。
- 最终 118 suites / 530 native tests，0 failure / error / skipped（G2-A 505 + 25）。
- Native Release Kotlin、app/native Debug lint：通过，native 70 / app 19 原有 warnings 保留，0 error；本切片新出现的三条 KTX 建议已按现有依赖修正，无新增 suppression / 依赖。
- 四包 Flutter tests：27 / 1 / 5 / 256，共 289；四包 analyze、手写 format 均通过。
- CI Python：17 tests（原 14 + 3 个 bridge / adapter wiring 守卫）；shared export / iOS phase4 原契约 10 tests、iOS phase0/1/3/4/5/6 与 cloud 静态验证通过。
- Debug APK：最终版本构建成功（exit 0），241331759 bytes，SHA-256 `45ca88d9f9c2bb5fa91f8b82dcefb381721eeb8e692efb0747c6ace4cf4a8948`；未安装到用户设备。
- 完整 iOS gate、真实 Android 构建与汇总由最终 PR head 的远端 CI 复核；merge SHA 另验，不把 PR 成功等同 merge 成功。

所有故障注入均使用内存假 backend / streams，唯一文件测试由测试自己创建并删除合成临时源。未访问 / 清理用户照片或真实 MediaStore。

## 未覆盖与不可夸大的部分

- 当前无已连接 Android 真机：未实测系统 StrictMode、真实 MediaStore provider、GPU / UI 响应及 API24–28 权限矩阵；JVM 线程与事务测试不是实机验收。
- API24–28 没有 `IS_PENDING`，写入期间媒体条目可能短暂可见；没有为消除该系统差异增加权限或抬高 minSdk。
- 进程强杀不执行 finally；本切片不引入持久事务 journal / 后台调度，因此不承诺强杀零孤儿。provider 拒绝 rollback 也不能宣称已删除，错误会明确暴露。此类硬件 / 外部系统边界集中列入 G2 验收缺口，不能静默关闭。
- 没有媒体格式解码 / 转码重写，仅禁止发布已知为空的拷贝；没有新增取消 API 或改变用户原始媒体生命周期。

## 官方依据

- [Android 共享媒体存储](https://developer.android.com/training/data-storage/shared/media)：app 自有媒体、pending 发布与按目标 URI 删除；不据此获得删除其他媒体的权限。
- [Flutter MethodChannel.Result](https://api.flutter.dev/javadoc/io/flutter/plugin/common/MethodChannel.Result.html)：Result 可在任意线程调用；本项目选择主线程回传是既定契约，不误称 Flutter 强制要求所有 Result 在主线程执行。

最终远端 run 和 merge 证据记入 PR 正文，不为状态同步反复追加代码提交。
