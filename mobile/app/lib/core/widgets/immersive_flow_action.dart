import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/theme.dart';

/// Shared immersive navigation action for the main editing flow.
///
/// A normal tap advances. Holding and dragging upward reveals a return target;
/// releasing while the target is armed triggers [onReturn].
class ImmersiveFlowAction extends StatefulWidget {
  static const nextControlKey = ValueKey('immersive-flow-next-control');
  static const exitTargetKey = ValueKey('immersive-flow-exit-target');

  final bool enabled;
  final VoidCallback onNext;
  final VoidCallback onReturn;
  final String nextSemanticsLabel;

  const ImmersiveFlowAction({
    super.key,
    required this.enabled,
    required this.onNext,
    required this.onReturn,
    this.nextSemanticsLabel = '下一步，长按并上拉可返回',
  });

  @override
  State<ImmersiveFlowAction> createState() => _ImmersiveFlowActionState();
}

class _ImmersiveFlowActionState extends State<ImmersiveFlowAction> {
  static const double _controlSize = 62;
  static const double _exitTargetOffset = 108;
  static const double _exitTargetRadius = 38;

  bool _dragActive = false;
  bool _exitTargetArmed = false;
  Offset _dragOffset = Offset.zero;
  Offset? _dragOrigin;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SizedBox(
        width: 176,
        height: 176,
        child: Stack(
          alignment: Alignment.bottomCenter,
          clipBehavior: Clip.none,
          children: [
            if (_dragActive && _dragOffset.dy < -6)
              Positioned(
                bottom: _exitTargetOffset - _exitTargetRadius,
                child: IgnorePointer(
                  child: Semantics(
                    button: true,
                    label: _exitTargetArmed ? '松开返回' : '上拉返回',
                    child: AnimatedScale(
                      duration: const Duration(milliseconds: 120),
                      scale: _exitTargetArmed ? 1.12 : 1,
                      child: AnimatedContainer(
                        key: ImmersiveFlowAction.exitTargetKey,
                        duration: const Duration(milliseconds: 120),
                        width: _exitTargetRadius * 2,
                        height: _exitTargetRadius * 2,
                        decoration: BoxDecoration(
                          color: _exitTargetArmed
                              ? AppTheme.coral
                              : AppTheme.warmSurface.withValues(alpha: 0.96),
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: _exitTargetArmed
                                ? AppTheme.coral
                                : AppTheme.warmBorder,
                            width: 1.5,
                          ),
                          boxShadow: const [
                            BoxShadow(
                              color: Color(0x24000000),
                              blurRadius: 18,
                              offset: Offset(0, 6),
                            ),
                          ],
                        ),
                        child: Icon(
                          Icons.arrow_back_rounded,
                          color: _exitTargetArmed
                              ? Colors.white
                              : AppTheme.warmTextPrimary,
                          size: 28,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            Positioned(
              bottom: 0,
              child: Transform.translate(
                offset: _dragOffset,
                child: Semantics(
                  button: true,
                  enabled: widget.enabled,
                  label: widget.enabled
                      ? widget.nextSemanticsLabel
                      : '下一步暂不可用，长按并上拉可返回',
                  child: Listener(
                    onPointerMove: _updateNavigationPointer,
                    onPointerUp: (_) => _finishDrag(),
                    onPointerCancel: (_) => _cancelDrag(),
                    child: GestureDetector(
                      key: ImmersiveFlowAction.nextControlKey,
                      behavior: HitTestBehavior.opaque,
                      onTap: widget.enabled
                          ? () {
                              HapticFeedback.mediumImpact();
                              widget.onNext();
                            }
                          : null,
                      onLongPressStart: _startNavigationDrag,
                      child: AnimatedOpacity(
                        duration: const Duration(milliseconds: 120),
                        opacity: widget.enabled ? 1 : 0.42,
                        child: Container(
                          width: _controlSize,
                          height: _controlSize,
                          decoration: const BoxDecoration(
                            gradient: AppTheme.coralActionGradient,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                color: Color(0x38F44848),
                                blurRadius: 20,
                                offset: Offset(0, 8),
                              ),
                            ],
                          ),
                          child: const Icon(
                            Icons.arrow_forward_rounded,
                            color: Colors.white,
                            size: 30,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _startNavigationDrag(LongPressStartDetails details) {
    HapticFeedback.selectionClick();
    setState(() {
      _dragActive = true;
      _exitTargetArmed = false;
      _dragOffset = Offset.zero;
      _dragOrigin = details.globalPosition;
    });
  }

  void _updateNavigationPointer(PointerMoveEvent event) {
    final origin = _dragOrigin;
    if (!_dragActive || origin == null) return;

    final raw = event.position - origin;
    final offset = Offset(raw.dx.clamp(-58.0, 58.0), raw.dy.clamp(-124.0, 0.0));
    final targetOffset = const Offset(0, -_exitTargetOffset);
    final armed = (offset - targetOffset).distance <= _exitTargetRadius + 8;

    if (armed != _exitTargetArmed) {
      HapticFeedback.selectionClick();
    }
    setState(() {
      _dragOffset = offset;
      _exitTargetArmed = armed;
    });
  }

  void _finishDrag() {
    if (!_dragActive) return;
    final shouldReturn = _exitTargetArmed;
    _resetDragState();
    if (!shouldReturn) return;

    HapticFeedback.mediumImpact();
    widget.onReturn();
  }

  void _cancelDrag() {
    if (_dragActive) _resetDragState();
  }

  void _resetDragState() {
    if (!mounted) return;
    setState(() {
      _dragActive = false;
      _exitTargetArmed = false;
      _dragOffset = Offset.zero;
      _dragOrigin = null;
    });
  }
}
