import 'package:flutter/material.dart';

import '../../app/theme.dart';

class MainFlowHeader extends StatelessWidget {
  final String title;
  final VoidCallback onClose;
  final String closeTooltip;
  final Widget? trailing;

  const MainFlowHeader({
    super.key,
    required this.title,
    required this.onClose,
    this.closeTooltip = '关闭',
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 68,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 18),
        child: Row(
          children: [
            SizedBox(
              width: 48,
              height: 48,
              child: IconButton(
                tooltip: closeTooltip,
                padding: EdgeInsets.zero,
                onPressed: onClose,
                icon: const Icon(
                  Icons.close_rounded,
                  size: 32,
                  color: AppTheme.warmTextPrimary,
                ),
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 19,
                    height: 1.1,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.3,
                  ),
                ),
              ),
            ),
            SizedBox(
              width: 48,
              height: 48,
              child: trailing == null
                  ? const SizedBox.shrink()
                  : Center(child: trailing),
            ),
          ],
        ),
      ),
    );
  }
}
