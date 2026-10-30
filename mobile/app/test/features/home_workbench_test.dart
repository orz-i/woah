import 'dart:async';
import 'dart:typed_data';

import 'package:app/app/theme.dart';
import 'package:app/app/brand.dart';
import 'package:app/features/import_video/domain/video_import_state.dart';
import 'package:app/features/import_video/presentation/home_tool_catalog.dart';
import 'package:app/features/import_video/presentation/import_video_controller.dart';
import 'package:app/features/import_video/presentation/import_video_screen.dart';
import 'package:app/features/protection_editor/presentation/protection_editor_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

base class _FakePlatformFile extends PlatformFile {
  _FakePlatformFile(this.name, String localPath) : uri = Uri.file(localPath);

  @override
  final String name;

  @override
  final Uri uri;

  @override
  Never get xFile => throw UnimplementedError('File contents not used');

  @override
  int? lengthSync() => 1;

  @override
  Future<int?> length() async => 1;

  @override
  Future<Uint8List> readAsBytes() async => Uint8List(0);

  @override
  Stream<Uint8List> readAsByteStream() => const Stream.empty();
}

class _FakeVideoPicker extends FilePickerPlatform {
  int calls = 0;
  Future<List<PlatformFile>>? response;

  @override
  Future<List<PlatformFile>> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    int compressionQuality = 0,
    AndroidOptions androidOptions = const AndroidOptions(),
    DarwinOptions darwinOptions = const DarwinOptions(),
    WindowsOptions windowsOptions = const WindowsOptions(),
    LinuxOptions linuxOptions = const LinuxOptions(),
    WebOptions webOptions = const WebOptions(),
  }) {
    expect(type, FileType.video);
    calls++;
    return response ?? Future.value([]);
  }
}

class _FakeProbeRepository extends NativeProcessingRepository {
  _FakeProbeRepository() : super(DanceNativeClient());

  int probes = 0;
  bool fail = false;

  @override
  Future<VideoInfo> probeVideo(String uri) async {
    probes++;
    if (fail) throw StateError('invalid media');
    expect(uri.replaceAll(String.fromCharCode(92), '/'), '/fake/dance.mp4');
    return const VideoInfo(
      codedWidth: 1920,
      codedHeight: 1080,
      displayWidth: 1920,
      displayHeight: 1080,
      fps: 30,
      durationMs: 24000,
      rotation: 0,
      videoCodec: 'video/avc',
      hasAudio: false,
    );
  }
}

GoRouter _testRouter() => GoRouter(
  routes: [
    GoRoute(path: '/', builder: (_, _) => const ImportVideoScreen()),
    GoRoute(
      path: '/comparison',
      builder: (_, _) => const Scaffold(body: Text('TEST_COMPARISON')),
    ),
    GoRoute(
      path: '/protection_editor',
      builder: (context, state) {
        final args = state.extra! as ProtectionEditorArgs;
        return Scaffold(
          body: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('TEST_PROTECTION_EDITOR'),
                Text(args.project.sourceUri),
                TextButton(
                  onPressed: () => context.pop(),
                  child: const Text('返回首页'),
                ),
              ],
            ),
          ),
        );
      },
    ),
  ],
);

Future<void> _pumpWorkbench(
  WidgetTester tester,
  GoRouter router, {
  NativeProcessingRepository? repository,
  double textScale = 1,
  TargetPlatform platform = TargetPlatform.android,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        if (repository != null)
          nativeRepositoryProvider.overrideWithValue(repository),
      ],
      child: MaterialApp.router(
        theme: AppTheme.darkTheme.copyWith(platform: platform),
        routerConfig: router,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  test(
    'canceling a replacement does not reuse the previously selected video',
    () async {
      final original = FilePickerPlatform.instance;
      FilePickerPlatform.instance = _FakeVideoPicker();
      addTearDown(() => FilePickerPlatform.instance = original);
      final repository = _FakeProbeRepository();
      final controller = ImportVideoController(repository);
      addTearDown(controller.dispose);
      await controller.probeVideoPath('/fake/dance.mp4');
      expect(await controller.pickAndProbeVideo(), isFalse);
      expect(controller.state.videoPath, '/fake/dance.mp4');
      expect(controller.state.isReady, isTrue);
      expect(repository.probes, 1);
    },
  );

  test('catalog never promotes in-editor capabilities to launchable cards', () {
    expect(HomeToolCatalog.standalone, isNotEmpty);
    expect(HomeToolCatalog.standalone.length, 2);
    final all = [...HomeToolCatalog.standalone];
    expect(all.map((tool) => tool.id).toSet().length, all.length);
  });

  test('comparison is available on iOS and Android only', () {
    for (final platform in TargetPlatform.values) {
      expect(
        HomeToolCatalog.forPlatform(
          platform,
        ).any((tool) => tool.launch == HomeToolLaunch.danceComparison),
        platform == TargetPlatform.iOS || platform == TargetPlatform.android,
        reason: platform.name,
      );
    }
  });

  testWidgets('iOS workbench launches the comparison workflow', (tester) async {
    final router = _testRouter();
    addTearDown(router.dispose);
    await _pumpWorkbench(tester, router, platform: TargetPlatform.iOS);
    expect(find.byType(PageView), findsOneWidget);
    expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
    await tester.drag(find.byType(PageView), const Offset(-300, 0));
    await tester.pumpAndSettle();
    expect(find.text('舞蹈对比'), findsOneWidget);
    expect(find.byKey(const ValueKey('home-comparison-cover')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('home-comparison-card')));
    await tester.pumpAndSettle();
    expect(find.text('TEST_COMPARISON'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('desktop workbench keeps comparison hidden', (tester) async {
    final router = _testRouter();
    addTearDown(router.dispose);
    await _pumpWorkbench(tester, router, platform: TargetPlatform.macOS);
    expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
    expect(find.byKey(const ValueKey('home-comparison-card')), findsNothing);
    expect(find.byType(PageView), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('standalone workflows exclude editor-internal tools', (
    tester,
  ) async {
    final router = _testRouter();
    addTearDown(router.dispose);
    await _pumpWorkbench(tester, router);
    expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
    expect(find.byType(PageView), findsOneWidget);
    expect(
      find.byKey(const ValueKey('home-card-page-indicator')),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('home-privacy-cover')), findsOneWidget);
    await tester.drag(find.byType(PageView), const Offset(-300, 0));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-comparison-cover')), findsOneWidget);
    expect(find.text('全部工具'), findsNothing);
    expect(find.text('编辑器内功能'), findsNothing);
    expect(find.text('智能裁切'), findsNothing);
    expect(find.text('人脸贴纸'), findsNothing);
    expect(find.text('人物特效'), findsNothing);
    expect(find.text(AppBrand.english), findsOneWidget);
    expect(find.text(AppBrand.chinese), findsNothing);
    expect(find.text('视频保护'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('photo cards retain side peek and pager at 390 x 844', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final router = _testRouter();
    addTearDown(router.dispose);
    await _pumpWorkbench(tester, router, platform: TargetPlatform.iOS);
    await tester.pumpAndSettle();

    expect(find.byType(PageView), findsOneWidget);
    final first = tester.getRect(
      find.byKey(const ValueKey('home-card-elevation')),
    );
    expect(first.width, lessThan(340));
    expect(first.height, greaterThan(250));
    expect(first.left, greaterThan(0));
    expect(first.right, lessThan(390));
    // The next card must visibly peek into the viewport before any swipe.
    final nextCard = tester.getRect(
      find.byKey(const ValueKey('home-comparison-elevation')),
    );
    expect(nextCard.left, greaterThan(first.right));
    expect(nextCard.left, lessThan(390));
    expect(
      find.byKey(const ValueKey('home-card-page-indicator')),
      findsOneWidget,
    );
    expect(
      tester.getSize(find.byKey(const ValueKey('home-card-page-dot-0'))).width,
      23,
    );
    expect(
      tester.getSize(find.byKey(const ValueKey('home-card-page-dot-1'))).width,
      11,
    );
    expect(find.byKey(const ValueKey('home-privacy-cover')), findsOneWidget);
    final privacyCover = tester.getRect(
      find.byKey(const ValueKey('home-privacy-cover')),
    );
    expect(privacyCover.size, first.size);
    expect(
      find.byKey(const ValueKey('home-privacy-text-overlay')),
      findsOneWidget,
    );
    final privacyTitleBottom = tester.getRect(find.text('视频保护')).bottom;
    final privacySubtitleBottom = tester
        .getRect(find.text('保留主角，遮挡其他人物'))
        .bottom;
    expect(
      tester.widget<Text>(find.text('视频保护')).style?.color,
      AppTheme.brandPearl,
    );

    await tester.drag(find.byType(PageView), const Offset(-300, 0));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-comparison-cover')), findsOneWidget);
    final comparison = tester.getRect(
      find.byKey(const ValueKey('home-comparison-elevation')),
    );
    final comparisonCover = tester.getRect(
      find.byKey(const ValueKey('home-comparison-cover')),
    );
    expect(comparisonCover.size, comparison.size);
    expect(comparison.size, first.size);
    expect(
      find.byKey(const ValueKey('home-comparison-text-overlay')),
      findsOneWidget,
    );
    // Both photo covers fill their cards. Native text shares the same
    // fixed bottom anchor, rather than sitting in separate color blocks.
    expect(
      tester.getRect(find.text('舞蹈对比')).bottom,
      closeTo(privacyTitleBottom, 0.5),
    );
    expect(
      tester.getRect(find.text('同步两段视频，对照练习动作')).bottom,
      closeTo(privacySubtitleBottom, 0.5),
    );
    expect(
      tester.widget<Text>(find.text('舞蹈对比')).style?.color,
      AppTheme.brandPearl,
    );
    expect(
      find.byKey(const ValueKey('home-comparison-card')).hitTestable(),
      findsOneWidget,
    );
    final previous = tester.getRect(
      find.byKey(const ValueKey('home-card-elevation')),
    );
    expect(previous.right, greaterThan(0));
    expect(previous.right, lessThan(390));
    expect(
      tester.getSize(find.byKey(const ValueKey('home-card-page-dot-0'))).width,
      11,
    );
    expect(
      tester.getSize(find.byKey(const ValueKey('home-card-page-dot-1'))).width,
      23,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('comparison remains accessible by swiping on compact 320 x 568', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final router = _testRouter();
    addTearDown(router.dispose);
    await _pumpWorkbench(tester, router, platform: TargetPlatform.android);
    await tester.drag(find.byType(PageView), const Offset(-250, 0));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('home-comparison-card')));
    await tester.pumpAndSettle();
    expect(find.text('TEST_COMPARISON'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('single import completes and resets after editor', (
    tester,
  ) async {
    final original = FilePickerPlatform.instance;
    final picker = _FakeVideoPicker()
      ..response = Future.value([
        _FakePlatformFile('dance.mp4', '/fake/dance.mp4'),
      ]);
    FilePickerPlatform.instance = picker;
    addTearDown(() => FilePickerPlatform.instance = original);
    final repo = _FakeProbeRepository();
    final router = _testRouter();
    addTearDown(router.dispose);
    await _pumpWorkbench(tester, router, repository: repo);

    await tester.tap(find.byKey(const ValueKey('home-import-card')));
    await tester.pumpAndSettle();
    expect(picker.calls, 1);
    expect(repo.probes, 1);
    expect(find.text('TEST_PROTECTION_EDITOR'), findsOneWidget);
    await tester.tap(find.text('返回首页'));
    await tester.pumpAndSettle();
    expect(
      ProviderScope.containerOf(
        tester.element(find.byType(ImportVideoScreen)),
      ).read(importVideoControllerProvider).status,
      VideoImportStatus.idle,
    );
    expect(find.text('视频保护'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('picker cannot be launched twice while busy; cancel stays home', (
    tester,
  ) async {
    final original = FilePickerPlatform.instance;
    final pending = Completer<List<PlatformFile>>();
    final picker = _FakeVideoPicker()..response = pending.future;
    FilePickerPlatform.instance = picker;
    addTearDown(() => FilePickerPlatform.instance = original);
    final router = _testRouter();
    addTearDown(router.dispose);

    await _pumpWorkbench(tester, router);
    await tester.tap(find.byKey(const ValueKey('home-import-card')));
    await tester.pump();
    expect(find.text('正在导入视频…'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('home-import-card')));
    expect(picker.calls, 1);
    pending.complete([]);
    await tester.pumpAndSettle();
    expect(picker.calls, 1);
    expect(find.text('视频保护'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed probe shows an error without navigating to editor', (
    tester,
  ) async {
    final original = FilePickerPlatform.instance;
    final picker = _FakeVideoPicker()
      ..response = Future.value([
        _FakePlatformFile('bad.mp4', '/fake/dance.mp4'),
      ]);
    FilePickerPlatform.instance = picker;
    addTearDown(() => FilePickerPlatform.instance = original);
    final repo = _FakeProbeRepository()..fail = true;
    final router = _testRouter();
    addTearDown(router.dispose);

    await _pumpWorkbench(tester, router, repository: repo);
    await tester.tap(find.byKey(const ValueKey('home-import-card')));
    await tester.pumpAndSettle();

    expect(find.textContaining('解析视频规格失败'), findsOneWidget);
    expect(find.text('TEST_PROTECTION_EDITOR'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('large text fits compact home cards', (tester) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final router = _testRouter();
    addTearDown(router.dispose);

    await _pumpWorkbench(tester, router, textScale: 1.6);
    expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.byKey(const ValueKey('home-import-card')));
    await tester.drag(find.byType(PageView), const Offset(-250, 0));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-comparison-card')), findsOneWidget);
    expect(find.text('全部工具'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
