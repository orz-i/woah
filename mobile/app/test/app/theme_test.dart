import 'package:app/app/system_ui.dart';
import 'package:app/app/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('main-flow surfaces stay in the dark graphite luminance family', () {
    expect(AppTheme.flowBackground.computeLuminance(), lessThan(0.01));
    expect(AppTheme.flowSurface.computeLuminance(), lessThan(0.02));
    expect(AppTheme.flowSurfaceSoft.computeLuminance(), lessThan(0.03));
    expect(AppTheme.flowBorder.computeLuminance(), lessThan(0.05));

    expect(AppTheme.flowTextPrimary.computeLuminance(), greaterThan(0.8));
    expect(AppTheme.flowTextSecondary.computeLuminance(), greaterThan(0.4));
  });

  test(
    'material defaults use graphite chrome with coral interaction accent',
    () {
      final theme = AppTheme.darkTheme;

      expect(theme.colorScheme.primary, AppTheme.coral);
      expect(theme.colorScheme.surface, AppTheme.surface);
      expect(
        theme.elevatedButtonTheme.style?.backgroundColor?.resolve(
          <WidgetState>{},
        ),
        AppTheme.surfaceHigh,
      );
      expect(theme.sliderTheme.activeTrackColor, AppTheme.coral);
      expect(theme.sliderTheme.thumbColor, AppTheme.coral);
    },
  );

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
