import 'dart:io';

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/foundation.dart';
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
  final String? initialPreviewPath;

  const ExportArgs({required this.project, this.initialPreviewPath});
}

class ExportScreen extends ConsumerStatefulWidget {
  final DanceProject project;
  final String? initialPreviewPath;

  const ExportScreen({
    super.key,
    required this.project,
    this.initialPreviewPath,
  });

  @override
  ConsumerState<ExportScreen> createState() => _ExportScreenState();
}

class _ExportScreenState extends ConsumerState<ExportScreen> {
  static const _failedRetryKey = ValueKey('export-failed-retry');
  static const _failedBackKey = ValueKey('export-failed-back');
  static const _failedCopyKey = ValueKey('export-failed-copy');
  static const _failedDiagnosticsKey = ValueKey('export-failed-diagnostics');
  static const _cancelActionKey = ValueKey('export-cancel-action');
  static const _cancelDialogKey = ValueKey('export-cancel-confirm-dialog');
  static const _cancelContinueKey = ValueKey('export-cancel-continue');
  static const _cancelConfirmKey = ValueKey('export-cancel-confirm');
  bool _cancelInFlight = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _startExportJob());
  }

  void _startExportJob() {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    ref
        .read(exportControllerProvider.notifier)
        .startExport(widget.project, 'export_$timestamp.mp4');
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

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        statusBarBrightness: Brightness.dark,
        systemNavigationBarColor: AppTheme.flowBackground,
        systemNavigationBarIconBrightness: Brightness.light,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.flowBackground,
        body: SafeArea(
          child: Column(
            children: [
              _buildTopBar(
                state,
                controller,
                isActive: isActive,
                isFailed: isFailed,
              ),
              Expanded(
                child: _buildMediaPreview(
                  state,
                  controller,
                  livePreviewToggleEnabled: isActive,
                ),
              ),
              if (isFailed)
                _buildFailurePanel(state)
              else
                _buildProgressPanel(
                  state,
                  showBackgroundHint: Platform.isAndroid,
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTopBar(
    ExportState state,
    ExportController controller, {
    required bool isActive,
    required bool isFailed,
  }) {
    return SizedBox(
      height: 56,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14),
        child: Row(
          children: [
            if (isFailed)
              Semantics(
                button: true,
                label: '返回编辑',
                child: GestureDetector(
                  key: _failedBackKey,
                  behavior: HitTestBehavior.opaque,
                  onTap: () {
                    HapticFeedback.selectionClick();
                    context.pop();
                  },
                  child: const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 4, vertical: 6),
                    child: Icon(
                      Icons.arrow_back_ios_new_rounded,
                      size: 22,
                      color: AppTheme.textPrimary,
                    ),
                  ),
                ),
              )
            else
              _buildCancelAction(
                controller,
                enabled: isActive && state.jobId != null,
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildCancelAction(
    ExportController controller, {
    required bool enabled,
  }) {
    final actionEnabled = enabled && !_cancelInFlight;
    return Semantics(
      container: true,
      button: true,
      enabled: actionEnabled,
      label: _cancelInFlight ? '正在取消处理' : '取消处理',
      child: ExcludeSemantics(
        child: GestureDetector(
          key: _cancelActionKey,
          behavior: HitTestBehavior.opaque,
          onTap: actionEnabled ? () => _confirmCancel(controller) : null,
          child: AnimatedOpacity(
            duration: const Duration(milliseconds: 120),
            opacity: actionEnabled || _cancelInFlight ? 1 : 0.42,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
              child: _cancelInFlight
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: AppTheme.gold,
                      ),
                    )
                  : const Icon(
                      Icons.close_rounded,
                      size: 24,
                      color: AppTheme.textPrimary,
                    ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMediaPreview(
    ExportState state,
    ExportController controller, {
    required bool livePreviewToggleEnabled,
  }) {
    final livePath = state.showLivePreview ? state.currentPreviewPath : null;
    final hasLivePreview = livePath != null && livePath.isNotEmpty;
    final fallbackPath = widget.initialPreviewPath;
    final hasFallbackPreview = fallbackPath != null && fallbackPath.isNotEmpty;
    final displayPath = hasLivePreview
        ? livePath
        : (hasFallbackPreview ? fallbackPath : null);
    final rawAspect = widget.project.outputAspectRatio > 0
        ? widget.project.outputAspectRatio
        : 16 / 9;
    final aspect = widget.project.follow.enabled
        ? rawAspect
        : rawAspect.clamp(0.55, 2.0).toDouble();

    return LayoutBuilder(
      builder: (context, constraints) {
        final maxWidth = constraints.maxWidth;
        final maxHeight = constraints.maxHeight;
        var mediaWidth = maxWidth;
        var mediaHeight = mediaWidth / aspect;
        if (mediaHeight > maxHeight) {
          mediaHeight = maxHeight;
          mediaWidth = mediaHeight * aspect;
        }

        return Container(
          key: const ValueKey('export-media-stage'),
          color: Colors.black,
          alignment: Alignment.center,
          child: Semantics(
            button: livePreviewToggleEnabled,
            label: state.showLivePreview ? '关闭实时画面' : '开启实时画面',
            child: GestureDetector(
              key: const ValueKey('export-live-preview-toggle'),
              behavior: HitTestBehavior.opaque,
              onTap: livePreviewToggleEnabled
                  ? () {
                      HapticFeedback.selectionClick();
                      controller.toggleLivePreview(!state.showLivePreview);
                    }
                  : null,
              child: SizedBox(
                width: mediaWidth,
                height: mediaHeight,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (displayPath != null)
                      Image.file(
                        File(displayPath),
                        fit: BoxFit.contain,
                        gaplessPlayback: true,
                        filterQuality: FilterQuality.low,
                        errorBuilder: (context, error, stackTrace) =>
                            _buildPreviewPlaceholder(state),
                      )
                    else
                      _buildPreviewPlaceholder(state),
                    if (livePreviewToggleEnabled)
                      Positioned(
                        right: 12,
                        bottom: 12,
                        child: _buildLivePreviewToggleOverlay(
                          icon: !state.showLivePreview
                              ? Icons.play_circle_outline_rounded
                              : (!hasLivePreview
                                  ? Icons.hourglass_top_rounded
                                  : Icons.visibility_off_outlined),
                          label: !state.showLivePreview
                              ? '点击查看实时画面'
                              : (!hasLivePreview
                                  ? '正在开启实时画面…'
                                  : '点击关闭实时画面'),
                        ),
                      ),
                    if (state.isFailed)
                      const Positioned(
                        right: 12,
                        top: 12,
                        child: Padding(
                          padding: EdgeInsets.symmetric(
                            horizontal: 4,
                            vertical: 4,
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                Icons.warning_amber_rounded,
                                size: 16,
                                color: AppTheme.gold,
                                shadows: [
                                  Shadow(
                                    color: Colors.black87,
                                    blurRadius: 4,
                                    offset: Offset(0, 1),
                                  ),
                                ],
                              ),
                              SizedBox(width: 5),
                              Text(
                                '导出失败',
                                style: TextStyle(
                                  color: AppTheme.gold,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600,
                                  shadows: [
                                    Shadow(
                                      color: Colors.black87,
                                      blurRadius: 4,
                                      offset: Offset(0, 1),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildPreviewPlaceholder(ExportState state) {
    return ColoredBox(
      color: const Color(0xFF08080A),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              state.isFailed
                  ? Icons.error_outline_rounded
                  : Icons.movie_filter_outlined,
              size: 44,
              color: state.isFailed
                  ? AppTheme.gold.withAlpha(180)
                  : AppTheme.flowTextMuted,
            ),
            const SizedBox(height: 9),
            Text(
              state.isFailed ? '没有可用的失败预览' : '实时画面已关闭',
              style: const TextStyle(
                color: AppTheme.flowTextSecondary,
                fontSize: 12.5,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildLivePreviewToggleOverlay({
    required IconData icon,
    required String label,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            icon,
            color: Colors.white,
            size: 16,
            shadows: const [
              Shadow(
                color: Colors.black87,
                blurRadius: 4,
                offset: Offset(0, 1),
              ),
            ],
          ),
          const SizedBox(width: 5),
          Text(
            label,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 12,
              fontWeight: FontWeight.w500,
              shadows: [
                Shadow(
                  color: Colors.black87,
                  blurRadius: 4,
                  offset: Offset(0, 1),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProgressPanel(
    ExportState state, {
    required bool showBackgroundHint,
  }) {
    final progress = state.progress.clamp(0.0, 1.0);
    final percent = (progress * 100).round();
    return Container(
      key: const ValueKey('export-progress-deck'),
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
      decoration: const BoxDecoration(
        color: AppTheme.flowBackground,
        border: Border(top: BorderSide(color: AppTheme.flowBorder)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                '$percent%',
                style: const TextStyle(
                  color: AppTheme.gold,
                  fontSize: 36,
                  height: 1,
                  fontWeight: FontWeight.w600,
                  letterSpacing: -1.4,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
              const Spacer(),
              Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text(
                  _remainingTimeLabel(state),
                  style: const TextStyle(
                    color: AppTheme.flowTextSecondary,
                    fontSize: 12.5,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 11),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: progress > 0 ? progress : null,
              minHeight: 5,
              backgroundColor: AppTheme.surfaceHigh,
              valueColor: const AlwaysStoppedAnimation(AppTheme.gold),
            ),
          ),
          const SizedBox(height: 13),
          Row(
            children: [
              const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: AppTheme.gold,
                ),
              ),
              const SizedBox(width: 9),
              Expanded(
                child: Text(
                  _statusTitle(state.status),
                  style: const TextStyle(
                    color: AppTheme.flowTextPrimary,
                    fontSize: 13.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          if (state.exportPlan?.hasFallback == true) ...[
            const SizedBox(height: 9),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(
                  Icons.high_quality_outlined,
                  size: 16,
                  color: AppTheme.flowTextMuted,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '设备编码能力限制，输出调整为 '
                    '${state.exportPlan!.width}×${state.exportPlan!.height}；帧时间保持源视频。',
                    style: const TextStyle(
                      color: AppTheme.flowTextMuted,
                      fontSize: 11.5,
                      height: 1.35,
                    ),
                  ),
                ),
              ],
            ),
          ],
          const SizedBox(height: 10),
          Row(
            children: [
              const Icon(
                Icons.verified_user_outlined,
                size: 16,
                color: AppTheme.flowTextMuted,
              ),
              const SizedBox(width: 7),
              const Expanded(
                child: Text(
                  '纯端侧离线处理，视频数据不离开本地',
                  style: TextStyle(
                    color: AppTheme.flowTextMuted,
                    fontSize: 11.5,
                  ),
                ),
              ),
              if (showBackgroundHint)
                const Padding(
                  padding: EdgeInsets.only(left: 10),
                  child: Icon(
                    Icons.phone_android_rounded,
                    size: 15,
                    color: AppTheme.flowTextMuted,
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildFailurePanel(ExportState state) {
    return Container(
      key: const ValueKey('export-failure-summary'),
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
      decoration: const BoxDecoration(
        color: AppTheme.flowBackground,
        border: Border(top: BorderSide(color: AppTheme.flowBorder)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '这次没有生成视频',
            style: TextStyle(
              color: AppTheme.flowTextPrimary,
              fontSize: 18,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            '当前编辑内容仍然保留，可重试或返回编辑。',
            style: TextStyle(color: AppTheme.flowTextSecondary, fontSize: 12.5),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: _InlineAction(
                  key: _failedRetryKey,
                  icon: Icons.refresh_rounded,
                  label: '重试导出',
                  primary: true,
                  onTap: () {
                    HapticFeedback.mediumImpact();
                    _startExportJob();
                  },
                ),
              ),
              if (kDebugMode) ...[
                const SizedBox(width: 8),
                _SquareAction(
                  key: _failedCopyKey,
                  icon: Icons.content_copy_rounded,
                  tooltip: '复制错误详情',
                  onTap: () => _copyError(state.errorMessage),
                ),
                const SizedBox(width: 8),
                _SquareAction(
                  key: _failedDiagnosticsKey,
                  icon: Icons.bug_report_outlined,
                  tooltip: '导出诊断包',
                  onTap: _exportDiagnostics,
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  String _statusTitle(ExportJobState status) {
    return switch (status) {
      ExportJobState.queued => '正在准备裁剪后的片段',
      ExportJobState.preparing => '正在准备裁剪后的片段',
      ExportJobState.processing => '正在处理裁剪后的片段',
      ExportJobState.muxing => '正在保存保护后的舞段',
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
    if (seconds < 60) return '预计还需 $seconds 秒';
    final minutes = seconds ~/ 60;
    final remainder = seconds % 60;
    return remainder == 0 ? '预计还需 $minutes 分钟' : '预计还需 $minutes 分 $remainder 秒';
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
      barrierColor: const Color(0x66000000),
      builder: (dialogContext) => Dialog(
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        insetPadding: const EdgeInsets.symmetric(horizontal: 28),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 320),
          child: Container(
            key: _cancelDialogKey,
            padding: const EdgeInsets.fromLTRB(18, 18, 18, 14),
            decoration: BoxDecoration(
              color: AppTheme.flowSurface,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: AppTheme.flowBorder),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x38000000),
                  blurRadius: 28,
                  offset: Offset(0, 12),
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    SizedBox(
                      width: 34,
                      height: 34,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: AppTheme.coralPale,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.close_rounded,
                          color: AppTheme.coral,
                          size: 19,
                        ),
                      ),
                    ),
                    SizedBox(width: 11),
                    Expanded(
                      child: Text(
                        '取消处理？',
                        style: TextStyle(
                          color: AppTheme.flowTextPrimary,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                const Text(
                  '当前处理进度不会保留。',
                  style: TextStyle(
                    color: AppTheme.flowTextSecondary,
                    fontSize: 12.5,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 14),
                const Divider(height: 1, color: AppTheme.flowBorder),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      key: _cancelContinueKey,
                      onPressed: () => Navigator.of(dialogContext).pop(),
                      style: TextButton.styleFrom(
                        minimumSize: const Size(72, 44),
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        foregroundColor: AppTheme.flowTextSecondary,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      child: const Text('继续处理'),
                    ),
                    const SizedBox(width: 6),
                    TextButton(
                      key: _cancelConfirmKey,
                      onPressed: () {
                        Navigator.of(dialogContext).pop();
                        _cancelAndReturn(controller);
                      },
                      style: TextButton.styleFrom(
                        minimumSize: const Size(84, 44),
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        foregroundColor: AppTheme.coralStrong,
                        backgroundColor: AppTheme.coralPale,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      child: const Text(
                        '取消处理',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _cancelAndReturn(ExportController controller) async {
    if (_cancelInFlight || !mounted) return;
    setState(() => _cancelInFlight = true);
    try {
      await controller.cancelExport();
      if (!mounted) return;
      context.pop();
    } catch (error) {
      if (!mounted) return;
      setState(() => _cancelInFlight = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('暂时无法取消，处理仍在继续')));
    }
  }
}



class _InlineAction extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool primary;
  final VoidCallback onTap;

  const _InlineAction({
    super.key,
    required this.icon,
    required this.label,
    required this.onTap,
    this.primary = false,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Ink(
          height: 46,
          decoration: BoxDecoration(
            color: primary ? AppTheme.gold : AppTheme.flowSurface,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: primary ? AppTheme.gold : AppTheme.flowBorder,
            ),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                icon,
                size: 18,
                color: primary ? AppTheme.textOnAccent : AppTheme.flowTextPrimary,
              ),
              const SizedBox(width: 8),
              Text(
                label,
                style: TextStyle(
                  color: primary
                      ? AppTheme.textOnAccent
                      : AppTheme.flowTextPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SquareAction extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  const _SquareAction({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(14),
          child: Ink(
            width: 46,
            height: 46,
            decoration: BoxDecoration(
              color: AppTheme.flowSurface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppTheme.flowBorder),
            ),
            child: Icon(icon, size: 19, color: AppTheme.flowTextSecondary),
          ),
        ),
      ),
    );
  }
}
