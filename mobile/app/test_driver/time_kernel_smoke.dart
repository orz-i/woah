import 'dart:convert';
import 'dart:io';
import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';

/// Real mobile Dart -> C ABI lookup. No channel mocks or independent math oracle.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final result = <String, Object?>{'platform': Platform.operatingSystem};
  try {
    final mapping = ComparisonMusicMapping.anchors(
      1000000,
      2000000,
      11000000,
      10000000,
    );
    if (mapping.timeScale != .8 ||
        mapping.offsetUs != 1200000 ||
        mapping.target(10000000) != 9200000 ||
        const ComparisonMusicMapping(0, 2).overlap(20, 11) != (0, 5) ||
        const ComparisonMusicMapping(-2, .5).target(1) != -1) {
      throw StateError('Shared timeline contract mismatch');
    }
    final inverse = mapping.inverse;
    if (inverse.target(9200000) != 10000000) {
      throw StateError('Inverse mismatch');
    }
    result['passed'] = true;
  } catch (error, stack) {
    result.addAll({'passed': false, 'error': '$error', 'stack': '$stack'});
  }
  final json = jsonEncode(result);
  await File(
    '${Directory.systemTemp.path}/time-kernel-smoke.json',
  ).writeAsString(json, flush: true);
  debugPrint('FRAME_KERNEL_TIME_REPORT=$json');
  runApp(
    MaterialApp(
      home: Scaffold(body: Center(child: Text(json))),
    ),
  );
}
