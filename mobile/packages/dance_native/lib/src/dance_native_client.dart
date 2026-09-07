import 'dart:async';
import 'package:flutter/services.dart';
import 'package:dance_domain/dance_domain.dart';
import 'bridge/dance_api.g.dart';
import 'mappers.dart';

/// Concrete client for interacting with Android / iOS native engine via Pigeon
class DanceNativeClient implements DanceProcessingEvents {
  static const MethodChannel _channel = MethodChannel('dance_native');
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
    int trimStartMs = 0,
  }) {
    return _api.analyzeVideo(
      AnalyzeRequestDto(
        videoUri: videoUri,
        modelProfile: modelProfile,
        trimStartMs: trimStartMs,
      ),
    );
  }

  /// Request a single rendered preview frame with applied effects
  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
  }) {
    return _api.getPreviewFrame(
      PreviewRequestDto(
        analysisCacheId: analysisCacheId,
        timestampMs: timestampMs,
        selectedPersonIds: selectedPersonIds,
        effects: effects.toDto(),
        follow: follow.toDto(),
        faceOnlyPersonIds: faceOnlyPersonIds,
      ),
    );
  }

  /// Start background video export job
  Future<String> startExport({
    required String sourceUri,
    required String analysisCacheId,
    required String outputFilePath,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
    int targetWidth = 1920,
    int targetHeight = 1080,
    double targetFps = 30.0,
    int videoBitrate = 8000000,
    String processingProfile = 'quality',
    bool enableLivePreview = false,
    int trimStartMs = 0,
    int? trimEndMs,
  }) {
    return _api.startExport(
      ExportRequestDto(
        sourceUri: sourceUri,
        analysisCacheId: analysisCacheId,
        outputFilePath: outputFilePath,
        selectedPersonIds: selectedPersonIds,
        effects: effects.toDto(),
        follow: follow.toDto(),
        targetWidth: targetWidth,
        targetHeight: targetHeight,
        targetFps: targetFps,
        videoBitrate: videoBitrate,
        processingProfile: processingProfile,
        enableLivePreview: enableLivePreview,
        faceOnlyPersonIds: faceOnlyPersonIds,
        trimStartMs: trimStartMs,
        trimEndMs: trimEndMs,
      ),
    );
  }

  Future<List<String>> getVideoFrameThumbnails({
    required String videoUri,
    required List<int> timestampsMs,
  }) async {
    final result = await _channel.invokeListMethod<String>(
      'getVideoFrameThumbnails',
      {'videoUri': videoUri, 'timestampsMs': timestampsMs},
    );
    return result ?? const [];
  }

  /// Phase 1 iOS-only inference probe.
  ///
  /// This deliberately stays outside the Pigeon product API until real-device
  /// parity is accepted. Android and other platforms may report a missing
  /// method if this diagnostic helper is invoked there.
  Future<Map<dynamic, dynamic>?> runIOSYoloPhase1Probe({
    required String videoUri,
    int timestampMs = 0,
    String backend = 'auto',
  }) {
    return _channel.invokeMapMethod<dynamic, dynamic>(
      'runIOSYoloPhase1Probe',
      {
        'videoUri': videoUri,
        'timestampMs': timestampMs,
        'backend': backend,
      },
    );
  }

  /// Toggle Android export live-preview capture for a running job.
  /// Platforms that do not expose the legacy MethodChannel control simply
  /// keep their existing export-preview behavior.
  Future<void> setExportLivePreviewEnabled({
    required String jobId,
    required bool enabled,
  }) async {
    try {
      await _channel.invokeMethod<void>('setExportLivePreviewEnabled', {
        'jobId': jobId,
        'enabled': enabled,
      });
    } on MissingPluginException {
      // Android implements this runtime control. Keep other platforms
      // backwards-compatible instead of failing the export UI toggle.
    }
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

  /// Save an exported MP4 video to the platform system gallery / photo library.
  Future<String?> saveVideoToGallery(String filePath) async {
    return _channel.invokeMethod<String>('saveVideoToGallery', {
      'filePath': filePath,
    });
  }

  /// Share the saved/exported media URI through the platform share sheet.
  Future<void> shareVideo(String publicUri) async {
    await _channel.invokeMethod<void>('shareVideo', {'publicUri': publicUri});
  }

  /// Open the saved/exported media URI in a native video viewer.
  Future<void> openVideo(String publicUri) async {
    await _channel.invokeMethod<void>('openVideo', {'publicUri': publicUri});
  }

  /// Create diagnostic bundle ZIP file
  Future<Map<dynamic, dynamic>?> createDiagnosticBundle() async {
    return _channel.invokeMapMethod<dynamic, dynamic>('createDiagnosticBundle');
  }

  /// Share diagnostic bundle via system share sheet
  Future<Map<dynamic, dynamic>?> shareDiagnosticBundle({
    String? filePath,
    String? publicUri,
  }) async {
    return _channel.invokeMapMethod<dynamic, dynamic>('shareDiagnosticBundle', {
      'filePath': filePath,
      'publicUri': publicUri,
    });
  }

  /// Clear old diagnostic logs
  Future<void> clearDiagnosticLogs() async {
    await _channel.invokeMethod<void>('clearDiagnosticLogs');
  }

  void dispose() {
    _progressController.close();
    DanceProcessingEvents.setUp(null);
  }
}
