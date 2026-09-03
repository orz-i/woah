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

    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        title: const Text('处理完成'),
        leading: IconButton(
          tooltip: '返回首页',
          icon: const Icon(Icons.home_outlined),
          onPressed: () {
            HapticFeedback.lightImpact();
            context.go('/');
          },
        ),
        actions: [
          PopupMenuButton<String>(
            tooltip: '更多',
            icon: const Icon(Icons.more_horiz_rounded),
            color: AppTheme.surfaceElevated,
            onSelected: (value) {
              if (value == 'diagnostics') _exportDiagnostics();
            },
            itemBuilder: (context) => [
              PopupMenuItem(
                value: 'diagnostics',
                enabled: !_isExportingDiagnostics,
                child: const ListTile(
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
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (outputPath.isNotEmpty)
                Container(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(AppTheme.radiusLarge),
                    border: Border.all(color: AppTheme.surfaceBorder),
                    boxShadow: const [
                      BoxShadow(
                        color: Color(0x60000000),
                        blurRadius: 22,
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
              const SizedBox(height: 20),
              SizedBox(
                height: 54,
                child: ElevatedButton.icon(
                  onPressed: _isSharing || _isSaving || _saveError != null
                      ? null
                      : _shareVideo,
                  icon: _isSharing
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: AppTheme.canvas,
                          ),
                        )
                      : const Icon(Icons.ios_share_rounded, size: 20),
                  label: Text(_isSharing ? '正在打开分享…' : '分享视频'),
                ),
              ),
              if (_saveError != null) ...[
                const SizedBox(height: 10),
                SizedBox(
                  height: 52,
                  child: ElevatedButton.icon(
                    onPressed: _isSaving ? null : () => _saveToGallery(),
                    icon: const Icon(Icons.save_alt_rounded, size: 19),
                    label: const Text('重新保存到相册'),
                  ),
                ),
              ],
              const SizedBox(height: 10),
              SizedBox(
                height: 52,
                child: OutlinedButton.icon(
                  onPressed: () {
                    HapticFeedback.mediumImpact();
                    context.go('/');
                  },
                  icon: const Icon(Icons.add_rounded, size: 19),
                  label: const Text('制作下一个'),
                ),
              ),
            ],
          ),
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
      title = '已保存到相册';
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

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
      decoration: AppTheme.panelDecoration(radius: 16),
      child: Row(
        children: [
          if (_isSaving)
            const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else
            Icon(
              icon,
              size: 24,
              color: _saveError != null ? AppTheme.error : AppTheme.metalHigh,
            ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppTheme.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  subtitle,
                  style: const TextStyle(
                    color: AppTheme.textMuted,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
