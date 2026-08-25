import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_native/dance_native.dart';
import '../../../repositories/native_processing_repository.dart';

final capabilitiesProvider = FutureProvider<NativeCapabilitiesDto>((ref) async {
  final repo = ref.watch(nativeRepositoryProvider);
  return repo.getCapabilities();
});

class ImportVideoScreen extends ConsumerWidget {
  const ImportVideoScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final capsAsync = ref.watch(capabilitiesProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dance Anonymizer'),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(Icons.memory, color: Colors.purpleAccent),
                          const SizedBox(width: 8),
                          Text(
                            '原生引擎状态 (Native Engine)',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      capsAsync.when(
                        data: (caps) => Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _buildInfoRow('Android API', '${caps.androidApi}'),
                            _buildInfoRow('GPU 支持', caps.gpuSupported ? '是 (OpenGL ES 3.0)' : '否'),
                            _buildInfoRow('H.264 编码器', caps.h264Encoder ? '支持' : '不支持'),
                            _buildInfoRow('HEVC 编码器', caps.hevcEncoder ? '支持' : '不支持'),
                            _buildInfoRow('CPU 核心数', '${caps.cpuCores} 核心'),
                            _buildInfoRow('推荐性能档位', caps.recommendedProfile.toUpperCase()),
                          ],
                        ),
                        loading: () => const Row(
                          children: [
                            SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                            SizedBox(width: 8),
                            Text('正在检测硬件能力...'),
                          ],
                        ),
                        error: (err, _) => Text(
                          '无法连接 Native 插件: $err',
                          style: const TextStyle(color: Colors.redAccent),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const Spacer(),
              ElevatedButton.icon(
                onPressed: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('Phase 1 将接入视频文件选择器与 MediaCodec 解析'),
                    ),
                  );
                },
                icon: const Icon(Icons.video_library),
                label: const Text('导入舞蹈视频 (Phase 1)'),
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(color: Colors.white70)),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}
