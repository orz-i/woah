import 'package:app/app/theme.dart';
import 'package:app/features/import_video/presentation/import_video_screen.dart';
import 'package:app/features/import_video/presentation/widgets/video_metadata_card.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets(
    'home preserves its video protection card alongside standalone workflows',
    (tester) async {
      final semantics = tester.ensureSemantics();

      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            theme: AppTheme.darkTheme,
            home: const ImportVideoScreen(),
          ),
        ),
      );
      await tester.pump();

      expect(find.text('Woah'), findsOneWidget);
      expect(find.text('记录每一个闪闪发光的你'), findsNothing);
      expect(find.text('视频保护'), findsOneWidget);
      expect(find.text('保留主角，遮挡其他人物'), findsOneWidget);
      expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
      final cardMaterial = tester.widget<Material>(
        find
            .descendant(
              of: find.byKey(const ValueKey('home-card-elevation')),
              matching: find.byType(Material),
            )
            .first,
      );
      expect(cardMaterial.color, AppTheme.darkColors.flowSurface);
      expect(find.byKey(const ValueKey('home-privacy-cover')), findsOneWidget);
      expect(
        find.byKey(const ValueKey('home-comparison-cover')),
        findsOneWidget,
      );
      final elevation = tester.widget<DecoratedBox>(
        find.byKey(const ValueKey('home-card-elevation')),
      );
      final elevationDecoration = elevation.decoration as BoxDecoration;
      expect(elevationDecoration.border, isNull);
      expect(elevationDecoration.boxShadow, hasLength(2));
      expect(
        elevationDecoration.boxShadow!.every((shadow) => shadow.offset.dy > 0),
        isTrue,
      );
      expect(find.bySemanticsLabel('视频保护，保留主角，遮挡其他人物'), findsOneWidget);
      expect(find.byIcon(Icons.close_rounded), findsNothing);
      expect(find.text('人脸贴纸'), findsNothing);
      expect(find.text('智能裁切'), findsNothing);
      expect(find.byKey(const ValueKey('home-buddy')), findsNothing);
      expect(
        tester.widget<Scaffold>(find.byType(Scaffold)).backgroundColor,
        AppTheme.flowBackground,
      );
      expect(tester.takeException(), isNull);
      semantics.dispose();
    },
  );

  for (final size in [const Size(320, 568), const Size(360, 640)]) {
    testWidgets('home fits compact phone viewport $size', (tester) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            theme: AppTheme.darkTheme,
            home: const ImportVideoScreen(),
          ),
        ),
      );
      await tester.pump();

      expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
      expect(find.text('保留主角，遮挡其他人物'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  group('VideoMetadataCard Widget Tests', () {
    testWidgets('Displays horizontal video parameters properly', (
      tester,
    ) async {
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
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: const Scaffold(
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

      final metadataCard = tester.widget<Container>(
        find.byKey(const ValueKey('video-metadata-card')),
      );
      final decoration = metadataCard.decoration! as BoxDecoration;
      expect(decoration.color, AppTheme.flowSurface);
      expect(decoration.border?.top.color, AppTheme.flowBorder);
    });

    testWidgets(
      'Displays rotated vertical video warning and auto-corrected resolution',
      (tester) async {
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
          MaterialApp(
            theme: AppTheme.darkTheme,
            home: const Scaffold(
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
      },
    );
  });
}
