import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_controller.dart';

void main() {
  group('FollowCrop Domain and Controller Tests', () {
    final testProject = DanceProject(
      id: 'proj_follow_test',
      sourceUri: 'file:///test.mp4',
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
      selectedPersonIds: {1},
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
      follow: const FollowConfig(
        enabled: false,
        zoom: 1.0,
        smoothFactor: 0.1,
      ),
    );

    test('Updates follow configuration in controller', () {
      final controller = EffectEditorController();
      controller.init(testProject);

      expect(controller.state.project?.follow.enabled, isFalse);

      controller.updateFollowConfig(
        enabled: true,
        targetPersonId: 1,
        zoom: 1.5,
        smoothFactor: 0.15,
      );

      final configured = controller.buildConfiguredProject();
      expect(configured, isNotNull);
      expect(configured!.follow.enabled, isTrue);
      expect(configured.follow.targetPersonId, equals(1));
      expect(configured.follow.zoom, equals(1.5));
      expect(configured.follow.smoothFactor, equals(0.15));
    });
  });
}
