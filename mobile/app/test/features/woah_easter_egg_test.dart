import 'package:app/core/metadata/woah_build_info.dart';
import 'package:app/features/import_video/presentation/import_video_screen.dart';
import 'package:app/features/import_video/presentation/woah_easter_egg_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('easter egg shows rolling credits and build metadata', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: WoahEasterEggScreen(
          buildInfoLoader: () async => const WoahBuildInfo(
            versionName: '2.3.4',
            buildNumber: '57',
            gitCommit: 'abcdef1234567890',
            buildType: 'release',
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('作者  CJ'), findsOneWidget);
    expect(find.text('本程序免费开源'), findsOneWidget);
    expect(find.text('谨防上当受骗'), findsOneWidget);
    expect(find.text('版本 · v2.3.4'), findsOneWidget);
    expect(find.text('构建 · #57'), findsOneWidget);
    expect(find.text('构建类型 · RELEASE'), findsOneWidget);
    expect(find.text('提交 · abcdef123456'), findsOneWidget);
    expect(find.text('处理 · LOCAL FIRST'), findsOneWidget);
    expect(find.text('隐私 · PRIVATE BY DESIGN'), findsOneWidget);
    expect(find.text('设备与诊断'), findsNothing);
    expect(find.text('加速能力'), findsNothing);
    expect(find.byKey(WoahEasterEggScreen.creditsRollKey), findsOneWidget);
    expect(find.byKey(WoahEasterEggScreen.closeButtonKey), findsOneWidget);

    final initialTop = tester.getTopLeft(find.text('作者  CJ')).dy;
    await tester.pump(const Duration(seconds: 2));
    final movedTop = tester.getTopLeft(find.text('作者  CJ')).dy;
    expect(movedTop, lessThan(initialTop));
  });

  testWidgets(
    'long pressing home Woah opens full-screen easter egg and closes',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            nativeRepositoryProvider.overrideWithValue(_NoopNativeRepository()),
          ],
          child: const MaterialApp(home: ImportVideoScreen()),
        ),
      );
      await tester.pump();

      expect(find.text('导入舞段'), findsOneWidget);
      expect(find.text('设备与诊断'), findsNothing);

      await tester.longPress(find.text('Woah'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('作者  CJ'), findsOneWidget);
      expect(find.text('本程序免费开源'), findsOneWidget);
      expect(find.text('谨防上当受骗'), findsOneWidget);
      expect(find.byKey(WoahEasterEggScreen.closeButtonKey), findsOneWidget);
      expect(find.text('设备与诊断'), findsNothing);

      await tester.tap(find.byKey(WoahEasterEggScreen.closeButtonKey));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 700));

      expect(find.text('导入舞段'), findsOneWidget);
      expect(find.text('作者  CJ'), findsNothing);
    },
  );
}

class _NoopNativeRepository implements NativeProcessingRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
