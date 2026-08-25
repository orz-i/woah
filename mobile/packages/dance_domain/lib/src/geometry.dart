/// Represents a normalized bounding box in [0.0, 1.0] range.
class NormalizedRect {
  final double left;
  final double top;
  final double right;
  final double bottom;

  const NormalizedRect({
    required this.left,
    required this.top,
    required this.right,
    required this.bottom,
  });

  double get width => (right - left).clamp(0.0, 1.0);
  double get height => (bottom - top).clamp(0.0, 1.0);
  double get centerX => left + width / 2.0;
  double get centerY => top + height / 2.0;

  Map<String, dynamic> toJson() => {
        'left': left,
        'top': top,
        'right': right,
        'bottom': bottom,
      };

  factory NormalizedRect.fromJson(Map<String, dynamic> json) => NormalizedRect(
        left: (json['left'] as num).toDouble(),
        top: (json['top'] as num).toDouble(),
        right: (json['right'] as num).toDouble(),
        bottom: (json['bottom'] as num).toDouble(),
      );

  NormalizedRect copyWith({
    double? left,
    double? top,
    double? right,
    double? bottom,
  }) {
    return NormalizedRect(
      left: left ?? this.left,
      top: top ?? this.top,
      right: right ?? this.right,
      bottom: bottom ?? this.bottom,
    );
  }

  @override
  String toString() =>
      'NormalizedRect(left: $left, top: $top, right: $right, bottom: $bottom)';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is NormalizedRect &&
          runtimeType == other.runtimeType &&
          left == other.left &&
          top == other.top &&
          right == other.right &&
          bottom == other.bottom;

  @override
  int get hashCode =>
      left.hashCode ^ top.hashCode ^ right.hashCode ^ bottom.hashCode;
}
