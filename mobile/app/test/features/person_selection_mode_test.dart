import 'package:app/features/person_selection/presentation/person_selection_controller.dart';
import 'package:app/features/person_selection/presentation/person_selection_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('fresh analysis excludes weak candidate from selectable targets', () async {
    final now = DateTime.utc(2026, 8, 31);
    final project = DanceProject(
      id: 'weak-candidate-default',
      sourceUri: 'file:///weak-candidate.mp4',
      videoInfo: const VideoInfo(
        codedWidth: 720,
        codedHeight: 1280,
        displayWidth: 720,
        displayHeight: 1280,
        fps: 60,
        durationMs: 1000,
        rotation: 0,
        videoCodec: 'h264',
        hasAudio: false,
      ),
      createdAt: now,
      updatedAt: now,
    );
    final controller = PersonSelectionController(
      _FakePersonSelectionRepository(confidences: const [0.92, 0.50]),
    );
    addTearDown(controller.dispose);

    await controller.analyzeProject(project);

    expect(controller.state.persons.map((p) => p.id).toSet(), equals({0}));
    expect(controller.state.selectedPersonIds, equals({0}));
    expect(controller.state.privacyModeForPerson(1), PersonPrivacyMode.none);

    controller.setPrivacyMode(1, PersonPrivacyMode.fullBody);
    expect(controller.state.selectedPersonIds, equals({0}));
  });

  test('stored privacy mode cannot revive excluded weak first-frame candidate', () async {
    final now = DateTime.utc(2026, 8, 31);
    final project = DanceProject(
      id: 'weak-stored-selection',
      sourceUri: 'file:///weak-stored-selection.mp4',
      videoInfo: const VideoInfo(
        codedWidth: 720,
        codedHeight: 1280,
        displayWidth: 720,
        displayHeight: 1280,
        fps: 60,
        durationMs: 1000,
        rotation: 0,
        videoCodec: 'h264',
        hasAudio: false,
      ),
      selectedPersonIds: const {1},
      createdAt: now,
      updatedAt: now,
    );
    final controller = PersonSelectionController(
      _FakePersonSelectionRepository(confidences: const [0.92, 0.50]),
    );
    addTearDown(controller.dispose);

    await controller.analyzeProject(project);

    expect(controller.state.persons.map((p) => p.id).toSet(), equals({0}));
    expect(controller.state.selectedPersonIds, isEmpty);
    expect(controller.state.faceOnlyPersonIds, isEmpty);
  });

  testWidgets('person selection keeps FULL_BODY and FACE_ONLY mutually exclusive',
      (tester) async {
    tester.view.physicalSize = const Size(720, 1280);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final now = DateTime.utc(2026, 8, 30);
    final project = DanceProject(
      id: 'privacy-mode-ui',
      sourceUri: 'file:///privacy-mode-ui.mp4',
      videoInfo: const VideoInfo(
        codedWidth: 720,
        codedHeight: 1280,
        displayWidth: 720,
        displayHeight: 1280,
        fps: 30,
        durationMs: 1000,
        rotation: 0,
        videoCodec: 'h264',
        hasAudio: false,
      ),
      selectedPersonIds: const {1},
      faceOnlyPersonIds: const {0},
      createdAt: now,
      updatedAt: now,
    );
    final repository = _FakePersonSelectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: PersonSelectionScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    final controller = container.read(personSelectionControllerProvider.notifier);
    expect(controller.state.selectedPersonIds, equals({1}));
    expect(controller.state.faceOnlyPersonIds, equals({0}));
    expect(controller.state.privacyModeForPerson(0), PersonPrivacyMode.faceOnly);
    expect(find.textContaining('仅人脸'), findsWidgets);

    await tester.tap(find.text('全身'));
    await tester.pump();
    expect(controller.state.selectedPersonIds, equals({0, 1}));
    expect(controller.state.faceOnlyPersonIds, isEmpty);
    expect(controller.state.privacyModeForPerson(0), PersonPrivacyMode.fullBody);

    await tester.tap(find.text('仅人脸'));
    await tester.pump();
    expect(controller.state.selectedPersonIds, equals({1}));
    expect(controller.state.faceOnlyPersonIds, equals({0}));
    expect(controller.state.privacyModeForPerson(0), PersonPrivacyMode.faceOnly);

    final configured = controller.buildConfiguredProject();
    expect(configured, isNotNull);
    expect(configured!.selectedPersonIds, equals({1}));
    expect(configured.faceOnlyPersonIds, equals({0}));
    expect(configured.persons.firstWhere((p) => p.id == 0).selected, isFalse);
    expect(configured.persons.firstWhere((p) => p.id == 1).selected, isTrue);
  });
}

class _FakePersonSelectionRepository implements NativeProcessingRepository {
  final List<double> confidences;

  _FakePersonSelectionRepository({this.confidences = const [0.94, 0.91]});

  @override
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
  }) async {
    return AnalyzeResultDto(
      analysisCacheId: 'privacy-mode-cache',
      videoInfo: VideoInfoDto(
        codedWidth: 720,
        codedHeight: 1280,
        displayWidth: 720,
        displayHeight: 1280,
        fps: 30,
        durationMs: 1000,
        rotation: 0,
        videoCodec: 'h264',
        audioCodec: null,
        hasAudio: false,
      ),
      persons: [
        DetectedPersonDto(
          id: 0,
          x1: 0.05,
          y1: 0.1,
          x2: 0.35,
          y2: 0.9,
          thumbnailPath: '',
          confidence: confidences[0],
        ),
        DetectedPersonDto(
          id: 1,
          x1: 0.55,
          y1: 0.1,
          x2: 0.85,
          y2: 0.9,
          thumbnailPath: '',
          confidence: confidences[1],
        ),
      ],
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
