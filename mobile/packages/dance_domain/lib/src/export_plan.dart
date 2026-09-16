import 'dart:math' as math;

import 'project.dart';

enum ExportTimingPolicy { preserveSourcePts }

enum ExportFallbackReason { encoderDimensionLimit }

/// One deterministic media contract shared by Flutter and both native exporters.
///
/// The plan never enlarges source-derived pixels. Native pipelines must consume
/// these values as-is instead of applying their own silent resolution/FPS caps.
class ExportPlan {
  final int width;
  final int height;
  final double nominalFps;
  final int videoBitrate;
  final ExportTimingPolicy timingPolicy;
  final ExportFallbackReason? fallbackReason;

  const ExportPlan({
    required this.width,
    required this.height,
    required this.nominalFps,
    required this.videoBitrate,
    this.timingPolicy = ExportTimingPolicy.preserveSourcePts,
    this.fallbackReason,
  });

  bool get hasFallback => fallbackReason != null;

  static ExportPlan forProject(
    DanceProject project, {
    int? maxEncodeWidth,
    int? maxEncodeHeight,
  }) {
    final desired = project.outputSize;
    var width = _evenFloor(desired.width);
    var height = _evenFloor(desired.height);
    ExportFallbackReason? fallbackReason;

    final maxWidth = maxEncodeWidth ?? 0;
    final maxHeight = maxEncodeHeight ?? 0;
    if (maxWidth > 0 && maxHeight > 0) {
      // Capability APIs commonly report landscape maxima. Treat the swapped
      // pair as the corresponding portrait capability.
      final portrait = height > width;
      final capWidth = portrait ? maxHeight : maxWidth;
      final capHeight = portrait ? maxWidth : maxHeight;
      if (width > capWidth || height > capHeight) {
        final exactNineSixteen =
            project.follow.enabled &&
            project.follow.outputAspectRatio == 9 / 16;
        if (exactNineSixteen) {
          final units = math.min(
            math.min(width ~/ 18, height ~/ 32),
            math.min(capWidth ~/ 18, capHeight ~/ 32),
          );
          width = math.max(1, units) * 18;
          height = math.max(1, units) * 32;
        } else {
          final scale = math.min(capWidth / width, capHeight / height);
          width = _evenFloor(width * scale);
          height = _evenFloor(height * scale);
        }
        fallbackReason = ExportFallbackReason.encoderDimensionLimit;
      }
    }

    final sourceFps = project.videoInfo.fps;
    final nominalFps = sourceFps.isFinite && sourceFps > 0 ? sourceFps : 30.0;

    // Quality-first H.264 target: approximately 0.13 bits/pixel/frame. This
    // preserves the previous ~8 Mbps behavior for 1080p30 while scaling with
    // both resolution and motion cadence for 4K/high-frame-rate inputs.
    final estimatedBitrate = (width * height * nominalFps * 0.13).round();
    final bitrate = estimatedBitrate.clamp(4_000_000, 80_000_000);

    return ExportPlan(
      width: width,
      height: height,
      nominalFps: nominalFps,
      videoBitrate: bitrate,
      fallbackReason: fallbackReason,
    );
  }

  static int _evenFloor(num value) {
    final integer = value.floor();
    if (integer <= 2) return 2;
    return integer.isEven ? integer : integer - 1;
  }

  @override
  String toString() {
    final fallback = fallbackReason == null ? 'none' : fallbackReason!.name;
    return 'ExportPlan(${width}x$height @ ${nominalFps.toStringAsFixed(3)}fps, '
        'bitrate=$videoBitrate, timing=${timingPolicy.name}, fallback=$fallback)';
  }
}
