import 'package:flutter/material.dart';
import 'palette.dart';

/// Woah (舞哇) / Lime Beat product color roles.
///
/// Brand/action colors intentionally do not alias user-configurable render
/// effects. Person selection follows the segmentation mask geometry; neither
/// selection nor a brand tint certifies that privacy protection is complete.
class AppTheme {
  // Lime Beat palette, sRGB. The W uses Ink / Lime with the same geometry.
  static const Color brandLime = Color(0xFFD8F54C);
  static const Color brandLavender = Color(0xFFDCD7FA);
  static const Color brandChalk = Color(0xFFF7F9F1);
  static const Color brandOlive = Color(0xFF50670E);
  static const Color brandInk = Color(0xFF17201C);
  static const Color brandSurface = Color(0xFF222C24);
  static const Color brandPearl = brandChalk;
  static const Color brandSecondary = Color(0xFFC4CEBC);

  // Semantic roles: these may share a value, but do not share a meaning.
  static const Color brandAction = brandLime;
  static const Color brandOnAction = brandInk;
  static const Color brandActionPressed = Color(0xFFC3DF36);
  static const Color editorSelection = brandLime;
  static const Color editorSelectionSubtle = Color(0x26D8F54C);
  static const Color controlBorder = Color(0xFF7B8874);
  static const Color decorativeDivider = Color(0xFF3A493B);
  static const Color statusWarning = Color(0xFFF0C879);
  static const Color statusWarningSubtle = Color(0x20F0C879);
  static const Color statusError = Color(0xFFFF9C9F);
  static const Color statusSuccess = Color(0xFF9DD6B9);

  // Main workflow chrome (import, protection, comparison, export, results).
  static const Color flowBackground = brandInk;
  static const Color flowSurface = brandSurface;
  static const Color flowSurfaceSoft = Color(0xFF2B352B);
  static const Color flowTextPrimary = brandPearl;
  static const Color flowTextSecondary = brandSecondary;
  static const Color flowTextMuted = Color(0xFFA3B297);
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
    colors: [brandActionPressed, brandAction, brandLime],
  );
  static const LinearGradient goldActionGradient = brandActionGradient;
  static const LinearGradient coralActionGradient = brandActionGradient;

  static const LinearGradient mediaPickerGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [surfaceHigh, flowSurfaceSoft, flowSurface],
    stops: [0.0, 0.48, 1.0],
  );

  // Video remains pure black; only the interface adopts the brand palette.
  static const Color background = brandInk;
  static const Color canvas = Color(0xFF000000);
  static const Color surface = brandSurface;
  static const Color surfaceElevated = flowSurfaceSoft;
  static const Color surfaceHigh = Color(0xFF354130);
  static const Color surfacePill = Color(0xFF40503A);
  static const Color surfaceBorder = decorativeDivider;
  static const Color sliderTrackInactive = Color(0xFF56644C);

  static const Color metalHigh = brandPearl;
  static const Color metalMid = brandSecondary;
  static const Color metalLow = flowTextMuted;

  static const Color textPrimary = brandPearl;
  static const Color textSecondary = brandSecondary;
  static const Color textMuted = flowTextMuted;
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

  /// System-dark companion; it uses the same lime accent as the light palette.
  static const darkColors = WoahPalette(
    background: background,
    flowBackground: flowBackground,
    flowSurface: flowSurface,
    flowSurfaceSoft: flowSurfaceSoft,
    flowTextPrimary: flowTextPrimary,
    flowTextSecondary: flowTextSecondary,
    flowTextMuted: flowTextMuted,
    flowBorder: flowBorder,
    surfaceElevated: surfaceElevated,
    surfaceHigh: surfaceHigh,
    surfaceBorder: surfaceBorder,
    sliderTrackInactive: sliderTrackInactive,
    brandAction: brandAction,
    brandOnAction: brandOnAction,
    editorSelection: editorSelection,
    editorSelectionSubtle: editorSelectionSubtle,
    controlBorder: controlBorder,
    textPrimary: textPrimary,
    textSecondary: textSecondary,
    textMuted: textMuted,
    textOnAccent: textOnAccent,
    error: error,
    statusWarning: statusWarning,
    statusWarningSubtle: statusWarningSubtle,
    statusSuccess: statusSuccess,
  );

  /// Bright rehearsal-room palette. Lime fills use ink labels; small selected
  /// text and control outlines use olive to remain readable on light surfaces.
  static const lightColors = WoahPalette(
    background: brandChalk,
    flowBackground: brandChalk,
    flowSurface: Color(0xFFFFFFFF),
    flowSurfaceSoft: Color(0xFFEEF1E5),
    flowTextPrimary: brandInk,
    flowTextSecondary: Color(0xFF46513F),
    flowTextMuted: Color(0xFF5D6B53),
    flowBorder: Color(0xFFCDD4BF),
    surfaceElevated: Color(0xFFFFFFFF),
    surfaceHigh: Color(0xFFEAE5F4),
    surfaceBorder: Color(0xFFCDD4BF),
    sliderTrackInactive: Color(0xFFA5B194),
    brandAction: brandLime,
    brandOnAction: brandInk,
    editorSelection: brandOlive,
    editorSelectionSubtle: Color(0x2450670E),
    controlBorder: Color(0xFF768369),
    textPrimary: brandInk,
    textSecondary: Color(0xFF46513F),
    textMuted: Color(0xFF5D6B53),
    textOnAccent: brandInk,
    error: Color(0xFFAD3044),
    statusWarning: Color(0xFF865A17),
    statusWarningSubtle: Color(0x22865A17),
    statusSuccess: Color(0xFF216A5E),
  );

  /// Reading the inherited Theme ensures screens update on an OS appearance
  /// change, including when a media editor is already open.
  static WoahPalette of(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark
      ? darkColors
      : lightColors;

  static ThemeData get darkTheme => _buildTheme(darkColors, Brightness.dark);

  static ThemeData get lightTheme => _buildTheme(lightColors, Brightness.light);

  static ThemeData _buildTheme(WoahPalette c, Brightness brightness) {
    final base = ThemeData(
      useMaterial3: true,
      brightness: brightness,
      scaffoldBackgroundColor: c.background,
      colorScheme: ColorScheme(
        brightness: brightness,
        primary: c.brandAction,
        onPrimary: c.brandOnAction,
        secondary: c.flowTextSecondary,
        onSecondary: c.background,
        secondaryContainer: brightness == Brightness.light
            ? brandLavender
            : const Color(0xFF3B354C),
        onSecondaryContainer: c.textPrimary,
        surface: c.flowSurface,
        onSurface: c.textPrimary,
        error: c.error,
        onError: c.background,
      ),
    );

    return base.copyWith(
      // Primary is a bright fill. Focus indicators and cursors need the
      // readable selection role on pale form surfaces instead.
      textSelectionTheme: TextSelectionThemeData(
        cursorColor: c.editorSelection,
        selectionColor: c.editorSelectionSubtle,
        selectionHandleColor: c.editorSelection,
      ),
      inputDecorationTheme: InputDecorationThemeData(
        focusedBorder: UnderlineInputBorder(
          borderSide: BorderSide(color: c.editorSelection, width: 2),
        ),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: c.editorSelection,
      ),
      textTheme: base.textTheme.copyWith(
        headlineLarge: TextStyle(
          color: c.textPrimary,
          fontSize: 36,
          height: 1.05,
          fontWeight: FontWeight.w700,
          letterSpacing: -1.2,
        ),
        headlineSmall: TextStyle(
          color: c.textPrimary,
          fontSize: 22,
          height: 1.2,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.4,
        ),
        titleLarge: TextStyle(
          color: c.textPrimary,
          fontSize: 18,
          height: 1.25,
          fontWeight: FontWeight.w600,
        ),
        titleMedium: TextStyle(
          color: c.textPrimary,
          fontSize: 16,
          height: 1.3,
          fontWeight: FontWeight.w600,
        ),
        bodyLarge: TextStyle(color: c.textPrimary, fontSize: 15, height: 1.45),
        bodyMedium: TextStyle(
          color: c.textSecondary,
          fontSize: 14,
          height: 1.45,
        ),
        bodySmall: TextStyle(color: c.textMuted, fontSize: 12, height: 1.4),
        labelLarge: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w600,
          letterSpacing: 0,
        ),
      ),
      cardTheme: CardThemeData(
        color: c.surfaceElevated,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusMedium),
          side: BorderSide(color: c.controlBorder, width: 1),
        ),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: c.background,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
        iconTheme: IconThemeData(color: c.textPrimary),
        titleTextStyle: TextStyle(
          color: c.textPrimary,
          fontSize: 16,
          fontWeight: FontWeight.w600,
          letterSpacing: -0.1,
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          minimumSize: const Size(minTouchTarget, minTouchTarget),
          backgroundColor: c.surfaceHigh,
          foregroundColor: c.textPrimary,
          disabledBackgroundColor: c.surfaceHigh,
          disabledForegroundColor: c.textMuted,
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
          foregroundColor: c.textPrimary,
          side: BorderSide(color: c.controlBorder, width: 1),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusMedium),
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size(minTouchTarget, minTouchTarget),
          foregroundColor: c.textPrimary,
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          minimumSize: const Size(minTouchTarget, minTouchTarget),
          foregroundColor: c.textPrimary,
        ),
      ),
      dividerTheme: DividerThemeData(
        color: c.surfaceBorder,
        thickness: 1,
        space: 24,
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: c.editorSelection,
        inactiveTrackColor: c.sliderTrackInactive,
        thumbColor: c.editorSelection,
        overlayColor: c.editorSelectionSubtle,
        trackHeight: 4,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return c.brandOnAction;
          return c.textMuted;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return c.brandAction;
          return c.surfaceHigh;
        }),
        trackOutlineColor: WidgetStateProperty.resolveWith((states) {
          return states.contains(WidgetState.selected)
              ? c.editorSelection
              : c.controlBorder;
        }),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: c.flowSurface,
        contentTextStyle: TextStyle(
          color: c.flowTextPrimary,
          fontSize: 13,
          fontWeight: FontWeight.w500,
        ),
        actionTextColor: c.editorSelection,
        elevation: 8,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusMedium),
          side: BorderSide(color: c.flowBorder),
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: c.flowSurface,
        modalBackgroundColor: c.flowSurface,
        surfaceTintColor: Colors.transparent,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: c.surfaceElevated,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusLarge),
          side: BorderSide(color: c.controlBorder),
        ),
      ),
    );
  }
}
