import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_controller.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_screen.dart';


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
      expect(find.text('编辑效果'), findsOneWidget);
      expect(find.text('下一步: 导出'), findsOneWidget);
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

      // Verify merged enhancement controls exist in the same panel
      final verticalScrollable = find.byType(Scrollable).last;
      await tester.scrollUntilVisible(
        find.text('描边宽度'),
        100,
        scrollable: verticalScrollable,
      );
      expect(find.text('描边宽度'), findsOneWidget);
      expect(find.text('人像提亮'), findsOneWidget);

      await tester.scrollUntilVisible(
        find.text('主角跟随画面裁剪'),
        100,
        scrollable: verticalScrollable,
      );
      expect(find.text('主角跟随画面裁剪'), findsOneWidget);
      expect(find.text('下一步: 导出'), findsOneWidget);

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

