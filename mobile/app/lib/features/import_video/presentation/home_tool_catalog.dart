import 'package:flutter/foundation.dart';

/// Only genuine standalone workflows are included in the Home registry.
/// Editor-level abilities are intentionally absent.
enum HomeToolLaunch { videoProtection, danceComparison }

class HomeTool {
  const HomeTool({
    required this.id,
    required this.title,
    required this.description,
    required this.coverAsset,
    required this.launch,
  });

  final String id;
  final String title;
  final String description;
  final String coverAsset;
  final HomeToolLaunch launch;
}

abstract final class HomeToolCatalog {
  static const standalone = <HomeTool>[
    HomeTool(
      id: 'video-protection',
      title: '视频保护',
      description: '保留主角，遮挡其他人物',
      coverAsset: 'assets/home/home-video-protection.jpg',
      launch: HomeToolLaunch.videoProtection,
    ),
    HomeTool(
      id: 'dance-comparison',
      title: '舞蹈对比',
      description: '同步两段视频，对照练习动作',
      coverAsset: 'assets/home/home-dance-comparison.jpg',
      launch: HomeToolLaunch.danceComparison,
    ),
  ];
  static List<HomeTool> forPlatform(TargetPlatform platform) =>
      platform == TargetPlatform.android || platform == TargetPlatform.iOS
      ? standalone
      : standalone
            .where((tool) => tool.launch != HomeToolLaunch.danceComparison)
            .toList();
}
