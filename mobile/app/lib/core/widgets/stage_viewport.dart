import 'package:flutter/material.dart';

import '../../app/theme.dart';

/// Shared warm-flow media stage surface.
///
/// The main flow should keep the media itself visually stable while each step
/// swaps the tools below it. This wrapper centralizes the rounded frame,
/// border, clipping, and elevation used by those stages.
class MediaStageFrame extends StatelessWidget {
  final Widget child;
  final double radius;
  final Color backgroundColor;
  final bool elevated;

  const MediaStageFrame({
    super.key,
    required this.child,
    this.radius = 26,
    this.backgroundColor = const Color(0xFFF1E7E1),
    this.elevated = true,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(radius),
        boxShadow: elevated
            ? const [
                BoxShadow(
                  color: Color(0x14000000),
                  blurRadius: 22,
                  offset: Offset(0, 7),
                ),
              ]
            : null,
      ),
      foregroundDecoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: AppTheme.warmBorder),
      ),
      clipBehavior: Clip.antiAlias,
      child: child,
    );
  }
}

/// 全屏多媒体主舞台组件
///
/// 提供沉浸式无黑边/智能 Letterbox 的全屏视口，支持双指捏合缩放（Pinch-to-zoom）、
/// 拖拽平移与双击快速复位居中。
class StageViewport extends StatefulWidget {
  final Widget child;
  final bool enableZoom;
  final double minScale;
  final double maxScale;
  final VoidCallback? onTap;
  final TransformationController? transformationController;
  final Color backgroundColor;

  const StageViewport({
    super.key,
    required this.child,
    this.enableZoom = true,
    this.minScale = 1.0,
    this.maxScale = 4.0,
    this.onTap,
    this.transformationController,
    this.backgroundColor = const Color(0xFF0A0A0C),
  });

  @override
  State<StageViewport> createState() => _StageViewportState();
}

class _StageViewportState extends State<StageViewport>
    with SingleTickerProviderStateMixin {
  late final TransformationController _controller;
  late final AnimationController _animController;
  Animation<Matrix4>? _animation;

  @override
  void initState() {
    super.initState();
    _controller = widget.transformationController ?? TransformationController();
    _animController =
        AnimationController(
          vsync: this,
          duration: const Duration(milliseconds: 240),
        )..addListener(() {
          if (_animation != null) {
            _controller.value = _animation!.value;
          }
        });
  }

  @override
  void dispose() {
    if (widget.transformationController == null) {
      _controller.dispose();
    }
    _animController.dispose();
    super.dispose();
  }

  void _handleDoubleTap() {
    if (!widget.enableZoom) return;
    final currentScale = _controller.value.getMaxScaleOnAxis();
    final isZoomed = currentScale > 1.05;

    final targetMatrix = isZoomed
        ? Matrix4.identity()
        : Matrix4.diagonal3Values(2.0, 2.0, 1.0);

    _animation = Matrix4Tween(begin: _controller.value, end: targetMatrix)
        .animate(
          CurvedAnimation(parent: _animController, curve: Curves.easeOutCubic),
        );

    _animController.forward(from: 0);
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onTap,
      onDoubleTap: _handleDoubleTap,
      child: Container(
        width: double.infinity,
        height: double.infinity,
        color: widget.backgroundColor,
        child: widget.enableZoom
            ? InteractiveViewer(
                transformationController: _controller,
                minScale: widget.minScale,
                maxScale: widget.maxScale,
                panEnabled: true,
                scaleEnabled: true,
                child: SizedBox.expand(child: widget.child),
              )
            : SizedBox.expand(child: widget.child),
      ),
    );
  }
}
