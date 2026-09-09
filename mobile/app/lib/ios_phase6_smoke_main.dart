import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _Phase6SmokeApp());
}

class _Phase6SmokeApp extends StatefulWidget {
  const _Phase6SmokeApp();

  @override
  State<_Phase6SmokeApp> createState() => _Phase6SmokeAppState();
}

class _Phase6SmokeAppState extends State<_Phase6SmokeApp> {
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
      final phase5Report = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSGoldenTracePhase5Smoke',
      );
      stdout.writeln('WOAH_GOLDEN_TRACE_PHASE5_SMOKE=PASS');
      stdout.writeln('WOAH_GOLDEN_TRACE_PHASE5_REPORT=$phase5Report');
      final phase6Report = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSPrivacyClassPhase6Smoke',
      );
      stdout.writeln('WOAH_PRIVACY_CLASS_PHASE6_SMOKE=PASS');
      stdout.writeln('WOAH_PRIVACY_CLASS_PHASE6_REPORT=$phase6Report');
      await stdout.flush();
      exit(0);
    } catch (error, stackTrace) {
      stderr.writeln('WOAH_PHASE6_SMOKE=FAIL');
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
        body: Center(child: Text('Woah iOS Phase 6 smoke')),
      ),
    );
  }
}
