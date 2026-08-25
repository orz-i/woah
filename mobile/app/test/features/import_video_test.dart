import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:app/features/import_video/presentation/widgets/video_metadata_card.dart';

void main() {
  group('VideoMetadataCard Widget Tests', () {
    testWidgets('Displays horizontal video parameters properly', (tester) async {
      const horizontalInfo = VideoInfo(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1920,
        displayHeight: 1080,
        fps: 29.97,
        durationMs: 15400,
        rotation: 0,
        videoCodec: 'video/avc',
        audioCodec: 'audio/mp4a-latm',
        hasAudio: true,
      );

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: VideoMetadataCard(
              info: horizontalInfo,
              fileName: 'dance_clip_01.mp4',
            ),
          ),
        ),
      );

      expect(find.text('dance_clip_01.mp4'), findsOneWidget);
      expect(find.text('横屏'), findsOneWidget);
      expect(find.text('1920 × 1080'), findsOneWidget);
      expect(find.text('30.0 fps'), findsOneWidget);
      expect(find.text('15.4 秒'), findsOneWidget);
      expect(find.text('H.264 (AVC)'), findsOneWidget);
      expect(find.text('AAC'), findsOneWidget);
    });

    testWidgets('Displays rotated vertical video warning and auto-corrected resolution', (tester) async {
      const verticalRotatedInfo = VideoInfo(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1080,
        displayHeight: 1920,
        fps: 60.0,
        durationMs: 8200,
        rotation: 90,
        videoCodec: 'video/hevc',
        hasAudio: false,
      );

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: VideoMetadataCard(
              info: verticalRotatedInfo,
              fileName: 'phone_recorded_dance.mp4',
            ),
          ),
        ),
      );

      expect(find.text('phone_recorded_dance.mp4'), findsOneWidget);
      expect(find.text('竖屏'), findsOneWidget);
      expect(find.text('1080 × 1920'), findsOneWidget);
      expect(find.text('90°'), findsOneWidget);
      expect(find.text('60.0 fps'), findsOneWidget);
      expect(find.text('H.265 (HEVC)'), findsOneWidget);
      expect(find.text('无音频'), findsOneWidget);
      expect(find.textContaining('检测到录制朝向 90°'), findsOneWidget);
    });
  });
}
