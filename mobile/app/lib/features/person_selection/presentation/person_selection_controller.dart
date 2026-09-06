import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/logging/app_logger.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/person_selection_state.dart';

final personSelectionControllerProvider =
    StateNotifierProvider.autoDispose<
      PersonSelectionController,
      PersonSelectionState
    >((ref) {
      final repo = ref.watch(nativeRepositoryProvider);
      return PersonSelectionController(repo);
    });

class PersonSelectionController extends StateNotifier<PersonSelectionState> {
  /// Selection-screen-only gate for the first-frame YOLO result.
  /// Native analysis metadata remains untouched.
  static const double selectionCandidateMinConfidence = 0.60;

  final NativeProcessingRepository _repository;

  PersonSelectionController(this._repository)
    : super(const PersonSelectionState());

  Future<void> analyzeProject(DanceProject project) async {
    try {
      state = state.copyWith(
        status: PersonSelectionStatus.analyzing,
        project: project,
        errorMessage: null,
        clearAnalysisCacheId: true,
        clearSelectionPreviewPath: true,
        selectionPreviewLoading: false,
      );

      AppLogger.d(
        'PersonSelectionController',
        'Analyzing video: ${project.sourceUri}',
      );
      final result = await _repository.analyzeVideo(
        videoUri: project.sourceUri,
        modelProfile: 'balanced',
        trimStartMs: project.trimStartMs,
      );

      final analyzedPersons = result.persons
          .map((dto) => dto.toDomain())
          .toList();
      final persons = analyzedPersons
          .where(
            (person) => person.confidence >= selectionCandidateMinConfidence,
          )
          .toList();
      final excludedPersons = analyzedPersons
          .where(
            (person) => person.confidence < selectionCandidateMinConfidence,
          )
          .toList();

      if (excludedPersons.isNotEmpty) {
        AppLogger.d(
          'PersonSelectionController',
          'Excluded low-confidence first-frame selection candidates: '
              '${excludedPersons.map((person) => '${person.id}:${person.confidence.toStringAsFixed(3)}').join(', ')}',
        );
      }

      final personIds = persons.map((person) => person.id).toSet();
      final storedTargets = {
        ...project.selectedPersonIds,
        ...project.faceOnlyPersonIds,
      }.intersection(personIds);
      final hasStoredTargets =
          project.selectedPersonIds.isNotEmpty ||
          project.faceOnlyPersonIds.isNotEmpty;
      final targetIds = hasStoredTargets ? storedTargets : personIds;

      // Legacy mixed projects are normalized to full-body for privacy safety.
      // A pure face-only project remains face-only when revisited.
      final initialMode =
          project.selectedPersonIds.isEmpty &&
              project.faceOnlyPersonIds.isNotEmpty
          ? ProjectPrivacyMode.faceOnly
          : ProjectPrivacyMode.fullBody;

      state = state.copyWith(
        status: PersonSelectionStatus.ready,
        persons: persons,
        selectedPersonIds: initialMode == ProjectPrivacyMode.fullBody
            ? targetIds
            : <int>{},
        faceOnlyPersonIds: initialMode == ProjectPrivacyMode.faceOnly
            ? targetIds
            : <int>{},
        privacyMode: initialMode,
        analysisCacheId: result.analysisCacheId,
        selectionPreviewLoading: true,
      );

      await _executePreviewRequest();
    } catch (e, stack) {
      AppLogger.e('PersonSelectionController', 'Analysis failed', e, stack);
      state = state.copyWith(
        status: PersonSelectionStatus.error,
        errorMessage: '人物分析失败，请重试。',
        selectionPreviewLoading: false,
      );
    }
  }

  static const selectionMaskEffect = EffectConfig(
    fillMode: FillMode.solid,
    fillColorArgb: 0xFFF44848,
    opacity: 0.38,
    borderWidth: 0,
  );

  int _previewRequestId = 0;

  void _requestSelectionPreview() {
    _executePreviewRequest();
  }

  Future<void> _executePreviewRequest() async {
    final cacheId = state.analysisCacheId;
    final project = state.project;
    if (cacheId == null || project == null) return;

    final currentRequestId = ++_previewRequestId;
    state = state.copyWith(selectionPreviewLoading: true);
    try {
      final isFaceOnly = state.privacyMode == ProjectPrivacyMode.faceOnly;
      final selectedPersonIds = isFaceOnly
          ? const <int>[]
          : state.selectedPersonIds.toList();
      final faceOnlyPersonIds = isFaceOnly
          ? state.faceOnlyPersonIds.toList()
          : const <int>[];

      final preview = await _repository.getPreviewFrame(
        analysisCacheId: cacheId,
        timestampMs: project.trimStartMs,
        selectedPersonIds: selectedPersonIds,
        faceOnlyPersonIds: faceOnlyPersonIds,
        effects: selectionMaskEffect,
        follow: const FollowConfig(),
      );

      if (!mounted || state.analysisCacheId != cacheId) return;
      if (currentRequestId == _previewRequestId) {
        state = state.copyWith(
          selectionPreviewPath: preview.thumbnailPath,
          selectionPreviewLoading: false,
        );
      }
    } catch (e) {
      AppLogger.d(
        'PersonSelectionController',
        'Selection stage preview unavailable: $e',
      );
      if (mounted &&
          state.analysisCacheId == cacheId &&
          currentRequestId == _previewRequestId) {
        state = state.copyWith(selectionPreviewLoading: false);
      }
    }
  }

  void setProjectPrivacyMode(ProjectPrivacyMode mode) {
    if (state.privacyMode == mode) return;
    _applyTargets(state.privacyTargetIds, mode: mode);
  }

  void togglePerson(int id) {
    if (!state.persons.any((person) => person.id == id)) return;
    final targets = Set<int>.from(state.privacyTargetIds);
    if (!targets.add(id)) targets.remove(id);
    _applyTargets(targets);
  }

  /// Compatibility entry point for older callers/tests. V2 intentionally turns
  /// any non-none choice into a project-wide mode rather than a per-person mode.
  void setPrivacyMode(int id, PersonPrivacyMode mode) {
    if (!state.persons.any((person) => person.id == id)) return;
    final targets = Set<int>.from(state.privacyTargetIds);
    switch (mode) {
      case PersonPrivacyMode.none:
        targets.remove(id);
        _applyTargets(targets);
      case PersonPrivacyMode.faceOnly:
        targets.add(id);
        _applyTargets(targets, mode: ProjectPrivacyMode.faceOnly);
      case PersonPrivacyMode.fullBody:
        targets.add(id);
        _applyTargets(targets, mode: ProjectPrivacyMode.fullBody);
    }
  }

  void selectAll() {
    _applyTargets(state.persons.map((person) => person.id).toSet());
  }

  void resetSelection() {
    final project = state.project;
    final personIds = state.persons.map((person) => person.id).toSet();
    if (project != null) {
      final storedTargets = {
        ...project.selectedPersonIds,
        ...project.faceOnlyPersonIds,
      }.intersection(personIds);
      final targets = storedTargets.isNotEmpty ? storedTargets : personIds;
      final mode =
          project.selectedPersonIds.isEmpty &&
              project.faceOnlyPersonIds.isNotEmpty
          ? ProjectPrivacyMode.faceOnly
          : ProjectPrivacyMode.fullBody;
      _applyTargets(targets, mode: mode);
    } else {
      _applyTargets(personIds, mode: ProjectPrivacyMode.fullBody);
    }
  }

  void deselectAll() {
    _applyTargets(<int>{});
  }

  void _applyTargets(Set<int> targets, {ProjectPrivacyMode? mode}) {
    final effectiveMode = mode ?? state.privacyMode;
    state = state.copyWith(
      privacyMode: effectiveMode,
      selectedPersonIds: effectiveMode == ProjectPrivacyMode.fullBody
          ? targets
          : <int>{},
      faceOnlyPersonIds: effectiveMode == ProjectPrivacyMode.faceOnly
          ? targets
          : <int>{},
    );
    _requestSelectionPreview();
  }

  DanceProject? buildConfiguredProject() {
    final project = state.project;
    if (project == null) return null;

    final updatedPersons = state.persons.map((person) {
      return person.copyWith(
        selected: state.selectedPersonIds.contains(person.id),
      );
    }).toList();

    var effects = project.effects;
    if (state.privacyMode == ProjectPrivacyMode.fullBody &&
        (effects.faceStickerEnabled || effects.fillMode == FillMode.sticker)) {
      effects = effects.copyWith(
        fillMode: FillMode.solid,
        faceStickerEnabled: false,
        stickerAssetId: 'disabled',
      );
    } else if (state.privacyMode == ProjectPrivacyMode.faceOnly &&
        effects.stickerAssetId == null) {
      // Before the effect-style selector existed, FACE_ONLY always rendered as
      // an opaque privacy sticker. Keep that safe/compatible first-run default.
      effects = effects.copyWith(
        fillMode: FillMode.sticker,
        faceStickerEnabled: true,
        stickerAssetId: 'builtin:sunglasses',
        stickerScale: 1.0,
      );
    }

    return project.copyWith(
      persons: updatedPersons,
      selectedPersonIds: state.selectedPersonIds,
      faceOnlyPersonIds: state.faceOnlyPersonIds,
      effects: effects,
      analysisCacheId: state.analysisCacheId,
      updatedAt: DateTime.now(),
    );
  }

  void updateProject(DanceProject updated) {
    final targets = {
      ...updated.selectedPersonIds,
      ...updated.faceOnlyPersonIds,
    };
    final mode =
        updated.selectedPersonIds.isEmpty &&
            updated.faceOnlyPersonIds.isNotEmpty
        ? ProjectPrivacyMode.faceOnly
        : ProjectPrivacyMode.fullBody;

    state = state.copyWith(project: updated);
    _applyTargets(targets, mode: mode);
  }
}
