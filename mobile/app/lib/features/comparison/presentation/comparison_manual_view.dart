import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import '../../../app/theme.dart';
import 'comparison_manual_session.dart';
import 'comparison_time.dart';

class ComparisonManualView extends StatelessWidget {
  const ComparisonManualView({
    super.key,
    required this.session,
    required this.onApply,
    required this.onCancel,
    this.saving = false,
    this.error,
  });
  final ComparisonManualSession session;
  final VoidCallback onApply, onCancel;
  final bool saving;
  final String? error;
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: session,
    builder: (context, _) => LayoutBuilder(
      builder: (context, bounds) {
        final panes = [
          for (var i = 0; i < 2; i++) Expanded(child: _pane(context, i)),
        ];
        return Column(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Text(
                error ?? session.error ?? '分别播放或拖动到同一歌词、鼓点，再完成校准。',
                style: TextStyle(
                  fontSize: 12,
                  color: error != null || session.error != null
                      ? AppTheme.error
                      : AppTheme.flowTextSecondary,
                ),
              ),
            ),
            Expanded(
              child: bounds.maxWidth > bounds.maxHeight
                  ? Row(children: panes)
                  : Column(children: panes),
            ),
            Container(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
              decoration: const BoxDecoration(
                color: AppTheme.flowSurface,
                border: Border(top: BorderSide(color: AppTheme.flowBorder)),
              ),
              child: Row(
                children: [
                  OutlinedButton(
                    onPressed: saving ? null : onCancel,
                    child: const Text('取消'),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton.icon(
                      key: const ValueKey('comparison-manual-apply'),
                      onPressed:
                          session.ready && session.error == null && !saving
                          ? onApply
                          : null,
                      style: FilledButton.styleFrom(
                        backgroundColor: AppTheme.gold,
                        foregroundColor: AppTheme.textOnAccent,
                        minimumSize: const Size(48, 48),
                      ),
                      icon: const Icon(Icons.check, size: 20),
                      label: Text(saving ? '正在保存…' : '完成校准'),
                    ),
                  ),
                ],
              ),
            ),
          ],
        );
      },
    ),
  );
  Widget _pane(BuildContext context, int role) {
    final video = session.videos[role];
    final label = role == 0 ? '老师' : '学生';
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
                  Center(
                    child: AspectRatio(
                      aspectRatio: video.value.aspectRatio,
                      child: VideoPlayer(video),
                    ),
                  )
                else
                  const Center(
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                Positioned(
                  top: 6,
                  left: 10,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Colors.black54,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      child: Text(label, style: const TextStyle(fontSize: 12)),
                    ),
                  ),
                ),
              ],
            ),
          ),
          ColoredBox(
            color: AppTheme.flowSurface,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    IconButton(
                      key: ValueKey('comparison-manual-play-$role'),
                      tooltip: (video.value.isPlaying ? '暂停' : '播放') + label,
                      onPressed: session.ready && !saving
                          ? () => session.toggle(role)
                          : null,
                      icon: Icon(
                        video.value.isPlaying
                            ? Icons.pause_rounded
                            : Icons.play_arrow_rounded,
                      ),
                    ),
                    Expanded(
                      child: Text(
                        '${comparisonTime(value)} / ${comparisonTime(duration)}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 12,
                          fontFeatures: [FontFeature.tabularFigures()],
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: '$label后退0.1秒',
                      onPressed: session.ready && !saving
                          ? () => session.seek(role, value - 100000)
                          : null,
                      icon: const Icon(Icons.chevron_left, size: 22),
                    ),
                    IconButton(
                      tooltip: '$label前进0.1秒',
                      onPressed: session.ready && !saving
                          ? () => session.seek(role, value + 100000)
                          : null,
                      icon: const Icon(Icons.chevron_right, size: 22),
                    ),
                  ],
                ),
                Semantics(
                  label: '$label源视频进度',
                  child: Slider(
                    key: ValueKey('comparison-manual-seek-$role'),
                    min: 0,
                    max: math.max(1, duration - 1000).toDouble(),
                    value: value
                        .clamp(0, math.max(1, duration - 1000))
                        .toDouble(),
                    semanticFormatterCallback: (v) => comparisonTime(v.round()),
                    onChangeStart: session.ready && !saving
                        ? (_) => unawaited(session.pause())
                        : null,
                    onChanged: session.ready && !saving
                        ? (v) => session.scrub(role, v.round())
                        : null,
                    onChangeEnd: session.ready && !saving
                        ? (v) => session.seek(role, v.round())
                        : null,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
