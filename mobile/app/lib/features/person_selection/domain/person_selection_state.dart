import 'package:dance_domain/dance_domain.dart';

enum PersonSelectionStatus { analyzing, ready, error }

/// V2 applies one privacy mode to the whole project.
///
/// Targets remain independent (selected / not selected), while the protection
/// style is globally either full-body or face-only. This removes the ambiguous
/// per-person three-state interaction without changing the persisted domain
/// format or native request contract.
enum ProjectPrivacyMode { fullBody, faceOnly }

class PersonSelectionState {
  final PersonSelectionStatus status;
  final DanceProject? project;
  final List<PersonTrack> persons;
  final Set<int> selectedPersonIds;
  final Set<int> faceOnlyPersonIds;
  final ProjectPrivacyMode privacyMode;
  final String? errorMessage;
  final String? analysisCacheId;
  final String? selectionPreviewPath;
  final bool selectionPreviewLoading;

  const PersonSelectionState({
    this.status = PersonSelectionStatus.analyzing,
    this.project,
    this.persons = const [],
    this.selectedPersonIds = const {},
    this.faceOnlyPersonIds = const {},
    this.privacyMode = ProjectPrivacyMode.fullBody,
    this.errorMessage,
    this.analysisCacheId,
    this.selectionPreviewPath,
    this.selectionPreviewLoading = false,
  });

  bool get isAnalyzing => status == PersonSelectionStatus.analyzing;
  bool get hasPersons => persons.isNotEmpty;

  Set<int> get privacyTargetIds => {...faceOnlyPersonIds, ...selectedPersonIds};

  bool isPersonSelected(int personId) => privacyTargetIds.contains(personId);

  PersonPrivacyMode privacyModeForPerson(int personId) {
    if (!isPersonSelected(personId)) return PersonPrivacyMode.none;
    return privacyMode == ProjectPrivacyMode.faceOnly
        ? PersonPrivacyMode.faceOnly
        : PersonPrivacyMode.fullBody;
  }

  PersonSelectionState copyWith({
    PersonSelectionStatus? status,
    DanceProject? project,
    List<PersonTrack>? persons,
    Set<int>? selectedPersonIds,
    Set<int>? faceOnlyPersonIds,
    ProjectPrivacyMode? privacyMode,
    String? errorMessage,
    String? analysisCacheId,
    bool clearAnalysisCacheId = false,
    String? selectionPreviewPath,
    bool clearSelectionPreviewPath = false,
    bool? selectionPreviewLoading,
  }) {
    return PersonSelectionState(
      status: status ?? this.status,
      project: project ?? this.project,
      persons: persons ?? this.persons,
      selectedPersonIds: selectedPersonIds ?? this.selectedPersonIds,
      faceOnlyPersonIds: faceOnlyPersonIds ?? this.faceOnlyPersonIds,
      privacyMode: privacyMode ?? this.privacyMode,
      errorMessage: errorMessage,
      analysisCacheId: clearAnalysisCacheId
          ? null
          : (analysisCacheId ?? this.analysisCacheId),
      selectionPreviewPath: clearSelectionPreviewPath
          ? null
          : (selectionPreviewPath ?? this.selectionPreviewPath),
      selectionPreviewLoading:
          selectionPreviewLoading ?? this.selectionPreviewLoading,
    );
  }
}
