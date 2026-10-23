import 'package:flutter/material.dart';

/// A registry entry is an action only when [launch] is non-null.
/// Editor-only capabilities must not masquerade as standalone flows.
enum HomeToolLaunch { videoProtection }

class HomeTool {
  const HomeTool({
    required this.id,
    required this.title,
    required this.description,
    required this.artworkIcon,
    required this.badgeIcon,
    this.launch,
  });

  final String id;
  final String title;
  final String description;
  final IconData artworkIcon;
  final IconData badgeIcon;
  final HomeToolLaunch? launch;

  bool get canLaunch => launch != null;
}

abstract final class HomeToolCatalog {
  /// Only truly standalone workflows belong in the homepage carousel.
  static const standalone = <HomeTool>[
    HomeTool(
      id: 'video-protection',
      title: '视频隐私保护',
      description: '智能识别人物，专注记录舞台上的你',
      artworkIcon: Icons.person_outline_rounded,
      badgeIcon: Icons.shield_outlined,
      launch: HomeToolLaunch.videoProtection,
    ),
  ];

  /// These currently live in the unified protection editor, after import.
  static const editorFeatures = <HomeTool>[
    HomeTool(
      id: 'follow-crop',
      title: '智能裁切',
      description: '跟随主角调整画面构图',
      artworkIcon: Icons.crop_rounded,
      badgeIcon: Icons.crop_rounded,
    ),
    HomeTool(
      id: 'face-sticker',
      title: '人脸贴纸',
      description: '使用自定义图片遮挡人脸',
      artworkIcon: Icons.face_retouching_natural_rounded,
      badgeIcon: Icons.face_retouching_natural_rounded,
    ),
    HomeTool(
      id: 'subject-effects',
      title: '人物特效',
      description: '全身、人脸保护与拉腿',
      artworkIcon: Icons.auto_awesome_rounded,
      badgeIcon: Icons.auto_awesome_rounded,
    ),
  ];
}
