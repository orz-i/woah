import 'package:app/core/diagnostics/ios_yolo_smoke_contract.dart';
import 'package:flutter_test/flutter_test.dart';

// Contract-parser fixtures only; these are not native inference evidence.
Map<String, dynamic> validReport() => {
  'phase': 'ios_yolo_phase1',
  'fixture': 'yolo_phase1_test_frame.jpg',
  'model_sha256':
      'ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340',
  'fixture_sha256':
      '0ebb17a79fa4ebd0ebcb74ecd71642d74048097a2a05dabadd596d4debb521a1',
  'requested_backend': 'tflite_xnnpack',
  'effective_backend': 'tflite_xnnpack',
  'confidence_threshold': 0.001,
  'fallback_reasons': <String>[],
  'frame_width': 320,
  'frame_height': 180,
  'initialization_ms': 1.0,
  'inference_ms': 2.0,
  'input_shape': [1, 3, 640, 640],
  'output_shapes': [
    [1, 116, 8400],
    [1, 32, 160, 160],
  ],
  'detection_count': 1,
  'detections': [
    {
      'confidence': 0.9,
      'bbox': [0.1, 0.1, 0.9, 0.9],
      'mask_nonzero': 2560,
      'mask_coverage': 0.1,
    },
  ],
};

void main() {
  test('accepts both tensor layouts supported by the real runner', () {
    verifyIOSYoloCpuSmokeReport(validReport());
    final report = validReport();
    report['output_shapes'] = [
      [1, 8400, 116],
      [1, 160, 160, 32],
    ];
    verifyIOSYoloCpuSmokeReport(report);
  });

  test('rejects production or unpinned confidence thresholds', () {
    for (final value in [0.25, 0, -1, double.nan, double.infinity]) {
      final report = validReport()..['confidence_threshold'] = value;
      expect(() => verifyIOSYoloCpuSmokeReport(report), throwsStateError);
    }
  });

  test('rejects absent and incomplete reports', () {
    expect(() => verifyIOSYoloCpuSmokeReport(null), throwsStateError);
    for (final key in validReport().keys) {
      final report = validReport()..remove(key);
      expect(
        () => verifyIOSYoloCpuSmokeReport(report),
        throwsStateError,
        reason: key,
      );
    }
  });

  test('rejects model/fixture drift and unexpected backends', () {
    for (final key in [
      'model_sha256',
      'fixture_sha256',
      'phase',
      'fixture',
      'requested_backend',
      'effective_backend',
    ]) {
      final report = validReport()..[key] = 'different';
      expect(() => verifyIOSYoloCpuSmokeReport(report), throwsStateError);
    }
  });

  test('rejects injected-fixture fallback and fake zero timings', () {
    final report = validReport();
    report['fallback_reasons'] = ['phase7_release_deterministic_fixture'];
    expect(() => verifyIOSYoloCpuSmokeReport(report), throwsStateError);
    for (final value in [0, -1, double.nan, double.infinity]) {
      final timed = validReport()..['inference_ms'] = value;
      expect(() => verifyIOSYoloCpuSmokeReport(timed), throwsStateError);
    }
  });

  test('rejects tensor mismatch and empty detections', () {
    final wrongInput = validReport()..['input_shape'] = [1, 640, 640, 3];
    expect(() => verifyIOSYoloCpuSmokeReport(wrongInput), throwsStateError);
    final empty = validReport()
      ..['detection_count'] = 0
      ..['detections'] = [];
    expect(() => verifyIOSYoloCpuSmokeReport(empty), throwsStateError);
  });

  test('rejects invalid confidence geometry and masks', () {
    final mutations = <String, Object>{
      'confidence': double.nan,
      'bbox': [0.9, 0.1, 0.1, 0.9],
      'mask_nonzero': 0,
      'mask_coverage': 0.5,
    };
    for (final entry in mutations.entries) {
      final report = validReport();
      (report['detections'] as List).first[entry.key] = entry.value;
      expect(() => verifyIOSYoloCpuSmokeReport(report), throwsStateError);
    }
  });
}
