import 'dart:ui';
import 'package:flutter/material.dart';
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
      final timestamp = DateTime.now().millisecondsSinceEpoch;
      final outPath = 'export_$timestamp.mp4';
      ref
          .read(exportControllerProvider.notifier)
          .startExport(widget.project, outPath);
    });
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
                      value: state.progress > 0 ? state.progress : null,
                      strokeWidth: 10,
                      backgroundColor: Colors.white12,
                      valueColor: const AlwaysStoppedAnimation<Color>(
                        Colors.deepPurpleAccent,
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
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 48),

              // Fixed height and strictly centered Status Description
              SizedBox(
                height: 72,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Text(
                      _getStatusTitle(state.status),
                      textAlign: TextAlign.center,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    const SizedBox(height: 8),
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
      bottomNavigationBar: state.isProcessing
          ? SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                child: OutlinedButton.icon(
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
                ),
              ),
            )
          : null,
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
