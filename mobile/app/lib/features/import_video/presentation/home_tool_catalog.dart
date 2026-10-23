/// Only genuine standalone workflows are included in the Home registry.
/// Editor-level abilities are intentionally absent.
enum HomeToolLaunch { videoProtection }

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
  ];
}
