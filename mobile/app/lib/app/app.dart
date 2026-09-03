import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'router.dart';
import 'system_ui.dart';
import 'theme.dart';

class DanceAnonymizerApp extends ConsumerStatefulWidget {
  const DanceAnonymizerApp({super.key});

  @override
  ConsumerState<DanceAnonymizerApp> createState() => _DanceAnonymizerAppState();
}

class _DanceAnonymizerAppState extends ConsumerState<DanceAnonymizerApp>
    with WidgetsBindingObserver {
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
