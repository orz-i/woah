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
      if (next.isCompleted && next.outputUri != null) {
        context.pushReplacement('/result', extra: next);
      }
    });

    final percent = (state.progress * 100).toStringAsFixed(1);
    final isFailed = state.isFailed;

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
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.symmetric(horizontal: 24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              // Perfectly Centered Circular Progress Ring
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
              const SizedBox(height: 48),

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
                      )
                    else
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
