import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:app/features/export/domain/export_state.dart';
import 'package:app/features/export/presentation/export_controller.dart';
import 'package:app/features/export/presentation/export_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:app/core/widgets/immersive_flow_action.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';

void main() {
  group('ExportState Domain Tests', () {
    test('ExportJobState fromString parses correctly', () {
      expect(
        ExportJobState.fromString('preparing'),
        equals(ExportJobState.preparing),
      );
      expect(
        ExportJobState.fromString('processing'),
        equals(ExportJobState.processing),
      );
      expect(
        ExportJobState.fromString('muxing'),
        equals(ExportJobState.muxing),
      );
      expect(
        ExportJobState.fromString('completed'),
        equals(ExportJobState.completed),
      );
      expect(
        ExportJobState.fromString('failed'),
        equals(ExportJobState.failed),
      );
      expect(
        ExportJobState.fromString('cancelled'),
        equals(ExportJobState.cancelled),
      );
      expect(
        ExportJobState.fromString('unknown_state'),
        equals(ExportJobState.processing),
      );
    });

    test('ExportState flags isProcessing and isCompleted', () {
      const state1 = ExportState(
        status: ExportJobState.processing,
        progress: 0.45,
        currentFrame: 135,
        totalFrames: 300,
        fps: 8.5,
      );

      expect(state1.isProcessing, isTrue);
      expect(state1.isCompleted, isFalse);

      const state2 = ExportState(
        status: ExportJobState.completed,
        outputUri: '/tmp/final_output.mp4',
        progress: 1.0,
      );

      expect(state2.isProcessing, isFalse);
      expect(state2.isCompleted, isTrue);
    });

    test(
      'ExportState defaults showLivePreview to false and updates with preview path',
      () {
        const defaultState = ExportState();
        expect(defaultState.showLivePreview, isFalse);
        expect(defaultState.currentPreviewPath, isNull);

        final updatedState = defaultState.copyWith(
          showLivePreview: true,
          currentPreviewPath: '/cache/export_live_preview/preview_job_1.jpg',
        );

        expect(updatedState.showLivePreview, isTrue);
        expect(
          updatedState.currentPreviewPath,
          equals('/cache/export_live_preview/preview_job_1.jpg'),
        );
      },
    );

    test('ExportState can explicitly clear a previous export error', () {
      const failed = ExportState(
        status: ExportJobState.failed,
        errorMessage: 'encoder failed',
      );

      final retrying = failed.copyWith(
        status: ExportJobState.preparing,
        clearErrorMessage: true,
      );

      expect(retrying.status, ExportJobState.preparing);
      expect(retrying.errorMessage, isNull);
      expect(retrying.isFailed, isFalse);
    });

    test('ExportState can explicitly clear a live preview frame', () {
      const live = ExportState(
        showLivePreview: true,
        currentPreviewPath: '/cache/live_12.jpg',
      );

      final hidden = live.copyWith(
        showLivePreview: false,
        clearCurrentPreviewPath: true,
      );

      expect(hidden.showLivePreview, isFalse);
      expect(hidden.currentPreviewPath, isNull);
    });
  });

  test('ExportController forwards temporal trim bounds', () async {
    final repository = _TrimCaptureRepository();
    final controller = ExportController(repository);
    addTearDown(controller.dispose);
    final now = DateTime.utc(2026, 9, 3);
    final project = DanceProject(
      id: 'trim-export',
      sourceUri: '/trim-export.mp4',
      videoInfo: const VideoInfo(
        codedWidth: 1280,
        codedHeight: 720,
        displayWidth: 1280,
        displayHeight: 720,
        fps: 30,
        durationMs: 12000,
        rotation: 0,
        videoCodec: 'h264',
        hasAudio: true,
      ),
      analysisCacheId: 'cache-trim',
      selectedPersonIds: const {0},
      trimStartMs: 2300,
      trimEndMs: 8700,
      createdAt: now,
      updatedAt: now,
    );

    await controller.startExport(project, 'out.mp4');

    expect(repository.lastTrimStartMs, 2300);
    expect(repository.lastTrimEndMs, 8700);
    expect(repository.lastProcessingProfile, 'quality');
    expect(controller.state.jobId, 'trim-job');
  });

  test(
    'ExportController forwards portrait reframe output dimensions',
    () async {
      final repository = _TrimCaptureRepository();
      final controller = ExportController(repository);
      addTearDown(controller.dispose);
      final now = DateTime.utc(2026, 9, 15);
      final project = DanceProject(
        id: 'portrait-export',
        sourceUri: '/portrait-export.mp4',
        videoInfo: const VideoInfo(
          codedWidth: 1920,
          codedHeight: 1080,
          displayWidth: 1920,
          displayHeight: 1080,
          fps: 30,
          durationMs: 5000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        analysisCacheId: 'cache-portrait',
        persons: const [
          PersonTrack(
            id: 2,
            normalizedInitialBox: NormalizedRect(
              left: 0.4,
              top: 0.1,
              right: 0.6,
              bottom: 0.9,
            ),
            thumbnailPath: '',
            confidence: 0.95,
          ),
        ],
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 2,
          outputAspectRatio: 9 / 16,
        ),
        createdAt: now,
        updatedAt: now,
      );

      await controller.startExport(project, 'portrait.mp4');

      expect(repository.lastTargetWidth, 594);
      expect(repository.lastTargetHeight, 1056);
      expect(repository.lastFollow.targetPersonId, 2);
      expect(repository.lastFollow.outputAspectRatio, 9 / 16);
    },
  );

  test(
    'ExportController preserves 4K 59.94 media contract when capability allows it',
    () async {
      final repository = _TrimCaptureRepository();
      final controller = ExportController(repository);
      addTearDown(controller.dispose);
      final now = DateTime.utc(2026, 9, 16);
      final project = DanceProject(
        id: '4k-contract',
        sourceUri: '/4k.mp4',
        videoInfo: const VideoInfo(
          codedWidth: 3840,
          codedHeight: 2160,
          displayWidth: 3840,
          displayHeight: 2160,
          fps: 59.94,
          durationMs: 5000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        analysisCacheId: 'cache-4k',
        selectedPersonIds: const {0},
        createdAt: now,
        updatedAt: now,
      );

      await controller.startExport(project, '4k.mp4');

      expect(repository.lastTargetWidth, 3840);
      expect(repository.lastTargetHeight, 2160);
      expect(repository.lastTargetFps, 59.94);
      expect(repository.lastVideoBitrate, greaterThan(8_000_000));
    },
  );

  test(
    'ExportController applies user FHD preference before device fallback',
    () async {
      final repository = _TrimCaptureRepository();
      final controller = ExportController(repository);
      addTearDown(controller.dispose);
      final now = DateTime.utc(2026, 9, 16);
      final project = DanceProject(
        id: '4k-fhd',
        sourceUri: '/4k.mp4',
        videoInfo: const VideoInfo(
          codedWidth: 3840,
          codedHeight: 2160,
          displayWidth: 3840,
          displayHeight: 2160,
          fps: 60,
          durationMs: 5000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        outputResolutionPreset: OutputResolutionPreset.fhd,
        analysisCacheId: 'cache-4k',
        selectedPersonIds: const {0},
        createdAt: now,
        updatedAt: now,
      );

      await controller.startExport(project, '4k-fhd.mp4');

      expect(repository.lastTargetWidth, 1920);
      expect(repository.lastTargetHeight, 1080);
      expect(
        controller.state.exportPlan!.resolutionPreset,
        OutputResolutionPreset.fhd,
      );
      expect(controller.state.exportPlan!.fallbackReason, isNull);
    },
  );

  test(
    'ExportController applies encoder dimension capability explicitly',
    () async {
      final repository = _TrimCaptureRepository(
        maxEncodeWidth: 1920,
        maxEncodeHeight: 1080,
      );
      final controller = ExportController(repository);
      addTearDown(controller.dispose);
      final now = DateTime.utc(2026, 9, 16);
      final project = DanceProject(
        id: '4k-fallback',
        sourceUri: '/4k.mp4',
        videoInfo: const VideoInfo(
          codedWidth: 3840,
          codedHeight: 2160,
          displayWidth: 3840,
          displayHeight: 2160,
          fps: 60,
          durationMs: 5000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        analysisCacheId: 'cache-4k',
        selectedPersonIds: const {0},
        createdAt: now,
        updatedAt: now,
      );

      await controller.startExport(project, '4k-fallback.mp4');

      expect(repository.lastTargetWidth, 1920);
      expect(repository.lastTargetHeight, 1080);
      expect(repository.lastTargetFps, 60);
    },
  );

  test(
    'ExportController ignores preview frames while live preview is off',
    () async {
      final repository = _PreviewToggleRepository();
      final controller = ExportController(repository);
      addTearDown(controller.dispose);
      addTearDown(repository.dispose);

      await controller.startExport(_testProject(), 'preview-toggle.mp4');
      expect(controller.state.showLivePreview, isFalse);

      repository.emitPreview('/cache/live_hidden.jpg', frame: 3);
      await Future<void>.delayed(Duration.zero);
      expect(controller.state.currentPreviewPath, isNull);

      controller.toggleLivePreview(true);
      await Future<void>.delayed(Duration.zero);
      expect(controller.state.showLivePreview, isTrue);
      expect(repository.livePreviewToggles, contains(true));

      repository.emitPreview('/cache/live_visible.jpg', frame: 4);
      await Future<void>.delayed(Duration.zero);
      expect(controller.state.currentPreviewPath, '/cache/live_visible.jpg');

      controller.toggleLivePreview(false);
      await Future<void>.delayed(Duration.zero);
      expect(controller.state.showLivePreview, isFalse);
      expect(controller.state.currentPreviewPath, isNull);
      expect(repository.livePreviewToggles.last, isFalse);
    },
  );

  testWidgets(
    'export live preview is opt-in and toggles by tapping the stage',
    (tester) async {
      final repository = _PreviewToggleRepository();
      addTearDown(repository.dispose);
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ExportScreen(project: _testProject())),
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(container.read(exportControllerProvider).showLivePreview, isFalse);
      expect(find.text('点击查看实时画面'), findsOneWidget);

      await tester.tap(
        find.byKey(const ValueKey('export-live-preview-toggle')),
      );
      await tester.pump();
      await tester.pump();

      expect(container.read(exportControllerProvider).showLivePreview, isTrue);
      expect(repository.livePreviewToggles.last, isTrue);
      expect(find.text('正在开启实时画面…'), findsOneWidget);

      repository.emitPreview('/cache/live_widget.jpg', frame: 8);
      await tester.pump();
      expect(
        container.read(exportControllerProvider).currentPreviewPath,
        '/cache/live_widget.jpg',
      );
      expect(find.text('实时画面 · 点击关闭'), findsOneWidget);

      await tester.tap(
        find.byKey(const ValueKey('export-live-preview-toggle')),
      );
      await tester.pump();
      await tester.pump();

      expect(container.read(exportControllerProvider).showLivePreview, isFalse);
      expect(
        container.read(exportControllerProvider).currentPreviewPath,
        isNull,
      );
      expect(repository.livePreviewToggles.last, isFalse);
      expect(find.text('点击查看实时画面'), findsOneWidget);
    },
  );

  testWidgets(
    'export surfaces encoder resolution fallback instead of hiding it',
    (tester) async {
      final repository = _PreviewToggleRepository(
        maxEncodeWidth: 1920,
        maxEncodeHeight: 1080,
      );
      addTearDown(repository.dispose);
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      final project = _testProject().copyWith(
        videoInfo: const VideoInfo(
          codedWidth: 3840,
          codedHeight: 2160,
          displayWidth: 3840,
          displayHeight: 2160,
          fps: 60,
          durationMs: 12000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
      );

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ExportScreen(project: project)),
        ),
      );
      await tester.pump();
      await tester.pump();

      final plan = container.read(exportControllerProvider).exportPlan;
      expect(plan?.fallbackReason, ExportFallbackReason.encoderDimensionLimit);
      expect(plan?.width, 1920);
      expect(plan?.height, 1080);
      expect(find.textContaining('设备编码能力限制'), findsOneWidget);
      expect(find.textContaining('1920×1080'), findsOneWidget);
    },
  );

  testWidgets(
    'active export uses a single circular cancel control without processing header',
    (tester) async {
      final repository = _PreviewToggleRepository();
      addTearDown(repository.dispose);
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ExportScreen(project: _testProject())),
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(find.text('正在保护舞段'), findsNothing);
      expect(find.text('取消处理'), findsNothing);
      expect(
        find.byKey(const ValueKey('export-cancel-action')),
        findsOneWidget,
      );
      expect(find.byKey(ImmersiveFlowAction.nextControlKey), findsNothing);
      expect(find.byIcon(Icons.close_rounded), findsOneWidget);
      expect(find.bySemanticsLabel('取消处理'), findsOneWidget);

      final stage = find.byKey(const ValueKey('export-media-stage'));
      final progressDeck = find.byKey(const ValueKey('export-progress-deck'));
      expect(stage, findsOneWidget);
      expect(progressDeck, findsOneWidget);
      final stageRect = tester.getRect(stage);
      final progressRect = tester.getRect(progressDeck);
      expect(stageRect.top, lessThan(progressRect.top));
      expect(progressRect.top - stageRect.bottom, lessThanOrEqualTo(18));

      await tester.tap(find.byKey(const ValueKey('export-cancel-action')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));

      expect(find.text('取消处理？'), findsOneWidget);
      expect(find.text('继续处理'), findsOneWidget);
    },
  );

  testWidgets('cancel stays disabled until the native export job exists', (
    tester,
  ) async {
    final repository = _PreviewToggleRepository()
      ..startCompleter = Completer<String>();
    addTearDown(repository.dispose);
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ExportScreen(project: _testProject())),
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(container.read(exportControllerProvider).jobId, isNull);
    expect(
      tester
          .widget<GestureDetector>(
            find.byKey(const ValueKey('export-cancel-action')),
          )
          .onTap,
      isNull,
    );

    repository.startCompleter!.complete('preview-toggle-job');
    await tester.pump();
    await tester.pump();

    expect(
      container.read(exportControllerProvider).jobId,
      'preview-toggle-job',
    );
    expect(
      tester
          .widget<GestureDetector>(
            find.byKey(const ValueKey('export-cancel-action')),
          )
          .onTap,
      isNotNull,
    );
  });

  testWidgets('active export cancel action has no hidden drag-return target', (
    tester,
  ) async {
    final repository = _PreviewToggleRepository();
    addTearDown(repository.dispose);
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ExportScreen(project: _testProject())),
      ),
    );
    await tester.pump();
    await tester.pump();

    final action = find.byKey(const ValueKey('export-cancel-action'));
    final gesture = await tester.startGesture(tester.getCenter(action));
    await tester.pump(const Duration(milliseconds: 650));
    await gesture.moveBy(const Offset(0, -108));
    await tester.pump();

    expect(find.byKey(ImmersiveFlowAction.exitTargetKey), findsNothing);
    expect(find.bySemanticsLabel('松开返回'), findsNothing);

    await gesture.cancel();
    await tester.pump();
    expect(find.text('取消处理？'), findsNothing);
  });

  testWidgets(
    'confirmed cancel waits for native cancellation before returning',
    (tester) async {
      final repository = _PreviewToggleRepository()
        ..cancelCompleter = Completer<void>();
      addTearDown(repository.dispose);
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      final router = GoRouter(
        initialLocation: '/edit',
        routes: [
          GoRoute(
            path: '/edit',
            builder: (context, state) =>
                const Scaffold(body: Center(child: Text('edit-marker'))),
          ),
          GoRoute(
            path: '/export',
            builder: (context, state) => ExportScreen(project: _testProject()),
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
      router.push('/export');
      await tester.pump();
      await tester.pump();
      for (
        var attempt = 0;
        attempt < 4 && container.read(exportControllerProvider).jobId == null;
        attempt++
      ) {
        await tester.pump(const Duration(milliseconds: 1));
      }
      expect(container.read(exportControllerProvider).jobId, isNotNull);

      await tester.tap(find.byKey(const ValueKey('export-cancel-action')));
      await tester.pump();
      await tester.tap(find.text('取消处理'));
      await tester.pump();

      expect(repository.cancelCalls, 1);
      expect(
        find.byKey(const ValueKey('export-cancel-action')).hitTestable(),
        findsOneWidget,
      );
      expect(find.text('edit-marker').hitTestable(), findsNothing);
      expect(
        tester
            .widget<GestureDetector>(
              find.byKey(const ValueKey('export-cancel-action')),
            )
            .onTap,
        isNull,
      );
      expect(
        find.descendant(
          of: find.byKey(const ValueKey('export-cancel-action')),
          matching: find.byType(CircularProgressIndicator),
        ),
        findsOneWidget,
      );

      repository.cancelCompleter!.complete();
      await tester.pump();
      await tester.pump();

      expect(find.text('edit-marker').hitTestable(), findsOneWidget);
      expect(
        find.byKey(const ValueKey('export-cancel-action')).hitTestable(),
        findsNothing,
      );
    },
  );

  testWidgets(
    'failed export uses a centered retry action with surrounding secondary actions',
    (tester) async {
      final repository = _PreviewToggleRepository();
      addTearDown(repository.dispose);
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ExportScreen(project: _testProject())),
        ),
      );
      await tester.pump();
      await tester.pump();

      repository.emitFailure('encoder failed');
      await tester.pump();

      final retry = find.byKey(const ValueKey('export-failed-retry'));
      final back = find.byKey(const ValueKey('export-failed-back'));
      final copy = find.byKey(const ValueKey('export-failed-copy'));
      final diagnostics = find.byKey(
        const ValueKey('export-failed-diagnostics'),
      );

      expect(retry, findsOneWidget);
      expect(back, findsOneWidget);
      expect(copy, findsOneWidget);
      expect(diagnostics, findsOneWidget);
      expect(
        find.byKey(const ValueKey('export-failure-summary')),
        findsOneWidget,
      );
      expect(find.text('这次没有生成视频'), findsOneWidget);
      expect(find.textContaining('当前编辑内容仍然保留'), findsOneWidget);
      expect(find.text('重试导出'), findsNothing);
      expect(find.text('返回编辑'), findsNothing);

      final retryCenter = tester.getCenter(retry);
      final backCenter = tester.getCenter(back);
      final copyCenter = tester.getCenter(copy);
      final diagnosticsCenter = tester.getCenter(diagnostics);
      expect(backCenter.dx, lessThan(retryCenter.dx));
      expect(diagnosticsCenter.dx, greaterThan(retryCenter.dx));
      expect(copyCenter.dy, lessThan(retryCenter.dy));

      expect(repository.startExportCalls, 1);
      await tester.tap(retry);
      await tester.pump();
      expect(repository.startExportCalls, 2);
    },
  );

  testWidgets(
    'failed export debug actions keep copy and diagnostics available',
    (tester) async {
      final repository = _PreviewToggleRepository();
      addTearDown(repository.dispose);
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ExportScreen(project: _testProject())),
        ),
      );
      await tester.pump();
      await tester.pump();
      repository.emitFailure('encoder failed');
      await tester.pump();

      await tester.tap(find.byKey(const ValueKey('export-failed-copy')));
      await tester.pump();
      expect(find.text('错误详情已复制'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('export-failed-diagnostics')));
      await tester.pump();
      await tester.pump();
      expect(repository.diagnosticShared, isTrue);
    },
  );
}

DanceProject _testProject() {
  final now = DateTime.utc(2026, 9, 5);
  return DanceProject(
    id: 'export-preview-toggle',
    sourceUri: '/preview-toggle.mp4',
    videoInfo: const VideoInfo(
      codedWidth: 1280,
      codedHeight: 720,
      displayWidth: 1280,
      displayHeight: 720,
      fps: 30,
      durationMs: 12000,
      rotation: 0,
      videoCodec: 'h264',
      hasAudio: true,
    ),
    analysisCacheId: 'cache-preview-toggle',
    selectedPersonIds: const {0},
    createdAt: now,
    updatedAt: now,
  );
}

class _TrimCaptureRepository implements NativeProcessingRepository {
  final int maxEncodeWidth;
  final int maxEncodeHeight;
  int? lastTrimStartMs;
  int? lastTrimEndMs;
  String? lastProcessingProfile;
  int? lastTargetWidth;
  int? lastTargetHeight;
  double? lastTargetFps;
  int? lastVideoBitrate;
  FollowConfig lastFollow = const FollowConfig();

  _TrimCaptureRepository({
    this.maxEncodeWidth = 3840,
    this.maxEncodeHeight = 2160,
  });

  @override
  Future<NativeCapabilitiesDto> getCapabilities() async =>
      NativeCapabilitiesDto(
        platform: 'test',
        osVersion: '1',
        gpuSupported: true,
        h264Encoder: true,
        hevcEncoder: true,
        maxEncodeWidth: maxEncodeWidth,
        maxEncodeHeight: maxEncodeHeight,
        cpuCores: 8,
        recommendedProfile: 'quality',
        supportedProfiles: const ['quality'],
        inferenceBackends: const ['test'],
      );

  @override
  Stream<JobStatusDto> get progressStream => const Stream.empty();

  @override
  Future<String> startExport({
    required String sourceUri,
    required String analysisCacheId,
    required String outputFilePath,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
    int targetWidth = 1920,
    int targetHeight = 1080,
    double targetFps = 30.0,
    int videoBitrate = 8000000,
    String processingProfile = 'quality',
    bool enableLivePreview = false,
    int trimStartMs = 0,
    int? trimEndMs,
  }) async {
    lastTrimStartMs = trimStartMs;
    lastTrimEndMs = trimEndMs;
    lastProcessingProfile = processingProfile;
    lastTargetWidth = targetWidth;
    lastTargetHeight = targetHeight;
    lastTargetFps = targetFps;
    lastVideoBitrate = videoBitrate;
    lastFollow = follow;
    return 'trim-job';
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _PreviewToggleRepository implements NativeProcessingRepository {
  final int maxEncodeWidth;
  final int maxEncodeHeight;
  final StreamController<JobStatusDto> _progress =
      StreamController<JobStatusDto>.broadcast();
  final List<bool> livePreviewToggles = [];
  int startExportCalls = 0;
  bool diagnosticShared = false;
  int cancelCalls = 0;
  Completer<String>? startCompleter;
  Completer<void>? cancelCompleter;

  _PreviewToggleRepository({
    this.maxEncodeWidth = 3840,
    this.maxEncodeHeight = 2160,
  });

  @override
  Stream<JobStatusDto> get progressStream => _progress.stream;

  @override
  Future<NativeCapabilitiesDto> getCapabilities() async =>
      NativeCapabilitiesDto(
        platform: 'test',
        osVersion: '1',
        gpuSupported: true,
        h264Encoder: true,
        hevcEncoder: true,
        maxEncodeWidth: maxEncodeWidth,
        maxEncodeHeight: maxEncodeHeight,
        cpuCores: 8,
        recommendedProfile: 'quality',
        supportedProfiles: const ['quality'],
        inferenceBackends: const ['test'],
      );

  @override
  Future<String> startExport({
    required String sourceUri,
    required String analysisCacheId,
    required String outputFilePath,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
    int targetWidth = 1920,
    int targetHeight = 1080,
    double targetFps = 30.0,
    int videoBitrate = 8000000,
    String processingProfile = 'quality',
    bool enableLivePreview = false,
    int trimStartMs = 0,
    int? trimEndMs,
  }) {
    startExportCalls++;
    return startCompleter?.future ?? Future<String>.value('preview-toggle-job');
  }

  @override
  Future<void> setExportLivePreviewEnabled({
    required String jobId,
    required bool enabled,
  }) async {
    livePreviewToggles.add(enabled);
  }

  @override
  Future<void> cancelJob(String jobId) {
    cancelCalls += 1;
    return cancelCompleter?.future ?? Future<void>.value();
  }

  void emitPreview(String path, {required int frame}) {
    _progress.add(
      JobStatusDto(
        jobId: 'preview-toggle-job',
        state: 'processing',
        currentFrame: frame,
        totalFrames: 100,
        fps: 10,
        progress: frame / 100,
        currentPreviewPath: path,
      ),
    );
  }

  void emitFailure(String message) {
    _progress.add(
      JobStatusDto(
        jobId: 'preview-toggle-job',
        state: 'failed',
        currentFrame: 8,
        totalFrames: 100,
        fps: 10,
        progress: 0.08,
        errorMessage: message,
      ),
    );
  }

  @override
  Future<Map<dynamic, dynamic>?> createDiagnosticBundle() async {
    return <dynamic, dynamic>{'filePath': '/tmp/diag.zip'};
  }

  @override
  Future<Map<dynamic, dynamic>?> shareDiagnosticBundle({
    String? filePath,
    String? publicUri,
  }) async {
    diagnosticShared = true;
    return <dynamic, dynamic>{'shared': true};
  }

  void dispose() {
    _progress.close();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
