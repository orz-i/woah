import 'package:flutter_test/flutter_test.dart';
import 'package:app/features/export/domain/export_state.dart';

void main() {
  group('ExportState Domain Tests', () {
    test('ExportJobState fromString parses correctly', () {
      expect(ExportJobState.fromString('preparing'), equals(ExportJobState.preparing));
      expect(ExportJobState.fromString('processing'), equals(ExportJobState.processing));
      expect(ExportJobState.fromString('muxing'), equals(ExportJobState.muxing));
      expect(ExportJobState.fromString('completed'), equals(ExportJobState.completed));
      expect(ExportJobState.fromString('failed'), equals(ExportJobState.failed));
      expect(ExportJobState.fromString('cancelled'), equals(ExportJobState.cancelled));
      expect(ExportJobState.fromString('unknown_state'), equals(ExportJobState.processing));
    });

    test('ExportState flags isProcessing and isCompleted', () {
      const state1 = ExportState(
        status: ExportJobState.processing,
        progress: 0.45,
        currentFrame: 135,
        totalFrames: 300,
        fps: 8.5,
      );

      expect(state1.isProcessing, isTrue);
      expect(state1.isCompleted, isFalse);

      const state2 = ExportState(
        status: ExportJobState.completed,
        outputUri: '/tmp/final_output.mp4',
        progress: 1.0,
      );

      expect(state2.isProcessing, isFalse);
      expect(state2.isCompleted, isTrue);
    });

    test('ExportState defaults showLivePreview to false and updates with preview path', () {
      const defaultState = ExportState();
      expect(defaultState.showLivePreview, isFalse);
      expect(defaultState.currentPreviewPath, isNull);

      final updatedState = defaultState.copyWith(
        showLivePreview: true,
        currentPreviewPath: '/cache/export_live_preview/preview_job_1.jpg',
      );

      expect(updatedState.showLivePreview, isTrue);
      expect(updatedState.currentPreviewPath, equals('/cache/export_live_preview/preview_job_1.jpg'));
    });
  });
}

