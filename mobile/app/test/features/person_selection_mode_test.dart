import 'package:app/features/person_selection/presentation/person_selection_controller.dart';
import 'package:app/features/person_selection/presentation/person_selection_screen.dart';
import 'package:app/features/person_selection/domain/person_selection_state.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'fresh analysis excludes weak candidate from selectable targets',
    () async {
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
        trimStartMs: 240,
        trimEndMs: 900,
        createdAt: now,
        updatedAt: now,
      );
      final repository = _FakePersonSelectionRepository(
        confidences: const [0.92, 0.56, 0.59, 0.60],
      );
      final controller = PersonSelectionController(repository);
      addTearDown(controller.dispose);

      await controller.analyzeProject(project);

      expect(controller.state.persons.map((p) => p.id).toSet(), equals({0, 3}));
      expect(controller.state.selectedPersonIds, equals({0, 3}));
      expect(controller.state.privacyModeForPerson(1), PersonPrivacyMode.none);
      expect(controller.state.privacyModeForPerson(2), PersonPrivacyMode.none);
      expect(repository.lastAnalyzeTrimStartMs, 240);
      expect(repository.lastPreviewTimestampMs, 240);

      controller.setPrivacyMode(1, PersonPrivacyMode.fullBody);
      expect(controller.state.selectedPersonIds, equals({0, 3}));
    },
  );

  test(
    'stored privacy mode cannot revive excluded weak first-frame candidate',
    () async {
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
        _FakePersonSelectionRepository(confidences: const [0.92, 0.56]),
      );
      addTearDown(controller.dispose);

      await controller.analyzeProject(project);

      expect(controller.state.persons.map((p) => p.id).toSet(), equals({0}));
      expect(controller.state.selectedPersonIds, isEmpty);
      expect(controller.state.faceOnlyPersonIds, isEmpty);
    },
  );

  testWidgets(
    'person selection applies one privacy mode to all selected targets',
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

      final controller = container.read(
        personSelectionControllerProvider.notifier,
      );
      // Legacy mixed projects are normalized to a single privacy-safe full-body mode.
      expect(controller.state.privacyMode, ProjectPrivacyMode.fullBody);
      expect(controller.state.selectedPersonIds, equals({0, 1}));
      expect(controller.state.faceOnlyPersonIds, isEmpty);
      expect(
        controller.state.privacyModeForPerson(0),
        PersonPrivacyMode.fullBody,
      );
      expect(
        controller.state.privacyModeForPerson(1),
        PersonPrivacyMode.fullBody,
      );
      expect(find.text('全身保护'), findsWidgets);
      expect(find.text('人脸保护'), findsWidgets);
      expect(find.text('选择要保护的人'), findsOneWidget);
      expect(find.text('点击画面中的人物可取消或重新选中'), findsOneWidget);
      expect(find.textContaining('已选择'), findsNothing);
      expect(find.byIcon(Icons.refresh_rounded), findsOneWidget);
      expect(find.byType(ListView), findsNothing);

      // The media canvas is the target-selection surface; no duplicate
      // thumbnail rail is required.
      expect(find.bySemanticsLabel('已选择人物'), findsNWidgets(2));
      await tester.tap(find.bySemanticsLabel('已选择人物').first);
      await tester.pump();
      expect(controller.state.selectedPersonIds, equals({1}));
      expect(find.bySemanticsLabel('未选择人物'), findsOneWidget);

      // The aligned top-right reselection icon restores the safe default-all
      // selection without rerunning detection.
      await tester.tap(find.byIcon(Icons.refresh_rounded));
      await tester.pump();
      expect(controller.state.selectedPersonIds, equals({0, 1}));

      await tester.tap(find.bySemanticsLabel('已选择人物').first);
      await tester.pump();
      expect(controller.state.selectedPersonIds, equals({1}));
      await tester.tap(find.bySemanticsLabel('未选择人物'));
      await tester.pump();
      expect(controller.state.selectedPersonIds, equals({0, 1}));

      await tester.tap(find.text('人脸保护').first);
      await tester.pump();
      expect(controller.state.privacyMode, ProjectPrivacyMode.faceOnly);
      expect(controller.state.selectedPersonIds, isEmpty);
      expect(controller.state.faceOnlyPersonIds, equals({0, 1}));
      expect(
        controller.state.privacyModeForPerson(0),
        PersonPrivacyMode.faceOnly,
      );
      expect(
        controller.state.privacyModeForPerson(1),
        PersonPrivacyMode.faceOnly,
      );

      controller.togglePerson(0);
      expect(controller.state.faceOnlyPersonIds, equals({1}));
      expect(controller.state.privacyModeForPerson(0), PersonPrivacyMode.none);

      await tester.tap(find.text('全身保护').first);
      await tester.pump();
      expect(controller.state.privacyMode, ProjectPrivacyMode.fullBody);
      expect(controller.state.selectedPersonIds, equals({1}));
      expect(controller.state.faceOnlyPersonIds, isEmpty);

      final configured = controller.buildConfiguredProject();
      expect(configured, isNotNull);
      expect(configured!.selectedPersonIds, equals({1}));
      expect(configured.faceOnlyPersonIds, isEmpty);
      expect(configured.persons.firstWhere((p) => p.id == 0).selected, isFalse);
      expect(configured.persons.firstWhere((p) => p.id == 1).selected, isTrue);
    },
  );
}

class _FakePersonSelectionRepository implements NativeProcessingRepository {
  final List<double> confidences;
  int? lastAnalyzeTrimStartMs;
  int? lastPreviewTimestampMs;

  _FakePersonSelectionRepository({this.confidences = const [0.94, 0.91]});

  @override
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
    int trimStartMs = 0,
  }) async {
    lastAnalyzeTrimStartMs = trimStartMs;
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
      persons: List.generate(confidences.length, (index) {
        final left = 0.05 + index * 0.20;
        return DetectedPersonDto(
          id: index,
          x1: left,
          y1: 0.1,
          x2: left + 0.15,
          y2: 0.9,
          thumbnailPath: '',
          confidence: confidences[index],
        );
      }),
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
  }) async {
    lastPreviewTimestampMs = timestampMs;
    return PreviewFrameDto(
      thumbnailPath: '',
      renderTimeMs: 1,
      timestampMs: timestampMs,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
