import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';
import 'widgets/video_metadata_card.dart';
import 'widgets/video_preview_player.dart';

class ImportVideoScreen extends ConsumerWidget {
  const ImportVideoScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final importState = ref.watch(importVideoControllerProvider);
    final controller = ref.read(importVideoControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dance Anonymizer'),
        actions: [
          if (importState.isReady)
            IconButton(
              icon: const Icon(Icons.refresh_rounded),
              tooltip: '重新选择',
              onPressed: () => controller.reset(),
            ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 1. App Introduction / Feature Header Banner
              _buildFeatureHeader(context),
              const SizedBox(height: 16),

              // 2. Main content area
              if (importState.isLoading)
                _buildLoadingCard(context, importState.status)
              else if (importState.isReady)
                _buildReadyView(context, importState, controller)
              else
                _buildImportPlaceholder(context, controller, importState.errorMessage),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFeatureHeader(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: const Color(0xFF131316),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFF2E2E34)),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Colors.white.withAlpha(12),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.shield_outlined, size: 22, color: Colors.white),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: const [
                Text(
                  '智能舞蹈视频人物隐私保护',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: Colors.white),
                ),
                SizedBox(height: 3),
                Text(
                  '精准识别人体轮廓 · 动态遮挡 · 保留原始高清画质',
                  style: TextStyle(fontSize: 12, color: Colors.white54),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildImportPlaceholder(
    BuildContext context,
    ImportVideoController controller,
    String? errorMessage,
  ) {
    return Column(
      children: [
        if (errorMessage != null) ...[
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.redAccent.withAlpha(20),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: Colors.redAccent.withAlpha(60)),
            ),
            child: Row(
              children: [
                const Icon(Icons.error_outline_rounded, color: Colors.redAccent, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    errorMessage,
                    style: const TextStyle(color: Colors.redAccent, fontSize: 13),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
        ],
        InkWell(
          onTap: () => controller.pickAndProbeVideo(),
          borderRadius: BorderRadius.circular(20),
          child: Container(
            height: 240,
            width: double.infinity,
            decoration: BoxDecoration(
              color: const Color(0xFF131316),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: Colors.white.withAlpha(30),
                width: 1.2,
                strokeAlign: BorderSide.strokeAlignInside,
              ),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: Colors.white.withAlpha(15),
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.white24),
                  ),
                  child: const Icon(
                    Icons.video_library_rounded,
                    size: 44,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 16),
                Text(
                  '选择舞蹈视频',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                ),
                const SizedBox(height: 6),
                const Text(
                  '支持常见视频格式，自动校准画面方向',
                  style: TextStyle(fontSize: 12, color: Colors.white54),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildLoadingCard(BuildContext context, VideoImportStatus status) {
    final text = status == VideoImportStatus.picking
        ? '正在选取视频...'
        : '正在解析视频信息...';

    return Container(
      height: 240,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: const Color(0xFF131316),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withAlpha(20)),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const CircularProgressIndicator(color: Colors.white),
          const SizedBox(height: 16),
          Text(text, style: const TextStyle(fontSize: 14, color: Colors.white70)),
        ],
      ),
    );
  }

  Widget _buildReadyView(
    BuildContext context,
    VideoImportState state,
    ImportVideoController controller,
  ) {
    final info = state.videoInfo!;
    final videoPath = state.videoPath!;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 1. Video Player
        VideoPreviewPlayer(
          videoPath: videoPath,
          aspectRatio: info.aspectRatio,
        ),
        const SizedBox(height: 16),

        // 2. Metadata Card
        VideoMetadataCard(
          info: info,
          fileName: state.videoName,
        ),
        const SizedBox(height: 24),

        // 3. Next step button (Pure white on black)
        ElevatedButton.icon(
          onPressed: () {
            final project = controller.createProject();
            if (project != null) {
              context.push('/person_selection', extra: project);
            }
          },
          icon: const Icon(Icons.person_search_rounded, size: 20),
          label: const Text(
            '下一步：智能识别人物',
            style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
          ),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.white,
            foregroundColor: Colors.black,
            padding: const EdgeInsets.symmetric(vertical: 16),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
            ),
          ),
        ),
      ],
    );
  }
}
