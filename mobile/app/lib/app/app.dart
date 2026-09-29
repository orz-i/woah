import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'router.dart';
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
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'Woah',
      theme: AppTheme.darkTheme,
      routerConfig: appRouter,
      debugShowCheckedModeBanner: false,
    );
  }
}
