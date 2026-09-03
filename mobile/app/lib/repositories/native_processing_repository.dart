import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import '../core/logging/app_logger.dart';

final nativeClientProvider = Provider<DanceNativeClient>((ref) {
  final client = DanceNativeClient();
  ref.onDispose(() => client.dispose());
  return client;
});

final nativeRepositoryProvider = Provider<NativeProcessingRepository>((ref) {
  final client = ref.watch(nativeClientProvider);
  return NativeProcessingRepository(client);
});

class NativeProcessingRepository {
  final DanceNativeClient _client;

  NativeProcessingRepository(this._client);

  Stream<JobStatusDto> get progressStream => _client.progressStream;

  Future<NativeCapabilitiesDto> getCapabilities() async {
    AppLogger.d('NativeRepository', 'Checking native capabilities...');
    return _client.getCapabilities();
  }

  Future<VideoInfo> probeVideo(String uri) async {
    AppLogger.d('NativeRepository', 'Probing video: $uri');
    return _client.probeVideo(uri);
  }

  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
    int trimStartMs = 0,
  }) async {
    AppLogger.d(
      'NativeRepository',
      'Analyzing video: $videoUri (profile: $modelProfile)',
    );
    return _client.analyzeVideo(
      videoUri: videoUri,
      modelProfile: modelProfile,
      trimStartMs: trimStartMs,
    );
  }

  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
  }) {
    return _client.getPreviewFrame(
      analysisCacheId: analysisCacheId,
      timestampMs: timestampMs,
      selectedPersonIds: selectedPersonIds,
      faceOnlyPersonIds: faceOnlyPersonIds,
      effects: effects,
      follow: follow,
    );
  }

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
    return _client.startExport(
      sourceUri: sourceUri,
      analysisCacheId: analysisCacheId,
      outputFilePath: outputFilePath,
      selectedPersonIds: selectedPersonIds,
      faceOnlyPersonIds: faceOnlyPersonIds,
      effects: effects,
      follow: follow,
      targetWidth: targetWidth,
      targetHeight: targetHeight,
      targetFps: targetFps,
      videoBitrate: videoBitrate,
      processingProfile: processingProfile,
      enableLivePreview: enableLivePreview,
      trimStartMs: trimStartMs,
      trimEndMs: trimEndMs,
    );
  }

  Future<List<String>> getVideoFrameThumbnails({
    required String videoUri,
    required List<int> timestampsMs,
  }) {
    return _client.getVideoFrameThumbnails(
      videoUri: videoUri,
      timestampsMs: timestampsMs,
    );
  }

  Future<void> cancelJob(String jobId) {
    return _client.cancelJob(jobId);
  }

  Future<JobStatusDto> getJobStatus(String jobId) {
    return _client.getJobStatus(jobId);
  }

  Future<void> releaseProject(String projectId) {
    return _client.releaseProject(projectId);
  }

  Future<String?> saveVideoToGallery(String filePath) {
    AppLogger.d(
      'NativeRepository',
      'Saving video to system gallery: $filePath',
    );
    return _client.saveVideoToGallery(filePath);
  }

  Future<void> shareVideo(String publicUri) {
    AppLogger.d('NativeRepository', 'Sharing exported video: $publicUri');
    return _client.shareVideo(publicUri);
  }

  Future<void> openVideo(String publicUri) {
    AppLogger.d('NativeRepository', 'Opening exported video: $publicUri');
    return _client.openVideo(publicUri);
  }

  Future<Map<dynamic, dynamic>?> createDiagnosticBundle() {
    AppLogger.d('NativeRepository', 'Creating diagnostic bundle...');
    return _client.createDiagnosticBundle();
  }

  Future<Map<dynamic, dynamic>?> shareDiagnosticBundle({
    String? filePath,
    String? publicUri,
  }) {
    AppLogger.d('NativeRepository', 'Sharing diagnostic bundle...');
    return _client.shareDiagnosticBundle(
      filePath: filePath,
      publicUri: publicUri,
    );
  }

  Future<void> clearDiagnosticLogs() {
    AppLogger.d('NativeRepository', 'Clearing diagnostic logs...');
    return _client.clearDiagnosticLogs();
  }
}
