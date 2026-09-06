import 'package:app/core/metadata/woah_build_info.dart';
import 'package:app/features/import_video/presentation/import_video_screen.dart';
import 'package:app/features/import_video/presentation/woah_easter_egg_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('easter egg shows author build commit and word cloud', (
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

    expect(find.text('CREATED BY'), findsOneWidget);
    expect(find.text('CJ'), findsOneWidget);
    expect(find.text('v2.3.4'), findsOneWidget);
    expect(find.text('#57'), findsOneWidget);
    expect(find.text('RELEASE'), findsOneWidget);
    expect(find.text('abcdef123456'), findsOneWidget);
    expect(find.text('DANCE'), findsOneWidget);
    expect(find.text('PRIVACY'), findsOneWidget);
    expect(find.text('DETERMINISTIC'), findsOneWidget);
    expect(find.text('设备与诊断'), findsNothing);
    expect(find.text('加速能力'), findsNothing);
    expect(find.byKey(WoahEasterEggScreen.closeButtonKey), findsOneWidget);
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
      await tester.pumpAndSettle();

      expect(find.text('CREATED BY'), findsOneWidget);
      expect(find.byKey(WoahEasterEggScreen.closeButtonKey), findsOneWidget);
      expect(find.text('设备与诊断'), findsNothing);

      await tester.tap(find.byKey(WoahEasterEggScreen.closeButtonKey));
      await tester.pumpAndSettle();

      expect(find.text('导入舞段'), findsOneWidget);
      expect(find.text('CREATED BY'), findsNothing);
    },
  );
}

class _NoopNativeRepository implements NativeProcessingRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
