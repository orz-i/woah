import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../domain/export_state.dart';
import '../../../repositories/native_processing_repository.dart';
import '../../import_video/presentation/widgets/video_preview_player.dart';

class ResultScreen extends ConsumerStatefulWidget {
  final ExportState exportState;

  const ResultScreen({
    super.key,
    required this.exportState,
  });

  @override
  ConsumerState<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends ConsumerState<ResultScreen> {
  bool _isSaving = false;
  bool _isSaved = false;
  bool _isExportingDiagnostics = false;
  String? _savedUri;

  @override
  void initState() {
    super.initState();
  }

  Future<void> _exportDiagnostics() async {
    if (_isExportingDiagnostics) return;

    HapticFeedback.mediumImpact();
    setState(() => _isExportingDiagnostics = true);
    try {
      final repo = ref.read(nativeRepositoryProvider);
      final bundle = await repo.createDiagnosticBundle();
      final fileName = bundle?['fileName'] as String? ?? 'diagnostic_bundle.zip';
      final filePath = bundle?['filePath'] as String?;
      final publicUri = bundle?['publicUri'] as String?;

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('完整导出诊断包已生成: $fileName'),
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
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('导出诊断包失败: $e'),
            backgroundColor: Colors.redAccent,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _isExportingDiagnostics = false);
      }
    }
  }

  Future<void> _saveToGallery() async {
    final outputPath = widget.exportState.outputUri;
    if (outputPath == null || outputPath.isEmpty || _isSaved || _isSaving) return;

    HapticFeedback.mediumImpact();
    setState(() => _isSaving = true);
    try {
      final uri = await ref.read(nativeRepositoryProvider).saveVideoToGallery(outputPath);
      if (mounted) {
        HapticFeedback.heavyImpact();
        setState(() {
          _isSaving = false;
          _isSaved = true;
          _savedUri = uri;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            backgroundColor: Color(0xFF22C55E),
            duration: Duration(seconds: 3),
            content: Text('🎉 视频已成功保存至系统相册 (Movies/DanceAnon)！', style: TextStyle(color: Colors.white)),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        HapticFeedback.lightImpact();
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            backgroundColor: Colors.redAccent,
            content: Text('保存至相册失败: $e'),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final outputPath = widget.exportState.outputUri ?? '';
    final project = widget.exportState.project;
    final file = File(outputPath);
    final fileSizeMb = file.existsSync()
        ? (file.lengthSync() / (1024 * 1024)).toStringAsFixed(1)
        : '0.0';

    return Scaffold(
      appBar: AppBar(
        title: const Text('导出完成'),
        leading: IconButton(
          icon: const Icon(Icons.home_outlined),
          onPressed: () {
            HapticFeedback.lightImpact();
            context.go('/');
          },
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 1. Preview Player with ambient shadow
              if (outputPath.isNotEmpty)
                Container(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withAlpha(150),
                        blurRadius: 24,
                        offset: const Offset(0, 8),
                      ),
                    ],
                  ),
                  child: VideoPreviewPlayer(
                    videoPath: outputPath,
                    aspectRatio: project?.videoInfo.aspectRatio ?? (16 / 9),
                  ),
                ),
              const SizedBox(height: 16),

              // 2. Summary 2x2 Grid Card
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.all(6),
                            decoration: BoxDecoration(
                              color: const Color(0xFF22C55E).withAlpha(25),
                              shape: BoxShape.circle,
                            ),
                            child: const Icon(Icons.check_circle_rounded, color: Color(0xFF22C55E), size: 18),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            '视频已处理就绪',
                            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                  color: Colors.white,
                                ),
                          ),
                        ],
                      ),
                      const Divider(height: 24),
                      Row(
                        children: [
                          Expanded(child: _buildMetricTile('文件大小', '$fileSizeMb MB', Icons.data_usage_rounded)),
                          const SizedBox(width: 12),
                          Expanded(
                            child: _buildMetricTile(
                              '输出分辨率',
                              '${project?.videoInfo.width ?? 1920}×${project?.videoInfo.height ?? 1080}',
                              Icons.aspect_ratio_rounded,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: _buildMetricTile(
                              '处理人物数',
                              '${project?.privacyTargetIds.length ?? 0} 位人物',
                              Icons.person_rounded,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: _buildMetricTile(
                              '原音轨保留',
                              project?.videoInfo.hasAudio == true ? '高清 AAC' : '无音频',
                              Icons.audiotrack_rounded,
                            ),
                          ),
                        ],
                      ),
                      if (_savedUri != null) ...[
                        const SizedBox(height: 12),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          decoration: BoxDecoration(
                            color: const Color(0xFF22C55E).withAlpha(15),
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(color: const Color(0xFF22C55E).withAlpha(40)),
                          ),
                          child: Row(
                            children: const [
                              Icon(Icons.photo_library_rounded, size: 16, color: Color(0xFF22C55E)),
                              SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  '已存储至系统相册 (Movies/DanceAnon)',
                                  style: TextStyle(fontSize: 12, color: Color(0xFF22C55E), fontWeight: FontWeight.w600),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),

              // 3. Action Buttons
              ElevatedButton.icon(
                onPressed: _isSaving ? null : _saveToGallery,
                icon: _isSaving
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                      )
                    : Icon(_isSaved ? Icons.check_circle_rounded : Icons.save_alt_rounded),
                label: Text(
                  _isSaving
                      ? '正在写入系统相册...'
                      : _isSaved
                          ? '已成功存入系统相册'
                          : '保存到系统相册 (Save to MediaStore)',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                style: ElevatedButton.styleFrom(
                  backgroundColor: _isSaved ? const Color(0xFF22C55E) : Colors.white,
                  foregroundColor: _isSaved ? Colors.white : Colors.black,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: _isExportingDiagnostics ? null : _exportDiagnostics,
                icon: _isExportingDiagnostics
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.bug_report_outlined),
                label: Text(
                  _isExportingDiagnostics ? '正在生成诊断包...' : '导出并分享完整诊断包',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                style: OutlinedButton.styleFrom(
                  foregroundColor: const Color(0xFF60A5FA),
                  side: const BorderSide(color: Color(0xFF3B82F6)),
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () {
                  HapticFeedback.mediumImpact();
                  context.go('/');
                },
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('制作下一个视频'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildMetricTile(String label, String value, IconData icon) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E24),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withAlpha(15)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 14, color: Colors.white54),
              const SizedBox(width: 6),
              Text(label, style: const TextStyle(fontSize: 11, color: Colors.white54)),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: Colors.white),
          ),
        ],
      ),
    );
  }
}
