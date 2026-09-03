import 'video_info.dart';
import 'person_track.dart';
import 'effects.dart';

/// Effective per-person privacy mode used by project/UI policy.
///
/// [selectedPersonIds] remains the persisted legacy FULL_BODY set. The optional
/// FACE_ONLY set is additive, and FULL_BODY always wins when an ID is present in
/// both sets so old projects and old selection flows remain deterministic.
enum PersonPrivacyMode {
  none,
  faceOnly,
  fullBody,
}

/// Top-level project state representing a user editing session
class DanceProject {
  final String id;
  final String sourceUri;
  final VideoInfo videoInfo;

  final List<PersonTrack> persons;
  final Set<int> selectedPersonIds;
  final Set<int> faceOnlyPersonIds;

  final EffectConfig effects;
  final FollowConfig follow;
  final CropConfig? crop;

  /// Optional temporal trim bounds in the original source timeline.
  /// A null [trimEndMs] means the source duration.
  final int trimStartMs;
  final int? trimEndMs;

  /// Cache ID returned from native analysis to reference cached masks/models
  final String? analysisCacheId;

  final DateTime createdAt;
  final DateTime updatedAt;

  const DanceProject({
    required this.id,
    required this.sourceUri,
    required this.videoInfo,
    this.persons = const [],
    this.selectedPersonIds = const {},
    this.faceOnlyPersonIds = const {},
    this.effects = const EffectConfig(),
    this.follow = const FollowConfig(),
    this.crop,
    this.trimStartMs = 0,
    this.trimEndMs,
    this.analysisCacheId,
    required this.createdAt,
    required this.updatedAt,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'sourceUri': sourceUri,
        'videoInfo': videoInfo.toJson(),
        'persons': persons.map((p) => p.toJson()).toList(),
        'selectedPersonIds': selectedPersonIds.toList(),
        'faceOnlyPersonIds': faceOnlyPersonIds.toList(),
        'effects': effects.toJson(),
        'follow': follow.toJson(),
        'crop': crop?.toJson(),
        'trimStartMs': trimStartMs,
        'trimEndMs': trimEndMs,
        'analysisCacheId': analysisCacheId,
        'createdAt': createdAt.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
      };

  factory DanceProject.fromJson(Map<String, dynamic> json) => DanceProject(
        id: json['id'] as String,
        sourceUri: json['sourceUri'] as String,
        videoInfo: VideoInfo.fromJson(json['videoInfo'] as Map<String, dynamic>),
        persons: (json['persons'] as List<dynamic>?)
                ?.map((e) => PersonTrack.fromJson(e as Map<String, dynamic>))
                .toList() ??
            const [],
        selectedPersonIds: (json['selectedPersonIds'] as List<dynamic>?)
                ?.map((e) => e as int)
                .toSet() ??
            const {},
        faceOnlyPersonIds: (json['faceOnlyPersonIds'] as List<dynamic>?)
                ?.map((e) => e as int)
                .toSet() ??
            const {},
        effects: json['effects'] != null
            ? EffectConfig.fromJson(json['effects'] as Map<String, dynamic>)
            : const EffectConfig(),
        follow: json['follow'] != null
            ? FollowConfig.fromJson(json['follow'] as Map<String, dynamic>)
            : const FollowConfig(),
        crop: json['crop'] != null
            ? CropConfig.fromJson(json['crop'] as Map<String, dynamic>)
            : null,
        trimStartMs: (json['trimStartMs'] as num?)?.toInt() ?? 0,
        trimEndMs: (json['trimEndMs'] as num?)?.toInt(),
        analysisCacheId: json['analysisCacheId'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: DateTime.parse(json['updatedAt'] as String),
      );

  DanceProject copyWith({
    String? id,
    String? sourceUri,
    VideoInfo? videoInfo,
    List<PersonTrack>? persons,
    Set<int>? selectedPersonIds,
    Set<int>? faceOnlyPersonIds,
    EffectConfig? effects,
    FollowConfig? follow,
    CropConfig? crop,
    int? trimStartMs,
    int? trimEndMs,
    bool clearTrimEnd = false,
    String? analysisCacheId,
    DateTime? createdAt,
    DateTime? updatedAt,
  }) {
    return DanceProject(
      id: id ?? this.id,
      sourceUri: sourceUri ?? this.sourceUri,
      videoInfo: videoInfo ?? this.videoInfo,
      persons: persons ?? this.persons,
      selectedPersonIds: selectedPersonIds ?? this.selectedPersonIds,
      faceOnlyPersonIds: faceOnlyPersonIds ?? this.faceOnlyPersonIds,
      effects: effects ?? this.effects,
      follow: follow ?? this.follow,
      crop: crop ?? this.crop,
      trimStartMs: trimStartMs ?? this.trimStartMs,
      trimEndMs: clearTrimEnd ? null : (trimEndMs ?? this.trimEndMs),
      analysisCacheId: analysisCacheId ?? this.analysisCacheId,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  /// Returns the effective mode for [personId]. FULL_BODY intentionally wins a
  /// conflict to match the native resolver and the original desktop behavior.
  PersonPrivacyMode privacyModeForPerson(int personId) {
    if (selectedPersonIds.contains(personId)) return PersonPrivacyMode.fullBody;
    if (faceOnlyPersonIds.contains(personId)) return PersonPrivacyMode.faceOnly;
    return PersonPrivacyMode.none;
  }

  /// All people receiving any privacy treatment, independent of mode.
  Set<int> get privacyTargetIds => {
        ...faceOnlyPersonIds,
        ...selectedPersonIds,
      };

  int get effectiveTrimEndMs =>
      (trimEndMs ?? videoInfo.durationMs).clamp(trimStartMs, videoInfo.durationMs);

  int get trimmedDurationMs =>
      (effectiveTrimEndMs - trimStartMs).clamp(0, videoInfo.durationMs);
}
