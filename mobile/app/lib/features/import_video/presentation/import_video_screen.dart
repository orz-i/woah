import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_native/dance_native.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';
import 'widgets/video_preview_player.dart';

final capabilitiesProvider = FutureProvider<NativeCapabilitiesDto>((ref) async {
  final repo = ref.watch(nativeRepositoryProvider);
  return repo.getCapabilities();
});

class ImportVideoScreen extends ConsumerWidget {
  const ImportVideoScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final importState = ref.watch(importVideoControllerProvider);
    final controller = ref.read(importVideoControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dance Anonymizer'),
        leading: Builder(
          builder: (scaffoldContext) => IconButton(
            icon: const Icon(Icons.info_outline_rounded),
            tooltip: '系统与设备信息 (右滑可查看)',
            onPressed: () {
              HapticFeedback.lightImpact();
              Scaffold.of(scaffoldContext).openDrawer();
            },
          ),
        ),
        actions: [
          if (importState.isReady)
            IconButton(
              icon: const Icon(Icons.refresh_rounded),
              tooltip: '重新选择视频',
              onPressed: () {
                HapticFeedback.mediumImpact();
                controller.reset();
              },
            ),
        ],
      ),
      drawer: _buildSystemInfoDrawer(context, ref),
      body: Builder(
        builder: (scaffoldContext) => GestureDetector(
          behavior: HitTestBehavior.translucent,
          onHorizontalDragEnd: (details) {
            // Swipe right to open system info drawer
            if (details.primaryVelocity != null && details.primaryVelocity! > 250) {
              HapticFeedback.lightImpact();
              Scaffold.of(scaffoldContext).openDrawer();
            }
          },
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 18.0, vertical: 12.0),
              child: Center(
                child: SingleChildScrollView(
                  physics: const BouncingScrollPhysics(),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
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
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSystemInfoDrawer(BuildContext context, WidgetRef ref) {
    final capsAsync = ref.watch(capabilitiesProvider);

    return Drawer(
      backgroundColor: const Color(0xFF0D0D10),
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: Colors.white.withAlpha(15),
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white24),
                    ),
                    child: const Icon(Icons.memory_rounded, color: Colors.white, size: 22),
                  ),
                  const SizedBox(width: 12),
                  const Text(
                    '系统与设备信息',
                    style: TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              const Text(
                '当前设备硬件加速与引擎运行参数',
                style: TextStyle(fontSize: 12, color: Colors.white54),
              ),
              const Divider(height: 28),
              Expanded(
                child: capsAsync.when(
                  data: (caps) => ListView(
                    physics: const BouncingScrollPhysics(),
                    children: [
                      _buildDrawerSectionTitle('系统规格与环境'),
                      _buildInfoTile('系统版本', '${caps.platform.toUpperCase()} ${caps.osVersion}'),
                      _buildInfoTile('处理器核心 (CPU)', '${caps.cpuCores} 核心'),
                      _buildInfoTile('性能配置档位', caps.recommendedProfile.toUpperCase()),
                      const SizedBox(height: 16),
                      _buildDrawerSectionTitle('图形与加速能力'),
                      _buildInfoTile('图形渲染 (GPU)', caps.gpuSupported ? 'GLES 3.0 硬件加速' : 'CPU 软解'),
                      _buildInfoTile('硬件编解码 (H.264)', caps.h264Encoder ? '支持 (MediaCodec)' : '未启用'),
                      _buildInfoTile('零内存拷贝管线', 'Zero-Copy OES/GLES'),
                      const SizedBox(height: 16),
                      _buildDrawerSectionTitle('软件与引擎'),
                      _buildInfoTile('核心 AI 引擎', 'ONNX Runtime Native'),
                      _buildInfoTile('应用版本', 'v1.0.0 (Pro Studio)'),
                    ],
                  ),
                  loading: () => const Center(
                    child: CircularProgressIndicator(color: Colors.white),
                  ),
                  error: (err, _) => Center(
                    child: Text(
                      '无法获取设备信息: $err',
                      style: const TextStyle(color: Colors.redAccent, fontSize: 13),
                    ),
                  ),
                ),
              ),
              const Divider(),
              Center(
                child: Text(
                  '向左滑动或点击空白处关闭',
                  style: TextStyle(fontSize: 12, color: Colors.white.withAlpha(80)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDrawerSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8.0, top: 4.0),
      child: Text(
        title,
        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Colors.white38, letterSpacing: 0.5),
      ),
    );
  }

  Widget _buildInfoTile(String title, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(title, style: const TextStyle(fontSize: 13, color: Colors.white70)),
          Text(
            value,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Colors.white),
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
      mainAxisSize: MainAxisSize.min,
      children: [
        if (errorMessage != null) ...[
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.redAccent.withAlpha(20),
              borderRadius: BorderRadius.circular(14),
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
        Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: () {
              HapticFeedback.mediumImpact();
              controller.pickAndProbeVideo();
            },
            borderRadius: BorderRadius.circular(28),
            splashColor: Colors.white.withAlpha(20),
            highlightColor: Colors.white.withAlpha(10),
            child: Container(
              height: 290,
              width: double.infinity,
              decoration: BoxDecoration(
                color: const Color(0xFF131316),
                borderRadius: BorderRadius.circular(28),
                border: Border.all(
                  color: Colors.white.withAlpha(25),
                  width: 1.2,
                  strokeAlign: BorderSide.strokeAlignInside,
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withAlpha(120),
                    blurRadius: 20,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    padding: const EdgeInsets.all(22),
                    decoration: BoxDecoration(
                      color: Colors.white.withAlpha(12),
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white24, width: 1.5),
                    ),
                    child: const Icon(
                      Icons.video_library_rounded,
                      size: 48,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    '选择舞蹈视频',
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                          letterSpacing: -0.3,
                        ),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    '支持常见视频格式，自动校准画面方向',
                    style: TextStyle(fontSize: 13, color: Colors.white54),
                  ),
                ],
              ),
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
      height: 280,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: const Color(0xFF131316),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: Colors.white.withAlpha(20)),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const SizedBox(
            width: 44,
            height: 44,
            child: CircularProgressIndicator(color: Colors.white, strokeWidth: 3),
          ),
          const SizedBox(height: 20),
          Text(text, style: const TextStyle(fontSize: 14, color: Colors.white70, fontWeight: FontWeight.w500)),
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
    final durationSec = (info.durationMs / 1000.0).toStringAsFixed(1);
    final isVertical = info.height > info.width;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        // 1. Video Player Container with Shadow
        Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withAlpha(150),
                blurRadius: 24,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Stack(
            children: [
              VideoPreviewPlayer(
                videoPath: videoPath,
                aspectRatio: info.aspectRatio,
              ),
              // Floating Video Info Badges
              Positioned(
                top: 12,
                left: 12,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                  decoration: BoxDecoration(
                    color: Colors.black.withAlpha(180),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.white24),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        isVertical ? Icons.stay_current_portrait_rounded : Icons.stay_current_landscape_rounded,
                        size: 14,
                        color: Colors.white70,
                      ),
                      const SizedBox(width: 5),
                      Text(
                        '${info.width}×${info.height} · ${info.fps.toStringAsFixed(0)}fps · $durationSec秒',
                        style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),

        // 2. Next step button (Pure white on black)
        ElevatedButton.icon(
          onPressed: () {
            HapticFeedback.mediumImpact();
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
            elevation: 2,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),
      ],
    );
  }
}
