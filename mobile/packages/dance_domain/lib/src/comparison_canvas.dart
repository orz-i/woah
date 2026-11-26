/// Canvas placement is relative to the output canvas, not the current bounding
/// box. x/y anchor the selected person's original bottom center; height is a
/// fraction of canvas height. Never normalize every moving detection box.
class ComparisonCanvasLayer {
  final double x, y, height, opacity;
  final bool mirror;
  const ComparisonCanvasLayer({
    required this.x,
    required this.y,
    required this.height,
    this.opacity = 1,
    this.mirror = false,
  });

  bool get valid =>
      x.isFinite &&
      y.isFinite &&
      height.isFinite &&
      opacity.isFinite &&
      x >= 0 &&
      x <= 1 &&
      y >= 0 &&
      y <= 1 &&
      height >= .05 &&
      height <= 2 &&
      opacity >= 0 &&
      opacity <= 1;

  ComparisonCanvasLayer copyWith({
    double? x,
    double? y,
    double? height,
    double? opacity,
    bool? mirror,
  }) => ComparisonCanvasLayer(
    x: x ?? this.x,
    y: y ?? this.y,
    height: height ?? this.height,
    opacity: opacity ?? this.opacity,
    mirror: mirror ?? this.mirror,
  );

  Map<String, Object?> toJson() => {
    'x': x,
    'y': y,
    'height': height,
    'opacity': opacity,
    'mirror': mirror,
  };
  factory ComparisonCanvasLayer.fromJson(Map<String, dynamic> json) {
    final v = ComparisonCanvasLayer(
      x: (json['x'] as num).toDouble(),
      y: (json['y'] as num).toDouble(),
      height: (json['height'] as num).toDouble(),
      opacity: (json['opacity'] as num).toDouble(),
      mirror: json['mirror'] as bool,
    );
    if (!v.valid) throw const FormatException('Invalid canvas layer');
    return v;
  }
}

/// Only editing preferences are durable. Person identity/alpha are session
/// data, so opening an old project always requires explicitly selecting people.
class ComparisonCanvasLayout {
  final ComparisonCanvasLayer reference, target;
  final bool targetOnTop;

  /// 0 black; 1 charcoal; 2 light gray.
  final int background;
  const ComparisonCanvasLayout({
    required this.reference,
    required this.target,
    this.targetOnTop = true,
    this.background = 0,
  });

  factory ComparisonCanvasLayout.sideBySide({
    bool mirrorReference = false,
    bool mirrorTarget = false,
  }) => ComparisonCanvasLayout(
    reference: ComparisonCanvasLayer(
      x: .27,
      y: .88,
      height: .70,
      mirror: mirrorReference,
    ),
    target: ComparisonCanvasLayer(
      x: .73,
      y: .88,
      height: .70,
      mirror: mirrorTarget,
    ),
  );

  bool get valid =>
      reference.valid && target.valid && background >= 0 && background <= 2;
  ComparisonCanvasLayer layer(bool isReference) =>
      isReference ? reference : target;
  ComparisonCanvasLayout withLayer(
    bool isReference,
    ComparisonCanvasLayer value,
  ) => copyWith(
    reference: isReference ? value : null,
    target: isReference ? null : value,
  );
  ComparisonCanvasLayout copyWith({
    ComparisonCanvasLayer? reference,
    ComparisonCanvasLayer? target,
    bool? targetOnTop,
    int? background,
  }) => ComparisonCanvasLayout(
    reference: reference ?? this.reference,
    target: target ?? this.target,
    targetOnTop: targetOnTop ?? this.targetOnTop,
    background: background ?? this.background,
  );

  ComparisonCanvasLayout overlay() => copyWith(
    reference: reference.copyWith(x: .5, y: .88, height: .70, opacity: 1),
    target: target.copyWith(x: .5, y: .88, height: .70, opacity: .5),
    targetOnTop: true,
  );
  ComparisonCanvasLayout resetPlacement() => copyWith(
    reference: reference.copyWith(x: .27, y: .88, height: .70, opacity: 1),
    target: target.copyWith(x: .73, y: .88, height: .70, opacity: 1),
    targetOnTop: true,
  );

  Map<String, Object?> toJson() => {
    'reference': reference.toJson(),
    'target': target.toJson(),
    'targetOnTop': targetOnTop,
    'background': background,
  };
  factory ComparisonCanvasLayout.fromJson(Map<String, dynamic> json) {
    final v = ComparisonCanvasLayout(
      reference: ComparisonCanvasLayer.fromJson(
        Map<String, dynamic>.from(json['reference'] as Map),
      ),
      target: ComparisonCanvasLayer.fromJson(
        Map<String, dynamic>.from(json['target'] as Map),
      ),
      targetOnTop: json['targetOnTop'] as bool,
      background: json['background'] as int,
    );
    if (!v.valid) throw const FormatException('Invalid canvas layout');
    return v;
  }
}
