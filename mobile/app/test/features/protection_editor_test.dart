import 'package:app/core/widgets/immersive_flow_action.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_controller.dart';
import 'package:app/features/export/presentation/export_screen.dart';
import 'package:app/features/person_selection/presentation/person_selection_controller.dart';
import 'package:app/features/protection_editor/presentation/protection_editor_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

void main() {
  final now = DateTime.utc(2026, 9, 14);
  final project = DanceProject(
    id: 'unified-protection-test',
    sourceUri: 'file:///unified-protection.mp4',
    videoInfo: const VideoInfo(
      codedWidth: 720,
      codedHeight: 1280,
      displayWidth: 720,
      displayHeight: 1280,
      fps: 30,
      durationMs: 4000,
      rotation: 0,
      videoCodec: 'h264',
      hasAudio: false,
    ),
    trimStartMs: 400,
    trimEndMs: 3200,
    createdAt: now,
    updatedAt: now,
  );

  testWidgets('unified editor is stable at 360x640 and uses one media stage', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('protection-editor-media-stage')),
      findsOneWidget,
    );
    final stageSize = tester.getSize(
      find.byKey(const ValueKey('protection-editor-media-stage')),
    );
    expect(stageSize.width, greaterThanOrEqualTo(180));
    expect(stageSize.height, greaterThan(stageSize.width));
    expect(
      find.byKey(const ValueKey('protection-editor-tool-deck')),
      findsOneWidget,
    );
    expect(find.text('保护对象'), findsOneWidget);
    expect(find.text('保护范围'), findsOneWidget);
    expect(find.text('遮挡样式'), findsOneWidget);
    expect(find.text('全身保护'), findsOneWidget);
    expect(find.text('人脸保护'), findsOneWidget);
    expect(find.text('马赛克'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('protection-editor-drawer-summary')),
      findsOneWidget,
    );
    expect(find.text('导出'), findsOneWidget);
    expect(find.byKey(const ValueKey('trim-range-section')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('integrated-video-trim-control')).hitTestable(),
      findsNothing,
    );
    expect(find.text('质量'), findsNothing);
    expect(find.text('均衡'), findsNothing);
    expect(find.text('快速'), findsNothing);
    expect(find.byKey(ImmersiveFlowAction.nextControlKey), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('control drawer snaps between compact and expanded states', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    final handle = find.byKey(const ValueKey('bottom_control_drawer_handle'));
    expect(handle, findsOneWidget);
    final initialTop = tester.getTopLeft(handle).dy;

    await tester.drag(handle, const Offset(0, 320));
    await tester.pumpAndSettle();
    final compactTop = tester.getTopLeft(handle).dy;
    expect(compactTop, greaterThan(initialTop));
    expect(
      find.byKey(const ValueKey('protection-editor-drawer-summary')),
      findsOneWidget,
    );

    await tester.drag(handle, const Offset(0, -560));
    await tester.pumpAndSettle();
    final expandedTop = tester.getTopLeft(handle).dy;
    expect(expandedTop, lessThan(compactTop));
    expect(tester.takeException(), isNull);
  });

  testWidgets('temporal trim change reanalyzes from the new first frame', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.analyzeRequestCount, 1);
    expect(repository.lastAnalyzeTrimStartMs, 400);

    final trimToggle = find.byKey(const ValueKey('trim-range-toggle'));
    await tester.ensureVisible(trimToggle);
    await tester.tap(trimToggle);
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('integrated-video-trim-control')),
      findsOneWidget,
    );

    final startHandle = find.byKey(const ValueKey('trim-start-handle'));
    await tester.ensureVisible(startHandle);
    await tester.pumpAndSettle();
    await tester.drag(startHandle, const Offset(48, 0), warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(repository.analyzeRequestCount, 2);
    expect(repository.lastAnalyzeTrimStartMs, greaterThan(400));
    final selectionState = container.read(personSelectionControllerProvider);
    expect(
      selectionState.project?.trimStartMs,
      repository.lastAnalyzeTrimStartMs,
    );
    expect(selectionState.selectedPersonIds, equals({0, 1}));
    expect(tester.takeException(), isNull);
  });

  testWidgets('reopening with cached analysis does not rerun person analysis', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    final cachedProject = project.copyWith(
      analysisCacheId: 'cached-analysis',
      persons: const [
        PersonTrack(
          id: 0,
          normalizedInitialBox: NormalizedRect(
            left: 0.08,
            top: 0.12,
            right: 0.42,
            bottom: 0.88,
          ),
          thumbnailPath: '',
          confidence: 0.94,
          selected: true,
        ),
        PersonTrack(
          id: 1,
          normalizedInitialBox: NormalizedRect(
            left: 0.55,
            top: 0.14,
            right: 0.88,
            bottom: 0.90,
          ),
          thumbnailPath: '',
          confidence: 0.91,
          selected: true,
        ),
      ],
      selectedPersonIds: const {0, 1},
    );

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: ProtectionEditorScreen(project: cachedProject),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.analyzeRequestCount, 0);
    expect(repository.previewRequestCount, greaterThanOrEqualTo(1));
    expect(
      container.read(personSelectionControllerProvider).selectedPersonIds,
      equals({0, 1}),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('stage target changes feed the real effect preview request', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.lastSelectedPersonIds, equals({0, 1}));
    expect(repository.lastPreviewTimestampMs, 400);
    expect(repository.lastTightMaskPreview, isTrue);

    final firstTarget = find.byKey(
      const ValueKey('protection-person-target-0'),
    );
    expect(firstTarget, findsOneWidget);
    final firstTargetWidget = tester.widget<GestureDetector>(firstTarget);
    expect(firstTargetWidget.child, isA<SizedBox>());
    expect(
      find.descendant(
        of: firstTarget,
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsNothing,
    );

    expect(find.bySemanticsLabel('人物 1，已保护'), findsOneWidget);
    await tester.tap(firstTarget);
    await tester.pump(const Duration(milliseconds: 250));
    await tester.pumpAndSettle();

    expect(
      container.read(personSelectionControllerProvider).selectedPersonIds,
      hasLength(1),
    );
    expect(repository.lastSelectedPersonIds, hasLength(1));
    expect(repository.previewRequestCount, greaterThanOrEqualTo(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('full-body and face effect drafts survive mode switching', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    final effectController = container.read(
      effectEditorControllerProvider.notifier,
    );
    effectController.updateProtectionStyle(FillMode.mosaic);
    effectController.updateOpacity(0.55);
    await tester.pump(const Duration(milliseconds: 250));

    await tester.tap(find.text('人脸保护'));
    await tester.pumpAndSettle();
    expect(
      container.read(personSelectionControllerProvider).faceOnlyPersonIds,
      equals({0, 1}),
    );
    expect(
      container.read(effectEditorControllerProvider).effects.fillMode,
      FillMode.sticker,
    );

    effectController.updateStickerAsset('builtin:panda');
    effectController.updateStickerScale(1.4);
    await tester.pump(const Duration(milliseconds: 250));

    await tester.tap(find.text('全身保护'));
    await tester.pumpAndSettle();
    var effects = container.read(effectEditorControllerProvider).effects;
    expect(effects.fillMode, FillMode.mosaic);
    expect(effects.opacity, 0.55);

    await tester.tap(find.text('人脸保护'));
    await tester.pumpAndSettle();
    effects = container.read(effectEditorControllerProvider).effects;
    expect(effects.fillMode, FillMode.sticker);
    expect(effects.stickerAssetId, 'builtin:panda');
    expect(effects.stickerScale, 1.4);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'portrait subject is independent of privacy and survives export',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      final landscape = project.copyWith(
        videoInfo: const VideoInfo(
          codedWidth: 1920,
          codedHeight: 1080,
          displayWidth: 1920,
          displayHeight: 1080,
          fps: 30,
          durationMs: 4000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        analysisCacheId: 'cached',
        selectedPersonIds: {0, 1},
        persons: const [
          PersonTrack(
            id: 0,
            normalizedInitialBox: NormalizedRect(
              left: .1,
              top: .1,
              right: .3,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
          PersonTrack(
            id: 1,
            normalizedInitialBox: NormalizedRect(
              left: .65,
              top: .1,
              right: .85,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
        ],
      );
      ExportArgs? exported;
      final router = GoRouter(
        initialLocation: '/protect',
        routes: [
          GoRoute(
            path: '/protect',
            builder: (_, state) => ProtectionEditorScreen(project: landscape),
          ),
          GoRoute(
            path: '/export',
            builder: (_, state) {
              exported = state.extra as ExportArgs;
              return const Scaffold(body: Text('portrait-export'));
            },
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
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('reframe-mode')));
      await tester.tap(find.text('竖屏 9:16'));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('reframe-subject-prompt')),
        findsOneWidget,
      );
      expect(find.text('轻触人物选择主角'), findsOneWidget);
      expect(find.bySemanticsLabel('选择人物 2 为主角'), findsOneWidget);
      await tester.tap(
        find.byKey(const ValueKey('protection-person-target-1')),
      );
      await tester.pumpAndSettle();
      expect(repository.lastFollow.enabled, isTrue);
      expect(repository.lastFollow.targetPersonId, 1);
      expect(repository.lastFollow.outputAspectRatio, 9 / 16);
      expect(repository.lastSelectedPersonIds, {0, 1});
      expect(
        find.byKey(const ValueKey('protection-person-target-0')),
        findsNothing,
      );
      var stageSize = tester.getSize(
        find.byKey(const ValueKey('protection-editor-media-stage')),
      );
      expect(stageSize.width / stageSize.height, closeTo(9 / 16, .001));

      // Source preview is a view mode, not a change to the saved export config.
      await tester.ensureVisible(
        find.byKey(const ValueKey('reframe-source-toggle')),
      );
      await tester.tap(find.byKey(const ValueKey('reframe-source-toggle')));
      await tester.pumpAndSettle();
      expect(repository.lastFollow.enabled, isFalse);
      expect(
        container.read(effectEditorControllerProvider).project!.follow.enabled,
        isTrue,
      );
      stageSize = tester.getSize(
        find.byKey(const ValueKey('protection-editor-media-stage')),
      );
      expect(stageSize.width / stageSize.height, closeTo(16 / 9, .001));
      await tester.ensureVisible(find.text('人脸保护'));
      await tester.tap(find.text('人脸保护'));
      await tester.pumpAndSettle();
      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .follow
            .targetPersonId,
        1,
      );
      final moreActions = find.byKey(
        const ValueKey('protection-target-more-actions'),
      );
      await tester.ensureVisible(moreActions);
      await tester.tap(moreActions);
      await tester.pumpAndSettle();
      await tester.tap(find.text('清空保护对象'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(ImmersiveFlowAction.nextControlKey));
      await tester.pumpAndSettle();
      expect(exported, isNotNull);
      expect(exported!.project.follow.enabled, isTrue);
      expect(exported!.project.follow.targetPersonId, 1);
      expect(exported!.project.selectedPersonIds, isEmpty);
      expect(exported!.project.faceOnlyPersonIds, isEmpty);
      expect(exported!.project.outputSize, (width: 594, height: 1056));
      expect(exported!.project.trimStartMs, 400);
      expect(exported!.initialPreviewPath, isNull);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'cancel subject selection leaves original privacy and framing intact',
    (tester) async {
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: project)),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('reframe-mode')));
      await tester.tap(find.text('竖屏 9:16'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(
        find.byKey(const ValueKey('reframe-cancel-selection')),
      );
      await tester.tap(find.byKey(const ValueKey('reframe-cancel-selection')));
      await tester.pumpAndSettle();
      expect(
        container.read(effectEditorControllerProvider).project!.follow.enabled,
        isFalse,
      );
      expect(repository.lastSelectedPersonIds, {0, 1});
      expect(find.bySemanticsLabel('选择人物 2 为主角'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'reopening a crop-only project does not silently restore privacy targets',
    (tester) async {
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      final cropOnlyProject = project.copyWith(
        videoInfo: const VideoInfo(
          codedWidth: 1920,
          codedHeight: 1080,
          displayWidth: 1920,
          displayHeight: 1080,
          fps: 30,
          durationMs: 4000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        analysisCacheId: 'crop-only-cache',
        persons: const [
          PersonTrack(
            id: 0,
            normalizedInitialBox: NormalizedRect(
              left: .1,
              top: .1,
              right: .3,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
          PersonTrack(
            id: 1,
            normalizedInitialBox: NormalizedRect(
              left: .65,
              top: .1,
              right: .85,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
        ],
        selectedPersonIds: <int>{},
        faceOnlyPersonIds: <int>{},
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
      );
      ExportArgs? exported;
      final router = GoRouter(
        initialLocation: '/protect',
        routes: [
          GoRoute(
            path: '/protect',
            builder: (_, state) =>
                ProtectionEditorScreen(project: cropOnlyProject),
          ),
          GoRoute(
            path: '/export',
            builder: (_, state) {
              exported = state.extra as ExportArgs;
              return const Scaffold(body: Text('crop-only-export'));
            },
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
      await tester.pumpAndSettle();

      expect(repository.analyzeRequestCount, 0);
      expect(
        container.read(personSelectionControllerProvider).privacyTargetIds,
        isEmpty,
      );
      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .follow
            .targetPersonId,
        1,
      );
      final stageSize = tester.getSize(
        find.byKey(const ValueKey('protection-editor-media-stage')),
      );
      expect(stageSize.width / stageSize.height, closeTo(9 / 16, .001));

      await tester.tap(find.byKey(ImmersiveFlowAction.nextControlKey));
      await tester.pumpAndSettle();
      expect(exported, isNotNull);
      expect(exported!.project.selectedPersonIds, isEmpty);
      expect(exported!.project.faceOnlyPersonIds, isEmpty);
      expect(exported!.project.follow.targetPersonId, 1);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('single next action goes directly to export', (tester) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    final router = GoRouter(
      initialLocation: '/protect',
      routes: [
        GoRoute(
          path: '/protect',
          builder: (context, state) => ProtectionEditorScreen(project: project),
        ),
        GoRoute(
          path: '/export',
          builder: (context, state) {
            final args = state.extra! as ExportArgs;
            return Scaffold(
              body: Center(child: Text('export-${args.project.id}')),
            );
          },
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
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(ImmersiveFlowAction.nextControlKey));
    await tester.pumpAndSettle();

    expect(find.text('export-unified-protection-test'), findsOneWidget);
    expect(find.text('编辑效果'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

class _FakeProtectionRepository implements NativeProcessingRepository {
  int analyzeRequestCount = 0;
  int? lastAnalyzeTrimStartMs;
  Set<int> lastSelectedPersonIds = const {};
  Set<int> lastFaceOnlyPersonIds = const {};
  int? lastPreviewTimestampMs;
  bool? lastTightMaskPreview;
  int previewRequestCount = 0;
  FollowConfig lastFollow = const FollowConfig();

  @override
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
    int trimStartMs = 0,
  }) async {
    analyzeRequestCount += 1;
    lastAnalyzeTrimStartMs = trimStartMs;
    return AnalyzeResultDto(
      analysisCacheId: 'unified-cache',
      videoInfo: VideoInfoDto(
        codedWidth: 720,
        codedHeight: 1280,
        displayWidth: 720,
        displayHeight: 1280,
        fps: 30,
        durationMs: 4000,
        rotation: 0,
        videoCodec: 'h264',
        audioCodec: null,
        hasAudio: false,
      ),
      persons: [
        DetectedPersonDto(
          id: 0,
          x1: 0.08,
          y1: 0.12,
          x2: 0.42,
          y2: 0.88,
          thumbnailPath: '',
          confidence: 0.94,
        ),
        DetectedPersonDto(
          id: 1,
          x1: 0.55,
          y1: 0.14,
          x2: 0.88,
          y2: 0.90,
          thumbnailPath: '',
          confidence: 0.91,
        ),
      ],
    );
  }

  @override
  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
    bool tightMaskPreview = false,
  }) async {
    previewRequestCount += 1;
    lastSelectedPersonIds = selectedPersonIds.toSet();
    lastFaceOnlyPersonIds = faceOnlyPersonIds.toSet();
    lastPreviewTimestampMs = timestampMs;
    lastTightMaskPreview = tightMaskPreview;
    lastFollow = follow;
    return PreviewFrameDto(
      thumbnailPath: '',
      renderTimeMs: 2,
      timestampMs: timestampMs,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
