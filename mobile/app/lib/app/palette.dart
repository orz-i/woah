import 'package:flutter/material.dart';

/// Colors of the *interface*, not the exported video or its mask effects.
///
/// Media pixels and user-selected effect ARGB values are independent of theme.
/// Quiet Frame brand colors remain fixed; light-mode controls use a deeper
/// green so their labels and selected states retain adequate contrast.
@immutable
class WoahPalette {
  const WoahPalette({
    required this.background,
    required this.flowBackground,
    required this.flowSurface,
    required this.flowSurfaceSoft,
    required this.flowTextPrimary,
    required this.flowTextSecondary,
    required this.flowTextMuted,
    required this.flowBorder,
    required this.surfaceElevated,
    required this.surfaceHigh,
    required this.surfaceBorder,
    required this.sliderTrackInactive,
    required this.brandAction,
    required this.brandOnAction,
    required this.editorSelection,
    required this.editorSelectionSubtle,
    required this.controlBorder,
    required this.textPrimary,
    required this.textSecondary,
    required this.textMuted,
    required this.textOnAccent,
    required this.error,
    required this.statusWarning,
    required this.statusWarningSubtle,
    required this.statusSuccess,
  });

  final Color background;
  final Color flowBackground, flowSurface, flowSurfaceSoft;
  final Color flowTextPrimary, flowTextSecondary, flowTextMuted, flowBorder;
  final Color surfaceElevated, surfaceHigh, surfaceBorder;
  final Color sliderTrackInactive;
  final Color brandAction, brandOnAction;
  final Color editorSelection, editorSelectionSubtle;
  final Color controlBorder;
  final Color textPrimary, textSecondary, textMuted, textOnAccent;
  final Color error, statusWarning, statusWarningSubtle, statusSuccess;

  /// Used for contrast with brandAction-filled buttons and icons.
  Color get onBrandAction => brandOnAction;

  /// Small item shadows should be softer on a pale background.
  Color get homeCardShadow => background.computeLuminance() > 0.5
      ? const Color(0x180B1415)
      : const Color(0x45000000);
}
