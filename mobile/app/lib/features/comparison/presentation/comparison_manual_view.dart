import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../app/theme.dart';
import 'comparison_manual_session.dart';
import 'comparison_time.dart';
import 'comparison_picture_surface.dart';

class ComparisonManualView extends StatelessWidget {
  const ComparisonManualView({
    super.key,
    required this.session,
    required this.onApply,
    required this.onCancel,
    this.saving = false,
    this.sideBySide,
    this.mirrorTeacher = false,
    this.mirrorLearner = false,
    this.teacherPicture = const ComparisonPicture(),
    this.learnerPicture = const ComparisonPicture(),
    this.error,
  });
  final ComparisonManualSession session;
  final VoidCallback onApply, onCancel;
  final bool saving;
  final bool? sideBySide;
  final bool mirrorTeacher, mirrorLearner;
  final ComparisonPicture teacherPicture, learnerPicture;
  final String? error;
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: session,
    builder: (context, _) => LayoutBuilder(
      builder: (context, bounds) {
        final panes = [
          for (var i = 0; i < 2; i++) Expanded(child: _pane(context, i)),
        ];
        final preview = (sideBySide ?? (bounds.maxWidth > bounds.maxHeight))
            ? Row(children: panes)
            : Column(children: panes);
        final wide =
            bounds.maxWidth >= 600 && bounds.maxWidth > bounds.maxHeight;
        return Column(
          children: [
            if (error != null || session.error != null)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                child: Text(
                  error ?? session.error!,
                  style: TextStyle(
                    fontSize: 12,
                    color: AppTheme.of(context).error,
                  ),
                ),
              ),
            Expanded(
              child: wide
                  ? Row(
                      children: [
                        Expanded(child: preview),
                        SizedBox(
                          width: math.min(300, bounds.maxWidth * .4),
                          child: Center(
                            child: SingleChildScrollView(
                              child: _footer(context),
                            ),
                          ),
                        ),
                      ],
                    )
                  : Column(
                      children: [
                        Expanded(child: preview),
                        _footer(context),
                      ],
                    ),
            ),
          ],
        );
      },
    ),
  );

  Widget _footer(BuildContext context) => Padding(
    key: const ValueKey('comparison-manual-actions'),
    padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          width: double.infinity,
          child: SegmentedButton<bool>(
            segments: const [
              ButtonSegment(value: false, label: Text('只调位置')),
              ButtonSegment(value: true, label: Text('校准速度差')),
            ],
            style: ButtonStyle(
              foregroundColor: WidgetStateProperty.resolveWith(
                (states) => states.contains(WidgetState.selected)
                    ? AppTheme.of(context).brandAction
                    : AppTheme.of(context).flowTextSecondary,
              ),
              backgroundColor: WidgetStateProperty.resolveWith(
                (states) => states.contains(WidgetState.selected)
                    ? AppTheme.of(context).editorSelectionSubtle
                    : Colors.transparent,
              ),
              side: const WidgetStatePropertyAll(BorderSide.none),
              textStyle: const WidgetStatePropertyAll(TextStyle(fontSize: 13)),
            ),
            selected: {session.rateCalibration},
            onSelectionChanged: saving || session.anchoring
                ? null
                : (v) => session.setRateCalibration(v.first),
            showSelectedIcon: false,
          ),
        ),
        if (session.rateCalibration)
          Row(
            children: [
              for (var i = 0; i < 2; i++)
                Expanded(
                  child: TextButton(
                    key: ValueKey('comparison-anchor-$i'),
                    onPressed:
                        saving ||
                            session.anchoring ||
                            (i == 1 && session.firstAnchor == null)
                        ? null
                        : () => session.selectAnchor(i),
                    style: TextButton.styleFrom(
                      minimumSize: const Size(48, 48),
                      foregroundColor: session.anchorIndex == i
                          ? AppTheme.of(context).brandAction
                          : AppTheme.of(context).flowTextSecondary,
                      backgroundColor: session.anchorIndex == i
                          ? AppTheme.of(context).editorSelectionSubtle
                          : Colors.transparent,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                    child: Text(
                      i == 0
                          ? '前段音乐点${session.firstAnchor != null ? ' ✓' : ''}'
                          : '后段音乐点',
                    ),
                  ),
                ),
            ],
          ),
        const SizedBox(height: 8),
        Row(
          children: [
            TextButton(
              onPressed: saving || session.anchoring ? null : onCancel,
              child: const Text('取消'),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FilledButton.icon(
                key: const ValueKey('comparison-manual-apply'),
                onPressed:
                    session.ready &&
                        session.error == null &&
                        !saving &&
                        !session.anchoring
                    ? session.rateCalibration && session.anchorIndex == 0
                          ? () => session.recordFirstAnchor()
                          : onApply
                    : null,
                style: FilledButton.styleFrom(
                  backgroundColor: AppTheme.of(context).brandAction,
                  foregroundColor: AppTheme.of(context).textOnAccent,
                  minimumSize: const Size(48, 48),
                ),
                icon: const Icon(Icons.check, size: 20),
                label: Text(
                  saving
                      ? '正在保存…'
                      : session.rateCalibration && session.anchorIndex == 0
                      ? '记录前段音乐点'
                      : '完成校准',
                ),
              ),
            ),
          ],
        ),
      ],
    ),
  );

  Widget _pane(BuildContext context, int role) {
    final video = session.videos[role];
    final label = role == 0 ? '参考' : '目标';
    final value = session.position(role);
    final duration = math.max(1, session.duration(role));
    return Container(
      key: ValueKey('comparison-manual-pane-$role'),
      color: Colors.black,
      child: Column(
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (video.value.isInitialized)
                  ComparisonPictureSurface(
                    aspectRatio: video.value.aspectRatio,
                    picture: role == 0 ? teacherPicture : learnerPicture,
                    child: Center(
                      child: AspectRatio(
                        aspectRatio: video.value.aspectRatio,
                        child: Transform.scale(
                          key: ValueKey('comparison-manual-mirror-$role'),
                          scaleX: (role == 0 ? mirrorTeacher : mirrorLearner)
                              ? -1
                              : 1,
                          child: VideoPlayer(video),
                        ),
                      ),
                    ),
                  )
                else
                  const Center(
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                Positioned(
                  top: 6,
                  left: 10,
                  right: 10,
                  child: LayoutBuilder(
                    builder: (context, bounds) {
                      final narrow = bounds.maxWidth < 260;
                      final roleBadge = _badge(
                        Text(label, style: const TextStyle(fontSize: 12)),
                      );
                      final timeBadge = _badge(
                        Text(
                          narrow
                              ? '${comparisonTime(value)}\n/ ${comparisonTime(duration)}'
                              : '${comparisonTime(value)} / ${comparisonTime(duration)}',
                          maxLines: 2,
                          style: TextStyle(
                            fontSize: narrow ? 10 : 11,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                      );
                      if (narrow) {
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            roleBadge,
                            const SizedBox(height: 4),
                            Align(
                              alignment: Alignment.topRight,
                              child: timeBadge,
                            ),
                          ],
                        );
                      }
                      return Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          roleBadge,
                          const SizedBox(width: 8),
                          Expanded(
                            child: Align(
                              alignment: Alignment.topRight,
                              child: timeBadge,
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
          ColoredBox(
            key: ValueKey('comparison-manual-controls-$role'),
            color: Theme.of(context).brightness == Brightness.dark
                ? Colors.black
                : AppTheme.of(context).background,
            child: LayoutBuilder(
              builder: (context, bounds) {
                final controls = <Widget>[
                  IconButton(
                    key: ValueKey('comparison-manual-play-$role'),
                    tooltip: (video.value.isPlaying ? '暂停' : '播放') + label,
                    onPressed: session.ready && !saving && !session.anchoring
                        ? () => session.toggle(role)
                        : null,
                    icon: Icon(
                      video.value.isPlaying
                          ? Icons.pause_rounded
                          : Icons.play_arrow_rounded,
                      size: 22,
                    ),
                  ),
                  Expanded(
                    child: SliderTheme(
                      data: SliderTheme.of(context).copyWith(
                        trackHeight: 2,
                        thumbShape: const RoundSliderThumbShape(
                          enabledThumbRadius: 6,
                        ),
                        overlayShape: const RoundSliderOverlayShape(
                          overlayRadius: 20,
                        ),
                      ),
                      child: Semantics(
                        label: '$label源视频进度',
                        child: SizedBox(
                          height: 48,
                          child: Slider(
                            key: ValueKey('comparison-manual-seek-$role'),
                            padding: const EdgeInsets.symmetric(horizontal: 12),
                            min: 0,
                            max: math.max(1, duration - 1000).toDouble(),
                            value: value
                                .clamp(0, math.max(1, duration - 1000))
                                .toDouble(),
                            semanticFormatterCallback: (v) =>
                                comparisonTime(v.round()),
                            onChangeStart:
                                session.ready && !saving && !session.anchoring
                                ? (_) => unawaited(session.pause())
                                : null,
                            onChanged:
                                session.ready && !saving && !session.anchoring
                                ? (v) => session.scrub(role, v.round())
                                : null,
                            onChangeEnd:
                                session.ready && !saving && !session.anchoring
                                ? (v) => session.seek(role, v.round())
                                : null,
                          ),
                        ),
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '$label后退0.1秒',
                    onPressed: session.ready && !saving && !session.anchoring
                        ? () => session.seek(role, value - 100000)
                        : null,
                    icon: const Icon(Icons.chevron_left, size: 22),
                  ),
                  IconButton(
                    tooltip: '$label前进0.1秒',
                    onPressed: session.ready && !saving && !session.anchoring
                        ? () => session.seek(role, value + 100000)
                        : null,
                    icon: const Icon(Icons.chevron_right, size: 22),
                  ),
                ];
                if (bounds.maxWidth >= 280) return Row(children: controls);
                return Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(children: controls.take(2).toList()),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                      children: [controls[2], controls[3]],
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _badge(Widget child) => DecoratedBox(
    decoration: BoxDecoration(
      color: Colors.black54,
      borderRadius: BorderRadius.circular(6),
    ),
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: DefaultTextStyle.merge(
        style: const TextStyle(color: Colors.white),
        child: child,
      ),
    ),
  );
}
