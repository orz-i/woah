import 'package:app/core/metadata/woah_build_info.dart';
import 'package:app/features/import_video/presentation/import_video_screen.dart';
import 'package:app/features/import_video/presentation/woah_easter_egg_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('easter egg reveals and cycles surprises with pose switching', (
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
    await tester.pump(const Duration(milliseconds: 1));

    expect(find.text('彩蛋时间'), findsOneWidget);
    expect(find.text('点一点星星'), findsOneWidget);
    expect(find.text('你已经很棒了'), findsNothing);
    expect(find.byKey(WoahEasterEggScreen.revealCardKey), findsNothing);
    _expectBuddyAsset(tester, WoahEasterEggScreen.idleAsset);

    await tester.tap(find.byKey(WoahEasterEggScreen.primaryActionKey));
    await tester.pump();
    expect(find.text('小伙伴正在打开惊喜…'), findsOneWidget);
    _expectBuddyAsset(tester, WoahEasterEggScreen.playfulAsset);

    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('你已经很棒了'), findsOneWidget);
    expect(find.text('继续做自己吧！'), findsOneWidget);
    expect(find.text('再看一个彩蛋'), findsOneWidget);
    expect(find.byKey(WoahEasterEggScreen.revealCardKey), findsOneWidget);
    _expectBuddyAsset(tester, WoahEasterEggScreen.happyAsset);

    await tester.tap(find.byKey(WoahEasterEggScreen.primaryActionKey));
    await tester.pump();
    _expectBuddyAsset(tester, WoahEasterEggScreen.waveAsset);

    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('愿每个舞动'), findsOneWidget);
    expect(find.text('都被温柔保护。'), findsOneWidget);
    _expectBuddyAsset(tester, WoahEasterEggScreen.happyAsset);

    expect(find.text('Woah  ·  CJ  ·  v2.3.4  ·  #57'), findsOneWidget);
    expect(find.text('abcdef123456  ·  art.gaoge.dance'), findsOneWidget);
    expect(find.byIcon(Icons.close_rounded), findsNothing);
  });

  testWidgets('easter egg stays usable on a compact phone viewport', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        home: WoahEasterEggScreen(
          buildInfoLoader: () async => const WoahBuildInfo(
            versionName: '0.1.0',
            buildNumber: '1',
            gitCommit: 'development',
            buildType: 'debug',
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1));

    expect(find.text('彩蛋时间'), findsOneWidget);
    expect(find.text('点一点星星'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'long pressing home Woah opens full-screen easter egg and system back exits',
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

      await tester.longPress(find.text('Woah'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('彩蛋时间'), findsOneWidget);
      expect(find.text('点一点星星'), findsOneWidget);
      expect(find.byIcon(Icons.close_rounded), findsNothing);

      await tester.binding.handlePopRoute();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 700));

      expect(find.text('导入舞段'), findsOneWidget);
      expect(find.text('彩蛋时间'), findsNothing);
    },
  );
}

void _expectBuddyAsset(WidgetTester tester, String assetName) {
  final images = tester.widgetList<Image>(
    find.byKey(WoahEasterEggScreen.buddyKey),
  );
  expect(images, isNotEmpty);
  final names = images
      .map((image) => image.image)
      .whereType<AssetImage>()
      .map((provider) => provider.assetName)
      .toSet();
  expect(names, contains(assetName));
}

class _NoopNativeRepository implements NativeProcessingRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
