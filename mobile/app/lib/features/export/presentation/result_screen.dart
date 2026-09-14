import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/stage_viewport.dart';
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
          child: LayoutBuilder(
            builder: (context, constraints) {
              return SingleChildScrollView(
                physics: const BouncingScrollPhysics(),
                child: ConstrainedBox(
                  constraints: BoxConstraints(minHeight: constraints.maxHeight),
                  child: IntrinsicHeight(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(18, 12, 18, 16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (outputPath.isNotEmpty)
                            MediaStageFrame(
                              key: const ValueKey('result-media-stage'),
                              backgroundColor: Colors.black,
                              child: VideoPreviewPlayer(
                                videoPath: outputPath,
                                aspectRatio:
                                    project?.videoInfo.aspectRatio ?? 16 / 9,
                              ),
                            ),
                          const SizedBox(height: 14),
                          _buildSaveStatus(fileSizeMb),
                          const Spacer(),
                          const SizedBox(height: 18),
                          _buildActionCluster(),
                        ],
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildActionCluster() {
    final shareEnabled = !_isSharing && !_isSaving && _saveError == null;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (kDebugMode) ...[
          Align(
            alignment: Alignment.centerRight,
            child: _ResultDiagnosticsButton(
              key: const ValueKey('result-diagnostics-action'),
              isExporting: _isExportingDiagnostics,
              onTap: _isExportingDiagnostics ? null : _exportDiagnostics,
            ),
          ),
          const SizedBox(height: 8),
        ],
        _buildShareButton(shareEnabled),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: _buildSecondaryButton(
                key: const ValueKey('result-next-action'),
                icon: Icons.add_rounded,
                label: '制作下一个',
                onTap: () {
                  HapticFeedback.mediumImpact();
                  context.go('/');
                },
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: _buildSecondaryButton(
                key: const ValueKey('result-open-action'),
                icon: Icons.folder_open_rounded,
                label: _isOpening ? '正在打开…' : '查看视频',
                onTap: _isOpening ? null : _openSavedVideo,
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildShareButton(bool shareEnabled) {
    return Semantics(
      button: true,
      enabled: shareEnabled,
      label: _isSharing ? '正在分享视频' : '分享视频',
      child: Tooltip(
        message: _isSharing ? '正在分享视频' : '分享视频',
        child: GestureDetector(
          key: const ValueKey('result-share-action'),
          behavior: HitTestBehavior.opaque,
          onTap: shareEnabled ? _shareVideo : null,
          child: AnimatedOpacity(
            duration: const Duration(milliseconds: 120),
            opacity: shareEnabled ? 1 : 0.46,
            child: Container(
              height: 52,
              decoration: BoxDecoration(
                gradient: AppTheme.coralActionGradient,
                borderRadius: BorderRadius.circular(18),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x38F44848),
                    blurRadius: 16,
                    offset: Offset(0, 6),
                  ),
                ],
              ),
              child: Center(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (_isSharing)
                      const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2.2,
                          color: Colors.white,
                        ),
                      )
                    else
                      const Icon(
                        Icons.ios_share_rounded,
                        color: Colors.white,
                        size: 20,
                      ),
                    const SizedBox(width: 8),
                    Text(
                      _isSharing ? '正在打开分享…' : '分享视频',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.3,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSecondaryButton({
    required Key key,
    required IconData icon,
    required String label,
    required VoidCallback? onTap,
  }) {
    final enabled = onTap != null;
    return Semantics(
      button: true,
      enabled: enabled,
      label: label,
      child: Tooltip(
        message: label,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            key: key,
            onTap: enabled ? onTap : null,
            borderRadius: BorderRadius.circular(16),
            child: Ink(
              height: 46,
              decoration: BoxDecoration(
                color: AppTheme.warmSurface,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: AppTheme.warmBorder, width: 1.0),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x0A000000),
                    blurRadius: 6,
                    offset: Offset(0, 2),
                  ),
                ],
              ),
              child: Center(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      icon,
                      size: 18,
                      color: AppTheme.warmTextPrimary,
                    ),
                    const SizedBox(width: 6),
                    Text(
                      label,
                      style: const TextStyle(
                        color: AppTheme.warmTextPrimary,
                        fontSize: 13.5,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSaveStatus(String? fileSizeMb) {
    final String title;
    final String subtitle;
    final String statusLabel;

    if (_isSaving) {
      title = '正在保存到相册';
      subtitle = '保存完成后即可直接分享';
      statusLabel = '保存中';
    } else if (_isSaved) {
      title = '已保存至系统相册';
      subtitle = fileSizeMb == null
          ? '视频已安全脱敏存储'
          : '视频已安全脱敏 · $fileSizeMb MB';
      statusLabel = '脱敏完成';
    } else if (_saveError != null) {
      title = '尚未存入系统相册';
      subtitle = '视频数据保留在设备中，可重新保存';
      statusLabel = '保存失败';
    } else {
      title = '视频处理完成';
      subtitle = '正在准备写入媒体库';
      statusLabel = '就绪';
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: AppTheme.warmSurface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppTheme.warmBorder),
        boxShadow: const [
          BoxShadow(
            color: Color(0x0A000000),
            blurRadius: 14,
            offset: Offset(0, 4),
          ),
        ],
      ),
      child: Row(
        children: [
          if (_isSaving)
            const SizedBox(
              width: 40,
              height: 40,
              child: Padding(
                padding: EdgeInsets.all(8),
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
                  color: AppTheme.coral,
                ),
              ),
            )
          else if (_saveError != null)
            Container(
              width: 40,
              height: 40,
              decoration: const BoxDecoration(
                color: AppTheme.coralPale,
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.error_outline_rounded,
                size: 22,
                color: AppTheme.coral,
              ),
            )
          else
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: const Color(0xFFEAF5EE),
                shape: BoxShape.circle,
                border: Border.all(
                  color: const Color(0xFFC3E4CD),
                  width: 1.0,
                ),
              ),
              child: const Icon(
                Icons.check_rounded,
                size: 22,
                color: Color(0xFF2E7D46),
              ),
            ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: const TextStyle(
                    color: AppTheme.warmTextSecondary,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          if (_saveError != null)
            TextButton(
              onPressed: _isSaving ? null : () => _saveToGallery(),
              child: const Text('重新保存'),
            )
          else
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
              decoration: BoxDecoration(
                color: const Color(0xFFEAF5EE),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                statusLabel,
                style: const TextStyle(
                  color: Color(0xFF2E7D46),
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
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
                    color: AppTheme.warmTextMuted,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    isExporting ? '正在导出…' : '导出诊断包',
                    style: const TextStyle(
                      fontSize: 11,
                      color: AppTheme.warmTextMuted,
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
