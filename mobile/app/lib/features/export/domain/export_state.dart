import 'package:dance_domain/dance_domain.dart';

enum ExportJobState {
  queued,
  preparing,
  processing,
  muxing,
  completed,
  failed,
  cancelled;

  static ExportJobState fromString(String value) {
    return ExportJobState.values.firstWhere(
      (e) => e.name.toLowerCase() == value.toLowerCase(),
      orElse: () => ExportJobState.processing,
    );
  }
}

class ExportState {
  final ExportJobState status;
  final String? jobId;
  final DanceProject? project;
  final double progress;
  final int currentFrame;
  final int totalFrames;
  final double fps;
  final String? outputUri;
  final String? errorMessage;

  const ExportState({
    this.status = ExportJobState.preparing,
    this.jobId,
    this.project,
    this.progress = 0.0,
    this.currentFrame = 0,
    this.totalFrames = 0,
    this.fps = 0.0,
    this.outputUri,
    this.errorMessage,
  });

  bool get isProcessing =>
      status == ExportJobState.preparing ||
      status == ExportJobState.processing ||
      status == ExportJobState.muxing;

  bool get isCompleted => status == ExportJobState.completed && outputUri != null;

  bool get isFailed => status == ExportJobState.failed || errorMessage != null;

  ExportState copyWith({
    ExportJobState? status,
    String? jobId,
    DanceProject? project,
    double? progress,
    int? currentFrame,
    int? totalFrames,
    double? fps,
    String? outputUri,
    String? errorMessage,
  }) {
    return ExportState(
      status: status ?? this.status,
      jobId: jobId ?? this.jobId,
      project: project ?? this.project,
      progress: progress ?? this.progress,
      currentFrame: currentFrame ?? this.currentFrame,
      totalFrames: totalFrames ?? this.totalFrames,
      fps: fps ?? this.fps,
      outputUri: outputUri ?? this.outputUri,
      errorMessage: errorMessage ?? this.errorMessage,
    );
  }
}
