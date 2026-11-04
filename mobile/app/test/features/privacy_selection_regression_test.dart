import 'package:app/features/person_selection/presentation/person_selection_controller.dart';
import 'package:app/features/person_selection/domain/person_selection_state.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter_test/flutter_test.dart';

const _videoInfo = VideoInfo(
  codedWidth: 1920,
  codedHeight: 1080,
  displayWidth: 1920,
  displayHeight: 1080,
  fps: 25,
  durationMs: 23240,
  rotation: 0,
  videoCodec: 'h264',
  hasAudio: false,
);

DanceProject _project() => DanceProject(
  id: 'privacy_selection_regression',
  sourceUri: 'group-hd.mp4',
  videoInfo: _videoInfo,
  createdAt: DateTime(2026),
  updatedAt: DateTime(2026),
);

class _Repository implements NativeProcessingRepository {
  int analysisCalls = 0;

  @override
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
    int trimStartMs = 0,
  }) async {
    analysisCalls++;
    return AnalyzeResultDto(
      analysisCacheId: 'group_analysis',
      videoInfo: _videoInfo.toDto(),
      persons: [
        DetectedPersonDto(
          id: 5,
          x1: .6116,
          y1: .2909,
          x2: .6671,
          y2: .7310,
          confidence: .34719809889793396,
          thumbnailPath: '',
        ),
        DetectedPersonDto(
          id: 6,
          x1: .5852,
          y1: .1969,
          x2: .7738,
          y2: .8975,
          confidence: .7987349033355713,
          thumbnailPath: '',
        ),
      ],
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  test(
    'occluded low confidence person remains protected when choosing a hero',
    () async {
      final repository = _Repository();
      final controller = PersonSelectionController(repository);
      addTearDown(controller.dispose);

      await controller.analyzeProject(
        _project(),
        privacyMode: ProjectPrivacyMode.faceOnly,
        selectionPreviewEnabled: false,
      );
      expect(controller.state.persons.map((p) => p.id), [5, 6]);
      expect(controller.state.faceOnlyPersonIds, {5, 6});
      controller.deselectPerson(6);
      final configured = controller.buildConfiguredProject()!;
      expect(configured.faceOnlyPersonIds, {5});
      expect(configured.selectedPersonIds, isEmpty);
      expect(configured.persons.map((p) => p.id), [5, 6]);
    },
  );

  test(
    'cached project retains a low confidence explicit privacy target',
    () async {
      final repository = _Repository();
      final controller = PersonSelectionController(repository);
      addTearDown(controller.dispose);
      final cached = _project().copyWith(
        analysisCacheId: 'cached_group',
        selectedPersonIds: {5},
        persons: const [
          PersonTrack(
            id: 5,
            normalizedInitialBox: NormalizedRect(
              left: .61,
              top: .29,
              right: .67,
              bottom: .73,
            ),
            thumbnailPath: '',
            confidence: .347,
          ),
        ],
      );
      await controller.prepareProject(cached, selectionPreviewEnabled: false);
      expect(repository.analysisCalls, 0);
      expect(controller.state.persons.single.id, 5);
      expect(controller.buildConfiguredProject()!.selectedPersonIds, {5});
    },
  );
}
