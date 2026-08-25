/// Information probed from a video file.
class VideoInfo {
  final int codedWidth;
  final int codedHeight;
  final int displayWidth;
  final int displayHeight;
  final double fps;
  final int durationMs;
  final int rotation;
  final String videoCodec;
  final String? audioCodec;
  final bool hasAudio;

  const VideoInfo({
    required this.codedWidth,
    required this.codedHeight,
    required this.displayWidth,
    required this.displayHeight,
    required this.fps,
    required this.durationMs,
    required this.rotation,
    required this.videoCodec,
    this.audioCodec,
    required this.hasAudio,
  });

  /// Visual width considering orientation rotation (0, 90, 180, 270)
  int get width => displayWidth;

  /// Visual height considering orientation rotation
  int get height => displayHeight;

  /// Aspect ratio (width / height)
  double get aspectRatio => height == 0 ? 1.0 : width / height;

  Map<String, dynamic> toJson() => {
        'codedWidth': codedWidth,
        'codedHeight': codedHeight,
        'displayWidth': displayWidth,
        'displayHeight': displayHeight,
        'fps': fps,
        'durationMs': durationMs,
        'rotation': rotation,
        'videoCodec': videoCodec,
        'audioCodec': audioCodec,
        'hasAudio': hasAudio,
      };

  factory VideoInfo.fromJson(Map<String, dynamic> json) => VideoInfo(
        codedWidth: json['codedWidth'] as int,
        codedHeight: json['codedHeight'] as int,
        displayWidth: json['displayWidth'] as int,
        displayHeight: json['displayHeight'] as int,
        fps: (json['fps'] as num).toDouble(),
        durationMs: json['durationMs'] as int,
        rotation: json['rotation'] as int,
        videoCodec: json['videoCodec'] as String,
        audioCodec: json['audioCodec'] as String?,
        hasAudio: json['hasAudio'] as bool? ?? false,
      );

  VideoInfo copyWith({
    int? codedWidth,
    int? codedHeight,
    int? displayWidth,
    int? displayHeight,
    double? fps,
    int? durationMs,
    int? rotation,
    String? videoCodec,
    String? audioCodec,
    bool? hasAudio,
  }) {
    return VideoInfo(
      codedWidth: codedWidth ?? this.codedWidth,
      codedHeight: codedHeight ?? this.codedHeight,
      displayWidth: displayWidth ?? this.displayWidth,
      displayHeight: displayHeight ?? this.displayHeight,
      fps: fps ?? this.fps,
      durationMs: durationMs ?? this.durationMs,
      rotation: rotation ?? this.rotation,
      videoCodec: videoCodec ?? this.videoCodec,
      audioCodec: audioCodec ?? this.audioCodec,
      hasAudio: hasAudio ?? this.hasAudio,
    );
  }

  @override
  String toString() =>
      'VideoInfo(${displayWidth}x$displayHeight, ${fps}fps, ${durationMs}ms, rot:$rotation, v:$videoCodec, a:$audioCodec)';
}
