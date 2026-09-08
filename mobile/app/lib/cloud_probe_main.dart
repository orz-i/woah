import 'dart:convert';

import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';

const _backends = <String>[
  'auto',
  'tflite_coreml',
  'tflite_metal',
  'tflite_xnnpack',
];

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _CloudProbeApp());
}

class _CloudProbeApp extends StatefulWidget {
  const _CloudProbeApp();

  @override
  State<_CloudProbeApp> createState() => _CloudProbeAppState();
}

class _CloudProbeAppState extends State<_CloudProbeApp> {
  static const _reportLabel = 'woah-ios-phase1-cloud-report';
  String _status = 'WOAH_PHASE1_RUNNING';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _run());
  }

  Future<void> _run() async {
    final client = DanceNativeClient();
    final reports = <Map<String, Object?>>[];
    try {
      for (final backend in _backends) {
        try {
          final result = await client.runIOSYoloPhase1BundledProbe(
            backend: backend,
          );
          reports.add({
            'backend': backend,
            'ok': true,
            'report': _jsonSafe(result),
          });
        } catch (error) {
          reports.add({
            'backend': backend,
            'ok': false,
            'error': error.toString(),
          });
        }
      }
      final payload = jsonEncode({
        'schema_version': 1,
        'phase': 'ios_yolo_phase1_cloud',
        'reports': reports,
      });
      if (!mounted) return;
      setState(() => _status = 'WOAH_PHASE1_REPORT:$payload');
    } catch (error) {
      if (!mounted) return;
      setState(() => _status = 'WOAH_PHASE1_ERROR:${error.toString()}');
    } finally {
      client.dispose();
    }
  }

  Object? _jsonSafe(Object? value) {
    if (value is Map) {
      return <String, Object?>{
        for (final entry in value.entries)
          entry.key.toString(): _jsonSafe(entry.value),
      };
    }
    if (value is Iterable) {
      return value.map(_jsonSafe).toList(growable: false);
    }
    if (value == null || value is num || value is bool || value is String) {
      return value;
    }
    return value.toString();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: Center(
          child: Semantics(
            container: true,
            label: _reportLabel,
            value: _status,
            child: ExcludeSemantics(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  _status.startsWith('WOAH_PHASE1_RUNNING')
                      ? 'Running iOS Phase 1 device probe…'
                      : 'iOS Phase 1 device probe complete',
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
