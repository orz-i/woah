import 'dart:convert';
import 'dart:io';

import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';

/// Simulator capability diagnostic only. The existing strict-backend video
/// probe does not exercise the selected-mask/identity pipeline, and simulator
/// results must never promote an iPhone production backend.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(home: Scaffold(body: Text('iOS backend QA'))));
  final root = Directory(
    '${Directory.systemTemp.parent.path}/Documents/comparison-cutout-fixtures',
  )..createSync(recursive: true);
  final video = File('${root.path}/source.mp4');
  final output = File('${root.path}/ios-backends-report.json');
  final rows = <Map<String, Object?>>[];
  void save(bool finished) => output.writeAsStringSync(
    jsonEncode({'finished': finished, 'simulatorOnly': true, 'rows': rows}),
    flush: true,
  );
  final client = DanceNativeClient();
  const backends = ['tflite_xnnpack', 'tflite_metal', 'tflite_coreml'];
  for (final backend in backends) {
    final row = <String, Object?>{'requested': backend};
    rows.add(row);
    save(false);
    try {
      final samples = <Map<String, Object?>>[];
      for (final timestamp in [0, 400, 800]) {
        final watch = Stopwatch()..start();
        final report = await client
            .runIOSYoloPhase1Probe(
              videoUri: video.path,
              timestampMs: timestamp,
              backend: backend,
            )
            .timeout(const Duration(seconds: 45));
        if (report == null) throw StateError('Missing native report');
        samples.add({
          'timestampMs': timestamp,
          'actualTimestampMs': report['actual_timestamp_ms'],
          'effective': report['effective_backend'],
          'initializationMs': report['initialization_ms'],
          'inferenceMs': report['inference_ms'],
          'wallMs': watch.elapsedMilliseconds,
          'detections': report['detections'],
        });
        row['samples'] = samples;
        save(false);
      }
      row['status'] = samples.every((s) => s['effective'] == backend)
          ? 'complete'
          : 'fallback';
    } catch (error) {
      row['status'] = 'failed';
      row['errorClass'] = error.runtimeType.toString();
    }
    save(false);
  }
  save(true);
}
