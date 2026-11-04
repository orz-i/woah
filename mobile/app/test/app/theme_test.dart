import 'package:app/app/system_ui.dart';
import 'package:app/app/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Lime Beat system-dark surfaces retain low luminance', () {
    expect(AppTheme.flowBackground.computeLuminance(), lessThan(0.025));
    expect(AppTheme.flowSurface.computeLuminance(), lessThan(0.04));
    expect(AppTheme.flowSurfaceSoft.computeLuminance(), lessThan(0.06));
    expect(AppTheme.flowBorder.computeLuminance(), lessThan(0.09));

    expect(AppTheme.flowTextPrimary.computeLuminance(), greaterThan(0.8));
    expect(AppTheme.flowTextSecondary.computeLuminance(), greaterThan(0.4));
  });

  test(
    'material defaults use Lime Beat surfaces and lime interaction accent',
    () {
      final theme = AppTheme.darkTheme;

      expect(theme.colorScheme.primary, AppTheme.brandAction);
      expect(theme.colorScheme.onPrimary, AppTheme.brandOnAction);
      expect(theme.colorScheme.onError, AppTheme.brandInk);
      expect(theme.colorScheme.surface, AppTheme.surface);
      expect(
        theme.elevatedButtonTheme.style?.backgroundColor?.resolve(
          <WidgetState>{},
        ),
        AppTheme.surfaceHigh,
      );
      expect(theme.sliderTheme.activeTrackColor, AppTheme.editorSelection);
      expect(theme.sliderTheme.thumbColor, AppTheme.editorSelection);
    },
  );

  test('Lime Beat color roles protect effect colors and contrast', () {
    double contrast(Color x, Color y) {
      final a = x.computeLuminance();
      final b = y.computeLuminance();
      final high = a > b ? a : b;
      final low = a < b ? a : b;
      return (high + 0.05) / (low + 0.05);
    }

    expect(AppTheme.brandAction, const Color(0xFFD8F54C));
    expect(AppTheme.brandOnAction, const Color(0xFF17201C));
    expect(
      contrast(AppTheme.brandOnAction, AppTheme.brandAction),
      greaterThan(7),
    );
    expect(contrast(Colors.white, AppTheme.brandAction), lessThan(4.5));
    expect(
      contrast(AppTheme.flowTextPrimary, AppTheme.flowSurface),
      greaterThan(7),
    );
    expect(
      contrast(AppTheme.flowTextSecondary, AppTheme.flowSurface),
      greaterThan(4.5),
    );
    expect(
      contrast(AppTheme.controlBorder, AppTheme.flowSurface),
      greaterThan(3),
    );
    expect(AppTheme.effectPaletteAmber, const Color(0xFFF5A623));
    expect(AppTheme.effectPaletteAmber, isNot(AppTheme.brandAction));
    expect(AppTheme.editorSelection, AppTheme.brandLime);
    expect(AppTheme.statusError, isNot(AppTheme.brandAction));
    expect(AppTheme.statusWarning, isNot(AppTheme.brandAction));
    final theme = AppTheme.darkTheme;
    expect(
      theme.switchTheme.thumbColor?.resolve({WidgetState.selected}),
      AppTheme.brandOnAction,
    );
    expect(
      theme.switchTheme.trackColor?.resolve({WidgetState.selected}),
      AppTheme.brandAction,
    );
  });

  test(
    'light palette separates lime action fills from readable selected text',
    () {
      double contrast(Color a, Color b) {
        final x = a.computeLuminance();
        final y = b.computeLuminance();
        return ((x > y ? x : y) + .05) / ((x < y ? x : y) + .05);
      }

      final light = AppTheme.lightColors;
      final dark = AppTheme.darkColors;
      expect(AppTheme.lightTheme.brightness, Brightness.light);
      expect(AppTheme.darkTheme.brightness, Brightness.dark);
      expect(light.flowBackground, AppTheme.brandChalk);
      expect(dark.flowBackground, AppTheme.brandInk);
      expect(light.brandAction, AppTheme.brandLime);
      expect(dark.brandAction, AppTheme.brandLime);
      expect(light.textOnAccent, AppTheme.brandInk);
      expect(
        contrast(light.flowTextPrimary, light.flowSurface),
        greaterThan(7),
      );
      expect(
        contrast(light.flowTextSecondary, light.flowSurface),
        greaterThan(4.5),
      );
      expect(
        contrast(light.flowTextMuted, light.flowSurface),
        greaterThan(4.5),
      );
      expect(
        contrast(light.brandAction, light.brandOnAction),
        greaterThan(4.5),
      );
      expect(contrast(light.controlBorder, light.flowSurface), greaterThan(3));
      expect(
        contrast(light.editorSelection, light.flowSurface),
        greaterThan(4.5),
      );
      expect(
        contrast(light.editorSelection, light.flowSurfaceSoft),
        greaterThan(4.5),
      );
      expect(
        contrast(light.editorSelection, light.brandAction),
        greaterThan(3),
      );
      expect(AppTheme.effectPaletteAmber, const Color(0xFFF5A623));
      expect(AppTheme.canvas, Colors.black);
      final theme = AppTheme.lightTheme;
      expect(
        contrast(theme.textSelectionTheme.cursorColor!, light.flowSurface),
        greaterThan(4.5),
      );
      expect(
        contrast(
          theme.inputDecorationTheme.focusedBorder!.borderSide.color,
          light.flowSurface,
        ),
        greaterThan(3),
      );
    },
  );

  test('system bar icons follow the active appearance', () {
    final light = appSystemUiStyleFor(Brightness.light);
    final dark = appSystemUiStyleFor(Brightness.dark);
    expect(light.statusBarIconBrightness, Brightness.dark);
    expect(light.systemNavigationBarIconBrightness, Brightness.dark);
    expect(light.systemNavigationBarColor, AppTheme.lightColors.flowBackground);
    expect(dark.statusBarIconBrightness, Brightness.light);
    expect(dark.systemNavigationBarIconBrightness, Brightness.light);
    expect(dark.systemNavigationBarColor, AppTheme.darkColors.flowBackground);
  });

  test('system chrome is configured for the dark main flow', () {
    expect(appSystemUiStyle.statusBarIconBrightness, Brightness.light);
    expect(appSystemUiStyle.statusBarBrightness, Brightness.dark);
    expect(appSystemUiStyle.systemNavigationBarColor, AppTheme.flowBackground);
    expect(
      appSystemUiStyle.systemNavigationBarIconBrightness,
      Brightness.light,
    );
  });
}
