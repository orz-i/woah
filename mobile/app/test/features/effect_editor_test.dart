import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:app/core/widgets/immersive_flow_action.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_controller.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_screen.dart';
import 'package:go_router/go_router.dart';


void main() {
  group('EffectEditorController Tests', () {
    final testProject = DanceProject(
      id: 'proj_test',
      sourceUri: 'file:///test.mp4',
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
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

    test('Updates fill mode and builds configured project', () {
      final controller = EffectEditorController();
      controller.init(testProject);
      expect(controller.state.effects.fillMode, equals(FillMode.solid));

      controller.updateFillMode(FillMode.gradient);
      controller.updateBorderWidth(8.0);
      controller.updateOpacity(0.85);
      controller.updateBorderColor(0xFF00E5FF);

      final configured = controller.buildConfiguredProject();
      expect(configured, isNotNull);
      expect(configured!.effects.fillMode, equals(FillMode.gradient));
      expect(configured.effects.borderWidth, equals(8.0));
      expect(configured.effects.opacity, equals(0.85));
      expect(configured.effects.borderColorArgb, equals(0xFF00E5FF));
    });

    test('Updates skin whiten and leg stretch parameters', () {
      final controller = EffectEditorController();
      controller.init(testProject);

      controller.updateSkinWhiten(0.75);
      var configured = controller.buildConfiguredProject();
      expect(configured!.effects.skinWhiten, equals(0.75));

      controller.updateLegStretch(enabled: true, stretch: 0.20);
      configured = controller.buildConfiguredProject();
      expect(configured!.effects.legStretchEnabled, isTrue);
      expect(configured.effects.legStretch, equals(0.20));
    });

    test('Face sticker style toggles real sticker configuration', () {
      final controller = EffectEditorController();
      controller.init(testProject);

      controller.updateProtectionStyle(FillMode.sticker);
      expect(controller.state.effects.fillMode, FillMode.sticker);
      expect(controller.state.effects.faceStickerEnabled, isTrue);
      expect(
        controller.state.effects.stickerAssetId,
        equals('builtin:sunglasses'),
      );

      controller.updateStickerAsset('builtin:panda');
      controller.updateStickerScale(1.4);
      var configured = controller.buildConfiguredProject();
      expect(configured!.effects.stickerAssetId, equals('builtin:panda'));
      expect(configured.effects.stickerScale, equals(1.4));

      controller.updateProtectionStyle(FillMode.blur);
      configured = controller.buildConfiguredProject();
      expect(configured!.effects.fillMode, FillMode.blur);
      expect(configured.effects.faceStickerEnabled, isFalse);
      expect(configured.effects.stickerAssetId, equals('disabled'));
    });

    test('Initializes preview and guards against out-of-order responses with sequence ID', () async {
      final repo = _FakeNativeRepository();
      final projectWithCache = testProject.copyWith(analysisCacheId: 'cache_123');
      final controller = EffectEditorController(repository: repo);

      controller.init(projectWithCache);
      expect(controller.state.previewLoading, isTrue);

      // Await initial preview completion
      await Future.delayed(const Duration(milliseconds: 50));
      expect(controller.state.previewLoading, isFalse);
      expect(controller.state.previewPath, equals('/path/to/rendered_preview.jpg'));
      expect(repo.lastTimestampMs, equals(0));

      // Trigger debounced update
      controller.updateOpacity(0.5);

      // Wait for debounce timer (200ms)
      await Future.delayed(const Duration(milliseconds: 250));
      expect(controller.state.previewRequestId, greaterThan(1));
      expect(controller.state.previewPath, equals('/path/to/rendered_preview.jpg'));
      controller.dispose();
    });

    test('uses only a full-frame handoff preview and never a person crop fallback', () {
      const personCrop = '/path/to/person_crop.jpg';
      const handoffPreview = '/path/to/selection_preview.jpg';
      final projectWithPerson = testProject.copyWith(
        persons: const [
          PersonTrack(
            id: 7,
            normalizedInitialBox: NormalizedRect(
              left: 0.1,
              top: 0.1,
              right: 0.4,
              bottom: 0.9,
            ),
            thumbnailPath: personCrop,
            confidence: 0.95,
          ),
        ],
      );

      final withHandoff = EffectEditorController();
      addTearDown(withHandoff.dispose);
      withHandoff.init(projectWithPerson, initialPreviewPath: handoffPreview);
      expect(withHandoff.state.previewThumbnailPath, handoffPreview);
      expect(withHandoff.state.previewThumbnailPath, isNot(personCrop));

      final withoutHandoff = EffectEditorController();
      addTearDown(withoutHandoff.dispose);
      withoutHandoff.init(projectWithPerson);
      expect(withoutHandoff.state.previewThumbnailPath, isNull);
    });
  });

  group('EffectEditorScreen Widget Tests', () {
    final testProject = DanceProject(
      id: 'proj_widget_test',
      sourceUri: 'file:///test.mp4',
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
      videoInfo: const VideoInfo(
        codedWidth: 1080,
        codedHeight: 1920,
        displayWidth: 1080,
        displayHeight: 1920,
        fps: 30,
        durationMs: 5000,
        rotation: 0,
        videoCodec: 'video/avc',
        hasAudio: true,
      ),
    );

    testWidgets('first frame keeps the full-frame handoff preview instead of a person crop', (tester) async {
      const handoffPreview = '/path/to/selection_preview.jpg';
      const personCrop = '/path/to/person_crop.jpg';
      final projectWithPerson = testProject.copyWith(
        persons: const [
          PersonTrack(
            id: 3,
            normalizedInitialBox: NormalizedRect(
              left: 0.1,
              top: 0.1,
              right: 0.4,
              bottom: 0.9,
            ),
            thumbnailPath: personCrop,
            confidence: 0.96,
          ),
        ],
      );
      final repo = _FakeNativeRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            home: EffectEditorScreen(
              project: projectWithPerson,
              initialPreviewPath: handoffPreview,
            ),
          ),
        ),
      );

      final stageImage = tester.widget<Image>(find.byType(Image).first);
      final provider = stageImage.image as FileImage;
      expect(provider.file.path, handoffPreview);
      expect(provider.file.path, isNot(personCrop));
      expect(stageImage.key, isNull);

      await tester.pump();
      expect(
        container.read(effectEditorControllerProvider).previewThumbnailPath,
        handoffPreview,
      );
    });

    testWidgets('renders stably without overflow on standard and small screens', (tester) async {
      tester.view.physicalSize = const Size(360, 640);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final repo = _FakeNativeRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            home: EffectEditorScreen(project: testProject),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('编辑效果'), findsNothing);
      expect(find.text('下一步: 导出'), findsNothing);
      expect(
        find.byKey(ImmersiveFlowAction.nextControlKey),
        findsOneWidget,
      );
      expect(
        find.byKey(ImmersiveFlowAction.exitTargetKey),
        findsNothing,
      );
      expect(find.textContaining('遮挡'), findsOneWidget);

      // Verify stage preview exists and has prominent non-zero height
      final stageFinder = find.byType(AspectRatio).first;
      expect(stageFinder, findsOneWidget);
      final stageSize = tester.getSize(stageFinder);
      expect(stageSize.height, greaterThan(150));
      expect(stageSize.width, greaterThan(100));

      // In unified panel, verify mode chips and sliders exist
      expect(find.text('马赛克'), findsOneWidget);
      expect(find.text('模糊'), findsOneWidget);
      expect(find.text('强度'), findsOneWidget);

      // Verify retained controls remain available in the same panel
      final verticalScrollable = find.byType(Scrollable).last;
      await tester.scrollUntilVisible(
        find.text('描边宽度'),
        100,
        scrollable: verticalScrollable,
      );
      expect(find.text('描边宽度'), findsOneWidget);
      expect(find.text('人像提亮'), findsNothing);
      expect(find.text('主角跟随画面裁剪'), findsNothing);
      expect(find.text('自动运镜保持主角居中'), findsNothing);
      expect(find.text('特写放大'), findsNothing);
      expect(find.text('下一步: 导出'), findsNothing);

      expect(tester.takeException(), isNull);
    });

    testWidgets('floating next control opens export settings on tap', (tester) async {
      final repo = _FakeNativeRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            home: EffectEditorScreen(project: testProject),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(ImmersiveFlowAction.nextControlKey),
      );
      await tester.pumpAndSettle();

      expect(find.text('导出设置'), findsOneWidget);
      expect(find.text('开始导出'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('long press and drag next control upward reveals return and pops',
        (tester) async {
      final repo = _FakeNativeRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);

      final router = GoRouter(
        initialLocation: '/',
        routes: [
          GoRoute(
            path: '/',
            builder: (context, state) => const Scaffold(
              body: Center(child: Text('editor-entry')),
            ),
          ),
          GoRoute(
            path: '/edit',
            builder: (context, state) =>
                EffectEditorScreen(project: testProject),
          ),
        ],
      );
      addTearDown(router.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      router.push('/edit');
      await tester.pumpAndSettle();

      final nextFinder =
          find.byKey(ImmersiveFlowAction.nextControlKey);
      final gesture = await tester.startGesture(tester.getCenter(nextFinder));
      await tester.pump(const Duration(milliseconds: 650));

      expect(
        find.byKey(ImmersiveFlowAction.exitTargetKey),
        findsNothing,
      );

      await gesture.moveBy(const Offset(0, -108));
      await tester.pump();
      expect(
        find.byKey(ImmersiveFlowAction.exitTargetKey),
        findsOneWidget,
      );
      expect(find.bySemanticsLabel('松开返回'), findsOneWidget);
      await gesture.up();
      await tester.pumpAndSettle();

      expect(find.text('editor-entry'), findsOneWidget);
      expect(find.text('导出设置'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('mode chip switching updates active protection style', (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final repo = _FakeNativeRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            home: EffectEditorScreen(project: testProject),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('马赛克'), findsOneWidget);
      await tester.tap(find.text('马赛克'));
      await tester.pumpAndSettle();

      final controller = container.read(effectEditorControllerProvider.notifier);
      expect(controller.state.effects.fillMode, equals(FillMode.mosaic));
      expect(tester.takeException(), isNull);
    });
  });
}

class _FakeNativeRepository implements NativeProcessingRepository {
  int? lastTimestampMs;

  @override
  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
  }) async {
    lastTimestampMs = timestampMs;
    return PreviewFrameDto(
      thumbnailPath: '/path/to/rendered_preview.jpg',
      renderTimeMs: 12,
      timestampMs: 0,
    );

  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

