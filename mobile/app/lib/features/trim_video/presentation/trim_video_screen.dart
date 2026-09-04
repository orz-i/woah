import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:video_player/video_player.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/main_flow_header.dart';
import '../../../repositories/native_processing_repository.dart';

class TrimVideoScreen extends ConsumerStatefulWidget {
  final DanceProject project;

  const TrimVideoScreen({super.key, required this.project});

  @override
  ConsumerState<TrimVideoScreen> createState() => _TrimVideoScreenState();
}

class _TrimVideoScreenState extends ConsumerState<TrimVideoScreen> {
  static const int _minimumClipMs = 1000;
  static const int _thumbnailCount = 10;

  VideoPlayerController? _videoController;
  List<String> _thumbnailPaths = const [];
  int _trimStartMs = 0;
  int _trimEndMs = 0;
  int _playheadMs = 0;
  bool _initializing = true;
  String? _errorMessage;
  bool _seekingFromListener = false;

  int get _durationMs => math.max(widget.project.videoInfo.durationMs, 1);

  @override
  void initState() {
    super.initState();
    _trimStartMs = widget.project.trimStartMs.clamp(0, _durationMs);
    _trimEndMs = widget.project.effectiveTrimEndMs.clamp(
      _trimStartMs,
      _durationMs,
    );
    if (_trimEndMs - _trimStartMs < _minimumClipMs &&
        _durationMs >= _minimumClipMs) {
      _trimEndMs = (_trimStartMs + _minimumClipMs).clamp(0, _durationMs);
      if (_trimEndMs - _trimStartMs < _minimumClipMs) {
        _trimStartMs = (_trimEndMs - _minimumClipMs).clamp(0, _durationMs);
      }
    }
    _playheadMs = _trimStartMs;
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final source = widget.project.sourceUri;
      final controller = source.startsWith('content://')
          ? VideoPlayerController.contentUri(Uri.parse(source))
          : VideoPlayerController.file(
              File(source.startsWith('file://') ? source.substring(7) : source),
            );
      await controller.initialize();
      controller.addListener(_onVideoTick);
      await controller.seekTo(Duration(milliseconds: _trimStartMs));

      final timestamps = List<int>.generate(_thumbnailCount, (index) {
        if (_thumbnailCount == 1) return 0;
        return ((_durationMs * index) / (_thumbnailCount - 1)).round();
      });
      final thumbnails = await ref
          .read(nativeRepositoryProvider)
          .getVideoFrameThumbnails(
            videoUri: widget.project.sourceUri,
            timestampsMs: timestamps,
          );

      if (!mounted) {
        await controller.dispose();
        return;
      }
      setState(() {
        _videoController = controller;
        _thumbnailPaths = thumbnails;
        _initializing = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _initializing = false;
        _errorMessage = '暂时无法预览这个舞段';
      });
    }
  }

  void _onVideoTick() {
    final controller = _videoController;
    if (controller == null || !controller.value.isInitialized || !mounted) {
      return;
    }
    final positionMs = controller.value.position.inMilliseconds.clamp(
      0,
      _durationMs,
    );
    if (controller.value.isPlaying && positionMs >= _trimEndMs) {
      if (_seekingFromListener) return;
      _seekingFromListener = true;
      controller
          .pause()
          .then((_) {
            return controller.seekTo(Duration(milliseconds: _trimStartMs));
          })
          .whenComplete(() {
            _seekingFromListener = false;
            if (mounted) setState(() => _playheadMs = _trimStartMs);
          });
      return;
    }
    if ((positionMs - _playheadMs).abs() >= 40) {
      setState(() => _playheadMs = positionMs);
    }
  }

  @override
  void dispose() {
    final controller = _videoController;
    if (controller != null) {
      controller.removeListener(_onVideoTick);
      unawaited(controller.dispose());
    }
    super.dispose();
  }

  Future<void> _togglePlayback() async {
    final controller = _videoController;
    if (controller == null || !controller.value.isInitialized) return;
    HapticFeedback.selectionClick();
    if (controller.value.isPlaying) {
      await controller.pause();
      return;
    }
    final current = controller.value.position.inMilliseconds;
    if (current < _trimStartMs || current >= _trimEndMs) {
      await controller.seekTo(Duration(milliseconds: _trimStartMs));
    }
    await controller.play();
  }

  Future<void> _seekTo(int valueMs) async {
    final value = valueMs.clamp(_trimStartMs, _trimEndMs);
    setState(() => _playheadMs = value);
    await _videoController?.seekTo(Duration(milliseconds: value));
  }

  Future<void> _setTrimStart(int valueMs) async {
    final maxStart = (_trimEndMs - _minimumClipMs).clamp(0, _durationMs);
    final value = valueMs.clamp(0, maxStart);
    setState(() {
      _trimStartMs = value;
      if (_playheadMs < value) _playheadMs = value;
    });
    if ((_videoController?.value.position.inMilliseconds ?? 0) < value) {
      await _seekTo(value);
    }
  }

  Future<void> _setTrimEnd(int valueMs) async {
    final minEnd = (_trimStartMs + _minimumClipMs).clamp(0, _durationMs);
    final value = valueMs.clamp(minEnd, _durationMs);
    setState(() {
      _trimEndMs = value;
      if (_playheadMs > value) _playheadMs = value;
    });
    if ((_videoController?.value.position.inMilliseconds ?? 0) > value) {
      await _seekTo(value);
    }
  }

  Future<void> _continue() async {
    HapticFeedback.mediumImpact();
    final controller = _videoController;
    if (controller?.value.isPlaying == true) await controller?.pause();
    if (!mounted) return;
    final trimmedProject = widget.project.copyWith(
      trimStartMs: _trimStartMs,
      trimEndMs: _trimEndMs,
      updatedAt: DateTime.now(),
    );
    await context.push('/person_selection', extra: trimmedProject);
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
        statusBarBrightness: Brightness.light,
        systemNavigationBarColor: AppTheme.warmBackground,
        systemNavigationBarIconBrightness: Brightness.dark,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.warmBackground,
        body: SafeArea(
          child: Column(
            children: [
              _buildHeader(),
              Expanded(
                child: SingleChildScrollView(
                  physics: const BouncingScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(18, 2, 18, 18),
                  child: Column(
                    children: [
                      _buildPreview(),
                      const SizedBox(height: 16),
                      _buildTimeline(),
                      const SizedBox(height: 14),
                      _buildTimeSummary(),
                      const SizedBox(height: 22),
                      _buildContinueButton(),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return MainFlowHeader(
      title: '预览并裁剪视频',
      closeTooltip: '返回',
      onClose: () {
        HapticFeedback.lightImpact();
        context.pop();
      },
    );
  }

  Widget _buildPreview() {
    final controller = _videoController;
    final aspect = widget.project.videoInfo.aspectRatio > 0
        ? widget.project.videoInfo.aspectRatio
        : 16 / 9;
    return ClipRRect(
      borderRadius: BorderRadius.circular(26),
      child: AspectRatio(
        aspectRatio: aspect,
        child: Stack(
          fit: StackFit.expand,
          children: [
            const ColoredBox(color: Color(0xFFF0E6E0)),
            if (controller != null && controller.value.isInitialized)
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: _togglePlayback,
                child: FittedBox(
                  fit: BoxFit.contain,
                  child: SizedBox(
                    width: controller.value.size.width,
                    height: controller.value.size.height,
                    child: VideoPlayer(controller),
                  ),
                ),
              )
            else if (_initializing)
              const Center(
                child: CircularProgressIndicator(
                  strokeWidth: 2.2,
                  color: AppTheme.coral,
                ),
              )
            else
              Center(
                child: Text(
                  _errorMessage ?? '无法预览视频',
                  style: const TextStyle(
                    color: AppTheme.warmTextSecondary,
                    fontSize: 13,
                  ),
                ),
              ),
            // 底部控制条：背景透明，仅纯文字与纯图标，大小水平位置绝对对齐
            Positioned(
              left: 14,
              right: 14,
              bottom: 12,
              child: SizedBox(
                height: 36,
                child: Stack(
                  children: [
                    // 左侧：时间显示（背景透明，纯文字 + 阴影）
                    Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        '${_formatTime(_playheadMs)} / ${_formatTime(_durationMs)}',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0.3,
                          shadows: [
                            Shadow(
                              color: Color(0xB3000000),
                              blurRadius: 4,
                              offset: Offset(0, 1),
                            ),
                          ],
                        ),
                      ),
                    ),
                    // 中间：播放/暂停按钮（背景透明，仅图标，绝对水平居中）
                    if (controller != null && controller.value.isInitialized)
                      Align(
                        alignment: Alignment.center,
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: _togglePlayback,
                          child: SizedBox(
                            width: 36,
                            height: 36,
                            child: Center(
                              child: Icon(
                                controller.value.isPlaying
                                    ? Icons.pause_rounded
                                    : Icons.play_arrow_rounded,
                                color: Colors.white,
                                size: 28,
                                shadows: const [
                                  Shadow(
                                    color: Color(0xB3000000),
                                    blurRadius: 4,
                                    offset: Offset(0, 1),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    // 右侧：从起点重新播放按钮（背景透明，仅图标）
                    if (controller != null && controller.value.isInitialized)
                      Align(
                        alignment: Alignment.centerRight,
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: () {
                            HapticFeedback.selectionClick();
                            _seekTo(_trimStartMs);
                          },
                          child: const SizedBox(
                            width: 36,
                            height: 36,
                            child: Center(
                              child: Icon(
                                Icons.replay_rounded,
                                color: Colors.white,
                                size: 24,
                                shadows: [
                                  Shadow(
                                    color: Color(0xB3000000),
                                    blurRadius: 4,
                                    offset: Offset(0, 1),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTimeline() {
    return Column(
      children: [
        SizedBox(
          height: 94,
          child: _TrimTimeline(
            durationMs: _durationMs,
            trimStartMs: _trimStartMs,
            trimEndMs: _trimEndMs,
            playheadMs: _playheadMs,
            thumbnailPaths: _thumbnailPaths,
            onStartChanged: _setTrimStart,
            onEndChanged: _setTrimEnd,
            onPlayheadChanged: _seekTo,
          ),
        ),
        const SizedBox(height: 8),
        _TimelineRuler(durationMs: _durationMs),
      ],
    );
  }

  Widget _buildTimeSummary() {
    final duration = math.max(_trimEndMs - _trimStartMs, 0);
    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: _TimeCard(
                label: '开始时间',
                value: _formatPrecise(_trimStartMs),
              ),
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 14),
              child: Icon(
                Icons.arrow_forward_rounded,
                color: AppTheme.coralSoft,
                size: 24,
              ),
            ),
            Expanded(
              child: _TimeCard(
                label: '结束时间',
                value: _formatPrecise(_trimEndMs),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Text.rich(
          TextSpan(
            text: '片段时长 ',
            style: const TextStyle(
              color: AppTheme.warmTextSecondary,
              fontSize: 13,
              fontWeight: FontWeight.w500,
            ),
            children: [
              TextSpan(
                text: _formatPrecise(duration),
                style: const TextStyle(
                  color: AppTheme.warmTextPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildContinueButton() {
    return SizedBox(
      width: double.infinity,
      height: 60,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          onTap: _continue,
          borderRadius: BorderRadius.circular(18),
          child: Ink(
            decoration: BoxDecoration(
              gradient: AppTheme.coralActionGradient,
              borderRadius: BorderRadius.circular(18),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x20F44848),
                  blurRadius: 14,
                  offset: Offset(0, 6),
                ),
              ],
            ),
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 22),
              child: Row(
                children: [
                  SizedBox(width: 30),
                  Expanded(
                    child: Text(
                      '下一步',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  Icon(
                    Icons.arrow_forward_rounded,
                    color: Colors.white,
                    size: 28,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _formatTime(int ms) {
    final seconds = (ms / 1000).floor();
    final minutes = seconds ~/ 60;
    final remainder = seconds % 60;
    return '${minutes.toString().padLeft(2, '0')}:${remainder.toString().padLeft(2, '0')}';
  }

  String _formatPrecise(int ms) {
    final seconds = ms / 1000.0;
    final minutes = seconds ~/ 60;
    final remainder = seconds - minutes * 60;
    if (minutes == 0) {
      return '00:${remainder.toStringAsFixed(1).padLeft(4, '0')}';
    }
    return '${minutes.toString().padLeft(2, '0')}:${remainder.toStringAsFixed(1).padLeft(4, '0')}';
  }
}

class _TimelineRuler extends StatelessWidget {
  final int durationMs;

  const _TimelineRuler({required this.durationMs});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        const trackInset = 16.0;
        final trackWidth = math.max(width - 2 * trackInset, 1.0);

        final points = [
          (0.0, '00:00'),
          (trackWidth / 3, _format(durationMs ~/ 3)),
          (trackWidth * 2 / 3, _format(durationMs * 2 ~/ 3)),
          (trackWidth, _formatPrecise(durationMs)),
        ];

        return SizedBox(
          height: 18,
          child: Stack(
            clipBehavior: Clip.none,
            children: points.map((pt) {
              final x = trackInset + pt.$1;
              final isFirst = pt.$1 == 0.0;
              final isLast = pt.$1 == trackWidth;
              return Positioned(
                left: isFirst
                    ? x - 4
                    : isLast
                        ? null
                        : x - 24,
                right: isLast ? (width - x) - 4 : null,
                width: (!isFirst && !isLast) ? 48 : null,
                top: 0,
                child: Text(
                  pt.$2,
                  textAlign: isFirst
                      ? TextAlign.left
                      : isLast
                          ? TextAlign.right
                          : TextAlign.center,
                  style: const TextStyle(
                    color: AppTheme.warmTextSecondary,
                    fontSize: 11,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              );
            }).toList(),
          ),
        );
      },
    );
  }

  String _format(int ms) {
    final total = ms ~/ 1000;
    return '${(total ~/ 60).toString().padLeft(2, '0')}:${(total % 60).toString().padLeft(2, '0')}';
  }

  String _formatPrecise(int ms) {
    final totalSec = ms / 1000.0;
    final min = totalSec ~/ 60;
    final sec = totalSec - min * 60;
    final frac = ((ms % 1000) / 100).round();
    if (frac == 0) {
      return _format(ms);
    }
    return '${min.toString().padLeft(2, '0')}:${sec.toStringAsFixed(1).padLeft(4, '0')}';
  }
}

enum _TrimDragMode { start, end, playhead }

class _TrimTimeline extends StatefulWidget {
  final int durationMs;
  final int trimStartMs;
  final int trimEndMs;
  final int playheadMs;
  final List<String> thumbnailPaths;
  final ValueChanged<int> onStartChanged;
  final ValueChanged<int> onEndChanged;
  final ValueChanged<int> onPlayheadChanged;

  const _TrimTimeline({
    required this.durationMs,
    required this.trimStartMs,
    required this.trimEndMs,
    required this.playheadMs,
    required this.thumbnailPaths,
    required this.onStartChanged,
    required this.onEndChanged,
    required this.onPlayheadChanged,
  });

  @override
  State<_TrimTimeline> createState() => _TrimTimelineState();
}

class _TrimTimelineState extends State<_TrimTimeline> {
  _TrimDragMode? _dragMode;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        const trackInset = 16.0;
        final trackWidth = math.max(width - 2 * trackInset, 1.0);

        double xForMs(int ms) =>
            trackInset +
            trackWidth *
                (ms.clamp(0, widget.durationMs) / widget.durationMs);

        int msForX(double x) =>
            (((x - trackInset) / trackWidth).clamp(0.0, 1.0) *
                    widget.durationMs)
                .round();

        final startX = xForMs(widget.trimStartMs);
        final endX = xForMs(widget.trimEndMs);
        final clampedPlayheadMs = widget.playheadMs.clamp(
          widget.trimStartMs,
          widget.trimEndMs,
        );
        final playX = xForMs(clampedPlayheadMs);

        const trackTop = 26.0;
        const trackHeight = 60.0;
        const trackBottom = trackTop + trackHeight;
        const handleWidth = 14.0;
        const bubbleWidth = 50.0;
        const bubbleHeight = 22.0;

        // 气泡绝对居中锚定 playX（因为预留了 trackInset=16，外层还有 18px padding，气泡居中绝不会超出屏幕）
        final bubbleLeft = playX - bubbleWidth / 2;

        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapUp: (details) {
            final x = details.localPosition.dx;
            final y = details.localPosition.dy;
            if (y <= trackTop + 6) {
              widget.onPlayheadChanged(
                msForX(x).clamp(widget.trimStartMs, widget.trimEndMs),
              );
              return;
            }
            const edgeGuard = 16.0;
            if ((x - startX).abs() <= edgeGuard ||
                (x - endX).abs() <= edgeGuard) {
              return;
            }
            widget.onPlayheadChanged(
              msForX(x).clamp(widget.trimStartMs, widget.trimEndMs),
            );
          },
          onHorizontalDragStart: (details) {
            final x = details.localPosition.dx;
            final y = details.localPosition.dy;

            // 1. 上方气泡区域或极贴近播放头，优先判定为拖动播放头
            if (y <= trackTop + 6 || (x - playX).abs() <= 12) {
              _dragMode = _TrimDragMode.playhead;
              widget.onPlayheadChanged(
                msForX(x).clamp(widget.trimStartMs, widget.trimEndMs),
              );
              return;
            }

            // 2. 判断是否命中左右手柄
            final startDistance = (x - startX).abs();
            final endDistance = (x - endX).abs();
            const edgeHitRadius = 24.0;

            if (startDistance <= edgeHitRadius && startDistance <= endDistance) {
              _dragMode = _TrimDragMode.start;
            } else if (endDistance <= edgeHitRadius) {
              _dragMode = _TrimDragMode.end;
            } else {
              _dragMode = _TrimDragMode.playhead;
              widget.onPlayheadChanged(
                msForX(x).clamp(widget.trimStartMs, widget.trimEndMs),
              );
            }
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
              case _TrimDragMode.playhead:
                widget.onPlayheadChanged(
                  value.clamp(widget.trimStartMs, widget.trimEndMs),
                );
                break;
              case null:
                break;
            }
          },
          onHorizontalDragEnd: (_) => _dragMode = null,
          onHorizontalDragCancel: () => _dragMode = null,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              // 1. 缩略图轨道底图（严格限制在 trackInset 到 width - trackInset 之间）
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
                        child: path != null && File(path).existsSync()
                            ? Image.file(File(path), fit: BoxFit.cover)
                            : ColoredBox(
                                color: index.isEven
                                    ? const Color(0xFFE8D7D0)
                                    : const Color(0xFFF2E4DE),
                              ),
                      );
                    }),
                  ),
                ),
              ),
              // 2. 左侧未选中暗色遮罩
              if (startX > trackInset)
                Positioned(
                  left: trackInset,
                  width: startX - trackInset,
                  top: trackTop,
                  height: trackHeight,
                  child: IgnorePointer(
                    child: ClipRRect(
                      borderRadius: const BorderRadius.horizontal(
                        left: Radius.circular(8),
                      ),
                      child: const ColoredBox(color: Color(0x75000000)),
                    ),
                  ),
                ),
              // 3. 右侧未选中暗色遮罩
              if (endX < width - trackInset)
                Positioned(
                  left: endX,
                  right: trackInset,
                  top: trackTop,
                  height: trackHeight,
                  child: IgnorePointer(
                    child: ClipRRect(
                      borderRadius: const BorderRadius.horizontal(
                        right: Radius.circular(8),
                      ),
                      child: const ColoredBox(color: Color(0x75000000)),
                    ),
                  ),
                ),
              // 4. 选区高亮外框（上下边框）
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
              // 5. 播放指针垂线（严格从气泡底部延伸到轨道下边缘）
              Positioned(
                left: playX - 1,
                top: bubbleHeight - 1,
                height: trackBottom - bubbleHeight + 2,
                child: IgnorePointer(
                  child: Container(
                    width: 2,
                    decoration: BoxDecoration(
                      color: AppTheme.coral,
                      borderRadius: BorderRadius.circular(1),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x33000000),
                          blurRadius: 2,
                          offset: Offset(0, 1),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              // 6. 浮动时间气泡（绝对居中对齐 playX）
              Positioned(
                left: bubbleLeft,
                top: 0,
                width: bubbleWidth,
                height: bubbleHeight,
                child: IgnorePointer(
                  child: Container(
                    decoration: BoxDecoration(
                      color: AppTheme.coral,
                      borderRadius: BorderRadius.circular(11),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x30F44848),
                          blurRadius: 4,
                          offset: Offset(0, 2),
                        ),
                      ],
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      _precise(clampedPlayheadMs),
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 10,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.2,
                      ),
                    ),
                  ),
                ),
              ),
              // 7. 左侧裁剪手柄（居中对齐 startX，包覆轨道左边缘）
              Positioned(
                left: startX - handleWidth / 2,
                top: trackTop - 1,
                child: const IgnorePointer(
                  child: _TrimHandle(isLeft: true),
                ),
              ),
              // 8. 右侧裁剪手柄（居中对齐 endX，包覆轨道右边缘）
              Positioned(
                left: endX - handleWidth / 2,
                top: trackTop - 1,
                child: const IgnorePointer(
                  child: _TrimHandle(isLeft: false),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  String _precise(int ms) =>
      '${(ms ~/ 60000).toString().padLeft(2, '0')}:${((ms % 60000) / 1000).toStringAsFixed(1).padLeft(4, '0')}';
}

class _TrimHandle extends StatelessWidget {
  final bool isLeft;

  const _TrimHandle({required this.isLeft});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 14,
      height: 62,
      decoration: BoxDecoration(
        color: Colors.white,
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
        height: 18,
        decoration: BoxDecoration(
          color: AppTheme.coral,
          borderRadius: BorderRadius.circular(1.5),
        ),
      ),
    );
  }
}

class _TimeCard extends StatelessWidget {
  final String label;
  final String value;

  const _TimeCard({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 76,
      decoration: BoxDecoration(
        color: AppTheme.warmSurfaceSoft,
        borderRadius: BorderRadius.circular(18),
      ),
      alignment: Alignment.center,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: const TextStyle(
              color: AppTheme.warmTextSecondary,
              fontSize: 12,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: const TextStyle(
              color: AppTheme.warmTextPrimary,
              fontSize: 17,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}
