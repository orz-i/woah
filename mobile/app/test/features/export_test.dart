import 'package:flutter_test/flutter_test.dart';
import 'package:app/features/export/domain/export_state.dart';
import 'package:app/features/export/presentation/export_controller.dart';
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
