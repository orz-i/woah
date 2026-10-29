import 'package:flutter/material.dart';

/// Woah (舞哇) / Quiet Frame v1.1 product color roles.
///
/// Brand/action colors intentionally do not alias user-configurable render
/// effects. Person selection follows the segmentation mask geometry; neither
/// selection nor a mint tint certifies that privacy protection is complete.
class AppTheme {
  // Locked Quiet Frame palette, sRGB.
  static const Color brandMint = Color(0xFF78DCC7);
  static const Color brandInk = Color(0xFF0B1415);
  static const Color brandSurface = Color(0xFF142123);
  static const Color brandPearl = Color(0xFFF2F6F3);
  static const Color brandSecondary = Color(0xFFB4C8C6);
  static const Color brandDeepMint = Color(0xFF216A5E);

  // Semantic roles: these may share a value, but do not share a meaning.
  static const Color brandAction = brandMint;
  static const Color brandOnAction = brandInk;
  static const Color brandActionPressed = Color(0xFF59C5AF);
  static const Color editorSelection = brandMint;
  static const Color editorSelectionSubtle = Color(0x2678DCC7);
  static const Color controlBorder = Color(0xFF607D80);
  static const Color decorativeDivider = Color(0xFF2D4548);
  static const Color statusWarning = Color(0xFFF0C879);
  static const Color statusWarningSubtle = Color(0x20F0C879);
  static const Color statusError = Color(0xFFFF9C9F);
  static const Color statusSuccess = Color(0xFF9DD6B9);

  // Main workflow chrome (import, protection, comparison, export, results).
  static const Color flowBackground = brandInk;
  static const Color flowSurface = brandSurface;
  static const Color flowSurfaceSoft = Color(0xFF1B2B2D);
  static const Color flowTextPrimary = brandPearl;
  static const Color flowTextSecondary = brandSecondary;
  static const Color flowTextMuted = Color(0xFF8DA6A4);
  static const Color flowBorder = decorativeDivider;

  // The amber option is a *user-selectable video effect color*, not the brand
  // action color. Do not replace or remap the integer stored in project data.
  static const Color effectPaletteAmber = Color(0xFFF5A623);
  static const Color gold = effectPaletteAmber; // Legacy effect palette alias.
  static const Color goldStrong = Color(0xFFE59800);
  static const Color goldLight = Color(0xFFFFC043);
  static const Color goldPale = Color(0xFF332612);
  static const Color coral = effectPaletteAmber; // Legacy; no UI usages.
  static const Color coralStrong = goldStrong;
  static const Color coralSoft = goldLight;
  static const Color coralPale = goldPale;

  static const LinearGradient brandActionGradient = LinearGradient(
    begin: Alignment.centerLeft,
    end: Alignment.centerRight,
    colors: [brandActionPressed, brandAction, brandMint],
  );
  static const LinearGradient goldActionGradient = brandActionGradient;
  static const LinearGradient coralActionGradient = brandActionGradient;

  static const LinearGradient mediaPickerGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [surfaceHigh, flowSurfaceSoft, flowSurface],
    stops: [0.0, 0.48, 1.0],
  );

  // Video remains pure black; the surrounding interface uses Quiet Frame Ink.
  static const Color background = brandInk;
  static const Color canvas = Color(0xFF000000);
  static const Color surface = brandSurface;
  static const Color surfaceElevated = Color(0xFF1B2B2D);
  static const Color surfaceHigh = Color(0xFF243638);
  static const Color surfacePill = Color(0xFF2B4143);
  static const Color surfaceBorder = decorativeDivider;
  static const Color sliderTrackInactive = Color(0xFF385153);

  static const Color metalHigh = brandPearl;
  static const Color metalMid = brandSecondary;
  static const Color metalLow = Color(0xFF8DA6A4);

  static const Color textPrimary = brandPearl;
  static const Color textSecondary = brandSecondary;
  static const Color textMuted = Color(0xFF8DA6A4);
  static const Color textOnAccent = brandOnAction;
  static const Color error = statusError;

  static const double radiusSmall = 8;
  static const double radiusMedium = 12;
  static const double radiusLarge = 18;
  static const double radiusCapsule = 22;
  static const double radiusSheet = 24;
  static const double minTouchTarget = 48;

  static BoxDecoration panelDecoration({double radius = radiusLarge}) {
    return BoxDecoration(
      color: surfaceElevated,
      borderRadius: BorderRadius.circular(radius),
      border: Border.all(color: surfaceBorder, width: 1),
    );
  }

  static ThemeData get darkTheme {
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: background,
      colorScheme: const ColorScheme(
        brightness: Brightness.dark,
        primary: brandAction,
        onPrimary: brandOnAction,
        secondary: brandSecondary,
        onSecondary: brandInk,
        surface: surface,
        onSurface: textPrimary,
        error: error,
        onError: brandInk,
      ),
    );

    return base.copyWith(
      textTheme: base.textTheme.copyWith(
        headlineLarge: const TextStyle(
          color: textPrimary,
          fontSize: 36,
          height: 1.05,
          fontWeight: FontWeight.w700,
          letterSpacing: -1.2,
        ),
        headlineSmall: const TextStyle(
          color: textPrimary,
          fontSize: 22,
          height: 1.2,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.4,
        ),
        titleLarge: const TextStyle(
          color: textPrimary,
          fontSize: 18,
          height: 1.25,
          fontWeight: FontWeight.w600,
        ),
        titleMedium: const TextStyle(
          color: textPrimary,
          fontSize: 16,
          height: 1.3,
          fontWeight: FontWeight.w600,
        ),
        bodyLarge: const TextStyle(
          color: textPrimary,
          fontSize: 15,
          height: 1.45,
        ),
        bodyMedium: const TextStyle(
          color: textSecondary,
          fontSize: 14,
          height: 1.45,
        ),
        bodySmall: const TextStyle(color: textMuted, fontSize: 12, height: 1.4),
        labelLarge: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w600,
          letterSpacing: 0,
        ),
      ),
      cardTheme: CardThemeData(
        color: surfaceElevated,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusMedium),
          side: const BorderSide(color: controlBorder, width: 1),
        ),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: background,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
        iconTheme: IconThemeData(color: textPrimary),
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 16,
          fontWeight: FontWeight.w600,
          letterSpacing: -0.1,
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          minimumSize: const Size(minTouchTarget, minTouchTarget),
          backgroundColor: surfaceHigh,
          foregroundColor: textPrimary,
          disabledBackgroundColor: surfaceHigh,
          disabledForegroundColor: metalLow,
          elevation: 0,
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusMedium),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(minTouchTarget, minTouchTarget),
          foregroundColor: textPrimary,
          side: const BorderSide(color: surfaceBorder, width: 1),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusMedium),
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size(minTouchTarget, minTouchTarget),
          foregroundColor: textPrimary,
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          minimumSize: const Size(minTouchTarget, minTouchTarget),
          foregroundColor: textPrimary,
        ),
      ),
      dividerTheme: const DividerThemeData(
        color: surfaceBorder,
        thickness: 1,
        space: 24,
      ),
      sliderTheme: const SliderThemeData(
        activeTrackColor: editorSelection,
        inactiveTrackColor: sliderTrackInactive,
        thumbColor: editorSelection,
        overlayColor: editorSelectionSubtle,
        trackHeight: 4,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return brandOnAction;
          return metalMid;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return brandAction;
          return surfaceHigh;
        }),
        trackOutlineColor: WidgetStateProperty.all(surfaceBorder),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: flowSurface,
        contentTextStyle: const TextStyle(
          color: flowTextPrimary,
          fontSize: 13,
          fontWeight: FontWeight.w500,
        ),
        actionTextColor: brandAction,
        elevation: 8,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusMedium),
          side: const BorderSide(color: flowBorder),
        ),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: surface,
        modalBackgroundColor: surface,
        surfaceTintColor: Colors.transparent,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: surfaceElevated,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusLarge),
          side: const BorderSide(color: controlBorder),
        ),
      ),
    );
  }
}
