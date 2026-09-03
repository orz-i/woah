import 'package:flutter/material.dart';

/// Woah V2 visual system.
///
/// The product intentionally stays monochrome: media is the visual focus while
/// chrome uses restrained black, graphite, silver and white surfaces. Semantic
/// red is kept only for destructive/error states.
class AppTheme {
  static const Color background = Color(0xFF050506);
  static const Color canvas = Color(0xFF09090B);
  static const Color surface = Color(0xFF111113);
  static const Color surfaceElevated = Color(0xFF19191C);
  static const Color surfaceHigh = Color(0xFF222225);
  static const Color surfaceBorder = Color(0xFF303034);

  static const Color primaryWhite = Color(0xFFF4F4F5);
  static const Color metalHigh = Color(0xFFE6E6E9);
  static const Color metalMid = Color(0xFFA8A8AE);
  static const Color metalLow = Color(0xFF66666D);

  static const Color textPrimary = Color(0xFFF4F4F5);
  static const Color textSecondary = Color(0xFFB3B3B8);
  static const Color textMuted = Color(0xFF77777D);
  static const Color error = Color(0xFFEF5350);

  static const double radiusSmall = 10;
  static const double radiusMedium = 14;
  static const double radiusLarge = 20;
  static const double radiusSheet = 24;
  static const double minTouchTarget = 48;

  static const LinearGradient metalGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFFF3F3F4), Color(0xFFB8B8BD), Color(0xFFE7E7E9)],
    stops: [0.0, 0.48, 1.0],
  );

  static BoxDecoration metalButtonDecoration({double radius = radiusMedium}) {
    return BoxDecoration(
      gradient: metalGradient,
      borderRadius: BorderRadius.circular(radius),
      border: Border.all(color: Colors.white.withAlpha(120), width: 0.8),
      boxShadow: const [
        BoxShadow(
          color: Color(0x28000000),
          blurRadius: 14,
          offset: Offset(0, 6),
        ),
      ],
    );
  }

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
        primary: primaryWhite,
        onPrimary: Color(0xFF09090B),
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
          backgroundColor: primaryWhite,
          foregroundColor: canvas,
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
      sliderTheme: SliderThemeData(
        activeTrackColor: metalHigh,
        inactiveTrackColor: surfaceHigh,
        thumbColor: primaryWhite,
        overlayColor: Colors.white.withAlpha(24),
        trackHeight: 3,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return canvas;
          return metalMid;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return metalHigh;
          return surfaceHigh;
        }),
        trackOutlineColor: WidgetStateProperty.all(surfaceBorder),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: surfaceHigh,
        contentTextStyle: const TextStyle(color: textPrimary, fontSize: 13),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusMedium),
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
