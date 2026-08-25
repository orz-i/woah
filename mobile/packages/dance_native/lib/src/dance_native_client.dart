import 'dart:async';
import 'package:dance_domain/dance_domain.dart';
import 'bridge/dance_api.g.dart';
import 'mappers.dart';

/// Concrete client for interacting with Android / iOS native engine via Pigeon
class DanceNativeClient implements DanceProcessingEvents {
  final DanceNativeApi _api;
  final StreamController<JobStatusDto> _progressController =
      StreamController<JobStatusDto>.broadcast();

  DanceNativeClient({DanceNativeApi? api}) : _api = api ?? DanceNativeApi() {
    DanceProcessingEvents.setUp(this);
  }

  /// Stream of job progress updates from native worker
  Stream<JobStatusDto> get progressStream => _progressController.stream;

  @override
  void onProgressUpdate(JobStatusDto status) {
    _progressController.add(status);
  }

  /// Check hardware and OS capabilities
  Future<NativeCapabilitiesDto> getCapabilities() {
    return _api.getCapabilities();
  }

  /// Probe video metadata (dimensions, rotation, codecs, duration)
  Future<VideoInfo> probeVideo(String uri) async {
    final dto = await _api.probeVideo(uri);
    return dto.toDomain();
  }

  /// Run first-frame person segmentation and return detected persons
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
  }) {
    return _api.analyzeVideo(AnalyzeRequestDto(
      videoUri: videoUri,
      modelProfile: modelProfile,
    ));
  }

  /// Request a single rendered preview frame with applied effects
  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
  }) {
    return _api.getPreviewFrame(PreviewRequestDto(
      analysisCacheId: analysisCacheId,
      timestampMs: timestampMs,
      selectedPersonIds: selectedPersonIds,
      effects: effects.toDto(),
      follow: follow.toDto(),
    ));
  }

  /// Start background video export job
  Future<String> startExport({
    required String analysisCacheId,
    required String outputFilePath,
    required List<int> selectedPersonIds,
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
    int targetWidth = 1920,
    int targetHeight = 1080,
    double targetFps = 30.0,
    int videoBitrate = 8000000,
  }) {
    return _api.startExport(ExportRequestDto(
      analysisCacheId: analysisCacheId,
      outputFilePath: outputFilePath,
      selectedPersonIds: selectedPersonIds,
      effects: effects.toDto(),
      follow: follow.toDto(),
      targetWidth: targetWidth,
      targetHeight: targetHeight,
      targetFps: targetFps,
      videoBitrate: videoBitrate,
    ));
  }

  /// Cancel an ongoing export job
  Future<void> cancelJob(String jobId) {
    return _api.cancelJob(jobId);
  }

  /// Query current status of an export job
  Future<JobStatusDto> getJobStatus(String jobId) {
    return _api.getJobStatus(jobId);
  }

  /// Free native memory and cache for a project
  Future<void> releaseProject(String projectId) {
    return _api.releaseProject(projectId);
  }

  void dispose() {
    _progressController.close();
    DanceProcessingEvents.setUp(null);
  }
}
