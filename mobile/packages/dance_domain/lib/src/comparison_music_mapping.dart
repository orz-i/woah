import 'dart:math' as math;

/// Music correspondence only: target source = reference source * scale + offset.
/// Pose timing is never an input. Viewing speed is independent of this mapping.
class ComparisonMusicMapping {
  final int offsetUs;
  final double timeScale;
  const ComparisonMusicMapping(this.offsetUs, [this.timeScale = 1]);

  bool get valid =>
      timeScale.isFinite &&
      timeScale >= .5 &&
      timeScale <= 2 &&
      offsetUs.abs() <= 172800000000;
  int target(int referenceUs) => (referenceUs * timeScale).round() + offsetUs;
  int reference(int targetUs) => ((targetUs - offsetUs) / timeScale).round();
  ComparisonMusicMapping get inverse =>
      ComparisonMusicMapping((-offsetUs / timeScale).round(), 1 / timeScale);

  (int, int)? overlap(int referenceEndUs, int targetEndUs) {
    if (!valid) return null;
    final start = math.max(0, (-offsetUs / timeScale).ceil());
    final end = math.min(
      referenceEndUs,
      ((targetEndUs - 1 - offsetUs) / timeScale).floor() + 1,
    );
    return end > start ? (start, end) : null;
  }

  factory ComparisonMusicMapping.anchors(int r1, int t1, int r2, int t2) {
    if (r2 - r1 < 2000000 || t2 - t1 < 2000000) {
      throw const FormatException('第二组音乐点需晚于第一组，两路至少间隔 2 秒');
    }
    final scale = (t2 - t1) / (r2 - r1);
    final value = ComparisonMusicMapping((t1 - r1 * scale).round(), scale);
    if (!value.valid) throw const FormatException('音乐速度差需在 0.5–2 倍内，请检查两组对应点');
    return value;
  }
}
