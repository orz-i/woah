import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _NativeRegressionApp());
}

class _NativeRegressionApp extends StatefulWidget {
  const _NativeRegressionApp();

  @override
  State<_NativeRegressionApp> createState() => _NativeRegressionAppState();
}

class _NativeRegressionAppState extends State<_NativeRegressionApp> {
  static const _channel = MethodChannel('dance_native');

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _run());
  }

  Future<void> _run() async {
    try {
      final metalReport = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSMetalSmoke',
      );
      stdout.writeln('WOAH_METAL_SMOKE=PASS');
      stdout.writeln('WOAH_METAL_REPORT=$metalReport');
      final exportReport = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSExportSmoke',
      );
      stdout.writeln('WOAH_EXPORT_SMOKE=PASS');
      stdout.writeln('WOAH_EXPORT_REPORT=$exportReport');
      final trackingReport = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSGoldenTraceSmoke',
      );
      stdout.writeln('WOAH_GOLDEN_TRACE_SMOKE=PASS');
      stdout.writeln('WOAH_GOLDEN_TRACE_REPORT=$trackingReport');
      final privacyClassReport = await _channel
          .invokeMapMethod<String, dynamic>('runIOSPrivacyClassSmoke');
      stdout.writeln('WOAH_PRIVACY_CLASS_SMOKE=PASS');
      stdout.writeln('WOAH_PRIVACY_CLASS_REPORT=$privacyClassReport');
      final cacheReport = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSCacheLifecycleSmoke',
      );
      stdout.writeln('WOAH_CACHE_LIFECYCLE_SMOKE=PASS');
      stdout.writeln('WOAH_CACHE_LIFECYCLE_REPORT=$cacheReport');
      final diagnosticReport = await _channel.invokeMapMethod<String, dynamic>(
        'runIOSDiagnosticPrivacySmoke',
      );
      stdout.writeln('WOAH_DIAGNOSTIC_PRIVACY_SMOKE=PASS');
      stdout.writeln('WOAH_DIAGNOSTIC_PRIVACY_REPORT=$diagnosticReport');
      await stdout.flush();
      exit(0);
    } catch (error, stackTrace) {
      stderr.writeln('WOAH_NATIVE_REGRESSION=FAIL');
      stderr.writeln(error);
      stderr.writeln(stackTrace);
      await stderr.flush();
      exit(1);
    }
  }

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: Scaffold(body: Center(child: Text('Woah iOS native regression'))),
    );
  }
}
