import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import 'export_controller.dart';
import '../domain/export_state.dart';

class ExportScreen extends ConsumerStatefulWidget {
  final DanceProject project;

  const ExportScreen({
    super.key,
    required this.project,
  });

  @override
  ConsumerState<ExportScreen> createState() => _ExportScreenState();
}

class _ExportScreenState extends ConsumerState<ExportScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _startExportJob();
    });
  }

  void _startExportJob() {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final outPath = 'export_$timestamp.mp4';
    ref
        .read(exportControllerProvider.notifier)
        .startExport(widget.project, outPath);
  }

  void _copyErrorLog(BuildContext context, String? errorMessage) {
    final text = errorMessage ?? '未知错误';
    Clipboard.setData(ClipboardData(text: text));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        backgroundColor: Colors.green,
        duration: Duration(seconds: 3),
        content: Text('📋 错误日志已复制到剪贴板，可直接粘贴反馈！'),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(exportControllerProvider);
    final controller = ref.read(exportControllerProvider.notifier);

    // Auto navigate on completion
    ref.listen<ExportState>(exportControllerProvider, (previous, next) {
      if (next.isCompleted && next.outputUri != null && next.outputUri!.endsWith('.mp4')) {
        context.pushReplacement('/result', extra: next);
      }
    });

    final percent = (state.progress * 100).toStringAsFixed(1);
    final isFailed = state.isFailed;
    final hasPreviewImage = state.outputUri != null &&
        (state.outputUri!.endsWith('.jpg') || state.outputUri!.endsWith('.jpeg')) &&
        File(state.outputUri!).existsSync();

    return Scaffold(
      appBar: AppBar(
        title: const Text('视频渲染导出'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => _confirmCancel(context, controller),
        ),
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              // Real-Time Live Rendered Frame Preview or Circular Ring
              if (hasPreviewImage && !isFailed) ...[
                Container(
                  width: double.infinity,
                  height: 260,
                  decoration: BoxDecoration(
                    color: Colors.black54,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: Colors.deepPurpleAccent.withAlpha(120), width: 1.5),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.deepPurpleAccent.withAlpha(40),
                        blurRadius: 16,
                        spreadRadius: 2,
                      ),
                    ],
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      Image.file(
                        File(state.outputUri!),
                        key: ValueKey(state.currentFrame ~/ 5),
                        gaplessPlayback: true,
                        fit: BoxFit.contain,
                        width: double.infinity,
                        height: double.infinity,
                      ),
                      Positioned(
                        top: 10,
                        left: 10,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black.withAlpha(180),
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(color: Colors.white24),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Container(
                                width: 8,
                                height: 8,
                                decoration: const BoxDecoration(
                                  color: Colors.redAccent,
                                  shape: BoxShape.circle,
                                ),
                              ),
                              const SizedBox(width: 6),
                              const Text(
                                '实时渲染监看',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: Colors.white,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Positioned(
                        bottom: 10,
                        right: 10,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black.withAlpha(180),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Text(
                            '${state.fps.toStringAsFixed(1)} FPS',
                            style: const TextStyle(
                              fontSize: 11,
                              color: Colors.white70,
                              fontWeight: FontWeight.bold,
                              fontFeatures: [FontFeature.tabularFigures()],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),
                // Linear Progress indicator below preview
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: LinearProgressIndicator(
                    value: state.progress > 0 ? state.progress : null,
                    minHeight: 8,
                    backgroundColor: Colors.white12,
                    valueColor: const AlwaysStoppedAnimation<Color>(Colors.deepPurpleAccent),
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      '$percent%',
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.bold,
                        fontFeatures: [FontFeature.tabularFigures()],
                      ),
                    ),
                    Text(
                      '已处理 ${state.currentFrame} / ${state.totalFrames} 帧',
                      style: const TextStyle(
                        color: Colors.white60,
                        fontSize: 13,
                        fontFeatures: [FontFeature.tabularFigures()],
                      ),
                    ),
                  ],
                ),
              ] else ...[
                // Circular Progress Ring when preview is preparing or failed
                Stack(
                  alignment: Alignment.center,
                  children: [
                    SizedBox(
                      width: 220,
                      height: 220,
                      child: CircularProgressIndicator(
                        value: isFailed ? 1.0 : (state.progress > 0 ? state.progress : null),
                        strokeWidth: 10,
                        backgroundColor: Colors.white12,
                        valueColor: AlwaysStoppedAnimation<Color>(
                          isFailed ? Colors.redAccent : Colors.deepPurpleAccent,
                        ),
                      ),
                    ),
                    SizedBox(
                      width: 180,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          if (isFailed) ...[
                            const Icon(Icons.error_outline_rounded, color: Colors.redAccent, size: 48),
                            const SizedBox(height: 8),
                            const Text(
                              '导出失败',
                              style: TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                                color: Colors.redAccent,
                              ),
                            ),
                          ] else ...[
                            Text(
                              '$percent%',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 38,
                                fontWeight: FontWeight.bold,
                                fontFeatures: [FontFeature.tabularFigures()],
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              '${state.fps.toStringAsFixed(1)} FPS',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 14,
                                color: Colors.white60,
                                fontWeight: FontWeight.w600,
                                fontFeatures: [FontFeature.tabularFigures()],
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 24),
                Text(
                  '已处理 ${state.currentFrame} / ${state.totalFrames} 帧',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Colors.white60,
                    fontSize: 13,
                    fontFeatures: [FontFeature.tabularFigures()],
                  ),
                ),
              ],

              const SizedBox(height: 24),

              // Status Description & Action
              SizedBox(
                height: 80,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Text(
                      isFailed ? '渲染遇到异常，请重试或复制日志反馈' : _getStatusTitle(state.status),
                      textAlign: TextAlign.center,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                            color: isFailed ? Colors.redAccent : Colors.white,
                          ),
                    ),
                    const SizedBox(height: 10),
                    if (isFailed)
                      OutlinedButton.icon(
                        onPressed: () => _copyErrorLog(context, state.errorMessage),
                        icon: const Icon(Icons.copy_rounded, size: 16),
                        label: const Text(
                          '复制错误信息',
                          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                        ),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: Colors.white,
                          side: const BorderSide(color: Colors.white30),
                          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(20),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          child: isFailed
              ? Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: () => Navigator.of(context).pop(),
                        icon: const Icon(Icons.arrow_back),
                        label: const Text('返回调节'),
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: ElevatedButton.icon(
                        onPressed: _startExportJob,
                        icon: const Icon(Icons.refresh_rounded),
                        label: const Text('重试导出'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.deepPurpleAccent,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                        ),
                      ),
                    ),
                  ],
                )
              : (state.isProcessing
                  ? OutlinedButton.icon(
                      onPressed: () => _confirmCancel(context, controller),
                      icon: const Icon(Icons.cancel_outlined, color: Colors.redAccent),
                      label: const Text(
                        '取消导出',
                        style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold),
                      ),
                      style: OutlinedButton.styleFrom(
                        side: BorderSide(color: Colors.redAccent.withAlpha(100)),
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                      ),
                    )
                  : const SizedBox.shrink()),
        ),
      ),
    );
  }

  String _getStatusTitle(ExportJobState status) {
    switch (status) {
      case ExportJobState.preparing:
        return '正在初始化硬件编解码器与 OpenGL 环境...';
      case ExportJobState.processing:
        return '正在进行逐帧 AI 分割与特效渲染...';
      case ExportJobState.muxing:
        return '正在混合原音轨与封装 MP4...';
      case ExportJobState.completed:
        return '导出完成！';
      case ExportJobState.cancelled:
        return '已取消导出';
      case ExportJobState.failed:
        return '导出失败';
      default:
        return '处理中...';
    }
  }

  void _confirmCancel(BuildContext context, ExportController controller) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认取消导出？'),
        content: const Text('正在进行中的视频渲染进度将丢失，并释放硬件编码器。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('继续导出'),
          ),
          TextButton(
            onPressed: () {
              Navigator.of(ctx).pop();
              controller.cancelExport();
              Navigator.of(context).pop();
            },
            child: const Text('确认取消', style: TextStyle(color: Colors.redAccent)),
          ),
        ],
      ),
    );
  }
}
