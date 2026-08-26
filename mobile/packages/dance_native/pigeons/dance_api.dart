import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/src/bridge/dance_api.g.dart',
  dartPackageName: 'dance_native',
  kotlinOut:
      'android/src/main/kotlin/com/danceanon/native/bridge/DanceApi.g.kt',
  kotlinOptions: KotlinOptions(
    package: 'com.danceanon.native.bridge',
  ),
  swiftOut: 'ios/Classes/DanceApi.g.swift',
  swiftOptions: SwiftOptions(),
))

class NativeCapabilitiesDto {
  final String platform;
  final String osVersion;
  final bool gpuSupported;
  final bool h264Encoder;
  final bool hevcEncoder;
  final int maxEncodeWidth;
  final int maxEncodeHeight;
  final int cpuCores;
  final String recommendedProfile;
  final List<String> supportedProfiles;
  final List<String> inferenceBackends;

  NativeCapabilitiesDto({
    required this.platform,
    required this.osVersion,
    required this.gpuSupported,
    required this.h264Encoder,
    required this.hevcEncoder,
    required this.maxEncodeWidth,
    required this.maxEncodeHeight,
    required this.cpuCores,
    required this.recommendedProfile,
    required this.supportedProfiles,
    required this.inferenceBackends,
  });
}

class VideoInfoDto {
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

  VideoInfoDto({
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
}

class DetectedPersonDto {
  final int id;
  final double x1;
  final double y1;
  final double x2;
  final double y2;
  final String thumbnailPath;
  final double confidence;

  DetectedPersonDto({
    required this.id,
    required this.x1,
    required this.y1,
    required this.x2,
    required this.y2,
    required this.thumbnailPath,
    required this.confidence,
  });
}

class AnalyzeRequestDto {
  final String videoUri;
  final String modelProfile;

  AnalyzeRequestDto({
    required this.videoUri,
    required this.modelProfile,
  });
}

class AnalyzeResultDto {
  final String analysisCacheId;
  final VideoInfoDto videoInfo;
  final List<DetectedPersonDto> persons;

  AnalyzeResultDto({
    required this.analysisCacheId,
    required this.videoInfo,
    required this.persons,
  });
}

class EffectConfigDto {
  final String fillMode;
  final int fillColorArgb;
  final int borderColorArgb;
  final double opacity;
  final double borderWidth;
  final double blurStrength;
  final bool faceStickerEnabled;
  final String? stickerAssetId;
  final double stickerScale;
  final double skinWhiten;
  final bool legStretchEnabled;
  final double legStretch;
  final double legZoneTop;
  final double legZoneBottom;

  EffectConfigDto({
    required this.fillMode,
    required this.fillColorArgb,
    required this.borderColorArgb,
    required this.opacity,
    required this.borderWidth,
    required this.blurStrength,
    required this.faceStickerEnabled,
    this.stickerAssetId,
    required this.stickerScale,
    required this.skinWhiten,
    required this.legStretchEnabled,
    required this.legStretch,
    required this.legZoneTop,
    required this.legZoneBottom,
  });
}

class FollowConfigDto {
  final bool enabled;
  final int? targetPersonId;
  final double zoom;
  final double smoothFactor;

  FollowConfigDto({
    required this.enabled,
    this.targetPersonId,
    required this.zoom,
    required this.smoothFactor,
  });
}

class PreviewRequestDto {
  final String analysisCacheId;
  final int timestampMs;
  final List<int> selectedPersonIds;
  final EffectConfigDto effects;
  final FollowConfigDto follow;

  PreviewRequestDto({
    required this.analysisCacheId,
    required this.timestampMs,
    required this.selectedPersonIds,
    required this.effects,
    required this.follow,
  });
}

class PreviewFrameDto {
  final String thumbnailPath;
  final int timestampMs;
  final double renderTimeMs;

  PreviewFrameDto({
    required this.thumbnailPath,
    required this.timestampMs,
    required this.renderTimeMs,
  });
}

class ExportRequestDto {
  final String sourceUri;
  final String analysisCacheId;
  final String outputFilePath;
  final List<int> selectedPersonIds;
  final EffectConfigDto effects;
  final FollowConfigDto follow;
  final int targetWidth;
  final int targetHeight;
  final double targetFps;
  final int videoBitrate;

  ExportRequestDto({
    required this.sourceUri,
    required this.analysisCacheId,
    required this.outputFilePath,
    required this.selectedPersonIds,
    required this.effects,
    required this.follow,
    required this.targetWidth,
    required this.targetHeight,
    required this.targetFps,
    required this.videoBitrate,
  });
}

class JobStatusDto {
  final String jobId;
  final String state;
  final int currentFrame;
  final int totalFrames;
  final double fps;
  final double progress;
  final String? outputUri;
  final String? errorCode;
  final String? errorMessage;

  JobStatusDto({
    required this.jobId,
    required this.state,
    required this.currentFrame,
    required this.totalFrames,
    required this.fps,
    required this.progress,
    this.outputUri,
    this.errorCode,
    this.errorMessage,
  });
}

@HostApi()
abstract class DanceNativeApi {
  @async
  NativeCapabilitiesDto getCapabilities();

  @async
  VideoInfoDto probeVideo(String uri);

  @async
  AnalyzeResultDto analyzeVideo(AnalyzeRequestDto request);

  @async
  PreviewFrameDto getPreviewFrame(PreviewRequestDto request);

  @async
  String startExport(ExportRequestDto request);

  @async
  void cancelJob(String jobId);

  @async
  JobStatusDto getJobStatus(String jobId);

  @async
  void releaseProject(String projectId);
}

@FlutterApi()
abstract class DanceProcessingEvents {
  void onProgressUpdate(JobStatusDto status);
}
