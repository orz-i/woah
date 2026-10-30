import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/system_ui.dart';
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

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: appSystemUiStyleFor(Theme.of(context).brightness),
      child: Scaffold(
        backgroundColor: AppTheme.of(context).flowBackground,
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
                      IconButton(
                        key: const ValueKey('result-next-action'),
                        tooltip: '返回首页',
                        onPressed: _isSaving
                            ? null
                            : () {
                                HapticFeedback.mediumImpact();
                                context.go('/');
                              },
                        icon: const Icon(Icons.close_rounded),
                      ),
                      const Spacer(),
                      if (kDebugMode)
                        PopupMenuButton<String>(
                          key: const ValueKey('result-more-action'),
                          tooltip: '更多操作',
                          icon: const Icon(Icons.more_horiz_rounded),
                          onSelected: (_) => _exportDiagnostics(),
                          itemBuilder: (context) => [
                            PopupMenuItem(
                              key: const ValueKey('result-diagnostics-action'),
                              value: 'diagnostics',
                              enabled: !_isExportingDiagnostics,
                              child: Text(
                                _isExportingDiagnostics ? '正在准备诊断包…' : '导出诊断包',
                              ),
                            ),
                          ],
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
                      ? Icon(
                          Icons.movie_outlined,
                          color: AppTheme.of(context).flowTextMuted,
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
    final colors = AppTheme.of(context);
    final String title;
    final String subtitle;
    final IconData icon;
    final Color iconColor;

    if (_isSaving) {
      title = '正在保存到相册';
      subtitle = '保存完成后即可查看和分享';
      icon = Icons.downloading_rounded;
      iconColor = colors.brandAction;
    } else if (_isSaved) {
      title = '已保存至系统相册';
      subtitle = fileSizeMb == null ? '可在相册中查看或分享' : '$fileSizeMb MB';
      icon = Icons.check_circle_outline_rounded;
      iconColor = colors.statusSuccess;
    } else if (_saveError != null) {
      title = '尚未存入系统相册';
      subtitle = '视频仍保留在设备中，可重新保存';
      icon = Icons.error_outline_rounded;
      iconColor = colors.error;
    } else {
      title = '视频处理完成';
      subtitle = '正在准备写入媒体库';
      icon = Icons.check_circle_outline_rounded;
      iconColor = colors.flowTextSecondary;
    }

    final openEnabled = !_isSaving && !_isOpening;
    final shareEnabled = !_isSharing && !_isSaving && _saveError == null;
    return Container(
      key: const ValueKey('result-status-panel'),
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
      decoration: BoxDecoration(
        color: colors.flowBackground,
        border: Border(top: BorderSide(color: colors.flowBorder)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            liveRegion: true,
            child: Row(
              children: [
                if (_isSaving)
                  SizedBox(
                    width: 21,
                    height: 21,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: iconColor,
                    ),
                  )
                else
                  Icon(icon, size: 21, color: iconColor),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        key: const ValueKey('result-save-status'),
                        style: TextStyle(
                          color: colors.flowTextPrimary,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: colors.flowTextSecondary,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: _saveError != null
                    ? OutlinedButton.icon(
                        key: const ValueKey('result-resave-action'),
                        onPressed: _isSaving ? null : () => _saveToGallery(),
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(0, AppTheme.minTouchTarget),
                        ),
                        icon: const Icon(Icons.refresh_rounded, size: 18),
                        label: const Text('重新保存'),
                      )
                    : OutlinedButton.icon(
                        key: const ValueKey('result-open-action'),
                        onPressed: openEnabled ? _openSavedVideo : null,
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(0, AppTheme.minTouchTarget),
                        ),
                        icon: const Icon(
                          Icons.play_circle_outline_rounded,
                          size: 18,
                        ),
                        label: Text(_isOpening ? '正在打开…' : '查看视频'),
                      ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  key: const ValueKey('result-share-action'),
                  onPressed: shareEnabled ? _shareVideo : null,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(0, AppTheme.minTouchTarget),
                  ),
                  icon: const Icon(Icons.ios_share_rounded, size: 18),
                  label: Text(_isSharing ? '正在分享…' : '分享视频'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
