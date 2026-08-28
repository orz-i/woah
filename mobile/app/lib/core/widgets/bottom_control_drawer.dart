import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 底部可拖拽控制抽屉组件
///
/// 采用现代毛玻璃（Glassmorphism）与多级吸附（Snap points）设计，承载控制面板、
/// 参数调节滑块、模式选择器与主要操作按钮。
/// 支持完全收起（minChildSize 降至边缘，留出 100% 全屏主舞台且绝无布局溢出）。
class BottomControlDrawer extends StatefulWidget {
  final double minChildSize;
  final double initialChildSize;
  final double maxChildSize;
  final List<double>? snapSizes;
  final Widget? peekHeader;
  final Widget child;
  final Widget? bottomActionBar;
  final DraggableScrollableController? controller;

  const BottomControlDrawer({
    super.key,
    this.minChildSize = 0.045,
    this.initialChildSize = 0.38,
    this.maxChildSize = 0.82,
    this.snapSizes,
    this.peekHeader,
    required this.child,
    this.bottomActionBar,
    this.controller,
  });

  @override
  State<BottomControlDrawer> createState() => _BottomControlDrawerState();
}

class _BottomControlDrawerState extends State<BottomControlDrawer> {
  late final DraggableScrollableController _sheetController;

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

  Widget _buildDragHandle() {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () {
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
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.only(top: 8, bottom: 6),
        alignment: Alignment.center,
        child: Container(
          width: 36,
          height: 4,
          decoration: BoxDecoration(
            color: Colors.white.withAlpha(90),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final effectiveSnapSizes = widget.snapSizes ??
        [
          widget.minChildSize,
          widget.initialChildSize,
          widget.maxChildSize,
        ];

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
            final isCollapsed = height < 90;

            return ClipRRect(
              borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
                child: Container(
                  decoration: BoxDecoration(
                    color: const Color(0xE6141418), // 深色毛玻璃半透底
                    borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
                    border: Border.all(
                      color: Colors.white.withAlpha(25),
                      width: 1.0,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withAlpha(140),
                        blurRadius: 24,
                        spreadRadius: 4,
                        offset: const Offset(0, -6),
                      ),
                    ],
                  ),
                  child: isCollapsed
                      ? SingleChildScrollView(
                          controller: scrollController,
                          physics: const ClampingScrollPhysics(),
                          child: SizedBox(
                            height: height,
                            child: Center(
                              child: _buildDragHandle(),
                            ),
                          ),
                        )
                      : Column(
                          children: [
                            // 1. Drag Handle
                            _buildDragHandle(),

                            // 2. Peek Header
                            if (widget.peekHeader != null) widget.peekHeader!,

                            // 3. Scrollable Controls Body
                            Expanded(
                              child: SingleChildScrollView(
                                controller: scrollController,
                                physics: const BouncingScrollPhysics(),
                                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                                child: widget.child,
                              ),
                            ),

                            // 4. Bottom Persistent Action Bar (有足够空间时显示)
                            if (widget.bottomActionBar != null && height >= 200)
                              SafeArea(
                                top: false,
                                child: Padding(
                                  padding: const EdgeInsets.fromLTRB(16, 6, 16, 12),
                                  child: widget.bottomActionBar!,
                                ),
                              ),
                          ],
                        ),
                ),
              ),
            );
          },
        );
      },
    );
  }
}
