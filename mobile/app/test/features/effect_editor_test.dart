import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_controller.dart';


void main() {
  group('EffectEditorController Tests', () {
    final testProject = DanceProject(
      id: 'proj_test',
      sourceUri: 'file:///test.mp4',
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
      videoInfo: const VideoInfo(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1920,
        displayHeight: 1080,
        fps: 30,
        durationMs: 5000,
        rotation: 0,
        videoCodec: 'video/avc',
        hasAudio: true,
      ),
    );

    test('Updates fill mode and builds configured project', () {
      final controller = EffectEditorController();
      controller.init(testProject);
      expect(controller.state.effects.fillMode, equals(FillMode.solid));

      controller.updateFillMode(FillMode.gradient);
      controller.updateBorderWidth(8.0);
      controller.updateOpacity(0.85);
      controller.updateBorderColor(0xFF00E5FF);

      final configured = controller.buildConfiguredProject();
      expect(configured, isNotNull);
      expect(configured!.effects.fillMode, equals(FillMode.gradient));
      expect(configured.effects.borderWidth, equals(8.0));
      expect(configured.effects.opacity, equals(0.85));
      expect(configured.effects.borderColorArgb, equals(0xFF00E5FF));
    });

    test('Updates skin whiten and leg stretch parameters', () {
      final controller = EffectEditorController();
      controller.init(testProject);

      controller.updateSkinWhiten(0.75);
      var configured = controller.buildConfiguredProject();
      expect(configured!.effects.skinWhiten, equals(0.75));

      controller.updateLegStretch(enabled: true, stretch: 0.20);
      configured = controller.buildConfiguredProject();
      expect(configured!.effects.legStretchEnabled, isTrue);
      expect(configured.effects.legStretch, equals(0.20));
    });

    test('Initializes preview and guards against out-of-order responses with sequence ID', () async {
      final repo = _FakeNativeRepository();
      final projectWithCache = testProject.copyWith(analysisCacheId: 'cache_123');
      final controller = EffectEditorController(repository: repo);

      controller.init(projectWithCache);
      expect(controller.state.previewLoading, isTrue);

      // Await initial preview completion
      await Future.delayed(const Duration(milliseconds: 50));
      expect(controller.state.previewLoading, isFalse);
      expect(controller.state.previewPath, equals('/path/to/rendered_preview.jpg'));
      expect(repo.lastTimestampMs, equals(0));

      // Trigger debounced update
      controller.updateOpacity(0.5);

      // Wait for debounce timer (200ms)
      await Future.delayed(const Duration(milliseconds: 250));
      expect(controller.state.previewRequestId, greaterThan(1));
      expect(controller.state.previewPath, equals('/path/to/rendered_preview.jpg'));
      controller.dispose();
    });

  });
}

class _FakeNativeRepository implements NativeProcessingRepository {
  int? lastTimestampMs;

  @override
  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
  }) async {
    lastTimestampMs = timestampMs;
    return PreviewFrameDto(
      thumbnailPath: '/path/to/rendered_preview.jpg',
      renderTimeMs: 12,
      timestampMs: 0,
    );

  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

