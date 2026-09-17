import 'package:flutter/material.dart';

/// Woah V2 visual system.
///
/// The product intentionally stays monochrome: media is the visual focus while
/// chrome uses restrained black, graphite, silver and white surfaces. Semantic
/// red is kept only for destructive/error states.
class AppTheme {
  // Main-flow chrome shared by import, editor, export and result. Media stays
  // visually dominant while every transition remains in the same graphite
  // luminance family.
  // Professional Video Editor Visual System Tokens
  // Immersive black canvas with amber-gold accent hierarchy
  static const Color flowBackground = Color(0xFF0C0C0E);
  static const Color flowSurface = Color(0xFF161618);
  static const Color flowSurfaceSoft = Color(0xFF18181B);
  static const Color flowTextPrimary = Color(0xFFF4F4F5);
  static const Color flowTextSecondary = Color(0xFFB3B3B8);
  static const Color flowTextMuted = Color(0xFF77777D);
  static const Color flowBorder = Color(0xFF262628);

  // Amber Gold Accent Hierarchy
  static const Color gold = Color(0xFFF5A623);
  static const Color goldStrong = Color(0xFFE59800);
  static const Color goldLight = Color(0xFFFFC043);
  static const Color goldPale = Color(0xFF332612);

  // Compatibility aliases
  static const Color coral = gold;
  static const Color coralStrong = goldStrong;
  static const Color coralSoft = goldLight;
  static const Color coralPale = goldPale;

  static const LinearGradient goldActionGradient = LinearGradient(
    begin: Alignment.centerLeft,
    end: Alignment.centerRight,
    colors: [goldStrong, gold, goldLight],
  );

  static const LinearGradient coralActionGradient = goldActionGradient;

  static const LinearGradient mediaPickerGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [surfaceHigh, flowSurfaceSoft, flowSurface],
    stops: [0.0, 0.48, 1.0],
  );

  static const Color background = Color(0xFF050506);
  static const Color canvas = Color(0xFF000000);
  static const Color surface = Color(0xFF141416);
  static const Color surfaceElevated = Color(0xFF1C1C1E);
  static const Color surfaceHigh = Color(0xFF262628);
  static const Color surfacePill = Color(0xFF2A2A2C);
  static const Color surfaceBorder = Color(0xFF2A2A2E);
  static const Color sliderTrackInactive = Color(0xFF38383A);

  static const Color metalHigh = Color(0xFFE6E6E9);
  static const Color metalMid = Color(0xFFA8A8AE);
  static const Color metalLow = Color(0xFF66666D);

  static const Color textPrimary = Color(0xFFF4F4F5);
  static const Color textSecondary = Color(0xFFB3B3B8);
  static const Color textMuted = Color(0xFF77777D);
  static const Color textOnAccent = Color(0xFF111111);
  static const Color error = Color(0xFFEF5350);

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
        primary: coral,
        onPrimary: Colors.white,
        secondary: metalMid,
        onSecondary: Color(0xFF09090B),
        surface: surface,
        onSurface: textPrimary,
        error: error,
        onError: Colors.white,
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
          side: const BorderSide(color: surfaceBorder, width: 1),
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
        activeTrackColor: gold,
        inactiveTrackColor: sliderTrackInactive,
        thumbColor: gold,
        overlayColor: Color(0x33F5A623),
        trackHeight: 4,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return Colors.white;
          return metalMid;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return coral;
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
        actionTextColor: coralStrong,
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
          side: const BorderSide(color: surfaceBorder),
        ),
      ),
    );
  }
}
