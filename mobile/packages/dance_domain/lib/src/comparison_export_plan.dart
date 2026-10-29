import 'dart:math' as math;
import 'comparison_project.dart';

/// Export the current composite canvas at 1x. Reference-source cadence is native.
class ComparisonExportPlan {
  final ComparisonProject project;
  final int startUs, endUs, width, height, gap;
  final bool sideBySide;
  const ComparisonExportPlan._(
    this.project,
    this.startUs,
    this.endUs,
    this.width,
    this.height,
    this.gap,
    this.sideBySide,
  );
  factory ComparisonExportPlan({
    required ComparisonProject project,
    required int durationUs,
    required int previewWidth,
    required int previewHeight,
    required bool sideBySide,
    bool selection = false,
  }) {
    if (!project.hasPair ||
        !project.musicMapping.valid ||
        durationUs <= 0 ||
        previewWidth < 16 ||
        previewHeight < 16) {
      throw const FormatException('请等待两路画面就绪后导出');
    }
    final start = selection ? project.loopStartUs : 0;
    final end = selection ? project.loopEndUs : durationUs;
    if (start == null ||
        end == null ||
        start < 0 ||
        end <= start ||
        end > durationUs) {
      throw const FormatException('请先在播放条上选定有效的 A/B 区间');
    }
    final factor = math.min(1.0, 1920 / math.max(previewWidth, previewHeight));
    final width = ((previewWidth * factor).floor() ~/ 2) * 2;
    final height = ((previewHeight * factor).floor() ~/ 2) * 2;
    if (width < 16 || height < 16) {
      throw const FormatException('当前画面比例无法编码，请调整排列后重试');
    }
    return ComparisonExportPlan._(
      project,
      start,
      end,
      width,
      height,
      (8 * factor).round().clamp(1, 64),
      sideBySide,
    );
  }
  int get durationUs => endUs - startUs;
}
