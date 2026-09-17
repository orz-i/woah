import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';

import '../../../../app/theme.dart';

class VideoMetadataCard extends StatelessWidget {
  final VideoInfo info;
  final String? fileName;

  const VideoMetadataCard({super.key, required this.info, this.fileName});

  @override
  Widget build(BuildContext context) {
    final isVertical = info.height > info.width;
    final durationSec = (info.durationMs / 1000.0).toStringAsFixed(1);

    return Container(
      key: const ValueKey('video-metadata-card'),
      decoration: BoxDecoration(
        color: AppTheme.flowSurface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppTheme.flowBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(
                  Icons.movie_outlined,
                  color: AppTheme.flowTextSecondary,
                  size: 20,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    fileName ?? '视频基本信息',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: AppTheme.flowTextPrimary,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: AppTheme.flowSurfaceSoft,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: AppTheme.flowBorder),
                  ),
                  child: Text(
                    isVertical ? '竖屏' : '横屏',
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: AppTheme.flowTextPrimary,
                    ),
                  ),
                ),
              ],
            ),
            const Divider(height: 24),
            _buildGridRow(
              context,
              icon1: Icons.aspect_ratio,
              label1: '画面分辨率',
              value1: '${info.width} × ${info.height}',
              icon2: Icons.screen_rotation,
              label2: '拍摄方向',
              value2: '${info.rotation}°',
            ),
            const SizedBox(height: 12),
            _buildGridRow(
              context,
              icon1: Icons.speed,
              label1: '画面流畅度',
              value1: '${info.fps.toStringAsFixed(1)} fps',
              icon2: Icons.timer_outlined,
              label2: '视频时长',
              value2: '$durationSec 秒',
            ),
            const SizedBox(height: 12),
            _buildGridRow(
              context,
              icon1: Icons.video_file_outlined,
              label1: '视频格式',
              value1: _formatCodec(info.videoCodec),
              icon2: Icons.audiotrack,
              label2: '音频声道',
              value2: info.hasAudio
                  ? (info.audioCodec != null
                        ? _formatCodec(info.audioCodec!)
                        : '包含音频')
                  : '无音频',
            ),
            if (info.rotation != 0) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppTheme.flowSurfaceSoft,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppTheme.flowBorder),
                ),
                child: Row(
                  children: [
                    const Icon(
                      Icons.info_outline,
                      color: AppTheme.flowTextSecondary,
                      size: 18,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '检测到录制朝向 ${info.rotation}°，画面已自动校准为最佳播放方向。',
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppTheme.flowTextSecondary,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildGridRow(
    BuildContext context, {
    required IconData icon1,
    required String label1,
    required String value1,
    required IconData icon2,
    required String label2,
    required String value2,
  }) {
    return Row(
      children: [
        Expanded(child: _buildItem(context, icon1, label1, value1)),
        const SizedBox(width: 12),
        Expanded(child: _buildItem(context, icon2, label2, value2)),
      ],
    );
  }

  Widget _buildItem(
    BuildContext context,
    IconData icon,
    String label,
    String value,
  ) {
    return Row(
      children: [
        Icon(icon, size: 18, color: AppTheme.flowTextMuted),
        const SizedBox(width: 6),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: const TextStyle(
                fontSize: 11,
                color: AppTheme.flowTextMuted,
              ),
            ),
            Text(
              value,
              style: const TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: AppTheme.flowTextPrimary,
              ),
            ),
          ],
        ),
      ],
    );
  }

  String _formatCodec(String mime) {
    if (mime.contains('avc')) return 'H.264 (AVC)';
    if (mime.contains('hevc')) return 'H.265 (HEVC)';
    if (mime.contains('mp4a')) return 'AAC';
    return mime.split('/').last.toUpperCase();
  }
}
