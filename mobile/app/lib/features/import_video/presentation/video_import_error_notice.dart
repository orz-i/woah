import 'package:flutter/material.dart';

import '../../../app/theme.dart';

/// Identical error treatment from either starting point of a video import.
class VideoImportErrorNotice extends StatelessWidget {
  const VideoImportErrorNotice({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: AppTheme.flowSurfaceSoft,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.error.withAlpha(88)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.error_outline_rounded,
            color: AppTheme.error,
            size: 18,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(
                color: AppTheme.flowTextSecondary,
                fontSize: 12,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
