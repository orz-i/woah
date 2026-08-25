import 'package:dance_domain/dance_domain.dart';
import 'bridge/dance_api.g.dart';

/// Extension methods to convert between domain models and Pigeon DTOs
extension VideoInfoDtoMapper on VideoInfoDto {
  VideoInfo toDomain() {
    return VideoInfo(
      codedWidth: codedWidth,
      codedHeight: codedHeight,
      displayWidth: displayWidth,
      displayHeight: displayHeight,
      fps: fps,
      durationMs: durationMs,
      rotation: rotation,
      videoCodec: videoCodec,
      audioCodec: audioCodec,
      hasAudio: hasAudio,
    );
  }
}

extension VideoInfoMapper on VideoInfo {
  VideoInfoDto toDto() {
    return VideoInfoDto(
      codedWidth: codedWidth,
      codedHeight: codedHeight,
      displayWidth: displayWidth,
      displayHeight: displayHeight,
      fps: fps,
      durationMs: durationMs,
      rotation: rotation,
      videoCodec: videoCodec,
      audioCodec: audioCodec,
      hasAudio: hasAudio,
    );
  }
}

extension DetectedPersonDtoMapper on DetectedPersonDto {
  PersonTrack toDomain({bool selected = true}) {
    return PersonTrack(
      id: id,
      normalizedInitialBox: NormalizedRect(
        left: x1,
        top: y1,
        right: x2,
        bottom: y2,
      ),
      thumbnailPath: thumbnailPath,
      confidence: confidence,
      selected: selected,
    );
  }
}

extension EffectConfigMapper on EffectConfig {
  EffectConfigDto toDto() {
    return EffectConfigDto(
      fillMode: fillMode.name,
      fillColorArgb: fillColorArgb,
      borderColorArgb: borderColorArgb,
      opacity: opacity,
      borderWidth: borderWidth,
      blurStrength: blurStrength,
      faceStickerEnabled: faceStickerEnabled,
      stickerAssetId: stickerAssetId,
      stickerScale: stickerScale,
      skinWhiten: skinWhiten,
      legStretchEnabled: legStretchEnabled,
      legStretch: legStretch,
      legZoneTop: legZoneTop,
      legZoneBottom: legZoneBottom,
    );
  }
}

extension FollowConfigMapper on FollowConfig {
  FollowConfigDto toDto() {
    return FollowConfigDto(
      enabled: enabled,
      targetPersonId: targetPersonId,
      zoom: zoom,
      smoothFactor: smoothFactor,
    );
  }
}
