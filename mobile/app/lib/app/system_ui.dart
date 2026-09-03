import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'theme.dart';

const appSystemUiStyle = SystemUiOverlayStyle(
  statusBarColor: Colors.transparent,
  statusBarIconBrightness: Brightness.dark,
  statusBarBrightness: Brightness.light,
  systemNavigationBarColor: AppTheme.warmBackground,
  systemNavigationBarIconBrightness: Brightness.dark,
  systemNavigationBarDividerColor: Colors.transparent,
  systemStatusBarContrastEnforced: false,
  systemNavigationBarContrastEnforced: false,
);

Future<void> applyAppImmersiveMode() async {
  await SystemChrome.setEnabledSystemUIMode(
    SystemUiMode.manual,
    overlays: const [SystemUiOverlay.bottom],
  );
  SystemChrome.setSystemUIOverlayStyle(appSystemUiStyle);
}
