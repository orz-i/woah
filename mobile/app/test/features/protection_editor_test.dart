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
    expect(find.text('全身保护'), findsOneWidget);
    expect(find.text('人脸保护'), findsOneWidget);
    expect(find.text('马赛克'), findsOneWidget);
    expect(find.byKey(ImmersiveFlowAction.nextControlKey), findsOneWidget);
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
      find.descendant(of: firstTarget, matching: find.byIcon(Icons.check_rounded)),
      findsNothing,
    );

    await tester.tap(find.bySemanticsLabel('已保护人物').first);
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

  testWidgets('single next action goes directly to export with profile', (
    tester,
  ) async {
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
          builder: (context, state) => ProtectionEditorScreen(
            project: project,
            processingProfile: 'balanced',
          ),
        ),
        GoRoute(
          path: '/export',
          builder: (context, state) {
            final args = state.extra! as ExportArgs;
            return Scaffold(
              body: Center(child: Text('export-${args.processingProfile}')),
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

    expect(find.text('export-balanced'), findsOneWidget);
    expect(find.text('编辑效果'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

class _FakeProtectionRepository implements NativeProcessingRepository {
  int analyzeRequestCount = 0;
  Set<int> lastSelectedPersonIds = const {};
  Set<int> lastFaceOnlyPersonIds = const {};
  int? lastPreviewTimestampMs;
  bool? lastTightMaskPreview;
  int previewRequestCount = 0;

  @override
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
    int trimStartMs = 0,
  }) async {
    analyzeRequestCount += 1;
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
    return PreviewFrameDto(
      thumbnailPath: '',
      renderTimeMs: 2,
      timestampMs: timestampMs,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
