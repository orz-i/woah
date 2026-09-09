import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _Phase4SmokeApp());
}

class _Phase4SmokeApp extends StatefulWidget {
  const _Phase4SmokeApp();

  @override
  State<_Phase4SmokeApp> createState() => _Phase4SmokeAppState();
}

class _Phase4SmokeAppState extends State<_Phase4SmokeApp> {
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
      stderr.writeln('WOAH_PHASE4_SMOKE=FAIL');
      stderr.writeln(error);
      stderr.writeln(stackTrace);
      await stderr.flush();
      exit(1);
    }
  }

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: Scaffold(body: Center(child: Text('Woah iOS Phase 4 smoke'))),
    );
  }
}
