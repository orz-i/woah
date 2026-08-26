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

      final persons = result.persons.map((dto) => dto.toDomain()).toList();
      final defaultSelected = persons.map((p) => p.id).toSet();

      state = state.copyWith(
        status: PersonSelectionStatus.ready,
        persons: persons,
        selectedPersonIds: defaultSelected,
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
    final updated = Set<int>.from(state.selectedPersonIds);
    if (updated.contains(id)) {
      updated.remove(id);
    } else {
      updated.add(id);
    }
    state = state.copyWith(selectedPersonIds: updated);
  }

  /// Select all detected persons
  void selectAll() {
    final allIds = state.persons.map((p) => p.id).toSet();
    state = state.copyWith(selectedPersonIds: allIds);
  }

  /// Deselect all detected persons
  void deselectAll() {
    state = state.copyWith(selectedPersonIds: {});
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
      analysisCacheId: state.analysisCacheId,
      updatedAt: DateTime.now(),
    );
  }

  /// Update project state (e.g. when returning from effect editor with updated effects/follow)
  void updateProject(DanceProject updated) {
    state = state.copyWith(
      project: updated,
      selectedPersonIds: updated.selectedPersonIds.isNotEmpty ? updated.selectedPersonIds : state.selectedPersonIds,
    );
  }
}
