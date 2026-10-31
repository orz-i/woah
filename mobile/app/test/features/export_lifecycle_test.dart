import 'dart:async';

import 'package:app/features/export/domain/export_state.dart';
import 'package:app/features/export/presentation/export_controller.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

// Promoted from docs/governance/evidence/export-lifecycle-repro.dart.txt.
// Barriers make ownership assertions independent of native scheduling.
void main() {
  late _Repository repo;
  late ExportController controller;
  var disposed = false;

  setUp(() {
    repo = _Repository();
    controller = ExportController(repo);
    disposed = false;
  });
  tearDown(() async {
    if (!disposed) controller.dispose();
    await tick();
    await repo.events.close();
  });

  test(
    'autoDispose provider cleans pending native start after listener leaves',
    () async {
      final localRepo = _Repository()..startBarrier = Completer<String>();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(localRepo)],
      );
      final subscription = container.listen(
        exportControllerProvider,
        (_, _) {},
      );
      final start = container
          .read(exportControllerProvider.notifier)
          .startExport(project(), 'out.mp4');
      await tick();
      subscription.close();
      await container.pump();
      localRepo.startBarrier!.complete('auto-disposed-job');
      await expectLater(start, completes);
      expect(localRepo.cancelled, ['auto-disposed-job']);
      expect(localRepo.activeJobs, isEmpty);
      expect(localRepo.events.hasListener, isFalse);
      container.dispose();
      await localRepo.events.close();
    },
  );

  test(
    'retry clears failed job before first await and cancels only late new ID',
    () async {
      await controller.startExport(project(), 'first.mp4');
      repo.emit('job-1', 'failed');
      await tick();
      repo.startBarrier = Completer<String>();
      final retry = controller.startExport(project(), 'second.mp4');
      expect(controller.state.jobId, isNull);
      expect(controller.state.errorMessage, isNull);
      await tick();
      final cancel = controller.cancelExport();
      await tick();
      expect(repo.cancelled, isEmpty);
      repo.startBarrier!.complete('job-2');
      await Future.wait([retry, cancel]);
      expect(repo.cancelled, ['job-2']);
      expect(repo.activeJobs, isEmpty);
      expect(controller.state.status, ExportJobState.cancelled);
      expect(controller.state.jobId, isNull);
    },
  );

  test(
    'retry clears completed output counters and plan but keeps preview choice',
    () async {
      controller.toggleLivePreview(true);
      await controller.startExport(project(), 'first.mp4');
      repo.emit('job-1', 'completed');
      await tick();
      repo.capabilityBarrier = Completer<NativeCapabilitiesDto>();
      final retry = controller.startExport(project(), 'second.mp4');
      final state = controller.state;
      expect(state.jobId, isNull);
      expect(state.outputUri, isNull);
      expect(state.currentPreviewPath, isNull);
      expect(state.exportPlan, isNull);
      expect(state.currentFrame, 0);
      expect(state.totalFrames, 0);
      expect(state.fps, 0);
      expect(state.progress, 0);
      expect(state.showLivePreview, isTrue);
      await controller.cancelExport();
      repo.capabilityBarrier!.complete(capabilities());
      await retry;
      expect(repo.starts, 1);
    },
  );

  test(
    'synchronous plan listener can dispose before native submission',
    () async {
      controller.addListener((state) {
        if (state.exportPlan != null) {
          controller.dispose();
          disposed = true;
        }
      });
      await expectLater(
        controller.startExport(project(), 'out.mp4'),
        completes,
      );
      await tick();
      expect(repo.starts, 0);
      expect(repo.events.hasListener, isFalse);
    },
  );

  test(
    'synchronous plan listener can cancel before native submission',
    () async {
      Future<void>? cancel;
      controller.addListener((state) {
        if (state.exportPlan != null &&
            state.status != ExportJobState.cancelled) {
          cancel ??= controller.cancelExport();
        }
      });
      await controller.startExport(project(), 'out.mp4');
      await cancel;
      expect(repo.starts, 0);
      expect(controller.state.status, ExportJobState.cancelled);
    },
  );

  test('cancel during capabilities never submits native job', () async {
    repo.capabilityBarrier = Completer<NativeCapabilitiesDto>();
    final start = controller.startExport(project(), 'out.mp4');
    await controller.cancelExport();
    repo.capabilityBarrier!.complete(capabilities());
    await start;
    expect(repo.starts, 0);
    expect(repo.cancelled, isEmpty);
    expect(controller.state.status, ExportJobState.cancelled);
  });

  test(
    'old capability continuation cannot mutate a newer generation',
    () async {
      repo.capabilityBarrier = Completer<NativeCapabilitiesDto>();
      final oldBarrier = repo.capabilityBarrier!;
      final old = controller.startExport(project(), 'old.mp4');
      await controller.cancelExport();
      repo.capabilityBarrier = null;
      await controller.startExport(project(), 'new.mp4');
      oldBarrier.complete(capabilities());
      await old;
      expect(repo.starts, 1);
      expect(controller.state.jobId, 'job-1');
    },
  );

  test(
    'dispose during capabilities does not start native work or keep listener',
    () async {
      repo.capabilityBarrier = Completer<NativeCapabilitiesDto>();
      final start = controller.startExport(project(), 'out.mp4');
      controller.dispose();
      disposed = true;
      repo.capabilityBarrier!.complete(capabilities());
      await start;
      await tick();
      expect(repo.starts, 0);
      expect(repo.events.hasListener, isFalse);
    },
  );

  test(
    'dispose during native start cleans late job and completes ignored Future',
    () async {
      repo.startBarrier = Completer<String>();
      final start = controller.startExport(project(), 'out.mp4');
      await tick();
      controller.dispose();
      disposed = true;
      repo.startBarrier!.complete('late-job');
      await expectLater(start, completes);
      expect(repo.cancelled, ['late-job']);
      expect(repo.activeJobs, isEmpty);
      expect(repo.events.hasListener, isFalse);
    },
  );

  test('native start failure after disposal is contained', () async {
    repo.startBarrier = Completer<String>();
    final start = controller.startExport(project(), 'out.mp4');
    await tick();
    controller.dispose();
    disposed = true;
    repo.startBarrier!.completeError(StateError('start rejected'));
    await expectLater(start, completes);
    expect(repo.cancelled, isEmpty);
    expect(repo.events.hasListener, isFalse);
  });

  test(
    'disposed cleanup retries transient cancellation failure with same ID',
    () async {
      repo.startBarrier = Completer<String>();
      repo.cancelFailures = 1;
      final start = controller.startExport(project(), 'out.mp4');
      await tick();
      controller.dispose();
      disposed = true;
      repo.startBarrier!.complete('late-job');
      await start;
      expect(repo.cancelled, ['late-job', 'late-job']);
      expect(repo.activeJobs, isEmpty);
    },
  );

  test(
    'persistent disposed cleanup error is contained, not marked success',
    () async {
      repo.startBarrier = Completer<String>();
      repo.cancelFailures = 2;
      final start = controller.startExport(project(), 'out.mp4');
      await tick();
      controller.dispose();
      disposed = true;
      repo.startBarrier!.complete('late-job');
      await expectLater(start, completes);
      expect(repo.cancelled, ['late-job', 'late-job']);
      expect(repo.activeJobs, {
        'late-job',
      }); // Native rejection remains a real failure.
      expect(repo.events.hasListener, isFalse);
    },
  );

  test('duplicate starts and cancels share one native operation', () async {
    repo.startBarrier = Completer<String>();
    final start = controller.startExport(project(), 'out.mp4');
    await controller.startExport(project(), 'duplicate.mp4');
    await tick();
    final first = controller.cancelExport();
    final second = controller.cancelExport();
    expect(identical(first, second), isTrue);
    repo.startBarrier!.complete('late-job');
    await Future.wait([start, first, second]);
    expect(repo.starts, 1);
    expect(repo.cancelled, ['late-job']);
    await controller.cancelExport();
    expect(repo.cancelled, ['late-job']);
  });

  test(
    'matching early terminal is replayed, unrelated and late progress ignored',
    () async {
      repo.startBarrier = Completer<String>();
      final start = controller.startExport(project(), 'out.mp4');
      await tick();
      repo.emit('other-job', 'failed');
      repo.emit('late-job', 'completed');
      repo.emit('late-job', 'processing');
      await tick();
      expect(controller.state.jobId, isNull);
      expect(controller.state.status, ExportJobState.preparing);
      repo.startBarrier!.complete('late-job');
      await start;
      expect(controller.state.status, ExportJobState.completed);
      repo.emit('late-job', 'processing');
      repo.emit('late-job', 'failed');
      await tick();
      expect(controller.state.status, ExportJobState.completed);
      await controller.cancelExport();
      expect(repo.cancelled, isEmpty);
    },
  );

  test(
    'job publication cancellation defers buffered terminal until RPC settles',
    () async {
      repo.startBarrier = Completer<String>();
      repo.cancelBarrier = Completer<void>();
      Future<void>? cancel;
      controller.addListener((state) {
        if (state.jobId != null && state.status != ExportJobState.cancelled) {
          cancel ??= controller.cancelExport();
        }
      });
      final start = controller.startExport(project(), 'out.mp4');
      await tick();
      repo.emit('late-job', 'completed');
      await tick();
      repo.startBarrier!.complete('late-job');
      await tick();
      expect(repo.cancelled, ['late-job']);
      expect(controller.state.status, ExportJobState.preparing);
      expect(controller.state.outputUri, isNull);
      repo.cancelBarrier!.complete();
      await Future.wait([start, cancel!]);
      expect(controller.state.status, ExportJobState.cancelled);
    },
  );

  test('old job events cannot change retry before or after new ID', () async {
    await controller.startExport(project(), 'first.mp4');
    repo.emit('job-1', 'failed');
    await tick();
    repo.startBarrier = Completer<String>();
    final retry = controller.startExport(project(), 'second.mp4');
    await tick();
    repo.emit('job-1', 'completed');
    await tick();
    expect(controller.state.outputUri, isNull);
    repo.startBarrier!.complete('job-2');
    await retry;
    repo.emit('job-1', 'failed');
    repo.emit('job-2', 'processing');
    await tick();
    expect(controller.state.status, ExportJobState.processing);
    expect(controller.state.jobId, 'job-2');
  });

  test(
    'pending-start cancellation error exposes new job and allows retry',
    () async {
      repo.startBarrier = Completer<String>();
      repo.cancelFailures = 1;
      final start = controller.startExport(project(), 'out.mp4');
      await tick();
      final check = expectLater(controller.cancelExport(), throwsStateError);
      repo.startBarrier!.complete('late-job');
      await Future.wait([start, check]);
      expect(controller.state.jobId, 'late-job');
      expect(controller.state.status, isNot(ExportJobState.cancelled));
      await controller.cancelExport();
      expect(repo.cancelled, ['late-job', 'late-job']);
      expect(repo.activeJobs, isEmpty);
    },
  );

  test(
    'cancel error replays deferred terminal; success suppresses late events',
    () async {
      await controller.startExport(project(), 'out.mp4');
      repo.cancelBarrier = Completer<void>();
      final error = expectLater(controller.cancelExport(), throwsStateError);
      repo.emit('job-1', 'failed');
      await tick();
      expect(controller.state.status, isNot(ExportJobState.failed));
      repo.cancelBarrier!.completeError(StateError('cancel rejected'));
      await error;
      expect(controller.state.status, ExportJobState.failed);
      repo.emit('job-1', 'processing');
      await tick();
      expect(controller.state.status, ExportJobState.failed);
    },
  );

  test(
    'dispose shares pending cancel and removes subscription exactly once',
    () async {
      await controller.startExport(project(), 'out.mp4');
      repo.cancelBarrier = Completer<void>();
      final cancel = controller.cancelExport();
      await tick();
      controller.dispose();
      disposed = true;
      repo.cancelBarrier!.complete();
      await cancel;
      await tick();
      expect(repo.cancelled, ['job-1']);
      expect(repo.listenerCancels, 1);
      expect(repo.activeJobs, isEmpty);
    },
  );
}

Future<void> tick() => Future<void>.delayed(Duration.zero);

DanceProject project() {
  final now = DateTime.utc(2026, 9, 5);
  return DanceProject(
    id: 'export-lifecycle',
    sourceUri: '/synthetic.mp4',
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
    analysisCacheId: 'synthetic-cache',
    selectedPersonIds: const {0},
    createdAt: now,
    updatedAt: now,
  );
}

NativeCapabilitiesDto capabilities() => NativeCapabilitiesDto(
  platform: 'test',
  osVersion: '1',
  gpuSupported: true,
  h264Encoder: true,
  hevcEncoder: true,
  maxEncodeWidth: 3840,
  maxEncodeHeight: 2160,
  cpuCores: 8,
  recommendedProfile: 'quality',
  supportedProfiles: const ['quality'],
  inferenceBackends: const ['test'],
);

class _Repository implements NativeProcessingRepository {
  late final events = StreamController<JobStatusDto>.broadcast(
    onCancel: () => listenerCancels++,
  );
  int listenerCancels = 0;
  int starts = 0;
  int cancelFailures = 0;
  final cancelled = <String>[];
  final activeJobs = <String>{};
  final terminalJobs = <String>{};
  Completer<NativeCapabilitiesDto>? capabilityBarrier;
  Completer<String>? startBarrier;
  Completer<void>? cancelBarrier;

  @override
  Stream<JobStatusDto> get progressStream => events.stream;
  @override
  Future<NativeCapabilitiesDto> getCapabilities() async =>
      capabilityBarrier == null
      ? capabilities()
      : await capabilityBarrier!.future;
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
    double targetFps = 30,
    int videoBitrate = 8000000,
    String processingProfile = 'quality',
    double cropClarityScale = 1,
    bool enableLivePreview = false,
    int trimStartMs = 0,
    int? trimEndMs,
  }) async {
    starts++;
    final id = startBarrier == null
        ? 'job-$starts'
        : await startBarrier!.future;
    if (!terminalJobs.contains(id)) activeJobs.add(id);
    return id;
  }

  @override
  Future<void> cancelJob(String jobId) async {
    cancelled.add(jobId);
    if (cancelFailures > 0) {
      cancelFailures--;
      throw StateError('cancel rejected');
    }
    await cancelBarrier?.future;
    activeJobs.remove(jobId);
  }

  @override
  Future<void> setExportLivePreviewEnabled({
    required String jobId,
    required bool enabled,
  }) async {}
  void emit(String jobId, String state) {
    if (['failed', 'completed', 'cancelled'].contains(state)) {
      terminalJobs.add(jobId);
      activeJobs.remove(jobId);
    }
    events.add(
      JobStatusDto(
        jobId: jobId,
        state: state,
        currentFrame: 8,
        totalFrames: 100,
        fps: 10,
        progress: 0.08,
        currentPreviewPath: '/synthetic-preview.jpg',
        outputUri: state == 'completed' ? '/synthetic-output.mp4' : null,
        errorMessage: state == 'failed' ? 'encoder rejected' : null,
      ),
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
