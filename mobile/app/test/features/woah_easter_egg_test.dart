import 'package:app/features/import_video/presentation/import_video_screen.dart';
import 'package:app/features/import_video/presentation/woah_easter_egg_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('Wavi reveals and cycles short messages by tapping the mascot', (
    tester,
  ) async {
    await tester.pumpWidget(MaterialApp(home: const WoahEasterEggScreen()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1));

    expect(find.text('嘿，你找到这里了。'), findsOneWidget);
    expect(find.text('轻触小精灵，开启一份小惊喜'), findsNothing);
    expect(find.text('WOAH  /  A LITTLE MOMENT'), findsNothing);
    expect(find.textContaining('免费开源'), findsNothing);
    expect(find.textContaining('art.gaoge.dance'), findsNothing);
    expect(find.text('不用每一步都完美'), findsNothing);
    expect(find.byKey(WoahEasterEggScreen.messageKey), findsNothing);
    expect(find.text('点一点星星'), findsNothing);
    expect(find.byType(FilledButton), findsNothing);
    _expectPose(tester, WoahEasterEggScreen.idleAsset);

    await tester.tap(find.byKey(WoahEasterEggScreen.mascotTapKey));
    await tester.pump();
    _expectPose(tester, WoahEasterEggScreen.peekAsset);
    expect(find.text('Wavi 正在转身…'), findsNothing);

    // Duplicate taps while transitioning cannot skip a surprise.
    await tester.tap(find.byKey(WoahEasterEggScreen.mascotTapKey));
    await tester.pump(const Duration(milliseconds: 280));
    await tester.pumpAndSettle();
    expect(find.text('不用每一步都完美'), findsOneWidget);
    expect(find.text('跟着自己的节奏就好。'), findsOneWidget);
    expect(find.textContaining('再轻触一次'), findsNothing);
    _expectPose(tester, WoahEasterEggScreen.joyAsset);

    await tester.tap(find.byKey(WoahEasterEggScreen.mascotTapKey));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(find.text('今天也有新的舞步'), findsOneWidget);
    expect(find.textContaining('02/05'), findsNothing);
    _expectPose(tester, WoahEasterEggScreen.waveAsset);

    expect(find.textContaining('WOAH  ·  CJ'), findsNothing);
    expect(find.textContaining('art.gaoge.dance'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Wavi stays usable on a small viewport with enlarged text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: const TextScaler.linear(1.6)),
          child: child!,
        ),
        home: const WoahEasterEggScreen(),
      ),
    );
    await tester.pump();
    expect(find.byType(SingleChildScrollView), findsOneWidget);
    expect(find.text('嘿，你找到这里了。'), findsOneWidget);
    await tester.ensureVisible(find.byKey(WoahEasterEggScreen.mascotTapKey));
    await tester.tap(find.byKey(WoahEasterEggScreen.mascotTapKey));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(find.text('不用每一步都完美'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('reduced motion reveals immediately with no looping animation', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(disableAnimations: true),
          child: const WoahEasterEggScreen(),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(WoahEasterEggScreen.mascotTapKey));
    await tester.pump();
    expect(find.text('不用每一步都完美'), findsOneWidget);
    expect(find.text('Wavi 正在转身…'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('long pressing the home Woah enters Wavi page and back exits', (
    tester,
  ) async {
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
    expect(find.text('嘿，你找到这里了。'), findsOneWidget);
    expect(find.byKey(WoahEasterEggScreen.mascotTapKey), findsOneWidget);
    expect(find.byTooltip('返回首页'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.text('视频保护'), findsOneWidget);
    expect(find.text('嘿，你找到这里了。'), findsNothing);
  });
}

void _expectPose(WidgetTester tester, String assetName) {
  final images = tester.widgetList<Image>(
    find.byKey(WoahEasterEggScreen.mascotKey),
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
