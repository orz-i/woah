import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'theme.dart';

/// Platform bars follow the *active* theme. No timer or location is needed.
SystemUiOverlayStyle appSystemUiStyleFor(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  return SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
    statusBarBrightness: dark ? Brightness.dark : Brightness.light,
    systemNavigationBarColor: dark
        ? AppTheme.darkColors.flowBackground
        : AppTheme.lightColors.flowBackground,
    systemNavigationBarIconBrightness: dark
        ? Brightness.light
        : Brightness.dark,
    systemNavigationBarDividerColor: Colors.transparent,
    systemStatusBarContrastEnforced: false,
    systemNavigationBarContrastEnforced: false,
  );
}

/// Backward-compatible dark style used by existing dark-only overlays/tests.
final appSystemUiStyle = appSystemUiStyleFor(Brightness.dark);

/// Status bar remains hidden in the editor; bottom system navigation remains
/// visible and uses icons contrasted to the device's current appearance.
Future<void> applyAppImmersiveMode() async {
  await SystemChrome.setEnabledSystemUIMode(
    SystemUiMode.manual,
    overlays: const [SystemUiOverlay.bottom],
  );
  SystemChrome.setSystemUIOverlayStyle(
    appSystemUiStyleFor(
      WidgetsBinding.instance.platformDispatcher.platformBrightness,
    ),
  );
}
