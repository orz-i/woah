import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'core/diagnostics/ios_yolo_smoke_contract.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _Phase7SmokeApp());
}

class _Phase7SmokeApp extends StatefulWidget {
  const _Phase7SmokeApp();

  @override
  State<_Phase7SmokeApp> createState() => _Phase7SmokeAppState();
}

class _Phase7SmokeAppState extends State<_Phase7SmokeApp> {
  static const _channel = MethodChannel('dance_native');

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _run());
  }

  Future<void> _run() async {
    try {
      final yoloReports = <Map<String, dynamic>>[];
      for (var invocation = 0; invocation < 2; invocation++) {
        final report = await _channel.invokeMapMethod<String, dynamic>(
          'runIOSYoloPhase1BundledProbe',
          {'backend': 'tflite_xnnpack'},
        );
        verifyIOSYoloCpuSmokeReport(report);
        yoloReports.add(report!);
      }
      stdout.writeln('WOAH_YOLO_CPU_REPORT=${jsonEncode(yoloReports)}');
      stdout.writeln('WOAH_YOLO_CPU_SMOKE=PASS');
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
      final phase7Report = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSReleasePhase7Smoke',
      );
      stdout.writeln('WOAH_RELEASE_PHASE7_SMOKE=PASS');
      stdout.writeln('WOAH_RELEASE_PHASE7_REPORT=$phase7Report');
      await stdout.flush();
      exit(0);
    } catch (error, stackTrace) {
      stderr.writeln('WOAH_PHASE7_SMOKE=FAIL');
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
        body: Center(child: Text('Woah iOS Phase 7 release smoke')),
      ),
    );
  }
}
