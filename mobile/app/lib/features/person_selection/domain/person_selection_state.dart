import 'package:dance_domain/dance_domain.dart';

enum PersonSelectionStatus {
  analyzing,
  ready,
  error,
}

class PersonSelectionState {
  final PersonSelectionStatus status;
  final DanceProject? project;
  final List<PersonTrack> persons;
  final Set<int> selectedPersonIds;
  final Set<int> faceOnlyPersonIds;
  final String? errorMessage;
  final String? analysisCacheId;

  const PersonSelectionState({
    this.status = PersonSelectionStatus.analyzing,
    this.project,
    this.persons = const [],
    this.selectedPersonIds = const {},
    this.faceOnlyPersonIds = const {},
    this.errorMessage,
    this.analysisCacheId,
  });

  bool get isAnalyzing => status == PersonSelectionStatus.analyzing;
  bool get hasPersons => persons.isNotEmpty;
  Set<int> get privacyTargetIds => {
        ...faceOnlyPersonIds,
        ...selectedPersonIds,
      };

  PersonPrivacyMode privacyModeForPerson(int personId) {
    if (selectedPersonIds.contains(personId)) return PersonPrivacyMode.fullBody;
    if (faceOnlyPersonIds.contains(personId)) return PersonPrivacyMode.faceOnly;
    return PersonPrivacyMode.none;
  }

  PersonSelectionState copyWith({
    PersonSelectionStatus? status,
    DanceProject? project,
    List<PersonTrack>? persons,
    Set<int>? selectedPersonIds,
    Set<int>? faceOnlyPersonIds,
    String? errorMessage,
    String? analysisCacheId,
  }) {
    return PersonSelectionState(
      status: status ?? this.status,
      project: project ?? this.project,
      persons: persons ?? this.persons,
      selectedPersonIds: selectedPersonIds ?? this.selectedPersonIds,
      faceOnlyPersonIds: faceOnlyPersonIds ?? this.faceOnlyPersonIds,
      errorMessage: errorMessage,
      analysisCacheId: analysisCacheId ?? this.analysisCacheId,
    );
  }
}
