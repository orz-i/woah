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

    test('Leg stretch requires and follows the explicit protagonist', () {
      final controller = EffectEditorController();
      controller.init(testProject);

      controller.updateSkinWhiten(0.75);
      controller.updateLegStretch(enabled: true, stretch: 0.20);
      var configured = controller.buildConfiguredProject();
      expect(configured!.effects.skinWhiten, equals(0.75));
      expect(configured.effects.legStretchEnabled, isFalse);

      final protagonistProject = testProject.copyWith(
        persons: const [
          PersonTrack(
            id: 1,
            normalizedInitialBox: NormalizedRect(
              left: .2,
              top: .1,
              right: .6,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
        ],
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
      );
      controller.init(protagonistProject);
      controller.updateLegStretch(enabled: true, stretch: 0.20);
      configured = controller.buildConfiguredProject();
      expect(configured!.effects.legStretchEnabled, isTrue);
      expect(configured.effects.legStretch, equals(0.20));

      controller.updateFollowConfig(enabled: false);
      configured = controller.buildConfiguredProject();
      expect(configured!.effects.legStretchEnabled, isFalse);
    });

    test('Face sticker style toggles real sticker configuration', () {
      final controller = EffectEditorController();
      controller.init(testProject);

      controller.updateProtectionStyle(FillMode.sticker);
      expect(controller.state.effects.fillMode, FillMode.sticker);
      expect(controller.state.effects.faceStickerEnabled, isTrue);
      expect(
        controller.state.effects.stickerAssetId,
        equals('builtin:sunglasses'),
      );

      controller.updateStickerAsset('builtin:panda');
      controller.updateStickerScale(1.4);
      var configured = controller.buildConfiguredProject();
      expect(configured!.effects.stickerAssetId, equals('builtin:panda'));
      expect(configured.effects.stickerScale, equals(1.4));

      controller.updateProtectionStyle(FillMode.blur);
      configured = controller.buildConfiguredProject();
      expect(configured!.effects.fillMode, FillMode.blur);
      expect(configured.effects.faceStickerEnabled, isFalse);
      expect(configured.effects.stickerAssetId, equals('disabled'));
    });

    test(
      'Custom face sticker path and scale survive project configuration',
      () {
        final controller = EffectEditorController();
        controller.init(testProject);

        const customPath = '/app-support/stickers/sticker_123.png';
        controller.updateStickerAsset(customPath);
        controller.updateStickerScale(0.8);

        final configured = controller.buildConfiguredProject();
        expect(configured, isNotNull);
        expect(configured!.effects.fillMode, FillMode.sticker);
        expect(configured.effects.faceStickerEnabled, isTrue);
        expect(configured.effects.stickerAssetId, customPath);
        expect(configured.effects.stickerScale, 0.8);
      },
    );

    test(
      'Initializes preview and guards against out-of-order responses with sequence ID',
      () async {
        final repo = _FakeNativeRepository();
        final projectWithCache = testProject.copyWith(
          analysisCacheId: 'cache_123',
        );
        final controller = EffectEditorController(repository: repo);

        controller.init(projectWithCache);
        expect(controller.state.previewLoading, isTrue);

        // Await initial preview completion
        await Future.delayed(const Duration(milliseconds: 50));
        expect(controller.state.previewLoading, isFalse);
        expect(
          controller.state.previewPath,
          equals('/path/to/rendered_preview.jpg'),
        );
        expect(repo.lastTimestampMs, equals(0));
        expect(repo.lastTightMaskPreview, isTrue);

        controller.updatePreviewTimestamp(1750);
        await Future.delayed(const Duration(milliseconds: 50));
        expect(repo.lastTimestampMs, equals(1750));

        // Effect changes stay on the current playback frame instead of snapping
        // back to the trim start frame.
        controller.updateOpacity(0.5);

        // Wait for debounce timer (200ms)
        await Future.delayed(const Duration(milliseconds: 250));
        expect(repo.lastTimestampMs, equals(1750));
        expect(controller.state.previewRequestId, greaterThan(1));
        expect(
          controller.state.previewPath,
          equals('/path/to/rendered_preview.jpg'),
        );
        controller.dispose();
      },
    );

    test(
      'uses only a full-frame handoff preview and never a person crop fallback',
      () {
        const personCrop = '/path/to/person_crop.jpg';
        const handoffPreview = '/path/to/selection_preview.jpg';
        final projectWithPerson = testProject.copyWith(
          persons: const [
            PersonTrack(
              id: 7,
              normalizedInitialBox: NormalizedRect(
                left: 0.1,
                top: 0.1,
                right: 0.4,
                bottom: 0.9,
              ),
              thumbnailPath: personCrop,
              confidence: 0.95,
            ),
          ],
        );

        final withHandoff = EffectEditorController();
        addTearDown(withHandoff.dispose);
        withHandoff.init(projectWithPerson, initialPreviewPath: handoffPreview);
        expect(withHandoff.state.previewThumbnailPath, handoffPreview);
        expect(withHandoff.state.previewThumbnailPath, isNot(personCrop));

        final withoutHandoff = EffectEditorController();
        addTearDown(withoutHandoff.dispose);
        withoutHandoff.init(projectWithPerson);
        expect(withoutHandoff.state.previewThumbnailPath, isNull);
      },
    );
  });
}

class _FakeNativeRepository implements NativeProcessingRepository {
  int? lastTimestampMs;
  bool? lastTightMaskPreview;

  @override
  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
    bool tightMaskPreview = false,
  }) async {
    lastTimestampMs = timestampMs;
    lastTightMaskPreview = tightMaskPreview;
    return PreviewFrameDto(
      thumbnailPath: '/path/to/rendered_preview.jpg',
      renderTimeMs: 12,
      timestampMs: 0,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
