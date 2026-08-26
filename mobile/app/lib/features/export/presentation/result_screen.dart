import 'dart:io';
import 'package:flutter/material.dart';
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
  String? _savedUri;

  @override
  void initState() {
    super.initState();
    // Do not auto-save to gallery; user can click the save button manually
  }

  Future<void> _saveToGallery() async {
    final outputPath = widget.exportState.outputUri;
    if (outputPath == null || outputPath.isEmpty || _isSaved || _isSaving) return;

    setState(() => _isSaving = true);
    try {
      final uri = await ref.read(nativeRepositoryProvider).saveVideoToGallery(outputPath);
      if (mounted) {
        setState(() {
          _isSaving = false;
          _isSaved = true;
          _savedUri = uri;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            backgroundColor: Colors.green,
            content: Text('🎉 视频已成功保存至系统相册 (Movies/DanceAnon)！'),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
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
        title: const Text('导出成功 (Export Complete)'),
        leading: IconButton(
          icon: const Icon(Icons.home_outlined),
          onPressed: () => context.go('/'),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 1. Preview Player
              if (outputPath.isNotEmpty)
                VideoPreviewPlayer(
                  videoPath: outputPath,
                  aspectRatio: project?.videoInfo.aspectRatio ?? (16 / 9),
                ),
              const SizedBox(height: 16),

              // 2. Summary Card
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(Icons.check_circle, color: Colors.greenAccent),
                          const SizedBox(width: 8),
                          Text(
                            '打码视频已生成',
                            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                          ),
                        ],
                      ),
                      const Divider(height: 24),
                      _buildRow('输出文件大小', '$fileSizeMb MB'),
                      const SizedBox(height: 8),
                      _buildRow(
                        '输出分辨率',
                        '${project?.videoInfo.width ?? 1920} × ${project?.videoInfo.height ?? 1080}',
                      ),
                      const SizedBox(height: 8),
                      _buildRow('处理平均帧率', '${widget.exportState.fps.toStringAsFixed(1)} FPS'),
                      const SizedBox(height: 8),
                      _buildRow('已遮挡人物数', '${project?.selectedPersonIds.length ?? 0} 人'),
                      const SizedBox(height: 8),
                      _buildRow('音频保留', project?.videoInfo.hasAudio == true ? '无损 AAC' : '无'),
                      if (_savedUri != null) ...[
                        const SizedBox(height: 8),
                        _buildRow('相册位置', '系统相册 (Movies/DanceAnon)'),
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
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                      )
                    : Icon(_isSaved ? Icons.check_circle_outline : Icons.save_alt_rounded),
                label: Text(
                  _isSaving
                      ? '正在写入系统相册...'
                      : _isSaved
                          ? '已成功存至系统相册 (Movies/DanceAnon)'
                          : '保存到系统相册 (Save to MediaStore)',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                style: ElevatedButton.styleFrom(
                  backgroundColor: _isSaved ? const Color(0xFF22C55E) : Colors.white,
                  foregroundColor: _isSaved ? Colors.white : Colors.black,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () => context.go('/'),
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('制作下一个舞蹈视频'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: const TextStyle(color: Colors.white60, fontSize: 13)),
        Text(value, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
      ],
    );
  }
}
