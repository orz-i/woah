import 'package:flutter/foundation.dart';

/// Only genuine standalone workflows are included in the Home registry.
/// Editor-level abilities are intentionally absent.
enum HomeToolLaunch { videoProtection, danceComparison }

class HomeTool {
  const HomeTool({
    required this.id,
    required this.title,
    required this.description,
    required this.launch,
  });

  final String id;
  final String title;
  final String description;
  final HomeToolLaunch launch;
}

abstract final class HomeToolCatalog {
  static const standalone = <HomeTool>[
    HomeTool(
      id: 'video-protection',
      title: '导入视频',
      description: '从设备中选择视频',
      launch: HomeToolLaunch.videoProtection,
    ),
    HomeTool(
      id: 'dance-comparison',
      title: '舞蹈对比',
      description: '按共同音乐对比老师与自己',
      launch: HomeToolLaunch.danceComparison,
    ),
  ];
  static List<HomeTool> forPlatform(TargetPlatform platform) =>
      platform == TargetPlatform.android
      ? standalone
      : standalone
            .where((tool) => tool.launch != HomeToolLaunch.danceComparison)
            .toList();
}
