import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
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
  });
}
