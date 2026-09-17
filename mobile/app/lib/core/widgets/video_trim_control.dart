import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../app/theme.dart';

enum _TrimDragMode { start, end }

class VideoTrimControl extends StatefulWidget {
  final int durationMs;
  final int trimStartMs;
  final int trimEndMs;
  final List<String> thumbnailPaths;
  final ValueChanged<int> onStartChanged;
  final ValueChanged<int> onEndChanged;
  final VoidCallback onTrimChangeEnd;

  const VideoTrimControl({
    super.key,
    required this.durationMs,
    required this.trimStartMs,
    required this.trimEndMs,
    required this.thumbnailPaths,
    required this.onStartChanged,
    required this.onEndChanged,
    required this.onTrimChangeEnd,
  });

  @override
  State<VideoTrimControl> createState() => _VideoTrimControlState();
}

class _VideoTrimControlState extends State<VideoTrimControl> {
  _TrimDragMode? _dragMode;

  @override
  Widget build(BuildContext context) {
    final duration = math.max(widget.trimEndMs - widget.trimStartMs, 0);
    return Container(
      key: const ValueKey('integrated-video-trim-control'),
      padding: const EdgeInsets.fromLTRB(10, 10, 10, 9),
      decoration: BoxDecoration(
        color: AppTheme.flowSurfaceSoft.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppTheme.flowBorder.withValues(alpha: 0.72)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(height: 66, child: _buildTimeline()),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: _Metric(label: '开始', value: _format(widget.trimStartMs)),
              ),
              const _MetricDivider(),
              Expanded(
                child: _Metric(label: '结束', value: _format(widget.trimEndMs)),
              ),
              const _MetricDivider(),
              Expanded(
                child: _Metric(
                  label: '时长',
                  value: _format(duration),
                  emphasize: true,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildTimeline() {
    final durationMs = math.max(widget.durationMs, 1);
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        const trackInset = 8.0;
        final trackWidth = math.max(width - 2 * trackInset, 1.0);

        double xForMs(int ms) =>
            trackInset + trackWidth * (ms.clamp(0, durationMs) / durationMs);

        int msForX(double x) =>
            (((x - trackInset) / trackWidth).clamp(0.0, 1.0) * durationMs)
                .round();

        final startX = xForMs(widget.trimStartMs);
        final endX = xForMs(widget.trimEndMs);
        const trackTop = 6.0;
        const trackHeight = 52.0;
        const handleWidth = 14.0;

        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onHorizontalDragStart: (details) {
            final x = details.localPosition.dx;
            final startDistance = (x - startX).abs();
            final endDistance = (x - endX).abs();
            const hitRadius = 30.0;
            if (startDistance > hitRadius && endDistance > hitRadius) {
              _dragMode = null;
              return;
            }
            _dragMode = startDistance <= endDistance
                ? _TrimDragMode.start
                : _TrimDragMode.end;
          },
          onHorizontalDragUpdate: (details) {
            final value = msForX(details.localPosition.dx);
            switch (_dragMode) {
              case _TrimDragMode.start:
                widget.onStartChanged(value);
                break;
              case _TrimDragMode.end:
                widget.onEndChanged(value);
                break;
              case null:
                break;
            }
          },
          onHorizontalDragEnd: (_) {
            final changed = _dragMode != null;
            _dragMode = null;
            if (changed) widget.onTrimChangeEnd();
          },
          onHorizontalDragCancel: () => _dragMode = null,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned(
                left: trackInset,
                right: trackInset,
                top: trackTop,
                height: trackHeight,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: List.generate(10, (index) {
                      final path = index < widget.thumbnailPaths.length
                          ? widget.thumbnailPaths[index]
                          : null;
                      return Expanded(
                        child: path == null
                            ? ColoredBox(
                                color: index.isEven
                                    ? const Color(0xFFE8D7D0)
                                    : const Color(0xFFF2E4DE),
                              )
                            : Image.file(
                                File(path),
                                fit: BoxFit.cover,
                                gaplessPlayback: true,
                                filterQuality: FilterQuality.low,
                                errorBuilder: (context, error, stackTrace) =>
                                    ColoredBox(
                                      color: index.isEven
                                          ? const Color(0xFFE8D7D0)
                                          : const Color(0xFFF2E4DE),
                                    ),
                              ),
                      );
                    }),
                  ),
                ),
              ),
              if (startX > trackInset)
                Positioned(
                  left: trackInset,
                  width: startX - trackInset,
                  top: trackTop,
                  height: trackHeight,
                  child: const IgnorePointer(
                    child: ColoredBox(color: Color(0x75000000)),
                  ),
                ),
              if (endX < width - trackInset)
                Positioned(
                  left: endX,
                  right: trackInset,
                  top: trackTop,
                  height: trackHeight,
                  child: const IgnorePointer(
                    child: ColoredBox(color: Color(0x75000000)),
                  ),
                ),
              Positioned(
                left: startX,
                width: (endX - startX).clamp(0.0, trackWidth),
                top: trackTop - 1,
                height: trackHeight + 2,
                child: IgnorePointer(
                  child: Container(
                    decoration: BoxDecoration(
                      border: Border.all(color: AppTheme.coral, width: 2),
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                ),
              ),
              Positioned(
                left: startX - handleWidth / 2,
                top: trackTop - 1,
                child: const IgnorePointer(
                  child: _TrimHandle(
                    key: ValueKey('trim-start-handle'),
                    isLeft: true,
                  ),
                ),
              ),
              Positioned(
                left: endX - handleWidth / 2,
                top: trackTop - 1,
                child: const IgnorePointer(
                  child: _TrimHandle(
                    key: ValueKey('trim-end-handle'),
                    isLeft: false,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  String _format(int ms) {
    final totalSeconds = ms / 1000.0;
    final minutes = totalSeconds ~/ 60;
    final seconds = totalSeconds - minutes * 60;
    return '${minutes.toString().padLeft(2, '0')}:${seconds.toStringAsFixed(1).padLeft(4, '0')}';
  }
}

class _TrimHandle extends StatelessWidget {
  final bool isLeft;

  const _TrimHandle({super.key, required this.isLeft});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 14,
      height: 54,
      decoration: BoxDecoration(
        color: AppTheme.flowSurface,
        borderRadius: BorderRadius.horizontal(
          left: Radius.circular(isLeft ? 8 : 3),
          right: Radius.circular(isLeft ? 3 : 8),
        ),
        border: Border.all(color: AppTheme.coral, width: 2),
        boxShadow: const [
          BoxShadow(
            color: Color(0x22000000),
            blurRadius: 4,
            offset: Offset(0, 2),
          ),
        ],
      ),
      alignment: Alignment.center,
      child: Container(
        width: 2.5,
        height: 16,
        decoration: BoxDecoration(
          color: AppTheme.coral,
          borderRadius: BorderRadius.circular(1.5),
        ),
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  final String label;
  final String value;
  final bool emphasize;

  const _Metric({
    required this.label,
    required this.value,
    this.emphasize = false,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          label,
          style: const TextStyle(
            color: AppTheme.flowTextSecondary,
            fontSize: 10.5,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: TextStyle(
            color: emphasize ? AppTheme.coralStrong : AppTheme.flowTextPrimary,
            fontSize: 13,
            fontWeight: FontWeight.w700,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
      ],
    );
  }
}

class _MetricDivider extends StatelessWidget {
  const _MetricDivider();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 1,
      height: 28,
      margin: const EdgeInsets.symmetric(horizontal: 5),
      color: AppTheme.flowBorder,
    );
  }
}
