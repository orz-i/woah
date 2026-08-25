import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_native/dance_native.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';
import 'widgets/video_metadata_card.dart';
import 'widgets/video_preview_player.dart';

final capabilitiesProvider = FutureProvider<NativeCapabilitiesDto>((ref) async {
  final repo = ref.watch(nativeRepositoryProvider);
  return repo.getCapabilities();
});

class ImportVideoScreen extends ConsumerWidget {
  const ImportVideoScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final capsAsync = ref.watch(capabilitiesProvider);
    final importState = ref.watch(importVideoControllerProvider);
    final controller = ref.read(importVideoControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dance Anonymizer'),
        actions: [
          if (importState.isReady)
            IconButton(
              icon: const Icon(Icons.refresh),
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
              // 1. Hardware capabilities card
              _buildCapabilitiesCard(context, capsAsync),
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

  Widget _buildCapabilitiesCard(
    BuildContext context,
    AsyncValue<NativeCapabilitiesDto> capsAsync,
  ) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.memory, color: Colors.purpleAccent, size: 20),
                const SizedBox(width: 8),
                Text(
                  '原生引擎与硬件状态',
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            capsAsync.when(
              data: (caps) => Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  _buildCapsBadge('API', '${caps.androidApi}'),
                  _buildCapsBadge('GPU', caps.gpuSupported ? 'GLES 3.0' : 'No'),
                  _buildCapsBadge('H.264', caps.h264Encoder ? '支持' : '不支持'),
                  _buildCapsBadge('CPU', '${caps.cpuCores}核'),
                  _buildCapsBadge('Profile', caps.recommendedProfile.toUpperCase()),
                ],
              ),
              loading: () => const Row(
                children: [
                  SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  SizedBox(width: 8),
                  Text('检测设备能力中...', style: TextStyle(fontSize: 12)),
                ],
              ),
              error: (err, _) => Text(
                'Native 连接异常: $err',
                style: const TextStyle(color: Colors.redAccent, fontSize: 12),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCapsBadge(String label, String value) {
    return Column(
      children: [
        Text(label, style: const TextStyle(fontSize: 10, color: Colors.white54)),
        const SizedBox(height: 2),
        Text(value, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
      ],
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
              color: Colors.redAccent.withAlpha(40),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: Colors.redAccent.withAlpha(100)),
            ),
            child: Row(
              children: [
                const Icon(Icons.error_outline, color: Colors.redAccent),
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
              color: const Color(0xFF1D1B20),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: Colors.purpleAccent.withAlpha(60),
                width: 1.5,
                strokeAlign: BorderSide.strokeAlignInside,
              ),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  padding: const EdgeInsets.all(18),
                  decoration: BoxDecoration(
                    color: Colors.purpleAccent.withAlpha(30),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(
                    Icons.video_library_rounded,
                    size: 48,
                    color: Colors.purpleAccent,
                  ),
                ),
                const SizedBox(height: 16),
                Text(
                  '选择舞蹈视频',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                ),
                const SizedBox(height: 6),
                const Text(
                  '支持 MP4 / MOV / H.264 / HEVC，自动处理手机旋转朝向',
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
        ? '正在选取本地视频...'
        : '正在通过 Native MediaExtractor 深度解析视频规格...';

    return Container(
      height: 240,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: const Color(0xFF1D1B20),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const CircularProgressIndicator(),
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

        // 3. Next step button
        ElevatedButton.icon(
          onPressed: () {
            final project = controller.createProject();
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text(
                  '成功创建项目: ${project?.id} (${info.width}x${info.height})，准备进入 Phase 2/3 首帧人物分析',
                ),
              ),
            );
          },
          icon: const Icon(Icons.person_search_rounded),
          label: const Text(
            '下一步：首帧人物分析 (Phase 2/3)',
            style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
          ),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.deepPurpleAccent,
            foregroundColor: Colors.white,
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
