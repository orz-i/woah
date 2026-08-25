import 'geometry.dart';

/// Represents a detected and tracked person candidate in video.
class PersonTrack {
  /// Unique track ID assigned by native tracker (sorted left-to-right on first frame: 0, 1, 2...)
  final int id;

  /// Normalized initial bounding box in the first frame [0.0, 1.0]
  final NormalizedRect normalizedInitialBox;

  /// Local filesystem path to cropped thumbnail image
  final String thumbnailPath;

  /// Confidence score from detector [0.0, 1.0]
  final double confidence;

  /// Whether this person is selected for anonymization/effects
  final bool selected;

  const PersonTrack({
    required this.id,
    required this.normalizedInitialBox,
    required this.thumbnailPath,
    required this.confidence,
    this.selected = true,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'normalizedInitialBox': normalizedInitialBox.toJson(),
        'thumbnailPath': thumbnailPath,
        'confidence': confidence,
        'selected': selected,
      };

  factory PersonTrack.fromJson(Map<String, dynamic> json) => PersonTrack(
        id: json['id'] as int,
        normalizedInitialBox: NormalizedRect.fromJson(
            json['normalizedInitialBox'] as Map<String, dynamic>),
        thumbnailPath: json['thumbnailPath'] as String,
        confidence: (json['confidence'] as num).toDouble(),
        selected: json['selected'] as bool? ?? true,
      );

  PersonTrack copyWith({
    int? id,
    NormalizedRect? normalizedInitialBox,
    String? thumbnailPath,
    double? confidence,
    bool? selected,
  }) {
    return PersonTrack(
      id: id ?? this.id,
      normalizedInitialBox: normalizedInitialBox ?? this.normalizedInitialBox,
      thumbnailPath: thumbnailPath ?? this.thumbnailPath,
      confidence: confidence ?? this.confidence,
      selected: selected ?? this.selected,
    );
  }

  @override
  String toString() =>
      'PersonTrack(id: $id, conf: ${confidence.toStringAsFixed(2)}, selected: $selected)';
}
