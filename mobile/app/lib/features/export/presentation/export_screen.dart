import 'dart:io';

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/export_state.dart';
import 'export_controller.dart';

class ExportArgs {
  final DanceProject project;
  final String processingProfile;

  const ExportArgs({required this.project, this.processingProfile = 'quality'});
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
    WidgetsBinding.instance.addPostFrameCallback((_) => _startExportJob());
  }

  void _startExportJob() {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    ref
        .read(exportControllerProvider.notifier)
        .startExport(
          widget.project,
          'export_$timestamp.mp4',
          processingProfile: widget.processingProfile,
        );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(exportControllerProvider);
    final controller = ref.read(exportControllerProvider.notifier);

    ref.listen<ExportState>(exportControllerProvider, (previous, next) {
      if (next.isCompleted &&
          next.outputUri != null &&
          next.outputUri!.endsWith('.mp4')) {
        HapticFeedback.heavyImpact();
        context.pushReplacement('/result', extra: next);
      }
    });

    final isFailed = state.isFailed;
    final isActive =
        state.status == ExportJobState.queued || state.isProcessing;

    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        title: Text(isFailed ? '导出失败' : '导出中'),
        leading: IconButton(
          tooltip: '取消导出',
          icon: const Icon(Icons.close_rounded),
          onPressed: isActive
              ? () => _confirmCancel(controller)
              : () => context.pop(),
        ),
        actions: [
          if (isFailed)
            PopupMenuButton<String>(
              tooltip: '更多',
              icon: const Icon(Icons.more_horiz_rounded),
              color: AppTheme.surfaceElevated,
              onSelected: (value) {
                if (value == 'copy') {
                  _copyError(state.errorMessage);
                } else if (value == 'diagnostics') {
                  _exportDiagnostics();
                }
              },
              itemBuilder: (context) => const [
                PopupMenuItem(
                  value: 'copy',
                  child: ListTile(
                    dense: true,
                    leading: Icon(Icons.copy_rounded),
                    title: Text('复制错误详情'),
                  ),
                ),
                PopupMenuItem(
                  value: 'diagnostics',
                  child: ListTile(
                    dense: true,
                    leading: Icon(Icons.bug_report_outlined),
                    title: Text('导出诊断包'),
                  ),
                ),
              ],
            ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 132),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: MediaQuery.sizeOf(context).height - 230,
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                _buildProgressVisual(state),
                const SizedBox(height: 26),
                Text(
                  isFailed ? '处理没有完成' : _statusTitle(state.status),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: AppTheme.textPrimary,
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  isFailed
                      ? '可以重试导出；技术详情已收进右上角菜单。'
                      : _remainingTimeLabel(state),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: AppTheme.textMuted,
                    fontSize: 13,
                  ),
                ),
                if (!isFailed) ...[
                  const SizedBox(height: 26),
                  _buildBackgroundHint(),
                  const SizedBox(height: 14),
                  _buildLivePreviewSection(state, controller),
                ],
              ],
            ),
          ),
        ),
      ),
      bottomNavigationBar: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 20),
          child: isFailed
              ? Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: () => context.pop(),
                        child: const Text('返回编辑'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: ElevatedButton.icon(
                        onPressed: _startExportJob,
                        icon: const Icon(Icons.refresh_rounded, size: 18),
                        label: const Text('重试'),
                      ),
                    ),
                  ],
                )
              : OutlinedButton.icon(
                  onPressed: isActive ? () => _confirmCancel(controller) : null,
                  icon: const Icon(Icons.close_rounded, size: 18),
                  label: const Text('取消导出'),
                ),
        ),
      ),
    );
  }

  Widget _buildProgressVisual(ExportState state) {
    final progress = state.progress.clamp(0.0, 1.0);
    final percent = (progress * 100).round();
    final isFailed = state.isFailed;
    return SizedBox(
      width: 190,
      height: 190,
      child: Stack(
        alignment: Alignment.center,
        children: [
          SizedBox.expand(
            child: CircularProgressIndicator(
              value: isFailed ? 1 : (progress > 0 ? progress : null),
              strokeWidth: 8,
              backgroundColor: AppTheme.surfaceHigh,
              valueColor: AlwaysStoppedAnimation(
                isFailed ? AppTheme.error : AppTheme.metalHigh,
              ),
            ),
          ),
          if (isFailed)
            const Icon(
              Icons.error_outline_rounded,
              size: 52,
              color: AppTheme.error,
            )
          else
            Text(
              '$percent%',
              style: const TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 40,
                fontWeight: FontWeight.w700,
                letterSpacing: -1.5,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildBackgroundHint() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: AppTheme.panelDecoration(radius: 14),
      child: const Row(
        children: [
          Icon(
            Icons.phone_android_rounded,
            size: 21,
            color: AppTheme.metalHigh,
          ),
          SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '可以切换到其他应用',
                  style: TextStyle(
                    color: AppTheme.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                SizedBox(height: 3),
                Text(
                  '处理会在后台继续运行',
                  style: TextStyle(color: AppTheme.textMuted, fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLivePreviewSection(
    ExportState state,
    ExportController controller,
  ) {
    return Container(
      width: double.infinity,
      decoration: AppTheme.panelDecoration(radius: 14),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          SwitchListTile(
            value: state.showLivePreview,
            onChanged: (value) {
              HapticFeedback.lightImpact();
              controller.toggleLivePreview(value);
            },
            secondary: const Icon(
              Icons.visibility_outlined,
              color: AppTheme.metalMid,
              size: 21,
            ),
            title: const Text(
              '实时预览',
              style: TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
            subtitle: const Text(
              '需要时查看处理画面，默认关闭以减少额外开销',
              style: TextStyle(color: AppTheme.textMuted, fontSize: 12),
            ),
          ),
          if (state.showLivePreview) ...[
            const Divider(height: 1),
            AspectRatio(
              aspectRatio: widget.project.videoInfo.aspectRatio > 0
                  ? widget.project.videoInfo.aspectRatio
                  : 16 / 9,
              child: Container(
                color: Colors.black,
                child:
                    state.currentPreviewPath != null &&
                        File(state.currentPreviewPath!).existsSync()
                    ? Image.file(
                        File(state.currentPreviewPath!),
                        key: ValueKey(
                          '${state.currentPreviewPath}_${state.currentFrame}',
                        ),
                        fit: BoxFit.contain,
                        gaplessPlayback: true,
                      )
                    : const Center(
                        child: SizedBox(
                          width: 24,
                          height: 24,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _statusTitle(ExportJobState status) {
    return switch (status) {
      ExportJobState.queued => '正在准备处理',
      ExportJobState.preparing => '正在准备视频',
      ExportJobState.processing => '正在保护人物',
      ExportJobState.muxing => '正在保存视频',
      ExportJobState.completed => '处理完成',
      ExportJobState.cancelled => '已取消',
      ExportJobState.failed => '处理没有完成',
    };
  }

  String _remainingTimeLabel(ExportState state) {
    if (state.status == ExportJobState.muxing) return '正在完成最后一步';
    if (state.totalFrames <= 0 || state.fps <= 0) return '正在计算剩余时间…';

    final remainingFrames = (state.totalFrames - state.currentFrame).clamp(
      0,
      state.totalFrames,
    );
    final seconds = (remainingFrames / state.fps).ceil();
    if (seconds <= 0) return '即将完成';
    if (seconds < 60) return '预计还需约 $seconds 秒';
    final minutes = seconds ~/ 60;
    final remainder = seconds % 60;
    return remainder == 0
        ? '预计还需约 $minutes 分钟'
        : '预计还需约 $minutes 分 $remainder 秒';
  }

  void _copyError(String? errorMessage) {
    Clipboard.setData(ClipboardData(text: errorMessage ?? '未知错误'));
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('错误详情已复制')));
  }

  Future<void> _exportDiagnostics() async {
    try {
      final repo = ref.read(nativeRepositoryProvider);
      final bundle = await repo.createDiagnosticBundle();
      await repo.shareDiagnosticBundle(
        filePath: bundle?['filePath'] as String?,
        publicUri: bundle?['publicUri'] as String?,
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('诊断包导出失败')));
    }
  }

  void _confirmCancel(ExportController controller) {
    HapticFeedback.lightImpact();
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('取消导出？'),
        content: const Text('当前进度将不会保留。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('继续导出'),
          ),
          TextButton(
            onPressed: () {
              Navigator.of(dialogContext).pop();
              controller.cancelExport();
              context.pop();
            },
            child: const Text('取消导出', style: TextStyle(color: AppTheme.error)),
          ),
        ],
      ),
    );
  }
}
