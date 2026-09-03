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
                  padding: const EdgeInsets.fromLTRB(18, 8, 18, 18),
                  child: Column(
                    children: [
                      _buildPreview(),
                      const SizedBox(height: 20),
                      _buildTimeline(),
                      const SizedBox(height: 20),
                      _buildTimeSummary(),
                      const SizedBox(height: 28),
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
    return SizedBox(
      height: 126,
      child: Stack(
        children: [
          Positioned(
            top: 8,
            left: 18,
            child: SizedBox(
              width: 48,
              height: 48,
              child: IconButton(
                tooltip: '返回',
                padding: EdgeInsets.zero,
                onPressed: () {
                  HapticFeedback.lightImpact();
                  context.pop();
                },
                icon: const Icon(
                  Icons.close_rounded,
                  size: 34,
                  color: AppTheme.warmTextPrimary,
                ),
              ),
            ),
          ),
          const Positioned(
            left: 70,
            right: 70,
            top: 48,
            child: Column(
              children: [
                Text(
                  '预览并裁剪视频',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 22,
                    height: 1.15,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.5,
                  ),
                ),
                SizedBox(height: 9),
                Text(
                  '拖动两端或移动中间选取你需要的片段',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: AppTheme.warmTextSecondary,
                    fontSize: 12,
                    height: 1.35,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
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
              FittedBox(
                fit: BoxFit.contain,
                child: SizedBox(
                  width: controller.value.size.width,
                  height: controller.value.size.height,
                  child: VideoPlayer(controller),
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
            if (controller != null && controller.value.isInitialized)
              Align(
                alignment: Alignment.center,
                child: GestureDetector(
                  onTap: _togglePlayback,
                  child: Container(
                    width: 62,
                    height: 62,
                    decoration: const BoxDecoration(
                      color: Color(0x77000000),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      controller.value.isPlaying
                          ? Icons.pause_rounded
                          : Icons.play_arrow_rounded,
                      color: Colors.white,
                      size: 36,
                    ),
                  ),
                ),
              ),
            Positioned(
              left: 14,
              bottom: 14,
              child: _PreviewTimeChip(
                text:
                    '${_formatTime(_playheadMs)} / ${_formatTime(_durationMs)}',
              ),
            ),
            if (controller != null && controller.value.isInitialized)
              Positioned(
                right: 12,
                bottom: 10,
                child: IconButton(
                  tooltip: '从片段起点播放',
                  onPressed: () => _seekTo(_trimStartMs),
                  icon: const Icon(
                    Icons.fullscreen_rounded,
                    color: Colors.white,
                    size: 28,
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
        _TimelineRuler(durationMs: _durationMs),
        const SizedBox(height: 8),
        SizedBox(
          height: 118,
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
              padding: EdgeInsets.symmetric(horizontal: 12),
              child: Icon(
                Icons.swap_horiz_rounded,
                color: AppTheme.coralSoft,
                size: 30,
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
        const SizedBox(height: 18),
        Text(
          '时长 ${_formatPrecise(duration)}',
          style: const TextStyle(
            color: AppTheme.warmTextPrimary,
            fontSize: 14,
            fontWeight: FontWeight.w500,
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

class _PreviewTimeChip extends StatelessWidget {
  final String text;

  const _PreviewTimeChip({required this.text});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.white.withAlpha(230),
        borderRadius: BorderRadius.circular(22),
      ),
      child: Text(
        text,
        style: const TextStyle(
          color: AppTheme.warmTextPrimary,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _TimelineRuler extends StatelessWidget {
  final int durationMs;

  const _TimelineRuler({required this.durationMs});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text('00:00', style: _style),
        Text(_format(durationMs ~/ 3), style: _style),
        Text(_format(durationMs * 2 ~/ 3), style: _style),
        Text(_format(durationMs), style: _style),
      ],
    );
  }

  static const _style = TextStyle(
    color: AppTheme.warmTextSecondary,
    fontSize: 11,
  );

  String _format(int ms) {
    final total = ms ~/ 1000;
    return '${(total ~/ 60).toString().padLeft(2, '0')}:${(total % 60).toString().padLeft(2, '0')}';
  }
}

class _TrimTimeline extends StatelessWidget {
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
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final startX = width * trimStartMs / durationMs;
        final endX = width * trimEndMs / durationMs;
        final playX = width * playheadMs / durationMs;

        return Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(
              left: 0,
              right: 0,
              top: 22,
              height: 72,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTapDown: (details) {
                  final value = (details.localPosition.dx / width * durationMs)
                      .round();
                  onPlayheadChanged(value);
                },
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: Row(
                    children: List.generate(10, (index) {
                      final path = index < thumbnailPaths.length
                          ? thumbnailPaths[index]
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
            ),
            Positioned(
              left: 0,
              width: startX,
              top: 22,
              height: 72,
              child: const ColoredBox(color: Color(0x66000000)),
            ),
            Positioned(
              left: endX,
              right: 0,
              top: 22,
              height: 72,
              child: const ColoredBox(color: Color(0x66000000)),
            ),
            Positioned(
              left: startX,
              width: (endX - startX).clamp(0.0, width),
              top: 21,
              height: 74,
              child: IgnorePointer(
                child: Container(
                  decoration: BoxDecoration(
                    border: Border.all(color: AppTheme.coral, width: 2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ),
            Positioned(
              left: (startX - 20).clamp(-4.0, width - 40),
              top: 14,
              child: _TrimHandle(
                onDrag: (delta) {
                  onStartChanged(
                    (trimStartMs + delta * durationMs / width).round(),
                  );
                },
              ),
            ),
            Positioned(
              left: (endX - 20).clamp(-4.0, width - 40),
              top: 14,
              child: _TrimHandle(
                onDrag: (delta) {
                  onEndChanged(
                    (trimEndMs + delta * durationMs / width).round(),
                  );
                },
              ),
            ),
            Positioned(
              left: (playX - 1).clamp(0.0, width - 2),
              top: 0,
              bottom: 0,
              child: GestureDetector(
                behavior: HitTestBehavior.translucent,
                onHorizontalDragUpdate: (details) {
                  onPlayheadChanged(
                    (playheadMs + details.delta.dx * durationMs / width)
                        .round(),
                  );
                },
                child: Column(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: AppTheme.coral,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        _precise(playheadMs),
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    Expanded(child: Container(width: 2, color: AppTheme.coral)),
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  String _precise(int ms) =>
      '${(ms ~/ 60000).toString().padLeft(2, '0')}:${((ms % 60000) / 1000).toStringAsFixed(1).padLeft(4, '0')}';
}

class _TrimHandle extends StatelessWidget {
  final ValueChanged<double> onDrag;

  const _TrimHandle({required this.onDrag});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onHorizontalDragUpdate: (details) => onDrag(details.delta.dx),
      child: Container(
        width: 40,
        height: 88,
        decoration: BoxDecoration(
          color: AppTheme.warmSurface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppTheme.coral, width: 1.5),
          boxShadow: const [
            BoxShadow(
              color: Color(0x18000000),
              blurRadius: 8,
              offset: Offset(0, 3),
            ),
          ],
        ),
        alignment: Alignment.center,
        child: const Icon(
          Icons.drag_indicator_rounded,
          color: AppTheme.coral,
          size: 22,
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
      height: 84,
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
          const SizedBox(height: 6),
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
