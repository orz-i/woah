import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'router.dart';
import 'brand.dart';
import 'system_ui.dart';
import 'theme.dart';

class WoahApp extends ConsumerStatefulWidget {
  const WoahApp({super.key});

  @override
  ConsumerState<WoahApp> createState() => _WoahAppState();
}

class _WoahAppState extends ConsumerState<WoahApp> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      applyAppImmersiveMode();
    }
  }

  @override
  void didChangePlatformBrightness() {
    // MaterialApp's ThemeMode.system rebuilds its inherited Theme on its own.
    // System navigation icons are managed outside Flutter's widget tree.
    SystemChrome.setSystemUIOverlayStyle(
      appSystemUiStyleFor(
        WidgetsBinding.instance.platformDispatcher.platformBrightness,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: AppBrand.english,
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: ThemeMode.system,
      routerConfig: appRouter,
      debugShowCheckedModeBanner: false,
    );
  }
}
