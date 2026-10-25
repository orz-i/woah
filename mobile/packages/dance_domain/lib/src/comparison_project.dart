/// Durable local comparison state. Source timestamps are signed microseconds.
class ComparisonSource {
  final String uri;
  final String name;
  final int durationUs;
  final bool hasAudio;
  const ComparisonSource({
    required this.uri,
    required this.name,
    required this.durationUs,
    required this.hasAudio,
  });
  Map<String, Object?> toJson() => {
    'uri': uri,
    'name': name,
    'durationUs': durationUs,
    'hasAudio': hasAudio,
  };
  factory ComparisonSource.fromJson(Map<String, dynamic> json) {
    final source = ComparisonSource(
      uri: json['uri'] as String,
      name: json['name'] as String,
      durationUs: (json['durationUs'] as num).toInt(),
      hasAudio: json['hasAudio'] as bool,
    );
    if (source.uri.isEmpty || source.durationUs <= 0) {
      throw const FormatException('Invalid comparison source');
    }
    return source;
  }
}

class ComparisonProject {
  static const schemaVersion = 1;
  final String id;
  final String title;
  final ComparisonSource? teacher;
  final ComparisonSource? learner;
  final int offsetUs;
  final String alignment;
  final int? teacherAnchorUs;
  final int? learnerAnchorUs;
  final int positionUs;
  final double speed;
  final int updatedAtMs;
  const ComparisonProject({
    required this.id,
    this.title = '舞蹈对比',
    this.teacher,
    this.learner,
    this.offsetUs = 0,
    this.alignment = 'unmatched',
    this.teacherAnchorUs,
    this.learnerAnchorUs,
    this.positionUs = 0,
    this.speed = 1,
    required this.updatedAtMs,
  });
  bool get hasPair => teacher != null && learner != null;
  Map<String, Object?> toJson() => {
    'schemaVersion': schemaVersion,
    'id': id,
    'title': title,
    'teacher': teacher?.toJson(),
    'learner': learner?.toJson(),
    'offsetUs': offsetUs,
    'alignment': alignment,
    'teacherAnchorUs': teacherAnchorUs,
    'learnerAnchorUs': learnerAnchorUs,
    'positionUs': positionUs,
    'speed': speed,
    'updatedAtMs': updatedAtMs,
  };
  factory ComparisonProject.fromJson(Map<String, dynamic> json) {
    if (json['schemaVersion'] != schemaVersion) {
      throw const FormatException('Unsupported comparison project version');
    }
    final offset = (json['offsetUs'] as num).toInt();
    final speed = (json['speed'] as num).toDouble();
    final position = (json['positionUs'] as num).toInt();
    final id = json['id'] as String;
    if (!RegExp(r'^[A-Za-z0-9_-]{1,80}$').hasMatch(id) ||
        offset.abs() > 86400000000 ||
        ![.5, .75, 1.0].contains(speed) ||
        position < 0) {
      throw const FormatException('Invalid comparison project state');
    }
    return ComparisonProject(
      id: id,
      title: json['title'] as String,
      teacher: json['teacher'] == null
          ? null
          : ComparisonSource.fromJson(
              Map<String, dynamic>.from(json['teacher'] as Map),
            ),
      learner: json['learner'] == null
          ? null
          : ComparisonSource.fromJson(
              Map<String, dynamic>.from(json['learner'] as Map),
            ),
      offsetUs: offset,
      alignment: json['alignment'] as String,
      teacherAnchorUs: (json['teacherAnchorUs'] as num?)?.toInt(),
      learnerAnchorUs: (json['learnerAnchorUs'] as num?)?.toInt(),
      positionUs: position,
      speed: speed,
      updatedAtMs: (json['updatedAtMs'] as num).toInt(),
    );
  }
  ComparisonProject copyWith({
    String? title,
    ComparisonSource? teacher,
    ComparisonSource? learner,
    int? offsetUs,
    String? alignment,
    int? teacherAnchorUs,
    int? learnerAnchorUs,
    bool clearAnchors = false,
    int? positionUs,
    double? speed,
  }) => ComparisonProject(
    id: id,
    title: title ?? this.title,
    teacher: teacher ?? this.teacher,
    learner: learner ?? this.learner,
    offsetUs: offsetUs ?? this.offsetUs,
    alignment: alignment ?? this.alignment,
    teacherAnchorUs: clearAnchors
        ? null
        : teacherAnchorUs ?? this.teacherAnchorUs,
    learnerAnchorUs: clearAnchors
        ? null
        : learnerAnchorUs ?? this.learnerAnchorUs,
    positionUs: positionUs ?? this.positionUs,
    speed: speed ?? this.speed,
    updatedAtMs: DateTime.now().millisecondsSinceEpoch,
  );
  ComparisonProject swapSources() => ComparisonProject(
    id: id,
    title: title,
    teacher: learner,
    learner: teacher,
    offsetUs: -offsetUs,
    alignment: alignment,
    teacherAnchorUs: learnerAnchorUs,
    learnerAnchorUs: teacherAnchorUs,
    positionUs: positionUs,
    speed: speed,
    updatedAtMs: DateTime.now().millisecondsSinceEpoch,
  );
}
