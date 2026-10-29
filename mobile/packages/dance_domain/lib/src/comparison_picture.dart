/// Picture-only transform. Pan follows the image's available fit/crop travel.
/// Positive X/Y moves right/down on screen, including when mirrored.
class ComparisonPicture {
  final double zoom, panX, panY;
  const ComparisonPicture({this.zoom = 1, this.panX = 0, this.panY = 0});

  factory ComparisonPicture.clamped(double zoom, double panX, double panY) {
    if (!zoom.isFinite || !panX.isFinite || !panY.isFinite) {
      throw const FormatException('Invalid comparison picture');
    }
    final z = zoom.clamp(1.0, 4.0).toDouble();
    return ComparisonPicture(
      zoom: z,
      panX: z == 1 ? 0 : panX.clamp(-1.0, 1.0).toDouble(),
      panY: z == 1 ? 0 : panY.clamp(-1.0, 1.0).toDouble(),
    );
  }
  Map<String, Object?> toJson() => {'zoom': zoom, 'panX': panX, 'panY': panY};
  factory ComparisonPicture.fromJson(Map<String, dynamic> json) {
    final z = (json['zoom'] as num).toDouble();
    final x = (json['panX'] as num).toDouble();
    final y = (json['panY'] as num).toDouble();
    if (!z.isFinite ||
        !x.isFinite ||
        !y.isFinite ||
        z < 1 ||
        z > 4 ||
        x.abs() > 1 ||
        y.abs() > 1) {
      throw const FormatException('Invalid comparison picture');
    }
    return ComparisonPicture.clamped(z, x, y);
  }
  @override
  bool operator ==(Object other) =>
      other is ComparisonPicture &&
      zoom == other.zoom &&
      panX == other.panX &&
      panY == other.panY;
  @override
  int get hashCode => Object.hash(zoom, panX, panY);
}
