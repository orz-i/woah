import 'package:app/app/system_ui.dart';
import 'package:app/app/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('main-flow surfaces stay in the Quiet Frame dark luminance family', () {
    expect(AppTheme.flowBackground.computeLuminance(), lessThan(0.01));
    expect(AppTheme.flowSurface.computeLuminance(), lessThan(0.02));
    expect(AppTheme.flowSurfaceSoft.computeLuminance(), lessThan(0.03));
    expect(AppTheme.flowBorder.computeLuminance(), lessThan(0.07));

    expect(AppTheme.flowTextPrimary.computeLuminance(), greaterThan(0.8));
    expect(AppTheme.flowTextSecondary.computeLuminance(), greaterThan(0.4));
  });

  test(
    'material defaults use Quiet Frame surfaces and mint interaction accent',
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

  test('Quiet Frame color roles protect effect colors and contrast', () {
    double contrast(Color x, Color y) {
      final a = x.computeLuminance();
      final b = y.computeLuminance();
      final high = a > b ? a : b;
      final low = a < b ? a : b;
      return (high + 0.05) / (low + 0.05);
    }

    expect(AppTheme.brandAction, const Color(0xFF78DCC7));
    expect(AppTheme.brandOnAction, const Color(0xFF0B1415));
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
    expect(AppTheme.editorSelection, AppTheme.brandMint);
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
