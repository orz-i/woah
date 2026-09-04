import 'package:flutter/material.dart';

import '../../app/theme.dart';

class MainFlowHeader extends StatelessWidget {
  final String title;
  final String? subtitle;
  final VoidCallback onClose;
  final String closeTooltip;
  final Widget? trailing;

  const MainFlowHeader({
    super.key,
    required this.title,
    this.subtitle,
    required this.onClose,
    this.closeTooltip = '关闭',
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: subtitle != null ? 74 : 68,
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
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
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
                    if (subtitle != null) ...[
                      const SizedBox(height: 4),
                      Text(
                        subtitle!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: AppTheme.warmTextSecondary,
                          fontSize: 12,
                          height: 1.1,
                          fontWeight: FontWeight.w400,
                        ),
                      ),
                    ],
                  ],
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
