import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _MetalSmokeApp());
}

class _MetalSmokeApp extends StatefulWidget {
  const _MetalSmokeApp();

  @override
  State<_MetalSmokeApp> createState() => _MetalSmokeAppState();
}

class _MetalSmokeAppState extends State<_MetalSmokeApp> {
  static const _channel = MethodChannel('dance_native');

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _run());
  }

  Future<void> _run() async {
    try {
      final metalReport = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSMetalPhase3Smoke',
      );
      stdout.writeln('WOAH_METAL_PHASE3_SMOKE=PASS');
      stdout.writeln('WOAH_METAL_PHASE3_REPORT=$metalReport');
      final exportReport = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSExportPhase4Smoke',
      );
      stdout.writeln('WOAH_EXPORT_PHASE4_SMOKE=PASS');
      stdout.writeln('WOAH_EXPORT_PHASE4_REPORT=$exportReport');
      await stdout.flush();
      exit(0);
    } catch (error, stackTrace) {
      stderr.writeln('WOAH_METAL_PHASE3_SMOKE=FAIL');
      stderr.writeln(error);
      stderr.writeln(stackTrace);
      await stderr.flush();
      exit(1);
    }
  }

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: Scaffold(
        body: Center(child: Text('Woah iOS Metal Phase 3 smoke')),
      ),
    );
  }
}
