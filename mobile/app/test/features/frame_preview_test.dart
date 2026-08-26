import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:app/features/frame_preview/presentation/frame_preview_screen.dart';

void main() {
  group('FramePreviewScreen Tests', () {
    final testProject = DanceProject(
      id: 'proj_preview_test',
      sourceUri: 'file:///test.mp4',
      analysisCacheId: 'cache_preview_123',
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
      selectedPersonIds: {0, 1},
      effects: const EffectConfig(
        fillMode: FillMode.mosaic,
        opacity: 0.90,
        borderWidth: 6.0,
      ),
      videoInfo: const VideoInfo(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1920,
        displayHeight: 1080,
        fps: 30,
        durationMs: 5000,
        rotation: 0,
        videoCodec: 'video/avc',
        hasAudio: true,
      ),
    );

    testWidgets('Renders preview screen layout and effect summaries correctly', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: FramePreviewScreen(project: testProject),
          ),
        ),
      );

      await tester.pump();

      // Check app bar title
      expect(find.text('首帧效果确认'), findsOneWidget);

      // Check summary chips
      expect(find.text('样式: 像素马赛克'), findsOneWidget);
      expect(find.text('透明度: 90%'), findsOneWidget);
      expect(find.text('描边: 6px'), findsOneWidget);
      expect(find.text('规格: 1920×1080'), findsOneWidget);
      expect(find.text('选中保护目标: 2 人'), findsOneWidget);

      // Check actions
      expect(find.text('返回调整'), findsOneWidget);
      expect(find.text('确认效果并开始导出'), findsOneWidget);
      expect(find.text('支持双指放大查看打码边缘'), findsOneWidget);
    });
  });
}
