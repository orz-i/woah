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

/// Visual selection slots are separate from the frozen native texture geometry.
/// After the first drop, reserve the middle third as a real black canvas and
/// fit each source pane into its own slot, preserving source aspect and hit map.
class ComparisonCutoutStage {
  final Size size;
  final int width, height;
  final bool horizontal, expanded;
  const ComparisonCutoutStage(
    this.size,
    this.width,
    this.height,
    this.horizontal,
    this.expanded,
  );

  Rect nativePane(bool reference) {
    const gap = 8;
    final first = ((horizontal ? width : height) - gap) ~/ 2;
    final start = reference ? 0 : first + gap;
    return horizontal
        ? Rect.fromLTWH(
            start.toDouble(),
            0,
            (reference ? first : width - start).toDouble(),
            height.toDouble(),
          )
        : Rect.fromLTWH(
            0,
            start.toDouble(),
            width.toDouble(),
            (reference ? first : height - start).toDouble(),
          );
  }

  Rect get canvas {
    return horizontal
        ? Rect.fromLTWH(size.width * .32, 0, size.width * .36, size.height)
        : Rect.fromLTWH(0, size.height * .32, size.width, size.height * .36);
  }

  Rect slot(bool reference) {
    if (!expanded) {
      final p = nativePane(reference);
      return Rect.fromLTWH(
        p.left * size.width / width,
        p.top * size.height / height,
        p.width * size.width / width,
        p.height * size.height / height,
      );
    }
    return horizontal
        ? Rect.fromLTWH(
            reference ? 0 : canvas.right,
            0,
            size.width * .32,
            size.height,
          )
        : Rect.fromLTWH(
            0,
            reference ? 0 : canvas.bottom,
            size.width,
            size.height * .32,
          );
  }

  Rect fitted(bool reference) {
    final p = nativePane(reference), s = slot(reference);
    if (!expanded) return s;
    final scale = math.min(s.width / p.width, s.height / p.height);
    return Rect.fromCenter(
      center: s.center,
      width: p.width * scale,
      height: p.height * scale,
    );
  }

  Offset? nativePoint(Offset point, bool reference) {
    final f = fitted(reference), p = nativePane(reference);
    if (!f.contains(point) || f.isEmpty) return null;
    return Offset(
      p.left + (point.dx - f.left) * p.width / f.width,
      p.top + (point.dy - f.top) * p.height / f.height,
    );
  }
}
