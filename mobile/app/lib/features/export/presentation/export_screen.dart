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

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
        statusBarBrightness: Brightness.light,
        systemNavigationBarColor: AppTheme.warmBackground,
        systemNavigationBarIconBrightness: Brightness.dark,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.warmBackground,
        body: SafeArea(
          child: Column(
            children: [
              _buildTopBar(state, isActive: isActive, controller: controller),
              Expanded(
                child: SingleChildScrollView(
                  physics: const BouncingScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 30),
                  child: Column(
                    children: [
                      _buildMediaPreview(state),
                      if (isFailed) ...[
                        const SizedBox(height: 34),
                        _buildPrimaryAction(
                          label: '重试导出',
                          icon: Icons.refresh_rounded,
                          onTap: _startExportJob,
                        ),
                        const SizedBox(height: 14),
                        _buildOutlinedAction(
                          label: '返回编辑',
                          onTap: () => context.pop(),
                        ),
                      ] else ...[
                        const SizedBox(height: 22),
                        _buildProgressCard(state),
                        const SizedBox(height: 14),
                        _buildBackgroundHint(),
                        const SizedBox(height: 20),
                        _buildOutlinedAction(
                          label: '取消处理',
                          enabled: isActive,
                          onTap: () => _confirmCancel(controller),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTopBar(
    ExportState state, {
    required bool isActive,
    required ExportController controller,
  }) {
    final isFailed = state.isFailed;
    return SizedBox(
      height: 78,
      child: Stack(
        alignment: Alignment.center,
        children: [
          Positioned(
            left: 8,
            child: IconButton(
              tooltip: isActive ? '取消处理' : '关闭',
              onPressed: isActive
                  ? () => _confirmCancel(controller)
                  : () => context.pop(),
              icon: const Icon(
                Icons.close_rounded,
                size: 34,
                color: AppTheme.warmTextPrimary,
              ),
            ),
          ),
          Text(
            isFailed ? '导出失败' : '正在保护舞段',
            style: const TextStyle(
              color: AppTheme.warmTextPrimary,
              fontSize: 24,
              height: 1.1,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.6,
            ),
          ),
          Positioned(
            right: 8,
            child: isFailed
                ? PopupMenuButton<String>(
                    tooltip: '更多',
                    icon: const Icon(
                      Icons.more_horiz_rounded,
                      size: 30,
                      color: AppTheme.warmTextPrimary,
                    ),
                    color: AppTheme.warmSurface,
                    surfaceTintColor: Colors.transparent,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                      side: const BorderSide(color: AppTheme.warmBorder),
                    ),
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
                        child: Row(
                          children: [
                            Icon(
                              Icons.copy_rounded,
                              color: AppTheme.warmTextSecondary,
                              size: 20,
                            ),
                            SizedBox(width: 12),
                            Text(
                              '复制错误详情',
                              style: TextStyle(
                                color: AppTheme.warmTextPrimary,
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ],
                        ),
                      ),
                      PopupMenuItem(
                        value: 'diagnostics',
                        child: Row(
                          children: [
                            Icon(
                              Icons.bug_report_outlined,
                              color: AppTheme.warmTextSecondary,
                              size: 20,
                            ),
                            SizedBox(width: 12),
                            Text(
                              '导出诊断包',
                              style: TextStyle(
                                color: AppTheme.warmTextPrimary,
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  )
                : const SizedBox(width: 48, height: 48),
          ),
        ],
      ),
    );
  }

  Widget _buildMediaPreview(ExportState state) {
    final previewPath = state.currentPreviewPath;
    final hasPreview =
        previewPath != null &&
        previewPath.isNotEmpty &&
        File(previewPath).existsSync();
    final rawAspect = widget.project.videoInfo.aspectRatio > 0
        ? widget.project.videoInfo.aspectRatio
        : 16 / 9;
    final aspect = rawAspect.clamp(0.65, 1.8).toDouble();

    return AspectRatio(
      aspectRatio: aspect,
      child: Container(
        decoration: BoxDecoration(
          color: const Color(0xFFF0E6E0),
          borderRadius: BorderRadius.circular(28),
          border: Border.all(color: AppTheme.warmBorder),
          boxShadow: const [
            BoxShadow(
              color: Color(0x12000000),
              blurRadius: 24,
              offset: Offset(0, 8),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (hasPreview)
              Image.file(
                File(previewPath),
                key: ValueKey('${previewPath}_${state.currentFrame}'),
                fit: BoxFit.cover,
                gaplessPlayback: true,
              )
            else
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(
                      Icons.movie_filter_outlined,
                      size: 52,
                      color: AppTheme.warmTextMuted,
                    ),
                    const SizedBox(height: 10),
                    Text(
                      state.isFailed ? '没有可用的失败预览' : '正在准备处理预览',
                      style: const TextStyle(
                        color: AppTheme.warmTextSecondary,
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
            if (state.isFailed)
              Positioned(
                right: 14,
                bottom: 14,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    gradient: AppTheme.coralActionGradient,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.warning_amber_rounded,
                        color: Colors.white,
                        size: 18,
                      ),
                      SizedBox(width: 6),
                      Text(
                        '导出失败',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            if (state.isFailed && state.fps > 0)
              Positioned(
                left: 14,
                bottom: 14,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 7,
                  ),
                  decoration: BoxDecoration(
                    color: const Color(0x88000000),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    _formatProcessedTime(state),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 12,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildProgressCard(ExportState state) {
    final progress = state.progress.clamp(0.0, 1.0);
    final percent = (progress * 100).round();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(22, 22, 22, 20),
      decoration: BoxDecoration(
        color: AppTheme.warmSurface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: AppTheme.warmBorder),
        boxShadow: const [
          BoxShadow(
            color: Color(0x10000000),
            blurRadius: 22,
            offset: Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                '$percent',
                style: const TextStyle(
                  color: AppTheme.coral,
                  fontSize: 54,
                  height: 0.95,
                  fontWeight: FontWeight.w500,
                  letterSpacing: -2,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
              const Padding(
                padding: EdgeInsets.only(bottom: 4),
                child: Text(
                  '%',
                  style: TextStyle(
                    color: AppTheme.coral,
                    fontSize: 25,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              const Spacer(),
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  _remainingTimeLabel(state),
                  style: const TextStyle(
                    color: AppTheme.warmTextSecondary,
                    fontSize: 13,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),
          ClipRRect(
            borderRadius: BorderRadius.circular(5),
            child: LinearProgressIndicator(
              value: progress > 0 ? progress : null,
              minHeight: 7,
              backgroundColor: AppTheme.coralPale,
              valueColor: const AlwaysStoppedAnimation(AppTheme.coral),
            ),
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
                  color: AppTheme.coral,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  _statusTitle(state.status),
                  style: const TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildBackgroundHint() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      decoration: BoxDecoration(
        color: AppTheme.warmSurface,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: AppTheme.warmBorder),
      ),
      child: const Row(
        children: [
          SizedBox(
            width: 46,
            height: 46,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: AppTheme.warmSurfaceSoft,
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.phone_android_rounded,
                size: 23,
                color: AppTheme.warmTextPrimary,
              ),
            ),
          ),
          SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '处理将在后台继续',
                  style: TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                SizedBox(height: 4),
                Text(
                  '你可以切换应用，处理完成后我们会在通知中提醒你。',
                  style: TextStyle(
                    color: AppTheme.warmTextSecondary,
                    fontSize: 12,
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPrimaryAction({
    required String label,
    required IconData icon,
    required VoidCallback onTap,
  }) {
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: Ink(
          height: 58,
          decoration: BoxDecoration(
            gradient: AppTheme.coralActionGradient,
            borderRadius: BorderRadius.circular(18),
            boxShadow: const [
              BoxShadow(
                color: Color(0x22F44848),
                blurRadius: 16,
                offset: Offset(0, 7),
              ),
            ],
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: Colors.white, size: 20),
              const SizedBox(width: 9),
              Text(
                label,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildOutlinedAction({
    required String label,
    required VoidCallback onTap,
    bool enabled = true,
  }) {
    return Opacity(
      opacity: enabled ? 1 : 0.45,
      child: SizedBox(
        width: double.infinity,
        height: 58,
        child: OutlinedButton(
          onPressed: enabled ? onTap : null,
          style: OutlinedButton.styleFrom(
            side: const BorderSide(color: AppTheme.coral, width: 1.3),
            foregroundColor: AppTheme.coralStrong,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(18),
            ),
          ),
          child: Text(
            label,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
          ),
        ),
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

  String _formatProcessedTime(ExportState state) {
    if (state.fps <= 0) return '00:00';
    final totalSeconds = (state.currentFrame / state.fps)
        .floor()
        .clamp(0, 359999)
        .toInt();
    final minutes = totalSeconds ~/ 60;
    final seconds = totalSeconds % 60;
    return '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
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
      barrierColor: const Color(0x52000000),
      builder: (dialogContext) => Dialog(
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        insetPadding: const EdgeInsets.symmetric(horizontal: 30),
        child: Container(
          padding: const EdgeInsets.fromLTRB(22, 22, 22, 20),
          decoration: BoxDecoration(
            color: AppTheme.warmSurface,
            borderRadius: BorderRadius.circular(26),
            border: Border.all(color: AppTheme.warmBorder),
            boxShadow: const [
              BoxShadow(
                color: Color(0x22000000),
                blurRadius: 30,
                offset: Offset(0, 12),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: const BoxDecoration(
                  color: AppTheme.coralPale,
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.close_rounded,
                  color: AppTheme.coral,
                  size: 26,
                ),
              ),
              const SizedBox(height: 16),
              const Text(
                '取消处理？',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: AppTheme.warmTextPrimary,
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                '当前处理进度不会保留。',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: AppTheme.warmTextSecondary,
                  fontSize: 13,
                ),
              ),
              const SizedBox(height: 22),
              Material(
                color: Colors.transparent,
                borderRadius: BorderRadius.circular(16),
                child: InkWell(
                  onTap: () => Navigator.of(dialogContext).pop(),
                  borderRadius: BorderRadius.circular(16),
                  child: Ink(
                    height: 54,
                    decoration: BoxDecoration(
                      gradient: AppTheme.coralActionGradient,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: const Center(
                      child: Text(
                        '继续处理',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                height: 52,
                child: OutlinedButton(
                  onPressed: () {
                    Navigator.of(dialogContext).pop();
                    controller.cancelExport();
                    context.pop();
                  },
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppTheme.coralStrong,
                    side: const BorderSide(color: AppTheme.warmBorder),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                    ),
                  ),
                  child: const Text(
                    '取消处理',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
