import 'time_kernel.dart';

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
  int target(int referenceUs) =>
      TimeKernel.map(referenceUs, timeScale, offsetUs);
  int reference(int targetUs) =>
      TimeKernel.map(targetUs, timeScale, offsetUs, inverse: true);
  ComparisonMusicMapping get inverse {
    final value = TimeKernel.inverse(timeScale, offsetUs);
    return ComparisonMusicMapping(value.$1, value.$2);
  }

  (int, int)? overlap(int referenceEndUs, int targetEndUs) {
    if (!valid) return null;
    return TimeKernel.overlap(referenceEndUs, targetEndUs, timeScale, offsetUs);
  }

  factory ComparisonMusicMapping.anchors(int r1, int t1, int r2, int t2) {
    if (r2 - r1 < 2000000 || t2 - t1 < 2000000) {
      throw const FormatException('第二组音乐点需晚于第一组，两路至少间隔 2 秒');
    }
    final mapping = TimeKernel.anchors(r1, t1, r2, t2);
    final value = ComparisonMusicMapping(mapping.$1, mapping.$2);
    if (!value.valid) throw const FormatException('音乐速度差需在 0.5–2 倍内，请检查两组对应点');
    return value;
  }
}
