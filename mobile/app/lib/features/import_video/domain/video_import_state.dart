import 'package:dance_domain/dance_domain.dart';

enum VideoImportStatus {
  idle,
  picking,
  probing,
  ready,
  error,
}

class VideoImportState {
  final VideoImportStatus status;
  final String? videoPath;
  final String? videoName;
  final VideoInfo? videoInfo;
  final String? errorMessage;

  const VideoImportState({
    this.status = VideoImportStatus.idle,
    this.videoPath,
    this.videoName,
    this.videoInfo,
    this.errorMessage,
  });

  bool get isReady => status == VideoImportStatus.ready && videoInfo != null;
  bool get isLoading =>
      status == VideoImportStatus.picking || status == VideoImportStatus.probing;

  VideoImportState copyWith({
    VideoImportStatus? status,
    String? videoPath,
    String? videoName,
    VideoInfo? videoInfo,
    String? errorMessage,
  }) {
    return VideoImportState(
      status: status ?? this.status,
      videoPath: videoPath ?? this.videoPath,
      videoName: videoName ?? this.videoName,
      videoInfo: videoInfo ?? this.videoInfo,
      errorMessage: errorMessage,
    );
  }
}
