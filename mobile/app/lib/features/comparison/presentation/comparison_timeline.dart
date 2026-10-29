import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../app/theme.dart';
import 'comparison_time.dart';

/// Editing-style A/B range and playhead share the same track center.
class ComparisonTimeline extends StatefulWidget {
  const ComparisonTimeline({
    super.key,
    required this.durationUs,
    required this.positionUs,
    required this.onSeek,
    required this.onRange,
    required this.onRangeStart,
    this.onScrub,
    this.startUs,
    this.endUs,
    this.loopEnabled = false,
    this.enabled = true,
  });
  final int durationUs, positionUs;
  final int? startUs, endUs;
  final bool enabled, loopEnabled;
  final ValueChanged<int> onSeek;
  final ValueChanged<int?>? onScrub;
  final Future<void> Function() onRangeStart;
  final Future<bool> Function(int, int) onRange;
  @override
  State<ComparisonTimeline> createState() => _ComparisonTimelineState();
}

class _ComparisonTimelineState extends State<ComparisonTimeline> {
  static const inset = 24.0;
  RangeValues? _draft, _initial;
  double? _scrub, _downX;
  bool? _startHandle, _focusedHandle;
  bool _committing = false, _scrubbing = false;
  Future<void>? _pause;
  bool get _enabled => widget.enabled && widget.durationUs > 0 && !_committing;
  int get _gap => math.min(100000, math.max(1, widget.durationUs));
  RangeValues _bounded(bool start, double value, RangeValues values) {
    if (widget.durationUs <= 0) return const RangeValues(0, 0);
    if (start) {
      final end = values.end
          .clamp(_gap.toDouble(), widget.durationUs.toDouble())
          .toDouble();
      return RangeValues(value.clamp(0, end - _gap).toDouble(), end);
    }
    final a = values.start
        .clamp(0, (widget.durationUs - _gap).toDouble())
        .toDouble();
    return RangeValues(
      a,
      value.clamp(a + _gap, widget.durationUs.toDouble()).toDouble(),
    );
  }

  RangeValues get _values {
    if (_draft != null) return _draft!;
    if (widget.durationUs <= 0) return const RangeValues(0, 0);
    final end = (widget.endUs ?? widget.durationUs).clamp(1, widget.durationUs);
    final start = (widget.startUs ?? 0).clamp(0, end - 1);
    return RangeValues(start.toDouble(), end.toDouble());
  }

  double _x(double time, double width) =>
      inset + time / math.max(1, widget.durationUs) * (width - inset * 2);
  double _time(double x, double width) =>
      ((x - inset) / math.max(1, width - inset * 2)).clamp(0.0, 1.0) *
      widget.durationUs;
  (double, double) _targets(RangeValues values, double width) {
    var a = _x(values.start, width), b = _x(values.end, width);
    if (b - a < 32) {
      final center = ((a + b) / 2).clamp(40.0, width - 40);
      a = center - 16;
      b = center + 16;
    }
    return (a, b);
  }

  void _previewSeek(double value) {
    setState(() => _scrub = value);
    widget.onScrub?.call(value.round());
  }

  void _seek(double value) {
    setState(() {
      _scrub = null;
      _scrubbing = false;
      _downX = null;
    });
    widget.onScrub?.call(null);
    widget.onSeek(value.round());
  }

  void _dragStart(DragStartDetails details, double width) {
    if (!_enabled) return;
    final values = _values, targets = _targets(_values, width);
    final x = _downX ?? details.localPosition.dx;
    final a = (x - _x(values.start, width)).abs(),
        b = (x - _x(values.end, width)).abs();
    final ta = (x - targets.$1).abs(), tb = (x - targets.$2).abs();
    if (math.min(a, b) > 24 && math.min(ta, tb) > 24) {
      _scrubbing = true;
      _previewSeek(_time(details.localPosition.dx, width));
      return;
    }
    _startHandle = math.min(a, b) <= 24 ? a <= b : ta <= tb;
    _initial = values;
    _downX = x;
    _scrubbing = false;
    _scrub = null;
    widget.onScrub?.call(null);
    _pause = widget.onRangeStart();
    setState(() => _draft = values);
  }

  void _dragUpdate(DragUpdateDetails details, double width) {
    if (_scrubbing) {
      _previewSeek(_time(details.localPosition.dx, width));
      return;
    }
    if (_initial == null || _startHandle == null || _downX == null) return;
    final delta =
        (details.localPosition.dx - _downX!) /
        (width - inset * 2) *
        widget.durationUs;
    final initial = _initial!;
    setState(
      () => _draft = _bounded(
        _startHandle!,
        ((_startHandle! ? initial.start : initial.end) + delta).roundToDouble(),
        initial,
      ),
    );
  }

  void _dragEnd() {
    if (_scrubbing) {
      _seek(_scrub ?? widget.positionUs.toDouble());
      return;
    }
    final draft = _draft;
    if (draft != null) unawaited(_commit(draft));
  }

  Future<void> _commit(RangeValues values) async {
    if (_committing) return;
    setState(() => _committing = true);
    try {
      await _pause;
      if (mounted) {
        await widget.onRange(values.start.round(), values.end.round());
      }
    } finally {
      if (mounted) {
        setState(() {
          _draft = _initial = null;
          _startHandle = null;
          _downX = null;
          _committing = false;
        });
      }
    }
  }

  void _nudge(bool start, bool forward) {
    if (!_enabled) return;
    final values = _values;
    final delta = math.min(100000, widget.durationUs) * (forward ? 1 : -1);
    final next = _bounded(
      start,
      (start ? values.start : values.end) + delta,
      values,
    );
    if (next == values) return;
    _pause = widget.onRangeStart();
    setState(() {
      _draft = next;
      _startHandle = start;
    });
    unawaited(_commit(next));
  }

  void _cancel() {
    if (_committing) return;
    setState(() {
      _draft = _initial = null;
      _startHandle = null;
      _downX = null;
      _scrub = null;
      _scrubbing = false;
    });
    widget.onScrub?.call(null);
  }

  bool _active(bool start) =>
      _focusedHandle == start || (_draft != null && _startHandle == start);
  Widget _handleSemantics(bool start, double center, int value) {
    final label = start ? 'A' : 'B', values = _values;
    final step = math.min(100000, widget.durationUs);
    final plus = _bounded(start, value + step.toDouble(), values);
    final minus = _bounded(start, value - step.toDouble(), values);
    return Positioned(
      left: center - 24,
      top: 0,
      width: 48,
      height: 48,
      child: Semantics(
        key: ValueKey('comparison-loop-$label'),
        label: '$label ${start ? '起点' : '终点'}',
        value: comparisonPreciseTime(value),
        increasedValue: comparisonPreciseTime(
          (start ? plus.start : plus.end).round(),
        ),
        decreasedValue: comparisonPreciseTime(
          (start ? minus.start : minus.end).round(),
        ),
        slider: true,
        enabled: _enabled,
        onIncrease: _enabled && plus != values
            ? () => _nudge(start, true)
            : null,
        onDecrease: _enabled && minus != values
            ? () => _nudge(start, false)
            : null,
        child: CallbackShortcuts(
          bindings: {
            const SingleActivator(LogicalKeyboardKey.arrowLeft): () =>
                _nudge(start, false),
            const SingleActivator(LogicalKeyboardKey.arrowRight): () =>
                _nudge(start, true),
          },
          child: Focus(
            canRequestFocus:
                widget.durationUs > 0 && (widget.enabled || _committing),
            onFocusChange: (focused) {
              if (mounted) {
                setState(
                  () => _focusedHandle = focused
                      ? start
                      : _focusedHandle == start
                      ? null
                      : _focusedHandle,
                );
              }
            },
            child: Tooltip(
              message: '拖动 $label · ${comparisonPreciseTime(value)}',
              excludeFromSemantics: true,
              child: const ExcludeSemantics(child: SizedBox.expand()),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, bounds) {
      final values = _values, targets = _targets(_values, bounds.maxWidth);
      final a = _x(values.start, bounds.maxWidth),
          b = _x(values.end, bounds.maxWidth);
      final position = (_scrub ?? widget.positionUs.toDouble())
          .clamp(0, math.max(1, widget.durationUs).toDouble())
          .toDouble();
      final hasRange =
          widget.startUs != null || widget.endUs != null || _draft != null;
      final rangeColor = !_enabled ? AppTheme.flowTextMuted : AppTheme.gold;
      final editingRange = _draft != null && _startHandle != null;
      final clock =
          (editingRange
                  ? (_startHandle! ? values.start : values.end)
                  : position)
              .round();
      final clockText =
          '${editingRange ? '${_startHandle! ? 'A' : 'B'} ' : ''}${comparisonTime(clock)}';
      final clockHeight = math.max(
        18.0,
        MediaQuery.textScalerOf(context).scale(12) * 1.45,
      );
      return SizedBox(
        key: const ValueKey('comparison-timeline'),
        height: 48 + clockHeight,
        child: Column(
          children: [
            SizedBox(
              height: clockHeight,
              child: Semantics(
                label: editingRange
                    ? (_startHandle! ? 'A 起点' : 'B 终点')
                    : '当前播放位置',
                value: comparisonPreciseTime(clock),
                excludeSemantics: true,
                child: Center(
                  child: Text(
                    clockText,
                    key: const ValueKey('comparison-time-readout'),
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppTheme.flowTextSecondary,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ),
              ),
            ),
            SizedBox(
              height: 48,
              child: Listener(
                onPointerCancel: (_) => _cancel(),
                child: GestureDetector(
                  key: const ValueKey('comparison-loop-handles'),
                  behavior: HitTestBehavior.opaque,
                  excludeFromSemantics: true,
                  onTapUp: _enabled
                      ? (details) => _seek(
                          _time(details.localPosition.dx, bounds.maxWidth),
                        )
                      : null,
                  onHorizontalDragDown: _enabled
                      ? (details) => _downX = details.localPosition.dx
                      : null,
                  onHorizontalDragStart: _enabled
                      ? (details) => _dragStart(details, bounds.maxWidth)
                      : null,
                  onHorizontalDragUpdate: _enabled
                      ? (details) => _dragUpdate(details, bounds.maxWidth)
                      : null,
                  onHorizontalDragEnd: _enabled ? (_) => _dragEnd() : null,
                  onHorizontalDragCancel: _cancel,
                  child: Stack(
                    clipBehavior: Clip.none,
                    children: [
                      Positioned(
                        left: inset,
                        right: inset,
                        top: 21,
                        height: 6,
                        child: IgnorePointer(
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: AppTheme.sliderTrackInactive,
                              borderRadius: BorderRadius.circular(3),
                            ),
                          ),
                        ),
                      ),
                      if (hasRange)
                        Positioned(
                          left: a,
                          width: math.max(0, b - a),
                          top: 17,
                          height: 14,
                          child: IgnorePointer(
                            child: DecoratedBox(
                              decoration: BoxDecoration(
                                color: rangeColor.withAlpha(25),
                                border: Border.all(
                                  color: rangeColor,
                                  width: 1.2,
                                ),
                                borderRadius: BorderRadius.circular(4),
                              ),
                            ),
                          ),
                        ),
                      for (final start in [true, false])
                        Positioned(
                          left: (start ? a : b) - 5,
                          top: 17,
                          width: 10,
                          height: 14,
                          child: IgnorePointer(
                            child: _ClipHandle(
                              key: ValueKey(
                                'comparison-loop-grip-${start ? 'A' : 'B'}',
                              ),
                              start: start,
                              color: !_enabled
                                  ? AppTheme.flowTextMuted
                                  : _active(start)
                                  ? AppTheme.goldLight
                                  : hasRange
                                  ? AppTheme.gold
                                  : AppTheme.flowTextSecondary,
                              focused: _focusedHandle == start,
                            ),
                          ),
                        ),
                      IgnorePointer(
                        child: SliderTheme(
                          data: SliderTheme.of(context).copyWith(
                            trackHeight: 6,
                            trackShape: const _SemanticsTrack(),
                            thumbShape: SliderComponentShape.noThumb,
                            overlayShape: const RoundSliderOverlayShape(
                              overlayRadius: 20,
                            ),
                          ),
                          child: Semantics(
                            label: '共同进度',
                            child: Slider(
                              key: const ValueKey('comparison-seek'),
                              min: 0,
                              max: math.max(1, widget.durationUs).toDouble(),
                              value: position,
                              semanticFormatterCallback: (value) =>
                                  '${comparisonPreciseTime(value.round())}，共 ${comparisonPreciseTime(widget.durationUs)}',
                              onChanged: _enabled
                                  ? (value) {
                                      if (_initial == null) _previewSeek(value);
                                    }
                                  : null,
                              onChangeEnd: _enabled
                                  ? (value) {
                                      if (_initial == null) _seek(value);
                                    }
                                  : null,
                            ),
                          ),
                        ),
                      ),
                      Positioned(
                        left: _x(position, bounds.maxWidth) - 1,
                        top: 15,
                        width: 2,
                        height: 18,
                        child: IgnorePointer(
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: _enabled
                                  ? AppTheme.flowTextPrimary
                                  : AppTheme.flowTextMuted,
                              borderRadius: BorderRadius.circular(1),
                            ),
                          ),
                        ),
                      ),
                      _handleSemantics(true, targets.$1, values.start.round()),
                      _handleSemantics(false, targets.$2, values.end.round()),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    },
  );
}

// The material slider supplies keyboard and accessibility actions; the shared
// rail above supplies the editor's range frame, grips and playback needle.
class _SemanticsTrack extends RoundedRectSliderTrackShape {
  const _SemanticsTrack();
  @override
  Rect getPreferredRect({
    required RenderBox parentBox,
    Offset offset = Offset.zero,
    required SliderThemeData sliderTheme,
    bool isEnabled = false,
    bool isDiscrete = false,
  }) => Rect.fromLTWH(
    offset.dx + 24,
    offset.dy + (parentBox.size.height - 6) / 2,
    math.max(0, parentBox.size.width - 48),
    6,
  );
  @override
  void paint(
    PaintingContext context,
    Offset offset, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required TextDirection textDirection,
    required Offset thumbCenter,
    Offset? secondaryOffset,
    bool isDiscrete = false,
    bool isEnabled = false,
    double additionalActiveTrackHeight = 2,
  }) {}
}

/// Matches the existing protection editor's integrated trim grips.
class _ClipHandle extends StatelessWidget {
  const _ClipHandle({
    super.key,
    required this.start,
    required this.color,
    required this.focused,
  });
  final bool start, focused;
  final Color color;
  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: color,
      borderRadius: BorderRadius.horizontal(
        left: start ? const Radius.circular(3) : Radius.zero,
        right: start ? Radius.zero : const Radius.circular(3),
      ),
      border: focused ? Border.all(color: AppTheme.flowTextPrimary) : null,
    ),
    child: Center(
      child: Container(
        width: 1.5,
        height: 8,
        decoration: BoxDecoration(
          color: Colors.black87,
          borderRadius: BorderRadius.circular(1),
        ),
      ),
    ),
  );
}
