import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app/app.dart';
import 'app/system_ui.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await applyAppImmersiveMode();
  runApp(const ProviderScope(child: DanceAnonymizerApp()));
}
