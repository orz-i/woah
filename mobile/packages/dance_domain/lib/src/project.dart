import 'video_info.dart';
import 'person_track.dart';
import 'effects.dart';

/// Top-level project state representing a user editing session
class DanceProject {
  final String id;
  final String sourceUri;
  final VideoInfo videoInfo;

  final List<PersonTrack> persons;
  final Set<int> selectedPersonIds;

  final EffectConfig effects;
  final FollowConfig follow;
  final CropConfig? crop;

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
    this.effects = const EffectConfig(),
    this.follow = const FollowConfig(),
    this.crop,
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
        'effects': effects.toJson(),
        'follow': follow.toJson(),
        'crop': crop?.toJson(),
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
        effects: json['effects'] != null
            ? EffectConfig.fromJson(json['effects'] as Map<String, dynamic>)
            : const EffectConfig(),
        follow: json['follow'] != null
            ? FollowConfig.fromJson(json['follow'] as Map<String, dynamic>)
            : const FollowConfig(),
        crop: json['crop'] != null
            ? CropConfig.fromJson(json['crop'] as Map<String, dynamic>)
            : null,
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
    EffectConfig? effects,
    FollowConfig? follow,
    CropConfig? crop,
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
      effects: effects ?? this.effects,
      follow: follow ?? this.follow,
      crop: crop ?? this.crop,
      analysisCacheId: analysisCacheId ?? this.analysisCacheId,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }
}
