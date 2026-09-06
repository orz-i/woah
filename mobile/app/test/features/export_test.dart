import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:app/features/export/domain/export_state.dart';
import 'package:app/features/export/presentation/export_controller.dart';
import 'package:app/features/export/presentation/export_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
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
    expect(controller.state.jobId, 'trim-job');
  });

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
  int? lastTrimStartMs;
  int? lastTrimEndMs;

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
    return 'trim-job';
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _PreviewToggleRepository implements NativeProcessingRepository {
  final StreamController<JobStatusDto> _progress =
      StreamController<JobStatusDto>.broadcast();
  final List<bool> livePreviewToggles = [];

  @override
  Stream<JobStatusDto> get progressStream => _progress.stream;

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
    return 'preview-toggle-job';
  }

  @override
  Future<void> setExportLivePreviewEnabled({
    required String jobId,
    required bool enabled,
  }) async {
    livePreviewToggles.add(enabled);
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

  void dispose() {
    _progress.close();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
