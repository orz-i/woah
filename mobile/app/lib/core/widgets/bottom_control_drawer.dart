import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/theme.dart';

/// 底部可拖拽控制抽屉组件
///
/// V2 使用克制的不透明石墨面板与多级吸附（Snap points），让媒体始终作为主舞台。
/// 用户可以直接上下拖动收起/展开，也可以点击顶部把手在主要吸附位置间切换。
class BottomControlDrawer extends StatefulWidget {
  final double minChildSize;
  final double initialChildSize;
  final double maxChildSize;
  final List<double>? snapSizes;
  final Widget? peekHeader;
  final Widget child;
  final Widget? bottomActionBar;
  final DraggableScrollableController? controller;
  final Gradient? panelGradient;
  final Color? panelColor;
  final Color? panelBorderColor;
  final Gradient? handleGradient;
  final Color? handleColor;
  final List<BoxShadow>? panelShadow;
  final double panelRadius;
  final Color? bottomActionBorderColor;
  final bool allowHandleOnlyCollapse;

  const BottomControlDrawer({
    super.key,
    this.minChildSize = 0.065,
    this.initialChildSize = 0.38,
    this.maxChildSize = 0.82,
    this.snapSizes,
    this.peekHeader,
    required this.child,
    this.bottomActionBar,
    this.controller,
    this.panelGradient,
    this.panelColor,
    this.panelBorderColor,
    this.handleGradient,
    this.handleColor,
    this.panelShadow,
    this.panelRadius = AppTheme.radiusSheet,
    this.bottomActionBorderColor,
    this.allowHandleOnlyCollapse = true,
  });

  @override
  State<BottomControlDrawer> createState() => _BottomControlDrawerState();
}

class _BottomControlDrawerState extends State<BottomControlDrawer> {
  late final DraggableScrollableController _sheetController;

  List<double> get _effectiveSnapSizes {
    final values =
        widget.snapSizes ??
        [widget.minChildSize, widget.initialChildSize, widget.maxChildSize];
    return [...values]..sort();
  }

  @override
  void initState() {
    super.initState();
    _sheetController = widget.controller ?? DraggableScrollableController();
  }

  @override
  void dispose() {
    if (widget.controller == null) {
      _sheetController.dispose();
    }
    super.dispose();
  }

  void _collapseCompletely() {
    HapticFeedback.lightImpact();
    _sheetController.animateTo(
      widget.minChildSize,
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
    );
  }

  void _expandToInitial() {
    HapticFeedback.lightImpact();
    _sheetController.animateTo(
      widget.initialChildSize,
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
    );
  }

  void _animateToNearestSnap({double velocity = 0}) {
    if (!_sheetController.isAttached) return;

    final current = _sheetController.size;
    final snapSizes = _effectiveSnapSizes;
    double target;

    if (velocity < -300) {
      target = snapSizes.firstWhere(
        (size) => size > current + 0.01,
        orElse: () => snapSizes.last,
      );
    } else if (velocity > 300) {
      target = snapSizes.reversed.firstWhere(
        (size) => size < current - 0.01,
        orElse: () => snapSizes.first,
      );
    } else {
      target = snapSizes.reduce(
        (a, b) => (a - current).abs() <= (b - current).abs() ? a : b,
      );
    }

    HapticFeedback.selectionClick();
    _sheetController.animateTo(
      target,
      duration: const Duration(milliseconds: 220),
      curve: Curves.easeOutCubic,
    );
  }

  Widget _buildDragHandle() {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onVerticalDragUpdate: (details) {
        if (!_sheetController.isAttached) return;
        final viewportHeight = MediaQuery.sizeOf(context).height;
        if (viewportHeight <= 0) return;

        final delta = details.delta.dy / viewportHeight;
        final nextSize = (_sheetController.size - delta).clamp(
          widget.minChildSize,
          widget.maxChildSize,
        );
        _sheetController.jumpTo(nextSize);
      },
      onVerticalDragEnd: (details) {
        _animateToNearestSnap(velocity: details.primaryVelocity ?? 0);
      },
      onTap: () {
        if (!_sheetController.isAttached) return;
        final current = _sheetController.size;
        if (current <= widget.minChildSize * 1.5) {
          _expandToInitial();
        } else if (current < widget.initialChildSize * 0.9) {
          _expandToInitial();
        } else if (current < widget.maxChildSize * 0.9) {
          HapticFeedback.lightImpact();
          _sheetController.animateTo(
            widget.maxChildSize,
            duration: const Duration(milliseconds: 260),
            curve: Curves.easeOutCubic,
          );
        } else {
          _collapseCompletely();
        }
      },
      child: SizedBox(
        key: const ValueKey('bottom_control_drawer_handle'),
        width: double.infinity,
        height: 28,
        child: Center(
          child: Container(
            width: 42,
            height: 5,
            decoration: BoxDecoration(
              color: widget.handleGradient == null
                  ? (widget.handleColor ?? AppTheme.metalMid)
                  : null,
              gradient:
                  widget.handleGradient ??
                  (widget.handleColor == null
                      ? const LinearGradient(
                          colors: [
                            AppTheme.metalLow,
                            AppTheme.metalHigh,
                            AppTheme.metalLow,
                          ],
                        )
                      : null),
              borderRadius: BorderRadius.circular(3),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final effectiveSnapSizes = _effectiveSnapSizes;

    return DraggableScrollableSheet(
      controller: _sheetController,
      minChildSize: widget.minChildSize,
      initialChildSize: widget.initialChildSize,
      maxChildSize: widget.maxChildSize,
      snap: true,
      snapSizes: effectiveSnapSizes,
      builder: (context, scrollController) {
        return LayoutBuilder(
          builder: (context, constraints) {
            final height = constraints.maxHeight;
            final viewportHeight = MediaQuery.sizeOf(context).height;
            final collapsedHeight = viewportHeight * widget.minChildSize;
            final isCollapsed =
                widget.allowHandleOnlyCollapse &&
                height <= collapsedHeight + 28;
            final showBottomAction = !isCollapsed && height >= 160;

            return Container(
              decoration: BoxDecoration(
                color: widget.panelGradient == null ? widget.panelColor : null,
                gradient:
                    widget.panelGradient ??
                    (widget.panelColor == null
                        ? const LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Color(0xFF18181B), Color(0xFF101012)],
                          )
                        : null),
                borderRadius: BorderRadius.vertical(
                  top: Radius.circular(widget.panelRadius),
                ),
                border: Border(
                  top: BorderSide(
                    color: widget.panelBorderColor ?? AppTheme.surfaceBorder,
                    width: 1,
                  ),
                ),
                boxShadow:
                    widget.panelShadow ??
                    const [
                      BoxShadow(
                        color: Color(0x8A000000),
                        blurRadius: 28,
                        spreadRadius: 2,
                        offset: Offset(0, -8),
                      ),
                    ],
              ),
              clipBehavior: Clip.antiAlias,
              child: isCollapsed
                  ? SingleChildScrollView(
                      controller: scrollController,
                      physics: const ClampingScrollPhysics(),
                      child: SizedBox(
                        height: height,
                        child: Align(
                          alignment: Alignment.topCenter,
                          child: _buildDragHandle(),
                        ),
                      ),
                    )
                  : Column(
                      children: [
                        _buildDragHandle(),
                        if (widget.peekHeader != null) widget.peekHeader!,
                        Expanded(
                          child: SingleChildScrollView(
                            controller: scrollController,
                            physics: const BouncingScrollPhysics(),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 18,
                              vertical: 8,
                            ),
                            child: widget.child,
                          ),
                        ),
                        if (widget.bottomActionBar != null && showBottomAction)
                          SafeArea(
                            top: false,
                            child: Container(
                              padding: const EdgeInsets.fromLTRB(18, 8, 18, 14),
                              decoration: BoxDecoration(
                                border: Border(
                                  top: BorderSide(
                                    color:
                                        widget.bottomActionBorderColor ??
                                        AppTheme.surfaceBorder,
                                  ),
                                ),
                              ),
                              child: widget.bottomActionBar!,
                            ),
                          ),
                      ],
                    ),
            );
          },
        );
      },
    );
  }
}
