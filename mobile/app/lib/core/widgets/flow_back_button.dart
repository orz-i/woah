import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/theme.dart';

/// Floating back button aligned with the graphite main-flow visual system.
///
/// Provides an accessible, familiar top-left return affordance while the media
/// stage remains the visual center.
class FlowBackButton extends StatelessWidget {
  static const backButtonKey = ValueKey('flow-back-button');

  final VoidCallback onPressed;
  final String tooltip;
  final double size;
  final Color foregroundColor;
  final Color backgroundColor;

  const FlowBackButton({
    super.key,
    required this.onPressed,
    this.tooltip = '返回上一步',
    this.size = AppTheme.minTouchTarget,
    this.foregroundColor = AppTheme.flowTextPrimary,
    this.backgroundColor = Colors.transparent,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: tooltip,
      child: Tooltip(
        message: tooltip,
        child: Material(
          color: backgroundColor,
          shape: const CircleBorder(),
          child: InkWell(
            key: backButtonKey,
            onTap: () {
              HapticFeedback.lightImpact();
              onPressed();
            },
            customBorder: const CircleBorder(),
            child: ConstrainedBox(
              constraints: const BoxConstraints(
                minWidth: AppTheme.minTouchTarget,
                minHeight: AppTheme.minTouchTarget,
              ),
              child: SizedBox(
                width: size,
                height: size,
                child: Center(
                  child: Icon(
                    Icons.arrow_back_ios_new_rounded,
                    size: 20,
                    color: foregroundColor,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
