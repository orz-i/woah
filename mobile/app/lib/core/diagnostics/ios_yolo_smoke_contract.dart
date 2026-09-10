/// Assertions for the diagnostic-only, real iOS YOLO CPU probe.
/// These checks are not a visual-privacy or physical-device acceptance gate.
void verifyIOSYoloCpuSmokeReport(Map<Object?, Object?>? report) {
  Never reject(String field) =>
      throw StateError('Invalid YOLO CPU probe: $field');

  if (report == null) reject('missing report');
  const modelSha =
      'ea5d150036c7fe0a77231f3d8fea7b96fc7816cd93c8af0a9edfbbd80ad9a340';
  const fixtureSha =
      '0ebb17a79fa4ebd0ebcb74ecd71642d74048097a2a05dabadd596d4debb521a1';
  if (report['phase'] != 'ios_yolo_phase1' ||
      report['fixture'] != 'yolo_phase1_test_frame.jpg' ||
      report['model_sha256'] != modelSha ||
      report['fixture_sha256'] != fixtureSha) {
    reject('model/fixture identity');
  }
  if (report['requested_backend'] != 'tflite_xnnpack' ||
      report['effective_backend'] != 'tflite_xnnpack') {
    reject('CPU backend');
  }
  final fallbacks = report['fallback_reasons'];
  if (fallbacks is! List || fallbacks.isNotEmpty) reject('fallbacks');

  bool shape(Object? value, List<int> expected) =>
      value is List &&
      value.length == expected.length &&
      List.generate(
        expected.length,
        (i) => value[i] == expected[i],
      ).every((matches) => matches);
  final outputs = report['output_shapes'];
  if (!shape(report['input_shape'], [1, 3, 640, 640]) ||
      outputs is! List ||
      outputs.length != 2 ||
      !(shape(outputs[0], [1, 116, 8400]) ||
          shape(outputs[0], [1, 8400, 116])) ||
      !(shape(outputs[1], [1, 32, 160, 160]) ||
          shape(outputs[1], [1, 160, 160, 32]))) {
    reject('tensor shapes');
  }
  for (final key in ['frame_width', 'frame_height']) {
    final value = report[key];
    if (value is! int || value <= 0) reject(key);
  }
  for (final key in ['initialization_ms', 'inference_ms']) {
    final value = report[key];
    if (value is! num || !value.isFinite || value <= 0) reject(key);
  }
  final detections = report['detections'];
  if (detections is! List ||
      detections.isEmpty ||
      report['detection_count'] != detections.length) {
    reject('detections');
  }
  for (final detection in detections) {
    if (detection is! Map) reject('detection record');
    final confidence = detection['confidence'];
    final nonzero = detection['mask_nonzero'];
    final coverage = detection['mask_coverage'];
    final box = detection['bbox'];
    if (confidence is! num ||
        !confidence.isFinite ||
        confidence <= 0 ||
        confidence > 1 ||
        nonzero is! int ||
        nonzero <= 0 ||
        nonzero > 25600 ||
        coverage is! num ||
        !coverage.isFinite ||
        (coverage - nonzero / 25600).abs() > 1e-8 ||
        box is! List ||
        box.length != 4 ||
        box.any((v) => v is! num || !v.isFinite || v < 0 || v > 1) ||
        (box[0] as num) >= (box[2] as num) ||
        (box[1] as num) >= (box[3] as num)) {
      reject('confidence/bounding box/mask');
    }
  }
}
