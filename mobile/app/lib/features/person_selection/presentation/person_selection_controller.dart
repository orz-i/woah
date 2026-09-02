import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import '../../../core/logging/app_logger.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/person_selection_state.dart';

final personSelectionControllerProvider = StateNotifierProvider.autoDispose<
    PersonSelectionController, PersonSelectionState>((ref) {
  final repo = ref.watch(nativeRepositoryProvider);
  return PersonSelectionController(repo);
});

class PersonSelectionController extends StateNotifier<PersonSelectionState> {
  /// Selection-screen-only gate for the first-frame YOLO result.
  ///
  /// Native analysis metadata deliberately keeps every detector result so export
  /// ID binding, TrackManager and compositor runtime behavior remain unchanged.
  /// This threshold only decides which first-frame people are offered to the user
  /// as selectable privacy targets.
  static const double selectionCandidateMinConfidence = 0.55;

  final NativeProcessingRepository _repository;

  PersonSelectionController(this._repository)
      : super(const PersonSelectionState());

  /// Run first frame YOLO segmentation on project video
  Future<void> analyzeProject(DanceProject project) async {
    try {
      state = state.copyWith(
        status: PersonSelectionStatus.analyzing,
        project: project,
        errorMessage: null,
      );

      AppLogger.d('PersonSelectionController', 'Analyzing video: ${project.sourceUri}');
      final result = await _repository.analyzeVideo(
        videoUri: project.sourceUri,
        modelProfile: 'balanced',
      );

      final analyzedPersons = result.persons.map((dto) => dto.toDomain()).toList();
      final persons = analyzedPersons
          .where((person) => person.confidence >= selectionCandidateMinConfidence)
          .toList();
      final excludedPersons = analyzedPersons
          .where((person) => person.confidence < selectionCandidateMinConfidence)
          .toList();
      if (excludedPersons.isNotEmpty) {
        AppLogger.d(
          'PersonSelectionController',
          'Excluded low-confidence first-frame selection candidates: '
              '${excludedPersons.map((person) => '${person.id}:${person.confidence.toStringAsFixed(3)}').join(', ')}',
        );
      }
      final personIds = persons.map((p) => p.id).toSet();
      final hasStoredPrivacyModes =
          project.selectedPersonIds.isNotEmpty || project.faceOnlyPersonIds.isNotEmpty;
      final defaultSelected = hasStoredPrivacyModes
          ? project.selectedPersonIds.intersection(personIds)
          : personIds;
      final defaultFaceOnly = hasStoredPrivacyModes
          ? project.faceOnlyPersonIds
              .intersection(personIds)
              .difference(defaultSelected)
          : <int>{};

      state = state.copyWith(
        status: PersonSelectionStatus.ready,
        persons: persons,
        selectedPersonIds: defaultSelected,
        faceOnlyPersonIds: defaultFaceOnly,
        analysisCacheId: result.analysisCacheId,
      );
    } catch (e, stack) {
      AppLogger.e('PersonSelectionController', 'Analysis failed', e, stack);
      state = state.copyWith(
        status: PersonSelectionStatus.error,
        errorMessage: '首帧人物分析失败: $e',
      );
    }
  }

  /// Toggle selection state for a specific person ID
  void togglePerson(int id) {
    final current = state.privacyModeForPerson(id);
    setPrivacyMode(
      id,
      current == PersonPrivacyMode.fullBody
          ? PersonPrivacyMode.none
          : PersonPrivacyMode.fullBody,
    );
  }

  /// Explicitly sets one person's privacy mode. The two persisted ID sets remain
  /// mutually exclusive in controller state; FULL_BODY conflict handling still
  /// exists at the domain/native boundary as an additional safety net.
  void setPrivacyMode(int id, PersonPrivacyMode mode) {
    if (!state.persons.any((person) => person.id == id)) {
      return;
    }
    final fullBody = Set<int>.from(state.selectedPersonIds)..remove(id);
    final faceOnly = Set<int>.from(state.faceOnlyPersonIds)..remove(id);
    switch (mode) {
      case PersonPrivacyMode.none:
        break;
      case PersonPrivacyMode.faceOnly:
        faceOnly.add(id);
      case PersonPrivacyMode.fullBody:
        fullBody.add(id);
    }
    state = state.copyWith(
      selectedPersonIds: fullBody,
      faceOnlyPersonIds: faceOnly,
    );
  }

  /// Select all detected persons
  void selectAll() {
    final allIds = state.persons.map((p) => p.id).toSet();
    state = state.copyWith(
      selectedPersonIds: allIds,
      faceOnlyPersonIds: {},
    );
  }

  /// Deselect all detected persons
  void deselectAll() {
    state = state.copyWith(
      selectedPersonIds: {},
      faceOnlyPersonIds: {},
    );
  }

  /// Produce final project with selection state applied
  DanceProject? buildConfiguredProject() {
    final proj = state.project;
    if (proj == null) return null;

    final updatedPersons = state.persons.map((p) {
      return p.copyWith(selected: state.selectedPersonIds.contains(p.id));
    }).toList();

    return proj.copyWith(
      persons: updatedPersons,
      selectedPersonIds: state.selectedPersonIds,
      faceOnlyPersonIds: state.faceOnlyPersonIds,
      analysisCacheId: state.analysisCacheId,
      updatedAt: DateTime.now(),
    );
  }

  /// Update project state (e.g. when returning from effect editor with updated effects/follow)
  void updateProject(DanceProject updated) {
    state = state.copyWith(
      project: updated,
      selectedPersonIds: updated.selectedPersonIds,
      faceOnlyPersonIds: updated.faceOnlyPersonIds,
    );
  }
}
