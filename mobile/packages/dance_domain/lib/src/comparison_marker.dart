/// An interval anchored to its original video, rather than a mutable alignment.
class ComparisonMarker {
  final String id, sourceUri, note;
  final int startUs, endUs;
  const ComparisonMarker({
    required this.id,
    required this.sourceUri,
    required this.startUs,
    required this.endUs,
    this.note = '',
  });
  Map<String, Object?> toJson() => {
    'id': id,
    'sourceUri': sourceUri,
    'startUs': startUs,
    'endUs': endUs,
    'note': note,
  };
  factory ComparisonMarker.fromJson(Map<String, dynamic> json) {
    final marker = ComparisonMarker(
      id: json['id'] as String,
      sourceUri: json['sourceUri'] as String,
      startUs: (json['startUs'] as num).toInt(),
      endUs: (json['endUs'] as num).toInt(),
      note: json['note'] as String? ?? '',
    );
    if (!RegExp(r'^[A-Za-z0-9_-]{1,80}$').hasMatch(marker.id) ||
        marker.sourceUri.isEmpty ||
        marker.startUs < 0 ||
        marker.endUs <= marker.startUs ||
        marker.endUs > 86400000000) {
      throw const FormatException('Invalid comparison marker');
    }
    return marker;
  }
  ComparisonMarker withNote(String value) => ComparisonMarker(
    id: id,
    sourceUri: sourceUri,
    startUs: startUs,
    endUs: endUs,
    note: value,
  );
}

class ComparisonMarkerRange {
  final int startUs, endUs;
  final bool clipped;
  const ComparisonMarkerRange(this.startUs, this.endUs, {this.clipped = false});
}
