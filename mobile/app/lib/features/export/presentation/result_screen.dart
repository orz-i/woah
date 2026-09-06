import 'dart:io';

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

  Future<void> _copySavedUri() async {
    if (!_isSaved || _savedUri == null || _savedUri!.isEmpty) {
      await _saveToGallery();
      if (!_isSaved || _savedUri == null || _savedUri!.isEmpty) return;
    }

    HapticFeedback.lightImpact();
    await Clipboard.setData(ClipboardData(text: _savedUri!));
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('保存地址已复制')));
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
          child: Stack(
            children: [
              SingleChildScrollView(
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(18, 14, 18, 190),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (outputPath.isNotEmpty)
                      Container(
                        decoration: BoxDecoration(
                          color: Colors.black,
                          borderRadius: BorderRadius.circular(28),
                          border: Border.all(color: AppTheme.warmBorder),
                          boxShadow: const [
                            BoxShadow(
                              color: Color(0x16000000),
                              blurRadius: 26,
                              offset: Offset(0, 8),
                            ),
                          ],
                        ),
                        clipBehavior: Clip.antiAlias,
                        child: VideoPreviewPlayer(
                          videoPath: outputPath,
                          aspectRatio: project?.videoInfo.aspectRatio ?? 16 / 9,
                        ),
                      ),
                    const SizedBox(height: 18),
                    _buildSaveStatus(fileSizeMb),
                    const SizedBox(height: 26),
                    const Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.shield_outlined,
                          size: 15,
                          color: AppTheme.warmTextMuted,
                        ),
                        SizedBox(width: 6),
                        Text(
                          '视频已安全保存到你的设备',
                          style: TextStyle(
                            color: AppTheme.warmTextMuted,
                            fontSize: 11,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: 8,
                child: _buildSuccessActionCluster(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSuccessActionCluster() {
    final shareEnabled = !_isSharing && !_isSaving && _saveError == null;
    return Center(
      child: SizedBox(
        width: 286,
        height: 166,
        child: Stack(
          alignment: Alignment.bottomCenter,
          children: [
            Positioned(
              left: 10,
              bottom: 16,
              child: _ResultSatelliteAction(
                key: const ValueKey('result-next-action'),
                icon: Icons.add_rounded,
                tooltip: '制作下一个',
                onTap: () {
                  HapticFeedback.mediumImpact();
                  context.go('/');
                },
              ),
            ),
            Positioned(
              left: 54,
              bottom: 88,
              child: _ResultSatelliteAction(
                key: const ValueKey('result-open-action'),
                icon: Icons.folder_open_rounded,
                tooltip: _isOpening ? '正在打开' : '查看文件',
                onTap: _isOpening ? null : _openSavedVideo,
              ),
            ),
            Positioned(
              right: 54,
              bottom: 88,
              child: _ResultSatelliteAction(
                key: const ValueKey('result-copy-action'),
                icon: Icons.link_rounded,
                tooltip: '复制保存地址',
                onTap: _isSaving ? null : _copySavedUri,
              ),
            ),
            Positioned(
              right: 10,
              bottom: 16,
              child: _ResultSatelliteAction(
                key: const ValueKey('result-diagnostics-action'),
                icon: Icons.bug_report_outlined,
                tooltip: _isExportingDiagnostics ? '正在准备诊断包' : '导出诊断包',
                onTap: _isExportingDiagnostics ? null : _exportDiagnostics,
              ),
            ),
            Positioned(
              bottom: 0,
              child: Semantics(
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
                        width: 70,
                        height: 70,
                        decoration: const BoxDecoration(
                          gradient: AppTheme.coralActionGradient,
                          shape: BoxShape.circle,
                          boxShadow: [
                            BoxShadow(
                              color: Color(0x38F44848),
                              blurRadius: 22,
                              offset: Offset(0, 9),
                            ),
                          ],
                        ),
                        child: Center(
                          child: _isSharing
                              ? const SizedBox(
                                  width: 22,
                                  height: 22,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2.2,
                                    color: Colors.white,
                                  ),
                                )
                              : const Icon(
                                  Icons.ios_share_rounded,
                                  color: Colors.white,
                                  size: 30,
                                ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSaveStatus(String? fileSizeMb) {
    final IconData icon;
    final String title;
    final String subtitle;

    if (_isSaving) {
      icon = Icons.downloading_rounded;
      title = '正在保存到相册';
      subtitle = '保存完成后即可直接分享';
    } else if (_isSaved) {
      icon = Icons.check_circle_outline_rounded;
      title = '已自动保存到相册';
      subtitle = fileSizeMb == null
          ? '视频已安全保存到系统媒体库'
          : '视频已安全保存 · $fileSizeMb MB';
    } else if (_saveError != null) {
      icon = Icons.error_outline_rounded;
      title = '尚未保存到相册';
      subtitle = '成品仍保留在应用中，可以重新保存';
    } else {
      icon = Icons.check_circle_outline_rounded;
      title = '视频处理完成';
      subtitle = '正在准备保存';
    }

    final statusLabel = _isSaving
        ? '保存中'
        : _isSaved
        ? '保存成功'
        : _saveError != null
        ? '保存失败'
        : '处理中';
    final statusColor = _saveError != null
        ? AppTheme.coral
        : const Color(0xFF5EA56B);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      decoration: BoxDecoration(
        color: AppTheme.warmSurface,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: AppTheme.warmBorder),
        boxShadow: const [
          BoxShadow(
            color: Color(0x10000000),
            blurRadius: 20,
            offset: Offset(0, 7),
          ),
        ],
      ),
      child: Row(
        children: [
          if (_isSaving)
            const SizedBox(
              width: 44,
              height: 44,
              child: Padding(
                padding: EdgeInsets.all(10),
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
                  color: AppTheme.coral,
                ),
              ),
            )
          else
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                gradient: _saveError == null
                    ? AppTheme.coralActionGradient
                    : null,
                color: _saveError != null ? AppTheme.coralPale : null,
                shape: BoxShape.circle,
              ),
              child: Icon(
                icon,
                size: 24,
                color: _saveError != null ? AppTheme.coral : Colors.white,
              ),
            ),
          const SizedBox(width: 13),
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
                const SizedBox(height: 3),
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
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: statusColor.withAlpha(24),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Text(
                statusLabel,
                style: TextStyle(
                  color: statusColor,
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

class _ResultSatelliteAction extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback? onTap;

  const _ResultSatelliteAction({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Semantics(
      button: true,
      enabled: enabled,
      label: tooltip,
      child: Tooltip(
        message: tooltip,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 120),
          opacity: enabled ? 1 : 0.42,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: enabled ? onTap : null,
            child: Container(
              width: 50,
              height: 50,
              decoration: BoxDecoration(
                color: AppTheme.warmSurface.withValues(alpha: 0.96),
                shape: BoxShape.circle,
                border: Border.all(color: AppTheme.warmBorder, width: 1.2),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x1E000000),
                    blurRadius: 14,
                    offset: Offset(0, 5),
                  ),
                ],
              ),
              child: Icon(
                icon,
                size: 22,
                color: AppTheme.warmTextPrimary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
