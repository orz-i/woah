import 'dart:async';

import 'package:app/core/cache/analysis_session.dart';
import 'package:app/features/person_selection/presentation/person_selection_controller.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter_test/flutter_test.dart';

const info = VideoInfo(
  codedWidth: 10,
  codedHeight: 10,
  displayWidth: 10,
  displayHeight: 10,
  fps: 30,
  durationMs: 1000,
  rotation: 0,
  videoCodec: 'h264',
  hasAudio: false,
);
DanceProject project(String id) => DanceProject(
  id: id,
  sourceUri: 'file:///$id.mp4',
  videoInfo: info,
  createdAt: DateTime(2026),
  updatedAt: DateTime(2026),
);
AnalyzeResultDto result(String id) => AnalyzeResultDto(
  analysisCacheId: id,
  videoInfo: VideoInfoDto(
    codedWidth: 10,
    codedHeight: 10,
    displayWidth: 10,
    displayHeight: 10,
    fps: 30,
    durationMs: 1000,
    rotation: 0,
    videoCodec: 'h264',
    hasAudio: false,
  ),
  persons: [],
);

class DeferredRepository implements NativeProcessingRepository {
  final pending = <Completer<AnalyzeResultDto>>[];
  final released = <String>[];
  @override
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
    int trimStartMs = 0,
  }) {
    final value = Completer<AnalyzeResultDto>();
    pending.add(value);
    return value.future;
  }

  @override
  Future<void> releaseProject(String projectId) async {
    released.add(projectId);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  test(
    '100 synthetic sessions close once and late results release themselves',
    () async {
      final released = <String>[];
      for (var i = 0; i < 100; i++) {
        final owner = AnalysisSession(
          releaseCache: (id) async {
            released.add(id);
          },
        );
        owner.track('cache_$i');
        await Future.wait([owner.close(), owner.close()]);
        owner.track('late_$i');
        await owner.close();
      }
      expect(released.length, 200);
      expect(released.toSet().length, 200);
    },
  );
  test(
    'a release failure can retry and never becomes an unhandled future',
    () async {
      var calls = 0;
      final owner = AnalysisSession(
        releaseCache: (_) async {
          if (++calls == 1) throw StateError('synthetic');
        },
      );
      owner.track('cache');
      await owner.close();
      await owner.close();
      await owner.close();
      expect(calls, 2);
    },
  );
  test(
    'unadopted duplicate cannot release a cache still owned by this session',
    () async {
      final released = <String>[];
      final owner = AnalysisSession(
        releaseCache: (id) async {
          released.add(id);
        },
      );
      owner.track('same');
      await owner.releaseUnadopted('same');
      expect(released, isEmpty);
      await owner.close();
      expect(released, ['same']);
    },
  );
  test(
    'older analysis completing after replacement cannot overwrite new state',
    () async {
      final repo = DeferredRepository();
      final controller = PersonSelectionController(repo);
      final a = AnalysisSession(releaseCache: repo.releaseProject);
      final b = AnalysisSession(releaseCache: repo.releaseProject);
      final first = controller.analyzeProject(
        project('A'),
        session: a,
        selectionPreviewEnabled: false,
      );
      final second = controller.analyzeProject(
        project('B'),
        session: b,
        selectionPreviewEnabled: false,
      );
      repo.pending[1].complete(result('cache_B'));
      expect(await second, isTrue);
      repo.pending[0].complete(result('cache_A'));
      expect(await first, isFalse);
      expect(controller.state.analysisCacheId, 'cache_B');
      expect(repo.released, ['cache_A']);
      await a.close();
      expect(repo.released, ['cache_A']);
      await b.close();
      expect(repo.released, ['cache_A', 'cache_B']);
      controller.dispose();
    },
  );
  test('late analysis error cannot replace a newer ready state', () async {
    final repo = DeferredRepository();
    final controller = PersonSelectionController(repo);
    final owner = AnalysisSession(releaseCache: repo.releaseProject);
    final first = controller.analyzeProject(
      project('A'),
      session: owner,
      selectionPreviewEnabled: false,
    );
    final second = controller.analyzeProject(
      project('B'),
      session: owner,
      selectionPreviewEnabled: false,
    );
    repo.pending[1].complete(result('cache_B'));
    await second;
    repo.pending[0].completeError(StateError('synthetic old failure'));
    expect(await first, isFalse);
    expect(controller.state.analysisCacheId, 'cache_B');
    expect(controller.state.errorMessage, isNull);
    controller.dispose();
    await owner.close();
  });
  test(
    'closed session receives no state update and releases late analysis',
    () async {
      final repo = DeferredRepository();
      final controller = PersonSelectionController(repo);
      final owner = AnalysisSession(releaseCache: repo.releaseProject);
      final operation = controller.analyzeProject(
        project('A'),
        session: owner,
        selectionPreviewEnabled: false,
      );
      await owner.close();
      repo.pending.single.complete(result('late'));
      expect(await operation, isFalse);
      expect(repo.released, ['late']);
      controller.dispose();
    },
  );
  test(
    'disposed controller releases its late response without reading state',
    () async {
      final repo = DeferredRepository();
      final controller = PersonSelectionController(repo);
      final owner = AnalysisSession(releaseCache: repo.releaseProject);
      final operation = controller.analyzeProject(
        project('A'),
        session: owner,
        selectionPreviewEnabled: false,
      );
      controller.dispose();
      repo.pending.single.complete(result('late'));
      expect(await operation, isFalse);
      expect(repo.released, ['late']);
      await owner.close();
    },
  );
  test(
    'preparing an already cached project invalidates an older analysis',
    () async {
      final repo = DeferredRepository();
      final controller = PersonSelectionController(repo);
      final owner = AnalysisSession(releaseCache: repo.releaseProject);
      final operation = controller.analyzeProject(
        project('A'),
        session: owner,
        selectionPreviewEnabled: false,
      );
      final cached = project('B').copyWith(
        analysisCacheId: 'existing',
        persons: [
          const PersonTrack(
            id: 0,
            normalizedInitialBox: NormalizedRect(
              left: 0,
              top: 0,
              right: 1,
              bottom: 1,
            ),
            thumbnailPath: '',
            confidence: 1,
          ),
        ],
      );
      expect(
        await controller.prepareProject(
          cached,
          session: owner,
          selectionPreviewEnabled: false,
        ),
        isTrue,
      );
      repo.pending.single.complete(result('late'));
      expect(await operation, isFalse);
      expect(controller.state.analysisCacheId, 'existing');
      expect(repo.released, ['late']);
      controller.dispose();
      await owner.close();
      expect(repo.released, ['late', 'existing']);
    },
  );
}
