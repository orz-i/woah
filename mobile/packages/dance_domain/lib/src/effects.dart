/// Fill mode for anonymizing body masks
enum FillMode {
  solid,
  blur,
  gradient,
  sticker,
  mosaic;

  static FillMode fromString(String value) {
    return FillMode.values.firstWhere(
      (e) => e.name.toLowerCase() == value.toLowerCase(),
      orElse: () => FillMode.solid,
    );
  }
}

/// Rendering and visual effect configuration matching Python reference pipeline
class EffectConfig {
  final FillMode fillMode;

  /// ARGB color int (e.g. 0xFF000000 for black)
  final int fillColorArgb;

  /// ARGB border color int
  final int borderColorArgb;

  /// Mask opacity [0.0, 1.0]
  final double opacity;

  /// Border width in pixels [0.0, 50.0]
  final double borderWidth;

  /// Blur kernel size / strength if fillMode == blur
  final double blurStrength;

  /// Whether face sticker replacement is enabled
  final bool faceStickerEnabled;

  /// Identifier or asset path for face sticker
  final String? stickerAssetId;

  /// Scale multiplier for sticker [0.5, 3.0]
  final double stickerScale;

  /// Skin whitening intensity [0.0, 1.0]
  final double skinWhiten;

  /// Whether leg stretch effect is enabled
  final bool legStretchEnabled;

  /// Leg stretch intensity [0.0, 0.5]
  final double legStretch;

  /// Normalized Y top boundary of leg zone [0.0, 1.0]
  final double legZoneTop;

  /// Normalized Y bottom boundary of leg zone [0.0, 1.0]
  final double legZoneBottom;

  const EffectConfig({
    this.fillMode = FillMode.solid,
    this.fillColorArgb = 0xFF000000,
    this.borderColorArgb = 0xFF00FF00,
    this.opacity = 1.0,
    this.borderWidth = 0.0,
    this.blurStrength = 15.0,
    this.faceStickerEnabled = false,
    this.stickerAssetId,
    this.stickerScale = 1.0,
    this.skinWhiten = 0.0,
    this.legStretchEnabled = false,
    this.legStretch = 0.0,
    this.legZoneTop = 0.55,
    this.legZoneBottom = 0.95,
  });

  Map<String, dynamic> toJson() => {
        'fillMode': fillMode.name,
        'fillColorArgb': fillColorArgb,
        'borderColorArgb': borderColorArgb,
        'opacity': opacity,
        'borderWidth': borderWidth,
        'blurStrength': blurStrength,
        'faceStickerEnabled': faceStickerEnabled,
        'stickerAssetId': stickerAssetId,
        'stickerScale': stickerScale,
        'skinWhiten': skinWhiten,
        'legStretchEnabled': legStretchEnabled,
        'legStretch': legStretch,
        'legZoneTop': legZoneTop,
        'legZoneBottom': legZoneBottom,
      };

  factory EffectConfig.fromJson(Map<String, dynamic> json) => EffectConfig(
        fillMode: FillMode.fromString(json['fillMode'] as String? ?? 'solid'),
        fillColorArgb: json['fillColorArgb'] as int? ?? 0xFF000000,
        borderColorArgb: json['borderColorArgb'] as int? ?? 0xFF00FF00,
        opacity: (json['opacity'] as num?)?.toDouble() ?? 1.0,
        borderWidth: (json['borderWidth'] as num?)?.toDouble() ?? 0.0,
        blurStrength: (json['blurStrength'] as num?)?.toDouble() ?? 15.0,
        faceStickerEnabled: json['faceStickerEnabled'] as bool? ?? false,
        stickerAssetId: json['stickerAssetId'] as String?,
        stickerScale: (json['stickerScale'] as num?)?.toDouble() ?? 1.0,
        skinWhiten: (json['skinWhiten'] as num?)?.toDouble() ?? 0.0,
        legStretchEnabled: json['legStretchEnabled'] as bool? ?? false,
        legStretch: (json['legStretch'] as num?)?.toDouble() ?? 0.0,
        legZoneTop: (json['legZoneTop'] as num?)?.toDouble() ?? 0.55,
        legZoneBottom: (json['legZoneBottom'] as num?)?.toDouble() ?? 0.95,
      );

  EffectConfig copyWith({
    FillMode? fillMode,
    int? fillColorArgb,
    int? borderColorArgb,
    double? opacity,
    double? borderWidth,
    double? blurStrength,
    bool? faceStickerEnabled,
    String? stickerAssetId,
    double? stickerScale,
    double? skinWhiten,
    bool? legStretchEnabled,
    double? legStretch,
    double? legZoneTop,
    double? legZoneBottom,
  }) {
    return EffectConfig(
      fillMode: fillMode ?? this.fillMode,
      fillColorArgb: fillColorArgb ?? this.fillColorArgb,
      borderColorArgb: borderColorArgb ?? this.borderColorArgb,
      opacity: opacity ?? this.opacity,
      borderWidth: borderWidth ?? this.borderWidth,
      blurStrength: blurStrength ?? this.blurStrength,
      faceStickerEnabled: faceStickerEnabled ?? this.faceStickerEnabled,
      stickerAssetId: stickerAssetId ?? this.stickerAssetId,
      stickerScale: stickerScale ?? this.stickerScale,
      skinWhiten: skinWhiten ?? this.skinWhiten,
      legStretchEnabled: legStretchEnabled ?? this.legStretchEnabled,
      legStretch: legStretch ?? this.legStretch,
      legZoneTop: legZoneTop ?? this.legZoneTop,
      legZoneBottom: legZoneBottom ?? this.legZoneBottom,
    );
  }
}

/// Dynamic camera following configuration
class FollowConfig {
  final bool enabled;
  final int? targetPersonId;
  final double zoom;
  final double smoothFactor;

  const FollowConfig({
    this.enabled = false,
    this.targetPersonId,
    this.zoom = 1.0,
    this.smoothFactor = 0.1,
  });

  Map<String, dynamic> toJson() => {
        'enabled': enabled,
        'targetPersonId': targetPersonId,
        'zoom': zoom,
        'smoothFactor': smoothFactor,
      };

  factory FollowConfig.fromJson(Map<String, dynamic> json) => FollowConfig(
        enabled: json['enabled'] as bool? ?? false,
        targetPersonId: json['targetPersonId'] as int?,
        zoom: (json['zoom'] as num?)?.toDouble() ?? 1.0,
        smoothFactor: (json['smoothFactor'] as num?)?.toDouble() ?? 0.1,
      );

  FollowConfig copyWith({
    bool? enabled,
    int? targetPersonId,
    double? zoom,
    double? smoothFactor,
  }) {
    return FollowConfig(
      enabled: enabled ?? this.enabled,
      targetPersonId: targetPersonId ?? this.targetPersonId,
      zoom: zoom ?? this.zoom,
      smoothFactor: smoothFactor ?? this.smoothFactor,
    );
  }
}

/// Static or crop boundary configuration
class CropConfig {
  final double x;
  final double y;
  final double width;
  final double height;

  const CropConfig({
    required this.x,
    required this.y,
    required this.width,
    required this.height,
  });

  Map<String, dynamic> toJson() => {
        'x': x,
        'y': y,
        'width': width,
        'height': height,
      };

  factory CropConfig.fromJson(Map<String, dynamic> json) => CropConfig(
        x: (json['x'] as num).toDouble(),
        y: (json['y'] as num).toDouble(),
        width: (json['width'] as num).toDouble(),
        height: (json['height'] as num).toDouble(),
      );
}
