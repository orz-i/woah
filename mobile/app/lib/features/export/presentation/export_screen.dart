import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../repositories/native_processing_repository.dart';
import 'export_controller.dart';
import '../domain/export_state.dart';


class ExportArgs {
  final DanceProject project;
  final String processingProfile;

  const ExportArgs({
    required this.project,
    this.processingProfile = 'quality',
  });
}

class ExportScreen extends ConsumerStatefulWidget {
  final DanceProject project;
  final String processingProfile;

  const ExportScreen({
    super.key,
    required this.project,
    this.processingProfile = 'quality',
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
        .startExport(widget.project, outPath, processingProfile: widget.processingProfile);
  }


  void _copyErrorLog(BuildContext context, String? errorMessage) {
    HapticFeedback.mediumImpact();
    final text = errorMessage ?? '未知错误';
    Clipboard.setData(ClipboardData(text: text));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        backgroundColor: Color(0xFF22C55E),
        duration: Duration(seconds: 3),
        content: Text('📋 错误日志已复制到剪贴板，可直接粘贴反馈！', style: TextStyle(color: Colors.white)),
      ),
    );
  }

  Future<void> _exportDiagnostics(BuildContext context) async {
    try {
      final repo = ref.read(nativeRepositoryProvider);
      final bundle = await repo.createDiagnosticBundle();
      final fileName = bundle?['fileName'] as String? ?? 'diagnostic_bundle.zip';
      final filePath = bundle?['filePath'] as String?;
      final publicUri = bundle?['publicUri'] as String?;

      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('诊断包已生成: $fileName'),
            backgroundColor: const Color(0xFF10B981),
            behavior: SnackBarBehavior.floating,
          ),
        );
      }

      await repo.shareDiagnosticBundle(
        filePath: filePath,
        publicUri: publicUri,
      );
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('导出诊断包失败: $e'),
            backgroundColor: Colors.redAccent,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(exportControllerProvider);
    final controller = ref.read(exportControllerProvider.notifier);

    // Auto navigate on completion
    ref.listen<ExportState>(exportControllerProvider, (previous, next) {
      if (next.isCompleted && next.outputUri != null && next.outputUri!.endsWith('.mp4')) {
        HapticFeedback.heavyImpact();
        context.pushReplacement('/result', extra: next);
      }
    });

    final percent = (state.progress * 100).toStringAsFixed(1);
    final isFailed = state.isFailed;

    return Scaffold(
      appBar: AppBar(
        title: const Text('正在导出视频'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => _confirmCancel(context, controller),
        ),
      ),
      body: Center(
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              // Live Preview Toggle Bar
              if (!isFailed && state.isProcessing) ...[
                Container(
                  margin: const EdgeInsets.only(bottom: 20),
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1E1E24),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: Colors.white10),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.remove_red_eye_outlined, size: 20, color: Colors.white70),
                      const SizedBox(width: 10),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              '实时画面预览',
                              style: TextStyle(
                                color: Colors.white,
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            Text(
                              '实时监视打码处理后的视频帧（默认关闭）',
                              style: TextStyle(
                                color: Colors.white38,
                                fontSize: 11,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Switch.adaptive(
                        value: state.showLivePreview,
                        activeThumbColor: Colors.white,
                        activeTrackColor: const Color(0xFF3B82F6),
                        onChanged: (val) {
                          HapticFeedback.lightImpact();
                          controller.toggleLivePreview(val);
                        },
                      ),

                    ],
                  ),
                ),
              ],

              // Central Visual Display: Toggle between Circular Progress & Live Rendered Frame
              AnimatedCrossFade(
                duration: const Duration(milliseconds: 300),
                crossFadeState: (state.showLivePreview && !isFailed)
                    ? CrossFadeState.showSecond
                    : CrossFadeState.showFirst,
                firstChild: Stack(
                  alignment: Alignment.center,
                  children: [
                    Container(
                      width: 220,
                      height: 220,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        boxShadow: [
                          if (!isFailed)
                            BoxShadow(
                              color: Colors.white.withAlpha(15),
                              blurRadius: 30,
                              spreadRadius: 2,
                            ),
                        ],
                      ),
                      child: CircularProgressIndicator(
                        value: isFailed ? 1.0 : (state.progress > 0 ? state.progress : null),
                        strokeWidth: 9,
                        backgroundColor: Colors.white12,
                        valueColor: AlwaysStoppedAnimation<Color>(
                          isFailed ? Colors.redAccent : Colors.white,
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
                                color: Colors.white,
                                letterSpacing: -1,
                                fontFeatures: [FontFeature.tabularFigures()],
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              state.progress >= 1.0 ? '即将完成' : '本地端侧处理中',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 13,
                                color: Colors.white60,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
                secondChild: Container(
                  width: double.infinity,
                  constraints: const BoxConstraints(maxHeight: 280),
                  decoration: BoxDecoration(
                    color: const Color(0xFF141418),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: const Color(0xFF3B82F6).withAlpha(80), width: 1.5),
                    boxShadow: [
                      BoxShadow(
                        color: const Color(0xFF3B82F6).withAlpha(25),
                        blurRadius: 24,
                        spreadRadius: 2,
                      ),
                    ],
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      if (state.currentPreviewPath != null &&
                          File(state.currentPreviewPath!).existsSync())
                        Image.file(
                          File(state.currentPreviewPath!),
                          key: ValueKey('${state.currentPreviewPath}_${state.currentFrame}'),
                          width: double.infinity,
                          height: double.infinity,
                          fit: BoxFit.contain,
                          gaplessPlayback: true,
                        )

                      else
                        Container(
                          color: const Color(0xFF1E1E24),
                          child: const Center(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                SizedBox(
                                  width: 32,
                                  height: 32,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 3,
                                    color: Colors.white70,
                                  ),
                                ),
                                SizedBox(height: 12),
                                Text(
                                  '正在捕获实时处理帧...',
                                  style: TextStyle(color: Colors.white60, fontSize: 13),
                                ),
                              ],
                            ),
                          ),
                        ),
                      // Top Overlay Tag
                      Positioned(
                        top: 12,
                        left: 12,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black.withAlpha(180),
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: Colors.white12),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Container(
                                width: 8,
                                height: 8,
                                decoration: const BoxDecoration(
                                  color: Color(0xFF22C55E),
                                  shape: BoxShape.circle,
                                ),
                              ),
                              const SizedBox(width: 6),
                              const Text(
                                '实时渲染中',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 11,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      // Bottom Overlay Progress
                      Positioned(
                        bottom: 12,
                        right: 12,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black.withAlpha(180),
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: Colors.white12),
                          ),
                          child: Text(
                            '$percent%',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                              fontFeatures: [FontFeature.tabularFigures()],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              const SizedBox(height: 24),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: const Color(0xFF1E1E24),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: Colors.white10),
                    ),
                    child: Text(
                      switch (widget.processingProfile.toLowerCase()) {
                        'sam2' => '⚡ SAM2 时序跟踪',
                        'speed' => '🚀 极速导出',
                        'balanced' => '⚖️ 均衡模式',
                        _ => '🌟 质量优先',
                      },
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                    decoration: BoxDecoration(
                      color: const Color(0xFF1E1E24),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: Colors.white10),
                    ),
                    child: Text(
                      '已处理 ${state.currentFrame} / ${state.totalFrames} 帧',
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        color: Colors.white70,
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        fontFeatures: [FontFeature.tabularFigures()],
                      ),
                    ),
                  ),
                ],
              ),
              if (state.fps > 0) ...[
                const SizedBox(height: 8),
                Text(
                  '当前渲染速度: ${state.fps.toStringAsFixed(1)} FPS',
                  style: const TextStyle(
                    color: Colors.white38,
                    fontSize: 12,
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
                      isFailed ? '导出遇到异常，请重试或复制日志反馈' : _getStatusTitle(state.status),
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
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
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
                              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(20),
                              ),
                            ),
                          ),
                          const SizedBox(width: 10),
                          OutlinedButton.icon(
                            onPressed: () => _exportDiagnostics(context),
                            icon: const Icon(Icons.bug_report_outlined, size: 16, color: Color(0xFF60A5FA)),
                            label: const Text(
                              '导出诊断包',
                              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Color(0xFF60A5FA)),
                            ),
                            style: OutlinedButton.styleFrom(
                              foregroundColor: const Color(0xFF60A5FA),
                              side: const BorderSide(color: Color(0xFF3B82F6)),
                              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(20),
                              ),
                            ),
                          ),
                        ],
                      ),
                  ],
                ),
              ),

              const SizedBox(height: 12),

              // Background Service Hint
              if (!isFailed)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  decoration: BoxDecoration(
                    color: Colors.white.withAlpha(6),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: Colors.white10),
                  ),
                  child: Row(
                    children: const [
                      Icon(Icons.lock_clock_rounded, size: 18, color: Colors.white60),
                      SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          '已开启前台保活服务，锁屏或切换到其他应用仍将继续导出。',
                          style: TextStyle(fontSize: 12, color: Colors.white60),
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
                            borderRadius: BorderRadius.circular(16),
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
                          backgroundColor: Colors.white,
                          foregroundColor: Colors.black,
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(16),
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
                          borderRadius: BorderRadius.circular(16),
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
      case ExportJobState.queued:
        return '正在排队等待处理...';
      case ExportJobState.preparing:
        return '正在准备视频资源...';
      case ExportJobState.processing:
        return '正在智能识别并绘制遮挡...';
      case ExportJobState.muxing:
        return '正在合成音频并完成保存...';
      case ExportJobState.completed:
        return '导出完成！';
      case ExportJobState.cancelled:
        return '已取消导出';
      case ExportJobState.failed:
        return '导出失败';
    }
  }

  void _confirmCancel(BuildContext context, ExportController controller) {
    HapticFeedback.lightImpact();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF18181C),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: const Text('确认取消导出？', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
        content: const Text('当前导出进度将丢失，确认取消吗？', style: TextStyle(color: Colors.white70)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('继续导出', style: TextStyle(color: Colors.white)),
          ),
          TextButton(
            onPressed: () {
              HapticFeedback.mediumImpact();
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
