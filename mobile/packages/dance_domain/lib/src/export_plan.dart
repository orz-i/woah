import 'dart:math' as math;

import 'project.dart';

enum ExportTimingPolicy { preserveSourcePts }

enum ExportFallbackReason { encoderDimensionLimit }

/// One deterministic media contract shared by Flutter and both native exporters.
///
/// Normal exports never enlarge source-derived pixels. Portrait subject reframing
/// is the only exception: when the 9:16 crop has fewer pixels than the requested
/// display envelope, crop clarity restoration may enlarge that crop by at most
/// 2x. Native pipelines consume [cropClarityScale] as an explicit enhancement
/// contract rather than applying hidden resolution changes.
class ExportPlan {
  final int width;
  final int height;
  final double nominalFps;
  final int videoBitrate;
  final OutputResolutionPreset resolutionPreset;
  final ExportTimingPolicy timingPolicy;
  final ExportFallbackReason? fallbackReason;

  /// Effective scale from visual source-crop pixels to encoded pixels.
  ///
  /// Values above 1 activate deterministic crop clarity restoration. V1 is
  /// deliberately bounded to 2x and only applies to subject-follow 9:16 crops.
  final double cropClarityScale;

  const ExportPlan({
    required this.width,
    required this.height,
    required this.nominalFps,
    required this.videoBitrate,
    this.resolutionPreset = OutputResolutionPreset.source,
    this.timingPolicy = ExportTimingPolicy.preserveSourcePts,
    this.fallbackReason,
    this.cropClarityScale = 1.0,
  });

  bool get hasFallback => fallbackReason != null;
  bool get hasCropClarityRestoration => cropClarityScale > 1.001;

  static ExportPlan forProject(
    DanceProject project, {
    int? maxEncodeWidth,
    int? maxEncodeHeight,
  }) {
    final desired = project.outputSize;
    final userBounded = _applyResolutionPreset(
      project,
      width: desired.width,
      height: desired.height,
    );
    final clarityTarget = _applyCropClarityTarget(
      project,
      width: userBounded.width,
      height: userBounded.height,
    );
    var width = _evenFloor(clarityTarget.width);
    var height = _evenFloor(clarityTarget.height);
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
        final exactNineSixteen = _isExactNineSixteenFollow(project);
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
      resolutionPreset: project.outputResolutionPreset,
      fallbackReason: fallbackReason,
      cropClarityScale: _cropClarityScaleFor(
        project,
        width: width,
        height: height,
      ),
    );
  }

  static ({int width, int height}) _applyResolutionPreset(
    DanceProject project, {
    required int width,
    required int height,
  }) {
    final landscapeBounds = switch (project.outputResolutionPreset) {
      OutputResolutionPreset.source => null,
      OutputResolutionPreset.fhd => (width: 1920, height: 1080),
      OutputResolutionPreset.hd => (width: 1280, height: 720),
    };
    if (landscapeBounds == null) {
      return (width: width, height: height);
    }

    final portrait = height > width;
    final capWidth = portrait ? landscapeBounds.height : landscapeBounds.width;
    final capHeight = portrait ? landscapeBounds.width : landscapeBounds.height;
    if (width <= capWidth && height <= capHeight) {
      return (width: width, height: height);
    }

    final exactNineSixteen = _isExactNineSixteenFollow(project);
    if (exactNineSixteen) {
      final units = math.min(
        math.min(width ~/ 18, height ~/ 32),
        math.min(capWidth ~/ 18, capHeight ~/ 32),
      );
      final safeUnits = math.max(1, units);
      return (width: safeUnits * 18, height: safeUnits * 32);
    }

    final scale = math.min(capWidth / width, capHeight / height);
    return (
      width: _evenFloor(width * scale),
      height: _evenFloor(height * scale),
    );
  }

  /// V1 crop-clarity policy: only a real 9:16 subject crop can be enlarged,
  /// and only up to the smaller of the user envelope and a strict 2x crop scale.
  /// Source mode uses FHD portrait as the restoration ceiling; higher-resolution
  /// source crops keep their existing source-derived geometry without downscaling.
  static ({int width, int height}) _applyCropClarityTarget(
    DanceProject project, {
    required int width,
    required int height,
  }) {
    final crop = _sourceCropSize(project);
    if (crop == null || !_isActualCrop(project, crop)) {
      return (width: width, height: height);
    }

    final currentScale = math.max(width / crop.width, height / crop.height);
    final envelopeUnits = switch (project.outputResolutionPreset) {
      OutputResolutionPreset.hd => 40, // 720x1280
      OutputResolutionPreset.fhd ||
      OutputResolutionPreset.source => 60, // 1080x1920
    };
    final x2Units = math.min(
      (crop.width * 2.0 / 18.0).floor(),
      (crop.height * 2.0 / 32.0).floor(),
    );
    if (x2Units <= 0) return (width: width, height: height);

    final currentUnits = math.min(width ~/ 18, height ~/ 32);
    final boundedUnits = math.max(1, math.min(envelopeUnits, x2Units));

    // A high-resolution crop that already reaches the requested restoration
    // envelope must not be pulled down merely because source mode can preserve
    // more than FHD. Smaller 1080p/720p crops are allowed to grow toward it.
    if (currentScale <= 1.001 && currentUnits >= envelopeUnits) {
      return (width: width, height: height);
    }

    // Never ask the V1 restoration path to exceed 2x. This also corrects future
    // non-default follow zoom values that would otherwise silently over-upscale.
    final targetUnits = currentScale > 2.0
        ? boundedUnits
        : math.max(currentUnits, boundedUnits);
    return (width: targetUnits * 18, height: targetUnits * 32);
  }

  static double _cropClarityScaleFor(
    DanceProject project, {
    required int width,
    required int height,
  }) {
    final crop = _sourceCropSize(project);
    if (crop == null || !_isActualCrop(project, crop)) return 1.0;
    final required = math.max(width / crop.width, height / crop.height);
    if (!required.isFinite || required <= 1.001) return 1.0;
    return required.clamp(1.0, 2.0).toDouble();
  }

  static ({double width, double height})? _sourceCropSize(
    DanceProject project,
  ) {
    if (!_isExactNineSixteenFollow(project)) return null;
    final sourceWidth = project.videoInfo.width.toDouble();
    final sourceHeight = project.videoInfo.height.toDouble();
    if (sourceWidth <= 0 || sourceHeight <= 0) return null;

    final sourceAspect = sourceWidth / sourceHeight;
    final outputAspect = project.follow.outputAspectRatio!;
    final zoom = project.follow.zoom.isFinite
        ? project.follow.zoom.clamp(1.0, 3.0).toDouble()
        : 1.0;
    final cropWidthFraction = math.min(1.0, outputAspect / sourceAspect) / zoom;
    final cropHeightFraction =
        math.min(1.0, sourceAspect / outputAspect) / zoom;
    return (
      width: sourceWidth * cropWidthFraction,
      height: sourceHeight * cropHeightFraction,
    );
  }

  static bool _isActualCrop(
    DanceProject project,
    ({double width, double height}) crop,
  ) {
    final sourceWidth = project.videoInfo.width.toDouble();
    final sourceHeight = project.videoInfo.height.toDouble();
    return crop.width < sourceWidth - 0.5 || crop.height < sourceHeight - 0.5;
  }

  static bool _isExactNineSixteenFollow(DanceProject project) {
    if (!project.follow.enabled) return false;
    final ratio = project.follow.outputAspectRatio;
    return ratio != null && ratio.isFinite && (ratio - 9 / 16).abs() < 1e-9;
  }

  static int _evenFloor(num value) {
    final integer = value.floor();
    if (integer <= 2) return 2;
    return integer.isEven ? integer : integer - 1;
  }

  @override
  String toString() {
    final fallback = fallbackReason == null ? 'none' : fallbackReason!.name;
    final clarity = hasCropClarityRestoration
        ? '${cropClarityScale.toStringAsFixed(3)}x'
        : 'off';
    return 'ExportPlan(${width}x$height @ ${nominalFps.toStringAsFixed(3)}fps, '
        'preset=${resolutionPreset.name}, bitrate=$videoBitrate, '
        'timing=${timingPolicy.name}, clarity=$clarity, fallback=$fallback)';
  }
}
