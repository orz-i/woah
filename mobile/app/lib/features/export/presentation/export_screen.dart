import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/system_ui.dart';
import '../../../core/cache/analysis_session.dart';
import '../../../app/theme.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/export_state.dart';
import 'export_controller.dart';

class ExportArgs {
  final DanceProject project;
  final String? initialPreviewPath;

  final AnalysisSession? session;
  const ExportArgs({
    required this.project,
    this.initialPreviewPath,
    this.session,
  });
}

class ExportScreen extends ConsumerStatefulWidget {
  final DanceProject project;
  final String? initialPreviewPath;
  final AnalysisSession? session;

  const ExportScreen({
    super.key,
    required this.project,
    this.initialPreviewPath,
    this.session,
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
    if (!mounted || (widget.session?.isClosed ?? false)) return;
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
      // Processing is deliberately cinematic in both system appearances.
      // Light mode still uses its own colors for dialogs and failure states.
      value: appSystemUiStyleFor(
        isFailed ? Theme.of(context).brightness : Brightness.dark,
      ),
      child: Scaffold(
        backgroundColor: isFailed
            ? AppTheme.of(context).flowBackground
            : const Color(0xFF141615),
        body: SafeArea(
          child: isFailed
              ? Column(
                  children: [
                    _buildFailureTopBar(),
                    Expanded(
                      child: _buildMediaPreview(
                        state,
                        controller,
                        livePreviewToggleEnabled: false,
                      ),
                    ),
                    _buildFailurePanel(state),
                  ],
                )
              : _buildCinematicProgressPage(
                  state,
                  controller,
                  isActive: isActive,
                ),
        ),
      ),
    );
  }

  Widget _buildCinematicProgressPage(
    ExportState state,
    ExportController controller, {
    required bool isActive,
  }) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          stops: [0, 0.30, 0.68, 1],
          colors: [
            Color(0xFF40454D),
            Color(0xFF29282A),
            Color(0xFF1C1B17),
            Color(0xFF111310),
          ],
        ),
      ),
      child: LayoutBuilder(
        builder: (context, viewport) {
          final height = viewport.maxHeight;
          final compact = height < 700;
          final frameWidth = math.min(
            viewport.maxWidth - (viewport.maxWidth < 360 ? 32 : 44),
            460.0,
          );
          // Keep the stage at the reference's editorial ~1.4:1 framing.
          // The actual video remains aspect-correct inside it.
          final frameHeight = math.min(
            frameWidth / 1.40,
            height * (compact ? 0.32 : 0.38),
          );
          // On taller phones the reference favors more breathing room above
          // the photo and between its glow and the status. Compress the gap
          // before the stop action so it stays comfortably in view.
          final roomy = ((height - 700) / 144).clamp(0.0, 1.0);
          final topGap = compact
              ? height * .045
              : height * (.12 + .063 * roomy);
          final mediaToStatusGap = compact
              ? 28.0
              : height * (.125 + .058 * roomy);
          final progressToStopGap = compact
              ? 22.0
              : height * (.060 - .046 * roomy);
          final bottomGap = compact ? 20.0 : height * .07;

          return SingleChildScrollView(
            key: const ValueKey('export-cinematic-scroll'),
            physics: const ClampingScrollPhysics(),
            child: ConstrainedBox(
              constraints: BoxConstraints(minHeight: height),
              child: Column(
                children: [
                  SizedBox(height: topGap),
                  _buildCinematicPreview(
                    state,
                    controller,
                    frameWidth: frameWidth,
                    frameHeight: frameHeight,
                    livePreviewToggleEnabled: isActive,
                  ),
                  SizedBox(height: mediaToStatusGap),
                  _buildProgressPanel(state),
                  SizedBox(height: progressToStopGap),
                  _buildCancelAction(
                    controller,
                    enabled: isActive && state.jobId != null,
                  ),
                  SizedBox(height: bottomGap),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildCinematicPreview(
    ExportState state,
    ExportController controller, {
    required double frameWidth,
    required double frameHeight,
    required bool livePreviewToggleEnabled,
  }) {
    // Keep the media's rounded silhouette, without a decorative border
    // or glow that competes with the video itself.
    return SizedBox(
      key: const ValueKey('export-cinematic-stage'),
      width: double.infinity,
      height: frameHeight,
      child: Center(
        child: Container(
          key: const ValueKey('export-cinematic-frame'),
          width: frameWidth,
          height: frameHeight,
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
            color: Colors.black,
            borderRadius: BorderRadius.circular(30),
          ),
          child: _buildMediaPreview(
            state,
            controller,
            livePreviewToggleEnabled: livePreviewToggleEnabled,
          ),
        ),
      ),
    );
  }

  Widget _buildFailureTopBar() {
    // Only failure keeps a conventional back action; active processing has
    // one bottom-centered stop control like the supplied visual reference.
    return SizedBox(
      height: 56,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14),
        child: Row(
          children: [
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
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 4,
                    vertical: 6,
                  ),
                  child: Icon(
                    Icons.arrow_back_ios_new_rounded,
                    size: 22,
                    color: AppTheme.of(context).textPrimary,
                  ),
                ),
              ),
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
            duration: const Duration(milliseconds: 140),
            opacity: actionEnabled || _cancelInFlight ? 1 : 0.5,
            child: Container(
              key: const ValueKey('export-stop-circle'),
              width: 72,
              height: 72,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: const Color(0x2EFFFFFF),
                border: Border.all(color: const Color(0x25FFFFFF), width: 1),
              ),
              alignment: Alignment.center,
              child: _cancelInFlight
                  ? const SizedBox(
                      width: 25,
                      height: 25,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : Container(
                      width: 20,
                      height: 20,
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(5),
                      ),
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
                      Stack(
                        fit: StackFit.expand,
                        children: [
                          // The blurred edge fill is UI-only. The centered
                          // image keeps its actual output aspect and never
                          // crops people from the exported media preview.
                          ImageFiltered(
                            imageFilter: ui.ImageFilter.blur(
                              sigmaX: 8,
                              sigmaY: 8,
                            ),
                            child: Image.file(
                              File(displayPath),
                              fit: BoxFit.cover,
                              gaplessPlayback: true,
                              filterQuality: FilterQuality.low,
                              color: const Color(0x55000000),
                              colorBlendMode: BlendMode.darken,
                              errorBuilder: (_, _, _) =>
                                  const ColoredBox(color: Colors.black),
                            ),
                          ),
                          Image.file(
                            File(displayPath),
                            fit: BoxFit.contain,
                            gaplessPlayback: true,
                            filterQuality: FilterQuality.low,
                            errorBuilder: (context, error, stackTrace) =>
                                _buildPreviewPlaceholder(state),
                          ),
                        ],
                      )
                    else
                      _buildPreviewPlaceholder(state),
                    if (displayPath != null && !state.isFailed)
                      const Positioned.fill(
                        child: IgnorePointer(
                          child: CustomPaint(
                            painter: _ExportStageDotsPainter(),
                          ),
                        ),
                      ),
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
                              : (!hasLivePreview ? '正在开启实时画面…' : '点击关闭实时画面'),
                        ),
                      ),
                    if (state.isFailed)
                      Positioned(
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
                                color: AppTheme.of(context).brandAction,
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
                                  color: AppTheme.of(context).brandAction,
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
                  ? AppTheme.darkColors.brandAction.withAlpha(180)
                  : AppTheme.darkColors.flowTextMuted,
            ),
            const SizedBox(height: 9),
            Text(
              state.isFailed ? '没有可用的失败预览' : '实时画面已关闭',
              style: TextStyle(
                color: AppTheme.darkColors.flowTextSecondary,
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
    // The whole media frame remains tappable; this small unobtrusive badge
    // preserves discoverability without covering the preview with a caption.
    return Tooltip(
      message: label,
      child: Container(
        width: 32,
        height: 32,
        decoration: const BoxDecoration(
          color: Color(0xA0000000),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, color: Colors.white, size: 18),
      ),
    );
  }

  Widget _buildProgressPanel(ExportState state) {
    final progress = state.progress.clamp(0.0, 1.0);
    final percent = (progress * 100).round();
    return Padding(
      key: const ValueKey('export-progress-deck'),
      padding: const EdgeInsets.symmetric(horizontal: 22),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _ExportProgressMark(progress: progress),
          const SizedBox(height: 20),
          Text(
            '${_statusTitle(state.status)}… $percent%',
            key: const ValueKey('export-progress-headline'),
            textAlign: TextAlign.center,
            maxLines: 2,
            style: const TextStyle(
              color: Color(0xFFF2F6F3),
              fontSize: 22,
              height: 1.22,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.35,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(height: 12),
          Text(
            _remainingTimeLabel(state),
            key: const ValueKey('export-time-estimate'),
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: Color(0xFF9AA8A5),
              fontSize: 11.5,
              height: 1.4,
            ),
          ),
          if (state.exportPlan?.hasCropClarityRestoration == true) ...[
            const SizedBox(height: 8),
            Text(
              '裁切清晰度恢复 · '
              '${state.exportPlan!.cropClarityScale.toStringAsFixed(1)}× · '
              '${state.exportPlan!.width}×${state.exportPlan!.height}',
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Color(0xFFC0CBC8),
                fontSize: 11,
                height: 1.4,
              ),
            ),
          ],
          if (state.exportPlan?.hasFallback == true) ...[
            const SizedBox(height: 8),
            Text(
              '设备编码能力限制，输出调整为 '
              '${state.exportPlan!.width}×${state.exportPlan!.height}；帧时间保持源视频。',
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Color(0xFFD5C19A),
                fontSize: 11,
                height: 1.4,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildFailurePanel(ExportState state) {
    return Container(
      key: const ValueKey('export-failure-summary'),
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
      decoration: BoxDecoration(
        color: AppTheme.of(context).flowBackground,
        border: Border(top: BorderSide(color: AppTheme.of(context).flowBorder)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '这次没有生成视频',
            style: TextStyle(
              color: AppTheme.of(context).flowTextPrimary,
              fontSize: 18,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            '当前编辑内容仍然保留，可重试或返回编辑。',
            style: TextStyle(
              color: AppTheme.of(context).flowTextSecondary,
              fontSize: 12.5,
            ),
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
      ExportJobState.queued => '正在准备视频',
      ExportJobState.preparing => '正在准备视频',
      ExportJobState.processing => '正在保护画面',
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
      // The dialog remains mounted during its exit animation, potentially
      // after the export route deactivates. Never resolve inherited theme
      // data through the export State.context from this dialog builder.
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
              color: AppTheme.of(dialogContext).flowSurface,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: AppTheme.of(dialogContext).flowBorder),
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
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    SizedBox(
                      width: 34,
                      height: 34,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: AppTheme.of(dialogContext).statusWarningSubtle,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.close_rounded,
                          color: AppTheme.of(dialogContext).statusWarning,
                          size: 19,
                        ),
                      ),
                    ),
                    SizedBox(width: 11),
                    Expanded(
                      child: Text(
                        '取消处理？',
                        style: TextStyle(
                          color: AppTheme.of(dialogContext).flowTextPrimary,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  '当前处理进度不会保留。',
                  style: TextStyle(
                    color: AppTheme.of(dialogContext).flowTextSecondary,
                    fontSize: 12.5,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 14),
                Divider(
                  height: 1,
                  color: AppTheme.of(dialogContext).flowBorder,
                ),
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
                        foregroundColor: AppTheme.of(
                          dialogContext,
                        ).flowTextSecondary,
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
                        foregroundColor: AppTheme.of(
                          dialogContext,
                        ).statusWarning,
                        backgroundColor: AppTheme.of(
                          dialogContext,
                        ).statusWarningSubtle,
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
            color: primary
                ? AppTheme.of(context).brandAction
                : AppTheme.of(context).flowSurface,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: primary
                  ? AppTheme.of(context).brandAction
                  : AppTheme.of(context).flowBorder,
            ),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                icon,
                size: 18,
                color: primary
                    ? AppTheme.of(context).textOnAccent
                    : AppTheme.of(context).flowTextPrimary,
              ),
              const SizedBox(width: 8),
              Text(
                label,
                style: TextStyle(
                  color: primary
                      ? AppTheme.of(context).textOnAccent
                      : AppTheme.of(context).flowTextPrimary,
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
              color: AppTheme.of(context).flowSurface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppTheme.of(context).flowBorder),
            ),
            child: Icon(
              icon,
              size: 19,
              color: AppTheme.of(context).flowTextSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

/// Quiet Frame progress symbol: a lightweight dotted halo and a small smile.
/// This is a UI progress ornament, not an effect drawn into the exported video.
class _ExportProgressMark extends StatelessWidget {
  const _ExportProgressMark({required this.progress});

  final double progress;

  @override
  Widget build(BuildContext context) {
    final percent = (progress * 100).round();
    return Semantics(
      label: '导出进度',
      value: '$percent%',
      child: CustomPaint(
        key: const ValueKey('export-progress-mark'),
        size: const Size(48, 48),
        painter: _ExportProgressMarkPainter(progress),
      ),
    );
  }
}

class _ExportProgressMarkPainter extends CustomPainter {
  const _ExportProgressMarkPainter(this.progress);

  final double progress;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    const count = 12;
    for (var i = 0; i < count; i++) {
      final angle = -math.pi / 2 + (2 * math.pi * i / count);
      final dot = center + Offset(math.cos(angle) * 19, math.sin(angle) * 19);
      final isLit = i / count <= progress;
      canvas.drawCircle(
        dot,
        2.6,
        Paint()
          ..color = Colors.white.withValues(alpha: isLit ? 1 : 0.62)
          ..isAntiAlias = true,
      );
    }

    final face = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.3
      ..strokeCap = StrokeCap.round;
    final smile = Path()
      ..moveTo(center.dx - 8, center.dy + 5)
      ..quadraticBezierTo(
        center.dx,
        center.dy + 13,
        center.dx + 8,
        center.dy + 5,
      );
    canvas.drawPath(smile, face);
    final eyes = Paint()..color = Colors.white;
    canvas.drawCircle(center.translate(-6, -3), 1.4, eyes);
    canvas.drawCircle(center.translate(6, -3), 1.4, eyes);
  }

  @override
  bool shouldRepaint(covariant _ExportProgressMarkPainter old) =>
      progress != old.progress;
}

/// Subtle grid from the supplied visual reference. It only decorates preview
/// UI when there is an actual frame; it never alters the export's output.
class _ExportStageDotsPainter extends CustomPainter {
  const _ExportStageDotsPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final dot = Paint()..color = const Color(0x33FFFFFF);
    for (var y = 10.0; y < size.height; y += 18) {
      for (var x = 10.0; x < size.width; x += 18) {
        canvas.drawCircle(Offset(x, y), 0.7, dot);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _ExportStageDotsPainter old) => false;
}
