import 'dart:math' as math;
import 'dart:ui';
import 'package:dance_domain/dance_domain.dart';

/// Inverse of the native integer pane/fit and zoom/pan quad. The returned
/// point is display-oriented, normalized, and unmirrored for person inference.
Offset? comparisonCutoutPoint({
  required Size viewport,
  required Offset point,
  required int width,
  required int height,
  required bool horizontal,
  required bool reference,
  required double aspect,
  required ComparisonPicture picture,
  required bool mirrored,
}) {
  const gap = 8;
  if (viewport.isEmpty ||
      width <= gap ||
      height <= gap ||
      !aspect.isFinite ||
      aspect <= 0) {
    return null;
  }
  final first = ((horizontal ? width : height) - gap) ~/ 2;
  final x = horizontal && !reference ? first + gap : 0;
  final y = !horizontal && !reference ? first + gap : 0;
  final w = horizontal ? (reference ? first : width - x) : width;
  final h = horizontal ? height : (reference ? first : height - y);
  final px = point.dx / viewport.width * width - x;
  final py = point.dy / viewport.height * height - y;
  if (px < 0 || px >= w || py < 0 || py >= h) return null;
  final fw = math.max(1, math.min(w.toDouble(), h * aspect).toInt());
  final fh = math.max(1, math.min(h.toDouble(), w / aspect).toInt());
  final zoom = picture.zoom;
  final sx = fw / w * zoom;
  final sy = fh / h * zoom;
  final cx = (2 * ((w - fw) ~/ 2 + fw / 2) / w - 1) * zoom;
  final cy = (1 - 2 * ((h - fh) ~/ 2 + fh / 2) / h) * zoom;
  final tx = (sx - 1).abs(), ty = (sy - 1).abs();
  final shiftX = zoom == 1 ? cx : (cx + picture.panX * tx).clamp(-tx, tx);
  final shiftY = zoom == 1 ? cy : (cy - picture.panY * ty).clamp(-ty, ty);
  var u = ((2 * px / w - 1 - shiftX) / sx + 1) / 2;
  final v = (1 - (1 - 2 * py / h - shiftY) / sy) / 2;
  if (mirrored) u = 1 - u;
  if (!u.isFinite || !v.isFinite || u < 0 || u >= 1 || v < 0 || v >= 1) {
    return null;
  }
  return Offset(u, v);
}

bool comparisonCutoutDrop(
  Size size,
  Offset start,
  Offset end, {
  required bool horizontal,
  required bool reference,
}) {
  final length = horizontal ? size.width : size.height;
  final a = horizontal ? start.dx : start.dy;
  final b = horizontal ? end.dx : end.dy;
  final crossLength = horizontal ? size.height : size.width;
  final cross = horizontal ? end.dy : end.dx;
  return length > 0 &&
      crossLength > 0 &&
      (reference ? b - a : a - b) >= 12 &&
      (b - length / 2).abs() <= length * .16 &&
      (cross - crossLength / 2).abs() <= crossLength * .42;
}
