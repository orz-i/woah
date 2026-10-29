import 'comparison_picture.dart';
import 'comparison_marker.dart';
import 'comparison_music_mapping.dart';

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
  final double timeScale;
  final String alignment;
  final int? teacherAnchorUs;
  final int? learnerAnchorUs;
  final int? teacherAnchor2Us, learnerAnchor2Us;
  final int positionUs;
  final double speed;

  /// Null follows screen orientation; an explicit choice is saved per project.
  final bool? sideBySide;
  final bool mirrorTeacher;
  final bool mirrorLearner;
  final ComparisonPicture teacherPicture, learnerPicture;
  final List<ComparisonMarker> markers;

  /// A/B points use the shared overlap timeline, with an exclusive end.
  final int? loopStartUs;
  final int? loopEndUs;
  final bool loopEnabled;
  final int updatedAtMs;
  const ComparisonProject({
    required this.id,
    this.title = '舞蹈对比',
    this.teacher,
    this.learner,
    this.offsetUs = 0,
    this.timeScale = 1,
    this.alignment = 'unmatched',
    this.teacherAnchorUs,
    this.learnerAnchorUs,
    this.teacherAnchor2Us,
    this.learnerAnchor2Us,
    this.positionUs = 0,
    this.speed = 1,
    this.sideBySide,
    this.mirrorTeacher = false,
    this.mirrorLearner = false,
    this.teacherPicture = const ComparisonPicture(),
    this.learnerPicture = const ComparisonPicture(),
    this.markers = const [],
    this.loopStartUs,
    this.loopEndUs,
    this.loopEnabled = false,
    required this.updatedAtMs,
  });
  ComparisonMusicMapping get musicMapping =>
      ComparisonMusicMapping(offsetUs, timeScale);
  bool get hasPair => teacher != null && learner != null;
  Map<String, Object?> toJson() => {
    'schemaVersion': schemaVersion,
    'id': id,
    'title': title,
    'teacher': teacher?.toJson(),
    'learner': learner?.toJson(),
    'offsetUs': offsetUs,
    'timeScale': timeScale,
    'alignment': alignment,
    'teacherAnchorUs': teacherAnchorUs,
    'learnerAnchorUs': learnerAnchorUs,
    'teacherAnchor2Us': teacherAnchor2Us,
    'learnerAnchor2Us': learnerAnchor2Us,
    'positionUs': positionUs,
    'speed': speed,
    'sideBySide': sideBySide,
    'mirrorTeacher': mirrorTeacher,
    'mirrorLearner': mirrorLearner,
    'teacherPicture': teacherPicture.toJson(),
    'learnerPicture': learnerPicture.toJson(),
    'markers': markers.map((marker) => marker.toJson()).toList(),
    'loopStartUs': loopStartUs,
    'loopEndUs': loopEndUs,
    'loopEnabled': loopEnabled,
    'updatedAtMs': updatedAtMs,
  };
  factory ComparisonProject.fromJson(Map<String, dynamic> json) {
    if (json['schemaVersion'] != schemaVersion) {
      throw const FormatException('Unsupported comparison project version');
    }
    final offset = (json['offsetUs'] as num).toInt();
    final timeScale = (json['timeScale'] as num?)?.toDouble() ?? 1;
    if (!ComparisonMusicMapping(offset, timeScale).valid) {
      throw const FormatException('Invalid music mapping');
    }
    final speed = (json['speed'] as num).toDouble();
    final position = (json['positionUs'] as num).toInt();
    final id = json['id'] as String;
    final loopStart = (json['loopStartUs'] as num?)?.toInt();
    final loopEnd = (json['loopEndUs'] as num?)?.toInt();
    final loopEnabled = json['loopEnabled'] as bool? ?? false;
    final anchorKeys = [
      'teacherAnchorUs',
      'learnerAnchorUs',
      'teacherAnchor2Us',
      'learnerAnchor2Us',
    ];
    final anchors = [
      for (final key in anchorKeys) (json[key] as num?)?.toInt(),
    ];
    if (anchors.any((v) => v != null && (v < 0 || v > 86400000000)) ||
        ((anchors[2] != null || anchors[3] != null) &&
            anchors.any((v) => v == null))) {
      throw const FormatException('Invalid music anchors');
    }
    final markers = (json['markers'] as List? ?? [])
        .map(
          (value) => ComparisonMarker.fromJson(
            Map<String, dynamic>.from(value as Map),
          ),
        )
        .toList();
    if (markers.map((marker) => marker.id).toSet().length != markers.length) {
      throw const FormatException('Duplicate comparison markers');
    }
    if (!RegExp(r'^[A-Za-z0-9_-]{1,80}$').hasMatch(id) ||
        offset.abs() > 172800000000 ||
        ![.5, .75, 1.0].contains(speed) ||
        position < 0) {
      throw const FormatException('Invalid comparison project state');
    }
    if ((loopStart != null && (loopStart < 0 || loopStart > 86400000000)) ||
        (loopEnd != null &&
            (loopEnd <= (loopStart ?? 0) || loopEnd > 86400000000)) ||
        (loopEnabled && (loopStart == null || loopEnd == null))) {
      throw const FormatException('Invalid comparison loop');
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
      timeScale: timeScale,
      alignment: json['alignment'] as String,
      teacherAnchorUs: (json['teacherAnchorUs'] as num?)?.toInt(),
      learnerAnchorUs: (json['learnerAnchorUs'] as num?)?.toInt(),
      teacherAnchor2Us: (json['teacherAnchor2Us'] as num?)?.toInt(),
      learnerAnchor2Us: (json['learnerAnchor2Us'] as num?)?.toInt(),
      positionUs: position,
      speed: speed,
      sideBySide: json['sideBySide'] as bool?,
      mirrorTeacher: json['mirrorTeacher'] as bool? ?? false,
      mirrorLearner: json['mirrorLearner'] as bool? ?? false,
      teacherPicture: json['teacherPicture'] == null
          ? const ComparisonPicture()
          : ComparisonPicture.fromJson(
              Map<String, dynamic>.from(json['teacherPicture'] as Map),
            ),
      learnerPicture: json['learnerPicture'] == null
          ? const ComparisonPicture()
          : ComparisonPicture.fromJson(
              Map<String, dynamic>.from(json['learnerPicture'] as Map),
            ),
      markers: List.unmodifiable(markers),
      loopStartUs: loopStart,
      loopEndUs: loopEnd,
      loopEnabled: loopEnabled,
      updatedAtMs: (json['updatedAtMs'] as num).toInt(),
    );
  }
  ComparisonProject copyWith({
    String? title,
    ComparisonSource? teacher,
    ComparisonSource? learner,
    int? offsetUs,
    double? timeScale,
    String? alignment,
    int? teacherAnchorUs,
    int? learnerAnchorUs,
    int? teacherAnchor2Us,
    int? learnerAnchor2Us,
    bool clearAnchors = false,
    int? positionUs,
    double? speed,
    bool? sideBySide,
    bool? mirrorTeacher,
    bool? mirrorLearner,
    ComparisonPicture? teacherPicture,
    ComparisonPicture? learnerPicture,
    List<ComparisonMarker>? markers,
    int? loopStartUs,
    int? loopEndUs,
    bool? loopEnabled,
    bool clearLoop = false,
    bool clearLoopEnd = false,
  }) => ComparisonProject(
    id: id,
    title: title ?? this.title,
    teacher: teacher ?? this.teacher,
    learner: learner ?? this.learner,
    offsetUs: offsetUs ?? this.offsetUs,
    timeScale: timeScale ?? this.timeScale,
    alignment: alignment ?? this.alignment,
    teacherAnchorUs: clearAnchors
        ? null
        : teacherAnchorUs ?? this.teacherAnchorUs,
    learnerAnchorUs: clearAnchors
        ? null
        : learnerAnchorUs ?? this.learnerAnchorUs,
    teacherAnchor2Us: clearAnchors
        ? null
        : teacherAnchor2Us ?? this.teacherAnchor2Us,
    learnerAnchor2Us: clearAnchors
        ? null
        : learnerAnchor2Us ?? this.learnerAnchor2Us,
    positionUs: positionUs ?? this.positionUs,
    speed: speed ?? this.speed,
    sideBySide: sideBySide ?? this.sideBySide,
    mirrorTeacher: mirrorTeacher ?? this.mirrorTeacher,
    mirrorLearner: mirrorLearner ?? this.mirrorLearner,
    teacherPicture: teacherPicture ?? this.teacherPicture,
    learnerPicture: learnerPicture ?? this.learnerPicture,
    markers: markers == null ? this.markers : List.unmodifiable(markers),
    loopStartUs: clearLoop ? null : loopStartUs ?? this.loopStartUs,
    loopEndUs: clearLoop || clearLoopEnd ? null : loopEndUs ?? this.loopEndUs,
    loopEnabled: clearLoop ? false : loopEnabled ?? this.loopEnabled,
    updatedAtMs: DateTime.now().millisecondsSinceEpoch,
  );

  /// Preserve reference-source practice intervals when the common origin changes.
  ComparisonProject withMusicMapping(
    ComparisonMusicMapping value, {
    int? oldReferenceStartUs,
    int? newReferenceStartUs,
    int? referencePositionUs,
    int? newDurationUs,
  }) {
    final next = value.overlap(teacher!.durationUs, learner!.durationUs);
    if (next == null) throw const FormatException('两段视频在此校准下没有共同区间');
    final before =
        oldReferenceStartUs ??
        musicMapping.overlap(teacher!.durationUs, learner!.durationUs)?.$1 ??
        0;
    final start = newReferenceStartUs ?? next.$1;
    final duration = newDurationUs ?? next.$2 - start;
    final shift = before - start;
    final a = loopStartUs == null ? null : loopStartUs! + shift;
    final b = loopEndUs == null ? null : loopEndUs! + shift;
    final available =
        a != null && a < duration && (b == null ? a >= 0 : b > 0 && b > a);
    return copyWith(
      offsetUs: value.offsetUs,
      timeScale: value.timeScale,
      positionUs: ((referencePositionUs ?? before + positionUs) - start).clamp(
        0,
        duration - 1,
      ),
      clearLoop: true,
    ).copyWith(
      loopStartUs: available ? a.clamp(0, duration - 1) : null,
      loopEndUs: available ? b?.clamp(0, duration) : null,
      loopEnabled: loopEnabled && available && b != null,
    );
  }

  ComparisonProject swapSources({int? referenceStartUs}) {
    final inverse = musicMapping.inverse;
    final before =
        referenceStartUs ??
        musicMapping.overlap(teacher!.durationUs, learner!.durationUs)?.$1 ??
        0;
    final next = inverse.overlap(learner!.durationUs, teacher!.durationUs);
    final start = next?.$1 ?? 0;
    final duration = next == null ? 1 : next.$2 - next.$1;
    int converted(int common) => musicMapping.target(before + common) - start;
    final a = loopStartUs == null ? null : converted(loopStartUs!);
    final b = loopEndUs == null ? null : converted(loopEndUs!);
    final available =
        a != null && a < duration && (b == null ? a >= 0 : b > 0 && b > a);
    return ComparisonProject(
      id: id,
      title: title,
      teacher: learner,
      learner: teacher,
      offsetUs: inverse.offsetUs,
      timeScale: inverse.timeScale,
      alignment: alignment,
      teacherAnchorUs: learnerAnchorUs,
      learnerAnchorUs: teacherAnchorUs,
      teacherAnchor2Us: learnerAnchor2Us,
      learnerAnchor2Us: teacherAnchor2Us,
      positionUs: converted(positionUs).clamp(0, duration - 1),
      speed: speed,
      sideBySide: sideBySide,
      mirrorTeacher: mirrorLearner,
      mirrorLearner: mirrorTeacher,
      teacherPicture: learnerPicture,
      learnerPicture: teacherPicture,
      markers: markers,
      loopStartUs: available ? a.clamp(0, duration - 1) : null,
      loopEndUs: available ? b?.clamp(0, duration) : null,
      loopEnabled: loopEnabled && available && b != null,
      updatedAtMs: DateTime.now().millisecondsSinceEpoch,
    );
  }

  ComparisonMarkerRange? markerRange(
    ComparisonMarker marker, {
    required int teacherStartUs,
    required int durationUs,
  }) {
    final int origin;
    if (marker.sourceUri == teacher?.uri) {
      origin = teacherStartUs;
    } else if (marker.sourceUri == learner?.uri) {
      origin = teacherStartUs;
    } else {
      return null;
    }
    final from = marker.sourceUri == teacher?.uri
        ? marker.startUs
        : musicMapping.reference(marker.startUs);
    final until = marker.sourceUri == teacher?.uri
        ? marker.endUs
        : musicMapping.reference(marker.endUs);
    final start = (from - origin).clamp(0, durationUs);
    final end = (until - origin).clamp(0, durationUs);
    if (end <= start) return null;
    return ComparisonMarkerRange(
      start,
      end,
      clipped: start != from - origin || end != until - origin,
    );
  }
}
