import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _Phase3SmokeApp());
}

class _Phase3SmokeApp extends StatefulWidget {
  const _Phase3SmokeApp();

  @override
  State<_Phase3SmokeApp> createState() => _Phase3SmokeAppState();
}

class _Phase3SmokeAppState extends State<_Phase3SmokeApp> {
  static const _channel = MethodChannel('dance_native');

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _run());
  }

  Future<void> _run() async {
    try {
      final report = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSMetalPhase3Smoke',
      );
      stdout.writeln('WOAH_METAL_PHASE3_SMOKE=PASS');
      stdout.writeln('WOAH_METAL_PHASE3_REPORT=$report');
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
      home: Scaffold(body: Center(child: Text('Woah iOS Phase 3 smoke'))),
    );
  }
}
