import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../repositories/native_processing_repository.dart';
import '../../import_video/presentation/widgets/video_preview_player.dart';
import '../domain/export_state.dart';

class ResultScreen extends ConsumerStatefulWidget {
  final ExportState exportState;

  const ResultScreen({super.key, required this.exportState});

  @override
  ConsumerState<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends ConsumerState<ResultScreen> {
  bool _isSaving = false;
  bool _isSaved = false;
  bool _isSharing = false;
  bool _isOpening = false;
  bool _isExportingDiagnostics = false;
  String? _savedUri;
  String? _saveError;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _saveToGallery(automatic: true);
    });
  }

  Future<void> _openSavedVideo() async {
    if (_isOpening) return;
    if (!_isSaved || _savedUri == null || _savedUri!.isEmpty) {
      await _saveToGallery();
      if (!_isSaved || _savedUri == null || _savedUri!.isEmpty) return;
    }

    HapticFeedback.lightImpact();
    setState(() => _isOpening = true);
    try {
      await ref.read(nativeRepositoryProvider).openVideo(_savedUri!);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('暂时无法打开系统视频查看器')));
      }
    } finally {
      if (mounted) setState(() => _isOpening = false);
    }
  }

  Future<void> _saveToGallery({bool automatic = false}) async {
    final outputPath = widget.exportState.outputUri;
    if (outputPath == null || outputPath.isEmpty || _isSaved || _isSaving) {
      return;
    }

    if (!automatic) HapticFeedback.mediumImpact();
    setState(() {
      _isSaving = true;
      _saveError = null;
    });

    try {
      final uri = await ref
          .read(nativeRepositoryProvider)
          .saveVideoToGallery(outputPath);
      if (!mounted) return;
      HapticFeedback.heavyImpact();
      setState(() {
        _isSaving = false;
        _isSaved = uri != null && uri.isNotEmpty;
        _savedUri = uri;
        _saveError = _isSaved ? null : '保存失败，请重试';
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isSaving = false;
        _saveError = '保存失败，请重试';
      });
    }
  }

  Future<void> _shareVideo() async {
    if (_isSharing) return;
    if (!_isSaved || _savedUri == null) {
      await _saveToGallery();
      if (!_isSaved || _savedUri == null) return;
    }

    HapticFeedback.mediumImpact();
    setState(() => _isSharing = true);
    try {
      await ref.read(nativeRepositoryProvider).shareVideo(_savedUri!);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('暂时无法打开系统分享面板')));
      }
    } finally {
      if (mounted) setState(() => _isSharing = false);
    }
  }

  Future<void> _exportDiagnostics() async {
    if (_isExportingDiagnostics) return;
    setState(() => _isExportingDiagnostics = true);
    try {
      final repo = ref.read(nativeRepositoryProvider);
      final bundle = await repo.createDiagnosticBundle();
      await repo.shareDiagnosticBundle(
        filePath: bundle?['filePath'] as String?,
        publicUri: bundle?['publicUri'] as String?,
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('诊断包导出失败')));
      }
    } finally {
      if (mounted) setState(() => _isExportingDiagnostics = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final outputPath = widget.exportState.outputUri ?? '';
    final project = widget.exportState.project;
    final file = File(outputPath);
    final fileSizeMb = file.existsSync()
        ? (file.lengthSync() / (1024 * 1024)).toStringAsFixed(1)
        : null;
    final shareEnabled = !_isSharing && !_isSaving && _saveError == null;
    final navigationEnabled = !_isSaving;

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
            key: const ValueKey('result-content-stack'),
            children: [
              SizedBox(
                height: 56,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 14),
                  child: Row(
                    children: [
                      Semantics(
                        key: const ValueKey('result-next-action'),
                        button: true,
                        enabled: navigationEnabled,
                        label: '返回首页',
                        child: Material(
                          color: Colors.transparent,
                          child: InkWell(
                            onTap: navigationEnabled
                                ? () {
                                    HapticFeedback.mediumImpact();
                                    context.go('/');
                                  }
                                : null,
                            borderRadius: BorderRadius.circular(16),
                            child: AnimatedOpacity(
                              duration: const Duration(milliseconds: 120),
                              opacity: navigationEnabled ? 1 : 0.42,
                              child: const Padding(
                                padding: EdgeInsets.all(8),
                                child: Icon(
                                  Icons.close_rounded,
                                  size: 24,
                                  color: AppTheme.textPrimary,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                      const Spacer(),
                      Semantics(
                        key: const ValueKey('result-share-action'),
                        button: true,
                        enabled: shareEnabled,
                        label: _isSharing ? '正在分享' : '分享',
                        child: Material(
                          color: Colors.transparent,
                          child: InkWell(
                            onTap: shareEnabled ? _shareVideo : null,
                            borderRadius: BorderRadius.circular(16),
                            child: AnimatedOpacity(
                              duration: const Duration(milliseconds: 120),
                              opacity: shareEnabled ? 1 : 0.42,
                              child: Padding(
                                padding: const EdgeInsets.all(8),
                                child: _isSharing
                                    ? const SizedBox(
                                        width: 24,
                                        height: 24,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          color: AppTheme.gold,
                                        ),
                                      )
                                    : const Icon(
                                        Icons.ios_share_rounded,
                                        size: 24,
                                        color: AppTheme.gold,
                                      ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              Expanded(
                child: Container(
                  key: const ValueKey('result-media-stage'),
                  width: double.infinity,
                  color: Colors.black,
                  alignment: Alignment.center,
                  child: outputPath.isEmpty
                      ? const Icon(
                          Icons.movie_outlined,
                          color: AppTheme.flowTextMuted,
                          size: 48,
                        )
                      : VideoPreviewPlayer(
                          videoPath: outputPath,
                          aspectRatio: project?.outputAspectRatio ?? 16 / 9,
                        ),
                ),
              ),
              _buildResultStatusPanel(fileSizeMb),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildResultStatusPanel(String? fileSizeMb) {
    final String title;
    final String subtitle;
    final IconData icon;
    final Color iconColor;

    if (_isSaving) {
      title = '正在保存到相册';
      subtitle = '保存完成后即可直接分享';
      icon = Icons.downloading_rounded;
      iconColor = AppTheme.gold;
    } else if (_isSaved) {
      title = '已保存至系统相册';
      subtitle = fileSizeMb == null ? '视频已安全脱敏存储' : '视频已安全脱敏 · $fileSizeMb MB';
      icon = Icons.check_circle_outline_rounded;
      iconColor = const Color(0xFF71C991);
    } else if (_saveError != null) {
      title = '尚未存入系统相册';
      subtitle = '视频仍保留在设备中，可重新保存';
      icon = Icons.error_outline_rounded;
      iconColor = AppTheme.error;
    } else {
      title = '视频处理完成';
      subtitle = '正在准备写入媒体库';
      icon = Icons.check_circle_outline_rounded;
      iconColor = AppTheme.flowTextSecondary;
    }

    return Container(
      key: const ValueKey('result-status-panel'),
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
      decoration: const BoxDecoration(
        color: AppTheme.flowBackground,
        border: Border(top: BorderSide(color: AppTheme.flowBorder)),
      ),
      child: Row(
        children: [
          if (_isSaving)
            const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: AppTheme.gold,
              ),
            )
          else
            Icon(icon, size: 21, color: iconColor),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppTheme.flowTextPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: const TextStyle(
                    color: AppTheme.flowTextSecondary,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          if (_saveError != null)
            Material(
              color: Colors.transparent,
              child: InkWell(
                key: const ValueKey('result-resave-action'),
                borderRadius: BorderRadius.circular(10),
                onTap: _isSaving ? null : () => _saveToGallery(),
                child: const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.refresh_rounded, size: 16, color: AppTheme.gold),
                      SizedBox(width: 4),
                      Text(
                        '重新保存',
                        style: TextStyle(
                          color: AppTheme.gold,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            )
          else
            Semantics(
              key: const ValueKey('result-open-action'),
              button: true,
              enabled: !_isSaving && !_isOpening,
              label: _isOpening ? '正在打开…' : '查看视频',
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  borderRadius: BorderRadius.circular(12),
                  onTap: !_isSaving && !_isOpening ? _openSavedVideo : null,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 6,
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (_isOpening)
                          const SizedBox(
                            width: 15,
                            height: 15,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppTheme.gold,
                            ),
                          )
                        else
                          const Icon(
                            Icons.play_circle_outline_rounded,
                            size: 17,
                            color: AppTheme.gold,
                          ),
                        const SizedBox(width: 5),
                        Text(
                          _isOpening ? '正在打开…' : '查看视频',
                          style: const TextStyle(
                            color: AppTheme.gold,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          if (kDebugMode) ...[
            const SizedBox(width: 4),
            _ResultDiagnosticsButton(
              key: const ValueKey('result-diagnostics-action'),
              isExporting: _isExportingDiagnostics,
              onTap: _isExportingDiagnostics ? null : _exportDiagnostics,
            ),
          ],
        ],
      ),
    );
  }
}

class _ResultDiagnosticsButton extends StatelessWidget {
  final bool isExporting;
  final VoidCallback? onTap;

  const _ResultDiagnosticsButton({
    super.key,
    required this.isExporting,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: isExporting ? '正在准备诊断包' : '导出诊断包',
      child: Tooltip(
        message: isExporting ? '正在准备诊断包' : '导出诊断包',
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(10),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(
                    Icons.bug_report_outlined,
                    size: 14,
                    color: AppTheme.flowTextMuted,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    isExporting ? '正在导出…' : '导出诊断包',
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppTheme.flowTextMuted,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
